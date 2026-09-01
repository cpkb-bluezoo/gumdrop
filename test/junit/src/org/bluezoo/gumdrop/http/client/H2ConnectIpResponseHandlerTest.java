/*
 * H2ConnectIpResponseHandlerTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 */

package org.bluezoo.gumdrop.http.client;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bluezoo.gumdrop.http.Capsule;
import org.bluezoo.gumdrop.http.CapsuleParser;
import org.bluezoo.gumdrop.http.ConnectIpAddress;
import org.bluezoo.gumdrop.http.ConnectIpRoute;
import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.http.HttpDatagramContext;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link H2ConnectIpResponseHandler} -- issue #400's RFC 9484
 * CONNECT-IP client over HTTP/2: acceptance, inbound packet/{@code
 * ADDRESS_ASSIGN}/{@code ROUTE_ADVERTISEMENT} delivery from
 * capsule-framed DATA frames, outbound {@link
 * ConnectIpClientSession#sendPacket}/{@link
 * ConnectIpClientSession#sendAddressRequest} producing correctly
 * capsule-framed request body bytes, and a rejected request.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class H2ConnectIpResponseHandlerTest {

    @Test
    public void testAcceptedRequestOpensAndDeliversInboundPacket() throws Exception {
        RecordingConnectIpHandler eventHandler = new RecordingConnectIpHandler();
        FakeHTTPRequest request = new FakeHTTPRequest();
        H2ConnectIpResponseHandler handler = new H2ConnectIpResponseHandler(request, eventHandler);

        handler.ok(new HTTPResponse(HTTPStatus.OK));
        handler.startResponseBody();
        assertNotNull("opened() should have been called", eventHandler.session);

        byte[] ipPacket = "hello-target".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer contextEncoded =
                HttpDatagramContext.encode(HttpDatagramContext.REGISTERED_CONTEXT_ID, ByteBuffer.wrap(ipPacket));
        byte[] contextBytes = new byte[contextEncoded.remaining()];
        contextEncoded.get(contextBytes);
        byte[] capsuleBytes = Capsule.datagram(contextBytes).encode();

        handler.responseBodyContent(ByteBuffer.wrap(capsuleBytes));

        assertNotNull("packetReceived() should have been called", eventHandler.lastPacket);
        byte[] delivered = new byte[eventHandler.lastPacket.remaining()];
        eventHandler.lastPacket.get(delivered);
        assertArrayEquals(ipPacket, delivered);
    }

    @Test
    public void testAddressAssignCapsuleIsDelivered() throws Exception {
        RecordingConnectIpHandler eventHandler = new RecordingConnectIpHandler();
        FakeHTTPRequest request = new FakeHTTPRequest();
        H2ConnectIpResponseHandler handler = new H2ConnectIpResponseHandler(request, eventHandler);
        handler.ok(new HTTPResponse(HTTPStatus.OK));
        handler.startResponseBody();

        InetAddress assignedAddress = InetAddress.getByName("192.0.2.5");
        ByteBuffer capsuleValue = ConnectIpAddress.encodeList(
                Arrays.asList(new ConnectIpAddress(1, assignedAddress, 32)));
        byte[] valueBytes = new byte[capsuleValue.remaining()];
        capsuleValue.get(valueBytes);
        byte[] capsuleBytes = new Capsule(ConnectIpAddress.TYPE_ADDRESS_ASSIGN, valueBytes).encode();

        handler.responseBodyContent(ByteBuffer.wrap(capsuleBytes));

        assertNotNull("addressAssigned() should have been called", eventHandler.lastAssigned);
        assertEquals(1, eventHandler.lastAssigned.size());
        assertEquals(assignedAddress, eventHandler.lastAssigned.get(0).getAddress());
    }

    @Test
    public void testRouteAdvertisementCapsuleIsDelivered() throws Exception {
        RecordingConnectIpHandler eventHandler = new RecordingConnectIpHandler();
        FakeHTTPRequest request = new FakeHTTPRequest();
        H2ConnectIpResponseHandler handler = new H2ConnectIpResponseHandler(request, eventHandler);
        handler.ok(new HTTPResponse(HTTPStatus.OK));
        handler.startResponseBody();

        InetAddress start = InetAddress.getByName("10.0.0.0");
        InetAddress end = InetAddress.getByName("10.0.0.255");
        ByteBuffer capsuleValue = ConnectIpRoute.encodeList(
                Arrays.asList(new ConnectIpRoute(start, end, ConnectIpRoute.IP_PROTOCOL_ALL)));
        byte[] valueBytes = new byte[capsuleValue.remaining()];
        capsuleValue.get(valueBytes);
        byte[] capsuleBytes = new Capsule(ConnectIpRoute.TYPE_ROUTE_ADVERTISEMENT, valueBytes).encode();

        handler.responseBodyContent(ByteBuffer.wrap(capsuleBytes));

        assertNotNull("routeAdvertised() should have been called", eventHandler.lastRoutes);
        assertEquals(1, eventHandler.lastRoutes.size());
        assertEquals(start, eventHandler.lastRoutes.get(0).getStartAddress());
    }

    @Test
    public void testSendPacketProducesCorrectlyFramedCapsuleOnRequestBody() throws Exception {
        RecordingConnectIpHandler eventHandler = new RecordingConnectIpHandler();
        FakeHTTPRequest request = new FakeHTTPRequest();
        H2ConnectIpResponseHandler handler = new H2ConnectIpResponseHandler(request, eventHandler);

        handler.ok(new HTTPResponse(HTTPStatus.OK));
        handler.startResponseBody();
        assertNotNull(eventHandler.session);

        byte[] ipPacket = "to-target".getBytes(StandardCharsets.US_ASCII);
        eventHandler.session.sendPacket(ByteBuffer.wrap(ipPacket));

        assertEquals(1, request.sentChunks.size());
        CapsuleParser parser = new CapsuleParser();
        List<Capsule> capsules = parser.push(ByteBuffer.wrap(request.sentChunks.get(0)));
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
        RecordingConnectIpHandler eventHandler = new RecordingConnectIpHandler();
        FakeHTTPRequest request = new FakeHTTPRequest();
        H2ConnectIpResponseHandler handler = new H2ConnectIpResponseHandler(request, eventHandler);

        handler.ok(new HTTPResponse(HTTPStatus.OK));
        handler.startResponseBody();
        assertNotNull(eventHandler.session);

        InetAddress requested = InetAddress.getByName("0.0.0.0");
        eventHandler.session.sendAddressRequest(Arrays.asList(new ConnectIpAddress(9, requested, 0)));

        assertEquals(1, request.sentChunks.size());
        CapsuleParser parser = new CapsuleParser();
        List<Capsule> capsules = parser.push(ByteBuffer.wrap(request.sentChunks.get(0)));
        assertEquals(1, capsules.size());
        assertEquals(ConnectIpAddress.TYPE_ADDRESS_REQUEST, capsules.get(0).getType());

        List<ConnectIpAddress> decoded = ConnectIpAddress.decodeList(ByteBuffer.wrap(capsules.get(0).getValue()));
        assertEquals(1, decoded.size());
        assertEquals(9, decoded.get(0).getRequestId());
        assertEquals(requested, decoded.get(0).getAddress());
    }

    @Test
    public void testCloseEndsTheRequestBody() throws Exception {
        RecordingConnectIpHandler eventHandler = new RecordingConnectIpHandler();
        FakeHTTPRequest request = new FakeHTTPRequest();
        H2ConnectIpResponseHandler handler = new H2ConnectIpResponseHandler(request, eventHandler);

        handler.ok(new HTTPResponse(HTTPStatus.OK));
        handler.startResponseBody();
        eventHandler.session.close();

        assertTrue("close() must end the underlying request body", request.bodyEnded);
    }

    @Test
    public void testEndResponseBodyNotifiesClosed() throws Exception {
        RecordingConnectIpHandler eventHandler = new RecordingConnectIpHandler();
        FakeHTTPRequest request = new FakeHTTPRequest();
        H2ConnectIpResponseHandler handler = new H2ConnectIpResponseHandler(request, eventHandler);

        handler.ok(new HTTPResponse(HTTPStatus.OK));
        handler.startResponseBody();
        handler.endResponseBody();

        assertTrue("closed() should have been called", eventHandler.closed);
    }

    @Test
    public void testRejectedRequestReportsErrorWithoutOpening() throws Exception {
        RecordingConnectIpHandler eventHandler = new RecordingConnectIpHandler();
        FakeHTTPRequest request = new FakeHTTPRequest();
        H2ConnectIpResponseHandler handler = new H2ConnectIpResponseHandler(request, eventHandler);

        handler.error(new HTTPResponse(HTTPStatus.FORBIDDEN));
        // A rejected Extended CONNECT still gets a startResponseBody()
        // call whenever the h2 stream isn't immediately closed -- the
        // "failed" guard must suppress opened() even so.
        handler.startResponseBody();

        assertNotNull("error() should have been called", eventHandler.error);
        assertNull("opened() should not have been called", eventHandler.session);
    }

    private static class FakeHTTPRequest implements HTTPRequest {
        final List<byte[]> sentChunks = new ArrayList<byte[]>();
        boolean bodyEnded;

        @Override public void header(String name, String value) { }
        @Override public void priority(int weight) { }
        @Override public void dependency(HTTPRequest parent) { }
        @Override public void exclusive(boolean exclusive) { }
        @Override public void send(HTTPResponseHandler handler) { }
        @Override public void startRequestBody(HTTPResponseHandler handler) { }

        @Override
        public int requestBodyContent(ByteBuffer data) {
            int remaining = data.remaining();
            byte[] bytes = new byte[remaining];
            data.get(bytes);
            sentChunks.add(bytes);
            return remaining;
        }

        @Override
        public void endRequestBody() {
            bodyEnded = true;
        }

        @Override
        public void cancel() { }
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
