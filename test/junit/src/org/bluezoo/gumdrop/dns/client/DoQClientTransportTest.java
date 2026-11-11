/*
 * DoQClientTransportTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.dns.client;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.bluezoo.gumdrop.tls.TlsConfig;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DoQClientTransport} session resumption and 0-RTT.
 * RFC 9250 section 4.5.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DoQClientTransportTest {

    /**
     * RFC 9250 section 4.5: only QUERY and NOTIFY opcodes may ride 0-RTT
     * early data -- checked directly against the raw wire bytes (before
     * any {@code DnsMessage} parse), matching {@code
     * DoQClientTransport.isEarlyDataEligible}'s own comment about the
     * header byte layout (RFC 1035 section 4.1.1: {@code QR(1) OPCODE(4)
     * ...}).
     */
    @Test
    public void testEarlyDataEligibleOpcodes() throws Exception {
        java.lang.reflect.Method method = DoQClientTransport.class
                .getDeclaredMethod("isEarlyDataEligible", java.nio.ByteBuffer.class);
        method.setAccessible(true);

        assertTrue("QUERY should be 0-RTT-eligible",
                (Boolean) method.invoke(null, header(org.bluezoo.gumdrop.dns.DnsMessage.OPCODE_QUERY)));
        assertTrue("NOTIFY should be 0-RTT-eligible",
                (Boolean) method.invoke(null, header(org.bluezoo.gumdrop.dns.DnsMessage.OPCODE_NOTIFY)));
        assertFalse("STATUS should not be 0-RTT-eligible",
                (Boolean) method.invoke(null, header(org.bluezoo.gumdrop.dns.DnsMessage.OPCODE_STATUS)));
        assertFalse("IQUERY should not be 0-RTT-eligible",
                (Boolean) method.invoke(null, header(org.bluezoo.gumdrop.dns.DnsMessage.OPCODE_IQUERY)));
        assertFalse("A too-short buffer should not be eligible",
                (Boolean) method.invoke(null, java.nio.ByteBuffer.wrap(new byte[] {0, 0})));
    }

    // Builds a minimal 3-byte prefix of a DNS header (ID(2) + the flags
    // byte containing QR(1)/OPCODE(4)) -- enough for isEarlyDataEligible's
    // own header-byte check, without needing a full DnsMessage.
    private static java.nio.ByteBuffer header(int opcode) {
        byte flagsHighByte = (byte) ((opcode << 3) & 0xFF);
        return java.nio.ByteBuffer.wrap(new byte[] {0, 0, flagsHighByte});
    }

    @Test
    public void testCloseOnNewTransport() {
        DoQClientTransport transport = new DoQClientTransport();
        transport.close();
    }

    @Test
    public void testSendWithoutOpenReportsError() {
        DoQClientTransport transport = new DoQClientTransport();
        final boolean[] errorReported = {false};
        java.nio.ByteBuffer data = java.nio.ByteBuffer.allocate(12);

        DnsClientTransportHandler handler = new DnsClientTransportHandler() {
            @Override
            public void onReceive(java.nio.ByteBuffer response) {
            }

            @Override
            public void onError(Exception cause) {
                errorReported[0] = true;
            }
        };

        try {
            Field handlerField = DoQClientTransport.class
                    .getDeclaredField("handler");
            handlerField.setAccessible(true);
            handlerField.set(transport, handler);
        } catch (ReflectiveOperationException e) {
            fail("Could not set handler: " + e);
        }

        transport.send(data);
        assertTrue("Should report error when not connected",
                errorReported[0]);
    }

    @Test
    public void testFluentSettersReturnTheTransport() {
        DoQClientTransport transport = new DoQClientTransport();
        assertSame(transport, transport.pinnedCertFingerprint("sha256:00"));
        assertSame(transport, transport.caFile(Paths.get("ca.pem")));
        assertSame(transport, transport.tls(new TlsConfig()));
    }

    /** Records what a {@link DoQClientTransport} stream handler hands to the resolver. */
    private static final class Delivered implements DnsClientTransportHandler {
        final List<ByteBuffer> single = new ArrayList<ByteBuffer>();
        final List<List<ByteBuffer>> sequences = new ArrayList<List<ByteBuffer>>();
        final List<Exception> errors = new ArrayList<Exception>();

        @Override
        public void onReceive(ByteBuffer data) {
            single.add(data);
        }

        @Override
        public void onReceiveSequence(List<ByteBuffer> messages) {
            sequences.add(messages);
        }

        @Override
        public void onError(Exception cause) {
            errors.add(cause);
        }
    }

    private static ProtocolHandler streamHandler(Delivered delivered, int originalId, ByteBuffer query)
            throws Exception {
        Class<?> type = Class.forName(DoQClientTransport.class.getName() + "$DoQStreamHandler");
        Constructor<?> ctor = type.getDeclaredConstructor(DnsClientTransportHandler.class, int.class,
                ByteBuffer.class);
        ctor.setAccessible(true);
        return (ProtocolHandler) ctor.newInstance(delivered, Integer.valueOf(originalId), query);
    }

    private static ByteBuffer framed(int length, int fillFrom) {
        ByteBuffer buf = ByteBuffer.allocate(2 + length);
        buf.putShort((short) length);
        for (int i = 0; i < length; i++) {
            buf.put((byte) (fillFrom + i));
        }
        buf.flip();
        return buf;
    }

    @Test
    public void testStreamSendsTheQueryThenClosesItsSide() throws Exception {
        Delivered delivered = new Delivered();
        ProtocolHandler handler = streamHandler(delivered, 7, ByteBuffer.wrap(new byte[] {1, 2, 3}));
        BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
        handler.connected(ep);
        assertArrayEquals(new byte[] {1, 2, 3}, ep.getAllBytes());
        assertEquals(1, ep.getCloseCount());
        handler.securityEstablished(null);
    }

    @Test
    public void testResponseCarriesTheOriginalMessageId() throws Exception {
        Delivered delivered = new Delivered();
        ProtocolHandler handler = streamHandler(delivered, 0x1234, ByteBuffer.allocate(0));
        ByteBuffer response = framed(12, 0);
        handler.receive(response);
        handler.readFinished();
        assertEquals(1, delivered.single.size());
        ByteBuffer got = delivered.single.get(0);
        assertEquals(12, got.remaining());
        assertEquals(0x12, got.get(0));
        assertEquals(0x34, got.get(1));
        assertEquals(2, got.get(2));
    }

    @Test
    public void testResponseSplitAcrossReceivesIsReassembled() throws Exception {
        Delivered delivered = new Delivered();
        ProtocolHandler handler = streamHandler(delivered, 5, ByteBuffer.allocate(0));
        ByteBuffer response = framed(12, 0);
        ByteBuffer head = ByteBuffer.wrap(new byte[] {response.get(0), response.get(1), response.get(2)});
        response.position(3);
        handler.receive(head);
        handler.receive(response);
        handler.disconnected();
        assertEquals(1, delivered.single.size());
        assertEquals(5, delivered.single.get(0).get(1));
    }

    @Test
    public void testSeveralFramedMessagesAreDeliveredAsASequence() throws Exception {
        Delivered delivered = new Delivered();
        ProtocolHandler handler = streamHandler(delivered, 9, ByteBuffer.allocate(0));
        ByteBuffer both = ByteBuffer.allocate(2 * 14);
        both.put(framed(12, 0));
        both.put(framed(12, 50));
        both.flip();
        handler.receive(both);
        handler.readFinished();
        assertTrue(delivered.single.isEmpty());
        assertEquals(1, delivered.sequences.size());
        assertEquals(2, delivered.sequences.get(0).size());
        assertEquals(9, delivered.sequences.get(0).get(1).get(1));
    }

    @Test
    public void testNothingIsDeliveredForAnEmptyOrTruncatedStream() throws Exception {
        Delivered delivered = new Delivered();
        ProtocolHandler handler = streamHandler(delivered, 1, ByteBuffer.allocate(0));
        handler.readFinished();
        handler.receive(ByteBuffer.wrap(new byte[] {0}));
        handler.disconnected();
        assertTrue(delivered.single.isEmpty());
        assertTrue(delivered.sequences.isEmpty());
    }

    @Test
    public void testStreamErrorIsPassedToTheResolver() throws Exception {
        Delivered delivered = new Delivered();
        ProtocolHandler handler = streamHandler(delivered, 1, ByteBuffer.allocate(0));
        IOException cause = new IOException("reset");
        handler.error(cause);
        assertEquals(1, delivered.errors.size());
        assertSame(cause, delivered.errors.get(0));
    }

    /** Counts {@link DnsClientTransportHandler#onConnected()} calls. */
    private static final class ConnectedCounter implements DnsClientTransportHandler {
        int connected;

        @Override
        public void onConnected() {
            connected++;
        }

        @Override
        public void onReceive(ByteBuffer data) {
        }

        @Override
        public void onError(Exception cause) {
        }
    }

    @Test
    public void testHandlerIsToldOnceWhenTheConnectionBecomesUsable() throws Exception {
        DoQClientTransport transport = new DoQClientTransport();
        ConnectedCounter counter = new ConnectedCounter();
        Field handler = DoQClientTransport.class.getDeclaredField("handler");
        handler.setAccessible(true);
        handler.set(transport, counter);
        java.lang.reflect.Method mark = DoQClientTransport.class.getDeclaredMethod(
                "markConnected", org.bluezoo.gumdrop.quic.QuicConnection.class, boolean.class);
        mark.setAccessible(true);
        // 0-RTT keys ready first, then the handshake completes
        mark.invoke(transport, null, Boolean.FALSE);
        mark.invoke(transport, null, Boolean.TRUE);
        assertEquals(1, counter.connected);
    }

}
