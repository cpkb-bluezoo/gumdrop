/*
 * ConnectUdpRelayEndToEndTest.java
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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.UDPEndpoint;
import org.bluezoo.gumdrop.UDPTransportFactory;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Production round-trip test for issue #393's server-side RFC 9298
 * CONNECT-UDP relay: a real UDP echo server, a real {@link
 * ConnectUdpRequestHandler} performing real (loopback-literal) DNS
 * resolution and a real policy check, and a real {@link
 * ConnectUdpRelay} underneath it -- the same components a live
 * HTTP/1.1, HTTP/2, or HTTP/3 CONNECT-UDP request would drive, exercised
 * directly against a minimal {@link HTTPResponseState} rather than a
 * full client, since the client-side helper is deferred (see the
 * issue's follow-up).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ConnectUdpRelayEndToEndTest {

    private SelectorLoop loop;
    private UDPEndpoint echoServer;

    @Before
    public void startLoopAndEchoServer() throws Exception {
        loop = new SelectorLoop(0);
        loop.start();
        UDPTransportFactory factory = new UDPTransportFactory();
        factory.start();
        echoServer = factory.createServerEndpoint(
                InetAddress.getLoopbackAddress(), 0, new EchoHandler(), loop);
    }

    @After
    public void stopLoopAndEchoServer() throws Exception {
        if (echoServer != null && echoServer.isOpen()) {
            echoServer.close();
        }
        if (loop != null) {
            loop.shutdown();
            loop.awaitQuiesce(2000);
        }
    }

    /** Echoes every received datagram straight back to its sender. */
    private static final class EchoHandler implements ProtocolHandler {
        private UDPEndpoint self;

        @Override
        public void connected(Endpoint endpoint) {
            this.self = (UDPEndpoint) endpoint;
        }

        @Override
        public void receive(ByteBuffer data) {
            InetSocketAddress source = (InetSocketAddress) self.getRemoteAddress();
            self.sendTo(data, source);
        }

        @Override
        public void disconnected() {
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
        }

        @Override
        public void error(Exception cause) {
        }
    }

    private static final class CapturingResponseState implements HTTPResponseState {
        private final SelectorLoop loop;
        final java.util.List<byte[]> sentDatagrams =
                java.util.Collections.synchronizedList(new java.util.ArrayList<byte[]>());
        final CountDownLatch acceptedLatch = new CountDownLatch(1);
        final CountDownLatch datagramLatch = new CountDownLatch(1);
        final CountDownLatch completeLatch = new CountDownLatch(1);
        volatile boolean accepted;

        CapturingResponseState(SelectorLoop loop) {
            this.loop = loop;
        }

        @Override
        public boolean sendDatagram(ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            sentDatagrams.add(bytes);
            datagramLatch.countDown();
            return true;
        }

        @Override
        public boolean acceptConnectUdp() {
            accepted = true;
            acceptedLatch.countDown();
            return true;
        }

        @Override public SocketAddress getRemoteAddress() { return null; }
        @Override public SocketAddress getLocalAddress() { return null; }
        @Override public boolean isSecure() { return true; }
        @Override public SecurityInfo getSecurityInfo() { return null; }
        @Override public HTTPVersion getVersion() { return HTTPVersion.HTTP_3; }
        @Override public String getScheme() { return "https"; }
        @Override public SelectorLoop getSelectorLoop() { return loop; }
        @Override public Principal getPrincipal() { return null; }
        @Override public void headers(Headers headers) { }
        @Override public void startResponseBody() { }
        @Override public void responseBodyContent(ByteBuffer data) { }
        @Override public void endResponseBody() { }
        @Override public void complete() { completeLatch.countDown(); }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public void onWritable(Runnable callback) { }
        @Override public void pauseRequestBody() { }
        @Override public void resumeRequestBody() { }
        @Override public boolean pushPromise(Headers headers) { return false; }
        @Override public void upgradeToWebSocket(String subprotocol, WebSocketEventHandler handler) { }
        @Override public void cancel() { }
    }

    private static Headers connectUdpRequestHeaders(String targetHost, int targetPort) {
        Headers headers = new Headers();
        headers.add(new Header(":method", "CONNECT"));
        headers.add(new Header(":protocol", "connect-udp"));
        headers.add(new Header(":scheme", "https"));
        headers.add(new Header(":authority", "proxy.example.test"));
        headers.add(new Header(":path", ConnectUdpTarget.encode(targetHost, targetPort)));
        headers.add(new Header("capsule-protocol", "?1"));
        return headers;
    }

    @Test
    public void testDatagramRelayedToRealUdpEchoServerAndBack() throws Exception {
        InetSocketAddress echoAddress = (InetSocketAddress) echoServer.getLocalAddress();

        final CapturingResponseState state = new CapturingResponseState(loop);
        ConnectUdpPolicy permissive = new ConnectUdpPolicy() {
            @Override
            public boolean isTargetAllowed(InetAddress address, int port) {
                return true;
            }
        };
        ConnectUdpRequestHandler handler = new ConnectUdpRequestHandler(permissive) {
        };

        loop.invokeLater(new Runnable() {
            @Override
            public void run() {
                handler.headers(state, connectUdpRequestHeaders(
                        echoAddress.getAddress().getHostAddress(), echoAddress.getPort()));
            }
        });

        assertTrue("CONNECT-UDP request should have been accepted within 5s",
                state.acceptedLatch.await(5, TimeUnit.SECONDS));

        byte[] payload = "hello-connect-udp".getBytes(StandardCharsets.US_ASCII);
        final ByteBuffer encoded = HttpDatagramContext.encode(HttpDatagramContext.REGISTERED_CONTEXT_ID,
                ByteBuffer.wrap(payload));
        loop.invokeLater(new Runnable() {
            @Override
            public void run() {
                handler.datagramReceived(state, encoded);
            }
        });

        assertTrue("echo reply should have been relayed back within 5s",
                state.datagramLatch.await(5, TimeUnit.SECONDS));
        assertEquals("exactly one datagram (the echo reply) should have been sent back",
                1, state.sentDatagrams.size());

        HttpDatagramContext decoded = HttpDatagramContext.decode(ByteBuffer.wrap(state.sentDatagrams.get(0)));
        assertEquals(HttpDatagramContext.REGISTERED_CONTEXT_ID, decoded.getContextId());
        byte[] echoedPayload = new byte[decoded.getPayload().remaining()];
        decoded.getPayload().get(echoedPayload);
        assertArrayEquals(payload, echoedPayload);
    }

    @Test
    public void testTargetDeniedByPolicyIsRejected() throws Exception {
        final CapturingResponseState state = new CapturingResponseState(loop);
        ConnectUdpPolicy restrictive = new ConnectUdpPolicy() {
            @Override
            public boolean isTargetAllowed(InetAddress address, int port) {
                return false;
            }
        };
        final ConnectUdpRequestHandler handler = new ConnectUdpRequestHandler(restrictive) {
        };

        loop.invokeLater(new Runnable() {
            @Override
            public void run() {
                handler.headers(state, connectUdpRequestHeaders("127.0.0.1", 9999));
            }
        });

        assertTrue("the policy-denied request should have completed (rejected) within 5s",
                state.completeLatch.await(5, TimeUnit.SECONDS));
        assertTrue("a policy-denied target must not be accepted", !state.accepted);
    }
}
