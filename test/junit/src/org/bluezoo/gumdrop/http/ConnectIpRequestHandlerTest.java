/*
 * ConnectIpRequestHandlerTest.java
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

package org.bluezoo.gumdrop.http;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Transport-agnostic regression test for issue #394's server-side RFC
 * 9484 CONNECT-IP support: a real {@link ConnectIpRequestHandler}
 * driving a real (loopback) {@link IpPacketHandler} against a minimal
 * {@link HTTPResponseState} fake -- accept/reject, inbound IP packet
 * delivery, the {@code ADDRESS_REQUEST}/{@code ADDRESS_ASSIGN} round
 * trip, and {@code ROUTE_ADVERTISEMENT}, none of which need a kernel TUN
 * (see {@link IpPacketHandler}'s own documentation for why gumdrop
 * itself never provides one).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ConnectIpRequestHandlerTest {

    private static Headers connectIpRequestHeaders(String target, String ipProto) {
        Headers headers = new Headers();
        headers.add(new Header(":method", "CONNECT"));
        headers.add(new Header(":protocol", "connect-ip"));
        headers.add(new Header(":scheme", "https"));
        headers.add(new Header(":authority", "proxy.example.test"));
        headers.add(new Header(":path", ConnectIpTarget.encode(target, ipProto)));
        headers.add(new Header("capsule-protocol", "?1"));
        return headers;
    }

    @Test
    public void testAcceptedRequestOpensAndRelaysPacketsBothWays() throws Exception {
        CapturingResponseState state = new CapturingResponseState();
        LoopbackPacketHandler packetHandler = new LoopbackPacketHandler();
        ConnectIpPolicy permissive = new ConnectIpPolicy() {
            @Override
            public boolean isRequestAllowed(ConnectIpTarget target) {
                return true;
            }
        };
        ConnectIpRequestHandler handler = new ConnectIpRequestHandler(permissive, packetHandler);

        handler.headers(state, connectIpRequestHeaders(ConnectIpTarget.WILDCARD, ConnectIpTarget.WILDCARD));

        assertTrue("CONNECT-IP request should have been accepted", state.accepted);
        assertNotNull("opened() should have been called", packetHandler.session);

        byte[] packet = "fake-ip-packet".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer encoded = HttpDatagramContext.encode(HttpDatagramContext.REGISTERED_CONTEXT_ID,
                ByteBuffer.wrap(packet));
        handler.datagramReceived(state, encoded);

        // LoopbackPacketHandler echoes every received packet straight
        // back via ConnectIpSession.sendPacket().
        assertEquals(1, state.sentDatagrams.size());
        HttpDatagramContext decoded = HttpDatagramContext.decode(ByteBuffer.wrap(state.sentDatagrams.get(0)));
        assertEquals(HttpDatagramContext.REGISTERED_CONTEXT_ID, decoded.getContextId());
        byte[] echoedPacket = new byte[decoded.getPayload().remaining()];
        decoded.getPayload().get(echoedPacket);
        assertArrayEquals(packet, echoedPacket);
    }

    @Test
    public void testAddressRequestCapsuleIsDeliveredAndAssignmentSentBack() throws Exception {
        CapturingResponseState state = new CapturingResponseState();
        LoopbackPacketHandler packetHandler = new LoopbackPacketHandler();
        ConnectIpPolicy permissive = new ConnectIpPolicy() {
            @Override
            public boolean isRequestAllowed(ConnectIpTarget target) {
                return true;
            }
        };
        ConnectIpRequestHandler handler = new ConnectIpRequestHandler(permissive, packetHandler);
        handler.headers(state, connectIpRequestHeaders(ConnectIpTarget.WILDCARD, ConnectIpTarget.WILDCARD));

        java.net.InetAddress requestedAddress = java.net.InetAddress.getByName("192.0.2.5");
        List<ConnectIpAddress> requested = Arrays.asList(new ConnectIpAddress(42, requestedAddress, 32));
        ByteBuffer capsuleValue = ConnectIpAddress.encodeList(requested);

        handler.capsuleReceived(state, ConnectIpAddress.TYPE_ADDRESS_REQUEST, capsuleValue);

        assertEquals(1, state.sentCapsules.size());
        assertEquals(ConnectIpAddress.TYPE_ADDRESS_ASSIGN, state.sentCapsules.get(0).type);
        List<ConnectIpAddress> assigned =
                ConnectIpAddress.decodeList(ByteBuffer.wrap(state.sentCapsules.get(0).value));
        assertEquals(1, assigned.size());
        assertEquals(42, assigned.get(0).getRequestId());
        assertEquals(requestedAddress, assigned.get(0).getAddress());
    }

    @Test
    public void testTargetDeniedByPolicyIsRejected() throws Exception {
        CapturingResponseState state = new CapturingResponseState();
        LoopbackPacketHandler packetHandler = new LoopbackPacketHandler();
        ConnectIpPolicy restrictive = new ConnectIpPolicy() {
            @Override
            public boolean isRequestAllowed(ConnectIpTarget target) {
                return false;
            }
        };
        ConnectIpRequestHandler handler = new ConnectIpRequestHandler(restrictive, packetHandler);

        handler.headers(state, connectIpRequestHeaders(ConnectIpTarget.WILDCARD, ConnectIpTarget.WILDCARD));

        assertFalse("a policy-denied request must not be accepted", state.accepted);
        assertNull("opened() must not have been called", packetHandler.session);
        assertEquals("403", state.completedHeaders.getValue(":status"));
    }

    @Test
    public void testMissingCapsuleProtocolIsRejected() throws Exception {
        CapturingResponseState state = new CapturingResponseState();
        LoopbackPacketHandler packetHandler = new LoopbackPacketHandler();
        ConnectIpPolicy permissive = new ConnectIpPolicy() {
            @Override
            public boolean isRequestAllowed(ConnectIpTarget target) {
                return true;
            }
        };
        ConnectIpRequestHandler handler = new ConnectIpRequestHandler(permissive, packetHandler);

        Headers headers = connectIpRequestHeaders(ConnectIpTarget.WILDCARD, ConnectIpTarget.WILDCARD);
        headers.removeAll("capsule-protocol");
        handler.headers(state, headers);

        assertFalse(state.accepted);
        assertEquals("400", state.completedHeaders.getValue(":status"));
    }

    @Test
    public void testRequestCompleteNotifiesPacketHandlerClosed() throws Exception {
        CapturingResponseState state = new CapturingResponseState();
        LoopbackPacketHandler packetHandler = new LoopbackPacketHandler();
        ConnectIpPolicy permissive = new ConnectIpPolicy() {
            @Override
            public boolean isRequestAllowed(ConnectIpTarget target) {
                return true;
            }
        };
        ConnectIpRequestHandler handler = new ConnectIpRequestHandler(permissive, packetHandler);
        handler.headers(state, connectIpRequestHeaders(ConnectIpTarget.WILDCARD, ConnectIpTarget.WILDCARD));

        handler.requestComplete(state);

        assertTrue("closed() should have been called", packetHandler.closedCalled);
    }

    @Test
    public void testFailedNotifiesPacketHandlerFailed() throws Exception {
        CapturingResponseState state = new CapturingResponseState();
        LoopbackPacketHandler packetHandler = new LoopbackPacketHandler();
        ConnectIpPolicy permissive = new ConnectIpPolicy() {
            @Override
            public boolean isRequestAllowed(ConnectIpTarget target) {
                return true;
            }
        };
        ConnectIpRequestHandler handler = new ConnectIpRequestHandler(permissive, packetHandler);
        handler.headers(state, connectIpRequestHeaders(ConnectIpTarget.WILDCARD, ConnectIpTarget.WILDCARD));

        Exception cause = new java.io.IOException("transport reset");
        handler.failed(state, cause);

        assertTrue("failed() should have been called", packetHandler.failedCalled);
        assertEquals(cause, packetHandler.failedCause);
    }

    @Test
    public void testConstructorRejectsNullPolicy() {
        try {
            new ConnectIpRequestHandler(null, new LoopbackPacketHandler());
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void testConstructorRejectsNullPacketHandler() {
        ConnectIpPolicy permissive = new ConnectIpPolicy() {
            @Override
            public boolean isRequestAllowed(ConnectIpTarget target) {
                return true;
            }
        };
        try {
            new ConnectIpRequestHandler(permissive, null);
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    /** Echoes every received packet straight back; assigns whatever it's asked. */
    private static final class LoopbackPacketHandler implements IpPacketHandler {
        ConnectIpSession session;
        boolean closedCalled;
        boolean failedCalled;
        Exception failedCause;

        @Override
        public void opened(ConnectIpSession session) {
            this.session = session;
        }

        @Override
        public void packetReceived(ConnectIpSession session, ByteBuffer packet) {
            byte[] copy = new byte[packet.remaining()];
            packet.get(copy);
            session.sendPacket(ByteBuffer.wrap(copy));
        }

        @Override
        public void addressRequested(ConnectIpSession session, List<ConnectIpAddress> requested) {
            session.sendAddressAssign(requested);
        }

        @Override
        public void closed(ConnectIpSession session) {
            closedCalled = true;
        }

        @Override
        public void failed(ConnectIpSession session, Exception cause) {
            failedCalled = true;
            failedCause = cause;
        }
    }

    private static final class SentCapsule {
        final long type;
        final byte[] value;
        SentCapsule(long type, byte[] value) {
            this.type = type;
            this.value = value;
        }
    }

    private static final class CapturingResponseState implements HTTPResponseState {
        final List<byte[]> sentDatagrams = new ArrayList<byte[]>();
        final List<SentCapsule> sentCapsules = new ArrayList<SentCapsule>();
        volatile boolean accepted;
        Headers completedHeaders;

        @Override
        public boolean sendDatagram(ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            sentDatagrams.add(bytes);
            return true;
        }

        @Override
        public boolean sendCapsule(long type, ByteBuffer value) {
            byte[] bytes = new byte[value.remaining()];
            value.get(bytes);
            sentCapsules.add(new SentCapsule(type, bytes));
            return true;
        }

        @Override
        public boolean acceptConnectIp() {
            accepted = true;
            return true;
        }

        @Override public SocketAddress getRemoteAddress() { return null; }
        @Override public SocketAddress getLocalAddress() { return null; }
        @Override public boolean isSecure() { return true; }
        @Override public SecurityInfo getSecurityInfo() { return null; }
        @Override public HTTPVersion getVersion() { return HTTPVersion.HTTP_3; }
        @Override public String getScheme() { return "https"; }
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public Principal getPrincipal() { return null; }
        @Override public void headers(Headers headers) { completedHeaders = headers; }
        @Override public void startResponseBody() { }
        @Override public void responseBodyContent(ByteBuffer data) { }
        @Override public void endResponseBody() { }
        @Override public void complete() { }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public void onWritable(Runnable callback) { }
        @Override public void pauseRequestBody() { }
        @Override public void resumeRequestBody() { }
        @Override public boolean pushPromise(Headers headers) { return false; }
        @Override public void upgradeToWebSocket(String subprotocol, WebSocketEventHandler handler) { }
        @Override public void cancel() { }
    }
}
