/*
 * ConnectUdpClientProtocolHandlerTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 */

package org.bluezoo.gumdrop.http.client;

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
import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.http.HttpDatagramContext;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link ConnectUdpClientProtocolHandler} -- issue #397's RFC
 * 9298 CONNECT-UDP client over HTTP/1.1: the {@link
 * ConnectUdpClientProtocolHandler#handleProtocolSwitch} hook (mirroring
 * {@code WebSocketClientProtocolHandler}'s own use of it), post-upgrade
 * capsule dispatch, and outbound {@link ConnectUdpSession#sendDatagram}
 * framing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ConnectUdpClientProtocolHandlerTest {

    @Test
    public void testHandleProtocolSwitchAcceptsConnectUdpUpgrade() {
        RecordingConnectUdpHandler eventHandler = new RecordingConnectUdpHandler();
        ConnectUdpClientProtocolHandler handler =
                new ConnectUdpClientProtocolHandler(null, eventHandler, "localhost", 8080, false);

        boolean handled = handler.handleProtocolSwitch(HTTPStatus.SWITCHING_PROTOCOLS, upgradeHeaders());

        assertTrue("a connect-udp Upgrade response must be accepted", handled);
        assertNotNull("opened() should have been called", eventHandler.session);
        assertTrue("the handler must report itself as externally handling data from now on",
                handler.isExternallyHandled());
    }

    @Test
    public void testHandleProtocolSwitchRejectsUnrelatedUpgrade() {
        RecordingConnectUdpHandler eventHandler = new RecordingConnectUdpHandler();
        ConnectUdpClientProtocolHandler handler =
                new ConnectUdpClientProtocolHandler(null, eventHandler, "localhost", 8080, false);

        Headers headers = new Headers();
        headers.add(new Header("connection", "upgrade"));
        headers.add(new Header("upgrade", "websocket"));

        boolean handled = handler.handleProtocolSwitch(HTTPStatus.SWITCHING_PROTOCOLS, headers);

        assertFalse("an unrelated Upgrade response must not be claimed", handled);
        assertNull("opened() should not have been called", eventHandler.session);
        assertFalse(handler.isExternallyHandled());
    }

    @Test
    public void testReceiveAfterSwitchDeliversInboundDatagram() throws Exception {
        RecordingConnectUdpHandler eventHandler = new RecordingConnectUdpHandler();
        ConnectUdpClientProtocolHandler handler =
                new ConnectUdpClientProtocolHandler(null, eventHandler, "localhost", 8080, false);
        handler.handleProtocolSwitch(HTTPStatus.SWITCHING_PROTOCOLS, upgradeHeaders());

        byte[] udpPayload = "hello-target".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer contextEncoded =
                HttpDatagramContext.encode(HttpDatagramContext.REGISTERED_CONTEXT_ID, ByteBuffer.wrap(udpPayload));
        byte[] contextBytes = new byte[contextEncoded.remaining()];
        contextEncoded.get(contextBytes);
        byte[] capsuleBytes = Capsule.datagram(contextBytes).encode();

        handler.receive(ByteBuffer.wrap(capsuleBytes));

        assertNotNull("datagramReceived() should have been called", eventHandler.lastDatagram);
        byte[] delivered = new byte[eventHandler.lastDatagram.remaining()];
        eventHandler.lastDatagram.get(delivered);
        assertArrayEquals(udpPayload, delivered);
    }

    @Test
    public void testSendDatagramProducesCorrectlyFramedCapsule() throws Exception {
        RecordingConnectUdpHandler eventHandler = new RecordingConnectUdpHandler();
        ConnectUdpClientProtocolHandler handler =
                new ConnectUdpClientProtocolHandler(null, eventHandler, "localhost", 8080, false);
        RecordingEndpoint endpoint = new RecordingEndpoint();
        handler.endpoint = endpoint;

        handler.handleProtocolSwitch(HTTPStatus.SWITCHING_PROTOCOLS, upgradeHeaders());
        assertNotNull(eventHandler.session);

        byte[] udpPayload = "to-target".getBytes(StandardCharsets.US_ASCII);
        eventHandler.session.sendDatagram(ByteBuffer.wrap(udpPayload));

        assertNotNull("a capsule should have been written to the endpoint", endpoint.lastSent);
        CapsuleParser parser = new CapsuleParser();
        List<Capsule> capsules = parser.push(ByteBuffer.wrap(endpoint.lastSent));
        assertEquals(1, capsules.size());
        assertEquals(Capsule.TYPE_DATAGRAM, capsules.get(0).getType());

        HttpDatagramContext decoded = HttpDatagramContext.decode(ByteBuffer.wrap(capsules.get(0).getValue()));
        assertEquals(HttpDatagramContext.REGISTERED_CONTEXT_ID, decoded.getContextId());
        byte[] decodedPayload = new byte[decoded.getPayload().remaining()];
        decoded.getPayload().get(decodedPayload);
        assertArrayEquals(udpPayload, decodedPayload);
    }

    @Test
    public void testDisconnectedAfterSwitchNotifiesClosed() {
        RecordingConnectUdpHandler eventHandler = new RecordingConnectUdpHandler();
        ConnectUdpClientProtocolHandler handler =
                new ConnectUdpClientProtocolHandler(null, eventHandler, "localhost", 8080, false);
        handler.handleProtocolSwitch(HTTPStatus.SWITCHING_PROTOCOLS, upgradeHeaders());

        handler.disconnected();

        assertTrue("closed() should have been called", eventHandler.closed);
    }

    private static Headers upgradeHeaders() {
        Headers headers = new Headers();
        headers.add(new Header("connection", "upgrade"));
        headers.add(new Header("upgrade", "connect-udp"));
        return headers;
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
        @Override public boolean isSecure() { return false; }
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
