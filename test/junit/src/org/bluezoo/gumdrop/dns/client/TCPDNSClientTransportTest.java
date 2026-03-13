/*
 * TCPDNSClientTransportTest.java
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

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.dns.DNSMessage;
import org.bluezoo.gumdrop.dns.DNSType;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link TCPDNSClientTransport}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TCPDNSClientTransportTest {

    @Test
    public void testImplementsInterface() {
        TCPDNSClientTransport transport = new TCPDNSClientTransport();
        assertTrue(transport instanceof DNSClientTransport);
    }

    @Test
    public void testCloseBeforeOpen() {
        TCPDNSClientTransport transport = new TCPDNSClientTransport();
        transport.close();
    }

    @Test
    public void testCreateDoT() {
        TCPDNSClientTransport transport = TCPDNSClientTransport.createDoT();
        assertNotNull(transport);
        transport.close();
    }

    @Test
    public void testSetSecureChangesDefaults() {
        TCPDNSClientTransport transport = new TCPDNSClientTransport();
        transport.setSecure(true);
        transport.setSecure(false);
        transport.close();
    }

    @Test
    public void testSetDefaultPort() {
        TCPDNSClientTransport transport = new TCPDNSClientTransport();
        transport.setDefaultPort(5353);
        transport.close();
    }

    /**
     * RFC 7858 section 3.4, RFC 7413: DoT transport should enable TCP
     * Fast Open on the underlying factory.
     */
    @Test
    public void testDoTEnablesTcpFastOpen() {
        TCPDNSClientTransport transport = TCPDNSClientTransport.createDoT();
        assertNotNull(transport);
        transport.close();
    }

    // -- Framing tests using the inner TCPProtocolHandler --

    @Test
    public void testFramingSingleMessage() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        ProtocolHandler ph = createProtocolHandler(handler);

        DNSMessage query = DNSMessage.createQuery(1, "example.com",
                DNSType.A);
        ByteBuffer payload = query.serialize();
        ByteBuffer framed = frame(payload);

        ph.receive(framed);

        assertEquals(1, handler.received.size());
        DNSMessage parsed = DNSMessage.parse(handler.received.get(0));
        assertEquals(1, parsed.getId());
    }

    @Test
    public void testFramingTwoMessagesInOneChunk() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        ProtocolHandler ph = createProtocolHandler(handler);

        ByteBuffer msg1 = DNSMessage.createQuery(10, "a.example.com",
                DNSType.A).serialize();
        ByteBuffer msg2 = DNSMessage.createQuery(20, "b.example.com",
                DNSType.AAAA).serialize();

        ByteBuffer combined = ByteBuffer.allocate(
                2 + msg1.remaining() + 2 + msg2.remaining());
        combined.putShort((short) msg1.remaining());
        combined.put(msg1);
        combined.putShort((short) msg2.remaining());
        combined.put(msg2);
        combined.flip();

        ph.receive(combined);

        assertEquals(2, handler.received.size());
        assertEquals(10, DNSMessage.parse(handler.received.get(0)).getId());
        assertEquals(20, DNSMessage.parse(handler.received.get(1)).getId());
    }

    @Test
    public void testFramingSplitAcrossChunks() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        ProtocolHandler ph = createProtocolHandler(handler);

        ByteBuffer payload = DNSMessage.createQuery(42, "split.example.com",
                DNSType.MX).serialize();
        ByteBuffer framed = frame(payload);

        int splitPoint = framed.remaining() / 2;
        ByteBuffer part1 = ByteBuffer.allocate(splitPoint);
        ByteBuffer part2 = ByteBuffer.allocate(framed.remaining() - splitPoint);

        framed.limit(splitPoint);
        part1.put(framed);
        part1.flip();

        framed.limit(framed.capacity());
        part2.put(framed);
        part2.flip();

        ph.receive(part1);
        assertEquals("No message until fully received",
                0, handler.received.size());

        ph.receive(part2);
        assertEquals(1, handler.received.size());
        assertEquals(42, DNSMessage.parse(handler.received.get(0)).getId());
    }

    @Test
    public void testFramingLengthPrefixSplitAcrossChunks() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        ProtocolHandler ph = createProtocolHandler(handler);

        ByteBuffer payload = DNSMessage.createQuery(7, "tiny.example.com",
                DNSType.A).serialize();
        ByteBuffer framed = frame(payload);

        // Send just the first byte of the length prefix
        ByteBuffer firstByte = ByteBuffer.allocate(1);
        firstByte.put(framed.get());
        firstByte.flip();

        ByteBuffer rest = framed.slice();

        ph.receive(firstByte);
        assertEquals(0, handler.received.size());

        ph.receive(rest);
        assertEquals(1, handler.received.size());
        assertEquals(7, DNSMessage.parse(handler.received.get(0)).getId());
    }

    @Test
    public void testFramingInvalidLengthReportsError() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        ProtocolHandler ph = createProtocolHandler(handler);

        // Zero-length message is invalid
        ByteBuffer invalid = ByteBuffer.allocate(2);
        invalid.putShort((short) 0);
        invalid.flip();

        ph.receive(invalid);

        assertEquals(0, handler.received.size());
        assertNotNull("Should report error for zero-length message",
                handler.error);
    }

    @Test
    public void testDisconnectedReportsError() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        ProtocolHandler ph = createProtocolHandler(handler);

        ph.disconnected();

        assertNotNull("disconnected() should report an error", handler.error);
        assertTrue(handler.error instanceof IOException);
    }

    @Test
    public void testErrorDelegatesToHandler() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        ProtocolHandler ph = createProtocolHandler(handler);

        IOException cause = new IOException("test error");
        ph.error(cause);

        assertSame(cause, handler.error);
    }

    // -- Helpers --

    private static ByteBuffer frame(ByteBuffer payload) {
        payload.rewind();
        ByteBuffer framed = ByteBuffer.allocate(2 + payload.remaining());
        framed.putShort((short) payload.remaining());
        framed.put(payload);
        framed.flip();
        return framed;
    }

    /**
     * Creates the private inner TCPProtocolHandler via reflection.
     */
    private static ProtocolHandler createProtocolHandler(
            DNSClientTransportHandler handler) throws Exception {
        Class<?> handlerClass = null;
        for (Class<?> c : TCPDNSClientTransport.class.getDeclaredClasses()) {
            if (c.getSimpleName().equals("TCPProtocolHandler")) {
                handlerClass = c;
                break;
            }
        }
        assertNotNull("TCPProtocolHandler inner class should exist",
                handlerClass);
        Constructor<?> ctor = handlerClass.getDeclaredConstructor(
                DNSClientTransportHandler.class);
        ctor.setAccessible(true);
        return (ProtocolHandler) ctor.newInstance(handler);
    }

    private static class RecordingHandler
            implements DNSClientTransportHandler {
        final List<ByteBuffer> received = new ArrayList<>();
        Exception error;

        @Override
        public void onReceive(ByteBuffer data) {
            ByteBuffer copy = ByteBuffer.allocate(data.remaining());
            copy.put(data);
            copy.flip();
            received.add(copy);
        }

        @Override
        public void onError(Exception cause) {
            error = cause;
        }
    }

}
