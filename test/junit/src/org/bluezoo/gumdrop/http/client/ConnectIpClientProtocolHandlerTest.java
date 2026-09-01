/*
 * ConnectIpClientProtocolHandlerTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 */

package org.bluezoo.gumdrop.http.client;

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
 * Tests for {@link ConnectIpClientProtocolHandler} -- issue #400's RFC
 * 9484 CONNECT-IP client over HTTP/1.1: the {@link
 * ConnectIpClientProtocolHandler#handleProtocolSwitch} hook (mirroring
 * {@link ConnectUdpClientProtocolHandler}'s own use of it), post-upgrade
 * capsule dispatch for all three capsule types, and outbound {@link
 * ConnectIpClientSession#sendPacket}/{@link
 * ConnectIpClientSession#sendAddressRequest} framing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ConnectIpClientProtocolHandlerTest {

    @Test
    public void testHandleProtocolSwitchAcceptsConnectIpUpgrade() {
        RecordingConnectIpHandler eventHandler = new RecordingConnectIpHandler();
        ConnectIpClientProtocolHandler handler =
                new ConnectIpClientProtocolHandler(null, eventHandler, "localhost", 8080, false);

        boolean handled = handler.handleProtocolSwitch(HTTPStatus.SWITCHING_PROTOCOLS, upgradeHeaders());

        assertTrue("a connect-ip Upgrade response must be accepted", handled);
        assertNotNull("opened() should have been called", eventHandler.session);
        assertTrue("the handler must report itself as externally handling data from now on",
                handler.isExternallyHandled());
    }

    @Test
    public void testHandleProtocolSwitchRejectsUnrelatedUpgrade() {
        RecordingConnectIpHandler eventHandler = new RecordingConnectIpHandler();
        ConnectIpClientProtocolHandler handler =
                new ConnectIpClientProtocolHandler(null, eventHandler, "localhost", 8080, false);

        Headers headers = new Headers();
        headers.add(new Header("connection", "upgrade"));
        headers.add(new Header("upgrade", "websocket"));

        boolean handled = handler.handleProtocolSwitch(HTTPStatus.SWITCHING_PROTOCOLS, headers);

        assertFalse("an unrelated Upgrade response must not be claimed", handled);
        assertNull("opened() should not have been called", eventHandler.session);
        assertFalse(handler.isExternallyHandled());
    }

    @Test
    public void testReceiveAfterSwitchDeliversInboundPacket() throws Exception {
        RecordingConnectIpHandler eventHandler = new RecordingConnectIpHandler();
        ConnectIpClientProtocolHandler handler =
                new ConnectIpClientProtocolHandler(null, eventHandler, "localhost", 8080, false);
        handler.handleProtocolSwitch(HTTPStatus.SWITCHING_PROTOCOLS, upgradeHeaders());

        byte[] ipPacket = "hello-target".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer contextEncoded =
                HttpDatagramContext.encode(HttpDatagramContext.REGISTERED_CONTEXT_ID, ByteBuffer.wrap(ipPacket));
        byte[] contextBytes = new byte[contextEncoded.remaining()];
        contextEncoded.get(contextBytes);
        byte[] capsuleBytes = Capsule.datagram(contextBytes).encode();

        handler.receive(ByteBuffer.wrap(capsuleBytes));

        assertNotNull("packetReceived() should have been called", eventHandler.lastPacket);
        byte[] delivered = new byte[eventHandler.lastPacket.remaining()];
        eventHandler.lastPacket.get(delivered);
        assertArrayEquals(ipPacket, delivered);
    }

    @Test
    public void testReceiveAfterSwitchDeliversAddressAssignCapsule() throws Exception {
        RecordingConnectIpHandler eventHandler = new RecordingConnectIpHandler();
        ConnectIpClientProtocolHandler handler =
                new ConnectIpClientProtocolHandler(null, eventHandler, "localhost", 8080, false);
        handler.handleProtocolSwitch(HTTPStatus.SWITCHING_PROTOCOLS, upgradeHeaders());

        InetAddress assignedAddress = InetAddress.getByName("192.0.2.5");
        ByteBuffer capsuleValue = ConnectIpAddress.encodeList(
                Arrays.asList(new ConnectIpAddress(1, assignedAddress, 32)));
        byte[] valueBytes = new byte[capsuleValue.remaining()];
        capsuleValue.get(valueBytes);
        byte[] capsuleBytes = new Capsule(ConnectIpAddress.TYPE_ADDRESS_ASSIGN, valueBytes).encode();

        handler.receive(ByteBuffer.wrap(capsuleBytes));

        assertNotNull("addressAssigned() should have been called", eventHandler.lastAssigned);
        assertEquals(assignedAddress, eventHandler.lastAssigned.get(0).getAddress());
    }

    @Test
    public void testReceiveAfterSwitchDeliversRouteAdvertisementCapsule() throws Exception {
        RecordingConnectIpHandler eventHandler = new RecordingConnectIpHandler();
        ConnectIpClientProtocolHandler handler =
                new ConnectIpClientProtocolHandler(null, eventHandler, "localhost", 8080, false);
        handler.handleProtocolSwitch(HTTPStatus.SWITCHING_PROTOCOLS, upgradeHeaders());

        InetAddress start = InetAddress.getByName("10.0.0.0");
        InetAddress end = InetAddress.getByName("10.0.0.255");
        ByteBuffer capsuleValue = ConnectIpRoute.encodeList(
                Arrays.asList(new ConnectIpRoute(start, end, ConnectIpRoute.IP_PROTOCOL_ALL)));
        byte[] valueBytes = new byte[capsuleValue.remaining()];
        capsuleValue.get(valueBytes);
        byte[] capsuleBytes = new Capsule(ConnectIpRoute.TYPE_ROUTE_ADVERTISEMENT, valueBytes).encode();

        handler.receive(ByteBuffer.wrap(capsuleBytes));

        assertNotNull("routeAdvertised() should have been called", eventHandler.lastRoutes);
        assertEquals(start, eventHandler.lastRoutes.get(0).getStartAddress());
    }

    @Test
    public void testSendPacketProducesCorrectlyFramedCapsule() throws Exception {
        RecordingConnectIpHandler eventHandler = new RecordingConnectIpHandler();
        ConnectIpClientProtocolHandler handler =
                new ConnectIpClientProtocolHandler(null, eventHandler, "localhost", 8080, false);
        RecordingEndpoint endpoint = new RecordingEndpoint();
        handler.endpoint = endpoint;

        handler.handleProtocolSwitch(HTTPStatus.SWITCHING_PROTOCOLS, upgradeHeaders());
        assertNotNull(eventHandler.session);

        byte[] ipPacket = "to-target".getBytes(StandardCharsets.US_ASCII);
        eventHandler.session.sendPacket(ByteBuffer.wrap(ipPacket));

        assertNotNull("a capsule should have been written to the endpoint", endpoint.lastSent);
        CapsuleParser parser = new CapsuleParser();
        List<Capsule> capsules = parser.push(ByteBuffer.wrap(endpoint.lastSent));
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
        ConnectIpClientProtocolHandler handler =
                new ConnectIpClientProtocolHandler(null, eventHandler, "localhost", 8080, false);
        RecordingEndpoint endpoint = new RecordingEndpoint();
        handler.endpoint = endpoint;

        handler.handleProtocolSwitch(HTTPStatus.SWITCHING_PROTOCOLS, upgradeHeaders());
        assertNotNull(eventHandler.session);

        InetAddress requested = InetAddress.getByName("0.0.0.0");
        eventHandler.session.sendAddressRequest(Arrays.asList(new ConnectIpAddress(9, requested, 0)));

        assertNotNull("a capsule should have been written to the endpoint", endpoint.lastSent);
        CapsuleParser parser = new CapsuleParser();
        List<Capsule> capsules = parser.push(ByteBuffer.wrap(endpoint.lastSent));
        assertEquals(1, capsules.size());
        assertEquals(ConnectIpAddress.TYPE_ADDRESS_REQUEST, capsules.get(0).getType());

        List<ConnectIpAddress> decoded = ConnectIpAddress.decodeList(ByteBuffer.wrap(capsules.get(0).getValue()));
        assertEquals(1, decoded.size());
        assertEquals(9, decoded.get(0).getRequestId());
        assertEquals(requested, decoded.get(0).getAddress());
    }

    @Test
    public void testDisconnectedAfterSwitchNotifiesClosed() {
        RecordingConnectIpHandler eventHandler = new RecordingConnectIpHandler();
        ConnectIpClientProtocolHandler handler =
                new ConnectIpClientProtocolHandler(null, eventHandler, "localhost", 8080, false);
        handler.handleProtocolSwitch(HTTPStatus.SWITCHING_PROTOCOLS, upgradeHeaders());

        handler.disconnected();

        assertTrue("closed() should have been called", eventHandler.closed);
    }

    private static Headers upgradeHeaders() {
        Headers headers = new Headers();
        headers.add(new Header("connection", "upgrade"));
        headers.add(new Header("upgrade", "connect-ip"));
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
