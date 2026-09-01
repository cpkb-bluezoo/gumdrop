/*
 * H3ClientConnectUdpResponseHandlerTest.java
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

package org.bluezoo.gumdrop.http.h3;

import java.lang.reflect.Field;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.http.Capsule;
import org.bluezoo.gumdrop.http.CapsuleParser;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpDatagramContext;
import org.bluezoo.gumdrop.http.client.ConnectUdpEventHandler;
import org.bluezoo.gumdrop.http.client.ConnectUdpSession;
import org.bluezoo.gumdrop.http.qpack.Decoder;
import org.bluezoo.gumdrop.http.qpack.SimpleEncoder;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Regression tests for issue #397: {@link H3ClientStream} has no notion of
 * CONNECT-UDP (or any upgrade protocol) at all, so these tests drive the
 * real {@code H3ClientStream} dispatch path (not {@link
 * H3ClientConnectUdpResponseHandler} directly) to confirm the CONNECT-UDP
 * client bridge is correctly wired: acceptance, inbound datagram delivery
 * (RFC 9298 section 5), outbound {@link ConnectUdpSession#sendDatagram}
 * producing correctly capsule-framed HTTP Datagrams (RFC 9297 section
 * 3.5), and a rejected request.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class H3ClientConnectUdpResponseHandlerTest {

    @Test
    public void testAcceptedRequestOpensAndDeliversInboundDatagram() throws Exception {
        RecordingConnectUdpHandler handler = new RecordingConnectUdpHandler();
        H3ClientStream stream = createConnectUdpStream(handler);

        stream.headersFrameReceived(encode(":status", "200", "capsule-protocol", "?1"));
        assertNotNull("opened() should have been called", handler.session);
        assertNull("error() should not have been called", handler.error);

        byte[] udpPayload = "hello-target".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer contextEncoded =
                HttpDatagramContext.encode(HttpDatagramContext.REGISTERED_CONTEXT_ID, ByteBuffer.wrap(udpPayload));
        byte[] contextBytes = new byte[contextEncoded.remaining()];
        contextEncoded.get(contextBytes);
        byte[] capsuleBytes = Capsule.datagram(contextBytes).encode();

        stream.dataFrameReceived(ByteBuffer.wrap(capsuleBytes), true);

        assertNotNull("datagramReceived() should have been called", handler.lastDatagram);
        byte[] delivered = new byte[handler.lastDatagram.remaining()];
        handler.lastDatagram.get(delivered);
        assertArrayEquals(udpPayload, delivered);
    }

    @Test
    public void testSendDatagramProducesCorrectlyFramedCapsule() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        RecordingConnectUdpHandler handler = new RecordingConnectUdpHandler();
        H3ClientStream stream = createConnectUdpStream(handler);
        setField(stream, "endpoint", endpoint);

        stream.headersFrameReceived(encode(":status", "200", "capsule-protocol", "?1"));
        assertNotNull(handler.session);

        byte[] udpPayload = "to-target".getBytes(StandardCharsets.US_ASCII);
        handler.session.sendDatagram(ByteBuffer.wrap(udpPayload));

        assertNotNull("a DATA frame should have been sent", endpoint.lastSent);
        // Strip the 9-byte HTTP/3 frame envelope (type/length varints for
        // a small payload collapse to this shape -- see H3Writer) to get
        // at the capsule bytes themselves; simplest robust check is to
        // decode via CapsuleParser directly against the raw frame minus
        // its H3Writer.writeData() framing, using the same parser the
        // real server side (ConnectUdpRelay) sees for inbound DATA.
        ByteBuffer withoutFrameHeader = extractDataFramePayload(endpoint.lastSent);
        CapsuleParser parser = new CapsuleParser();
        List<Capsule> capsules = parser.push(withoutFrameHeader);
        assertEquals(1, capsules.size());
        assertEquals(Capsule.TYPE_DATAGRAM, capsules.get(0).getType());

        HttpDatagramContext decoded = HttpDatagramContext.decode(ByteBuffer.wrap(capsules.get(0).getValue()));
        assertEquals(HttpDatagramContext.REGISTERED_CONTEXT_ID, decoded.getContextId());
        byte[] decodedPayload = new byte[decoded.getPayload().remaining()];
        decoded.getPayload().get(decodedPayload);
        assertArrayEquals(udpPayload, decodedPayload);
    }

    @Test
    public void testRejectedRequestReportsErrorWithoutOpening() throws Exception {
        RecordingConnectUdpHandler handler = new RecordingConnectUdpHandler();
        H3ClientStream stream = createConnectUdpStream(handler);

        stream.headersFrameReceived(encode(":status", "403"));

        assertNotNull("error() should have been called", handler.error);
        assertNull("opened() should not have been called", handler.session);
    }

    /**
     * Strips the HTTP/3 DATA frame envelope (RFC 9114 section 7.2.1: a
     * type varint, a length varint, then the payload) to recover the
     * capsule bytes {@link H3ClientStream#sendRawData} wrote. Frame type
     * (0x00) and, for payloads under 64 bytes, the length are both
     * single-byte varints.
     */
    private static ByteBuffer extractDataFramePayload(byte[] frame) {
        int offset = 0;
        offset += varIntLength(frame, offset); // type
        int lengthStart = offset;
        offset += varIntLength(frame, offset); // length
        return ByteBuffer.wrap(frame, offset, frame.length - offset);
    }

    private static int varIntLength(byte[] data, int offset) {
        int prefix = (data[offset] & 0xff) >> 6;
        switch (prefix) {
            case 0: return 1;
            case 1: return 2;
            case 2: return 4;
            default: return 8;
        }
    }

    private H3ClientStream createConnectUdpStream(ConnectUdpEventHandler handler) throws Exception {
        H3ClientConnectUdpResponseHandler responseHandler = new H3ClientConnectUdpResponseHandler(handler);
        // connection is null: exercises the stream/handler wiring in
        // isolation, without a real HTTP3ClientHandler/QuicConnection
        // stack -- H3ClientStream tolerates this (see H3ClientStreamTest).
        H3ClientStream stream = new H3ClientStream(null, new Decoder(4096), responseHandler);
        responseHandler.bindStream(stream);
        setField(stream, "streamId", 1L);

        Headers requestHeaders = new Headers();
        requestHeaders.add(new Header(":method", "CONNECT"));
        requestHeaders.add(new Header(":protocol", "connect-udp"));
        requestHeaders.add(new Header("capsule-protocol", "?1"));
        stream.prepareRequest(requestHeaders, false);
        return stream;
    }

    private static void setField(H3ClientStream stream, String name, Object value) throws Exception {
        Field f = H3ClientStream.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(stream, value);
    }

    private static ByteBuffer encode(String... pairs) {
        SimpleEncoder encoder = new SimpleEncoder();
        java.util.List<Header> headers = new java.util.ArrayList<Header>();
        for (int i = 0; i < pairs.length; i += 2) {
            headers.add(new Header(pairs[i], pairs[i + 1]));
        }
        ByteBuffer buf = ByteBuffer.allocate(4096);
        encoder.encode(buf, headers);
        buf.flip();
        return buf;
    }

    private static class RecordingEndpoint implements Endpoint {
        byte[] lastSent;
        @Override public void send(ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            lastSent = bytes;
        }
        @Override public boolean isOpen() { return true; }
        @Override public boolean isClosing() { return false; }
        @Override public void close() { }
        @Override public SocketAddress getLocalAddress() { return null; }
        @Override public SocketAddress getRemoteAddress() { return null; }
        @Override public boolean isSecure() { return true; }
        @Override public SecurityInfo getSecurityInfo() { return null; }
        @Override public void startTLS() { }
        @Override public void pauseRead() { }
        @Override public void resumeRead() { }
        @Override public void onWriteReady(Runnable callback) { }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public org.bluezoo.gumdrop.TimerHandle scheduleTimer(long delayMs, Runnable callback) { return null; }
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public Trace getTrace() { return null; }
        @Override public void setTrace(Trace trace) { }
        @Override public boolean isTelemetryEnabled() { return false; }
        @Override public TelemetryConfig getTelemetryConfig() { return null; }
    }

    private static class RecordingConnectUdpHandler implements ConnectUdpEventHandler {
        ConnectUdpSession session;
        ByteBuffer lastDatagram;
        boolean closed;
        Throwable error;

        @Override
        public void opened(ConnectUdpSession session) {
            this.session = session;
        }

        @Override
        public void datagramReceived(ByteBuffer payload) {
            this.lastDatagram = payload;
        }

        @Override
        public void closed() {
            this.closed = true;
        }

        @Override
        public void error(Throwable cause) {
            this.error = cause;
        }
    }
}
