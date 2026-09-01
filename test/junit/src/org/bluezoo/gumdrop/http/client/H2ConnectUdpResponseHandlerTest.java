/*
 * H2ConnectUdpResponseHandlerTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 */

package org.bluezoo.gumdrop.http.client;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.http.Capsule;
import org.bluezoo.gumdrop.http.CapsuleParser;
import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.http.HttpDatagramContext;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link H2ConnectUdpResponseHandler} -- issue #397's RFC 9298
 * CONNECT-UDP client over HTTP/2: acceptance, inbound datagram delivery
 * from capsule-framed DATA frames, outbound {@link
 * ConnectUdpSession#sendDatagram} producing correctly capsule-framed
 * request body bytes, and a rejected request.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class H2ConnectUdpResponseHandlerTest {

    @Test
    public void testAcceptedRequestOpensAndDeliversInboundDatagram() throws Exception {
        RecordingConnectUdpHandler eventHandler = new RecordingConnectUdpHandler();
        FakeHTTPRequest request = new FakeHTTPRequest();
        H2ConnectUdpResponseHandler handler = new H2ConnectUdpResponseHandler(request, eventHandler);

        handler.ok(new HTTPResponse(HTTPStatus.OK));
        handler.startResponseBody();
        assertNotNull("opened() should have been called", eventHandler.session);

        byte[] udpPayload = "hello-target".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer contextEncoded =
                HttpDatagramContext.encode(HttpDatagramContext.REGISTERED_CONTEXT_ID, ByteBuffer.wrap(udpPayload));
        byte[] contextBytes = new byte[contextEncoded.remaining()];
        contextEncoded.get(contextBytes);
        byte[] capsuleBytes = Capsule.datagram(contextBytes).encode();

        handler.responseBodyContent(ByteBuffer.wrap(capsuleBytes));

        assertNotNull("datagramReceived() should have been called", eventHandler.lastDatagram);
        byte[] delivered = new byte[eventHandler.lastDatagram.remaining()];
        eventHandler.lastDatagram.get(delivered);
        assertArrayEquals(udpPayload, delivered);
    }

    @Test
    public void testSendDatagramProducesCorrectlyFramedCapsuleOnRequestBody() throws Exception {
        RecordingConnectUdpHandler eventHandler = new RecordingConnectUdpHandler();
        FakeHTTPRequest request = new FakeHTTPRequest();
        H2ConnectUdpResponseHandler handler = new H2ConnectUdpResponseHandler(request, eventHandler);

        handler.ok(new HTTPResponse(HTTPStatus.OK));
        handler.startResponseBody();
        assertNotNull(eventHandler.session);

        byte[] udpPayload = "to-target".getBytes(StandardCharsets.US_ASCII);
        eventHandler.session.sendDatagram(ByteBuffer.wrap(udpPayload));

        assertEquals(1, request.sentChunks.size());
        CapsuleParser parser = new CapsuleParser();
        List<Capsule> capsules = parser.push(ByteBuffer.wrap(request.sentChunks.get(0)));
        assertEquals(1, capsules.size());
        assertEquals(Capsule.TYPE_DATAGRAM, capsules.get(0).getType());

        HttpDatagramContext decoded = HttpDatagramContext.decode(ByteBuffer.wrap(capsules.get(0).getValue()));
        assertEquals(HttpDatagramContext.REGISTERED_CONTEXT_ID, decoded.getContextId());
        byte[] decodedPayload = new byte[decoded.getPayload().remaining()];
        decoded.getPayload().get(decodedPayload);
        assertArrayEquals(udpPayload, decodedPayload);
    }

    @Test
    public void testCloseEndsTheRequestBody() throws Exception {
        RecordingConnectUdpHandler eventHandler = new RecordingConnectUdpHandler();
        FakeHTTPRequest request = new FakeHTTPRequest();
        H2ConnectUdpResponseHandler handler = new H2ConnectUdpResponseHandler(request, eventHandler);

        handler.ok(new HTTPResponse(HTTPStatus.OK));
        handler.startResponseBody();
        eventHandler.session.close();

        assertTrue("close() must end the underlying request body", request.bodyEnded);
    }

    @Test
    public void testEndResponseBodyNotifiesClosed() throws Exception {
        RecordingConnectUdpHandler eventHandler = new RecordingConnectUdpHandler();
        FakeHTTPRequest request = new FakeHTTPRequest();
        H2ConnectUdpResponseHandler handler = new H2ConnectUdpResponseHandler(request, eventHandler);

        handler.ok(new HTTPResponse(HTTPStatus.OK));
        handler.startResponseBody();
        handler.endResponseBody();

        assertTrue("closed() should have been called", eventHandler.closed);
    }

    @Test
    public void testRejectedRequestReportsErrorWithoutOpening() throws Exception {
        RecordingConnectUdpHandler eventHandler = new RecordingConnectUdpHandler();
        FakeHTTPRequest request = new FakeHTTPRequest();
        H2ConnectUdpResponseHandler handler = new H2ConnectUdpResponseHandler(request, eventHandler);

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
