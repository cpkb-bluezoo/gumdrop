/*
 * H3ClientConnectIpResponseHandlerTest.java
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
import java.net.InetAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.http.Capsule;
import org.bluezoo.gumdrop.http.CapsuleParser;
import org.bluezoo.gumdrop.http.ConnectIpAddress;
import org.bluezoo.gumdrop.http.ConnectIpRoute;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpDatagramContext;
import org.bluezoo.gumdrop.http.client.ConnectIpClientSession;
import org.bluezoo.gumdrop.http.client.ConnectIpEventHandler;
import org.bluezoo.gumdrop.http.qpack.Decoder;
import org.bluezoo.gumdrop.http.qpack.SimpleEncoder;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Regression tests for issue #400: {@link H3ClientStream} has no notion
 * of CONNECT-IP (or any upgrade protocol) at all, so these tests drive
 * the real {@code H3ClientStream} dispatch path (not {@link
 * H3ClientConnectIpResponseHandler} directly) to confirm the CONNECT-IP
 * client bridge is correctly wired: acceptance, inbound packet delivery
 * (RFC 9484 section 6), inbound {@code ADDRESS_ASSIGN}/{@code
 * ROUTE_ADVERTISEMENT} capsule delivery (RFC 9484 section 4.7), outbound
 * {@link ConnectIpClientSession#sendPacket}/{@link
 * ConnectIpClientSession#sendAddressRequest} producing correctly
 * capsule-framed bytes, and a rejected request.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class H3ClientConnectIpResponseHandlerTest {

    @Test
    public void testAcceptedRequestOpensAndDeliversInboundPacket() throws Exception {
        RecordingConnectIpHandler handler = new RecordingConnectIpHandler();
        H3ClientStream stream = createConnectIpStream(handler);

        stream.headersFrameReceived(encode(":status", "200", "capsule-protocol", "?1"));
        assertNotNull("opened() should have been called", handler.session);
        assertNull("error() should not have been called", handler.error);

        byte[] ipPacket = "fake-ip-packet".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer contextEncoded =
                HttpDatagramContext.encode(HttpDatagramContext.REGISTERED_CONTEXT_ID, ByteBuffer.wrap(ipPacket));
        byte[] contextBytes = new byte[contextEncoded.remaining()];
        contextEncoded.get(contextBytes);
        byte[] capsuleBytes = Capsule.datagram(contextBytes).encode();

        stream.dataFrameReceived(ByteBuffer.wrap(capsuleBytes), true);

        assertNotNull("packetReceived() should have been called", handler.lastPacket);
        byte[] delivered = new byte[handler.lastPacket.remaining()];
        handler.lastPacket.get(delivered);
        assertArrayEquals(ipPacket, delivered);
    }

    @Test
    public void testAddressAssignCapsuleIsDelivered() throws Exception {
        RecordingConnectIpHandler handler = new RecordingConnectIpHandler();
        H3ClientStream stream = createConnectIpStream(handler);
        stream.headersFrameReceived(encode(":status", "200", "capsule-protocol", "?1"));

        InetAddress assignedAddress = InetAddress.getByName("192.0.2.5");
        List<ConnectIpAddress> assignments = Arrays.asList(new ConnectIpAddress(1, assignedAddress, 32));
        ByteBuffer capsuleValue = ConnectIpAddress.encodeList(assignments);
        byte[] valueBytes = new byte[capsuleValue.remaining()];
        capsuleValue.get(valueBytes);
        byte[] capsuleBytes = new Capsule(ConnectIpAddress.TYPE_ADDRESS_ASSIGN, valueBytes).encode();

        stream.dataFrameReceived(ByteBuffer.wrap(capsuleBytes), true);

        assertNotNull("addressAssigned() should have been called", handler.lastAssigned);
        assertEquals(1, handler.lastAssigned.size());
        assertEquals(assignedAddress, handler.lastAssigned.get(0).getAddress());
    }

    @Test
    public void testRouteAdvertisementCapsuleIsDelivered() throws Exception {
        RecordingConnectIpHandler handler = new RecordingConnectIpHandler();
        H3ClientStream stream = createConnectIpStream(handler);
        stream.headersFrameReceived(encode(":status", "200", "capsule-protocol", "?1"));

        InetAddress start = InetAddress.getByName("10.0.0.0");
        InetAddress end = InetAddress.getByName("10.0.0.255");
        List<ConnectIpRoute> routes = Arrays.asList(new ConnectIpRoute(start, end, ConnectIpRoute.IP_PROTOCOL_ALL));
        ByteBuffer capsuleValue = ConnectIpRoute.encodeList(routes);
        byte[] valueBytes = new byte[capsuleValue.remaining()];
        capsuleValue.get(valueBytes);
        byte[] capsuleBytes = new Capsule(ConnectIpRoute.TYPE_ROUTE_ADVERTISEMENT, valueBytes).encode();

        stream.dataFrameReceived(ByteBuffer.wrap(capsuleBytes), true);

        assertNotNull("routeAdvertised() should have been called", handler.lastRoutes);
        assertEquals(1, handler.lastRoutes.size());
        assertEquals(start, handler.lastRoutes.get(0).getStartAddress());
    }

    @Test
    public void testSendPacketProducesCorrectlyFramedCapsule() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        RecordingConnectIpHandler handler = new RecordingConnectIpHandler();
        H3ClientStream stream = createConnectIpStream(handler);
        setField(stream, "endpoint", endpoint);

        stream.headersFrameReceived(encode(":status", "200", "capsule-protocol", "?1"));
        assertNotNull(handler.session);

        byte[] ipPacket = "to-target".getBytes(StandardCharsets.US_ASCII);
        handler.session.sendPacket(ByteBuffer.wrap(ipPacket));

        assertNotNull("a DATA frame should have been sent", endpoint.lastSent);
        ByteBuffer withoutFrameHeader = extractDataFramePayload(endpoint.lastSent);
        CapsuleParser parser = new CapsuleParser();
        List<Capsule> capsules = parser.push(withoutFrameHeader);
        assertEquals(1, capsules.size());
        assertEquals(Capsule.TYPE_DATAGRAM, capsules.get(0).getType());

        HttpDatagramContext decoded = HttpDatagramContext.decode(ByteBuffer.wrap(capsules.get(0).getValue()));
        assertEquals(HttpDatagramContext.REGISTERED_CONTEXT_ID, decoded.getContextId());
        byte[] decodedPayload = new byte[decoded.getPayload().remaining()];
        decoded.getPayload().get(decodedPayload);
        assertArrayEquals(ipPacket, decodedPayload);
    }

    @Test
    public void testSendAddressRequestProducesCorrectlyFramedCapsule() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        RecordingConnectIpHandler handler = new RecordingConnectIpHandler();
        H3ClientStream stream = createConnectIpStream(handler);
        setField(stream, "endpoint", endpoint);

        stream.headersFrameReceived(encode(":status", "200", "capsule-protocol", "?1"));
        assertNotNull(handler.session);

        InetAddress requested = InetAddress.getByName("0.0.0.0");
        handler.session.sendAddressRequest(Arrays.asList(new ConnectIpAddress(9, requested, 0)));

        assertNotNull("a DATA frame should have been sent", endpoint.lastSent);
        ByteBuffer withoutFrameHeader = extractDataFramePayload(endpoint.lastSent);
        CapsuleParser parser = new CapsuleParser();
        List<Capsule> capsules = parser.push(withoutFrameHeader);
        assertEquals(1, capsules.size());
        assertEquals(ConnectIpAddress.TYPE_ADDRESS_REQUEST, capsules.get(0).getType());

        List<ConnectIpAddress> decoded = ConnectIpAddress.decodeList(ByteBuffer.wrap(capsules.get(0).getValue()));
        assertEquals(1, decoded.size());
        assertEquals(9, decoded.get(0).getRequestId());
        assertEquals(requested, decoded.get(0).getAddress());
    }

    @Test
    public void testRejectedRequestReportsErrorWithoutOpening() throws Exception {
        RecordingConnectIpHandler handler = new RecordingConnectIpHandler();
        H3ClientStream stream = createConnectIpStream(handler);

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

    private H3ClientStream createConnectIpStream(ConnectIpEventHandler handler) throws Exception {
        H3ClientConnectIpResponseHandler responseHandler = new H3ClientConnectIpResponseHandler(handler);
        // connection is null: exercises the stream/handler wiring in
        // isolation, without a real HTTP3ClientHandler/QuicConnection
        // stack -- H3ClientStream tolerates this (see H3ClientStreamTest).
        H3ClientStream stream = new H3ClientStream(null, new Decoder(4096), responseHandler);
        responseHandler.bindStream(stream);
        setField(stream, "streamId", 1L);

        Headers requestHeaders = new Headers();
        requestHeaders.add(new Header(":method", "CONNECT"));
        requestHeaders.add(new Header(":protocol", "connect-ip"));
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

    private static class RecordingConnectIpHandler implements ConnectIpEventHandler {
        ConnectIpClientSession session;
        ByteBuffer lastPacket;
        List<ConnectIpAddress> lastAssigned;
        List<ConnectIpRoute> lastRoutes;
        boolean closed;
        Throwable error;

        @Override
        public void opened(ConnectIpClientSession session) {
            this.session = session;
        }

        @Override
        public void packetReceived(ByteBuffer packet) {
            this.lastPacket = packet;
        }

        @Override
        public void addressAssigned(List<ConnectIpAddress> assignments) {
            this.lastAssigned = assignments;
        }

        @Override
        public void routeAdvertised(List<ConnectIpRoute> routes) {
            this.lastRoutes = routes;
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
