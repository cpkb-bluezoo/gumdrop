/*
 * ConnectUdpClientIntegrationTest.java
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

package org.bluezoo.gumdrop.http.client;

import org.bluezoo.gumdrop.AbstractServerIntegrationTest;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.TestTlsFiles;
import org.bluezoo.gumdrop.UdpEndpoint;
import org.bluezoo.gumdrop.UdpTransportFactory;
import org.bluezoo.gumdrop.http.HttpServer;
import org.bluezoo.gumdrop.http.server.ConnectUdpPolicy;
import org.bluezoo.gumdrop.http.server.ConnectUdpRequestHandler;
import org.bluezoo.gumdrop.http.server.Http2Listener;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponseState;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end tests for {@link ConnectUdpClient} through a real CONNECT-UDP proxy.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ConnectUdpClientIntegrationTest extends AbstractServerIntegrationTest {

    private static final int HTTP_PROXY_PORT = 18107;
    private static final int HTTPS_PROXY_PORT = 18108;
    private static final String TEST_HOST = "::1";
    private static final int TIMEOUT_SECONDS = 8;

    private UdpEndpoint udpEchoServer;
    private int udpEchoPort;

    @Rule
    public Timeout globalTimeout = Timeout.builder()
            .withTimeout(TIMEOUT_SECONDS * 3L, TimeUnit.SECONDS)
            .withLookingForStuckThread(true)
            .build();

    @Override
    protected Collection<? extends Server> buildServers() throws Exception {
        HttpServer plaintext = HttpServer.compose()
                .listener(new Http2Listener()
                        .port(HTTP_PROXY_PORT)
                        .addresses(InetAddress.getByName(TEST_HOST)))
                .streamHandler(new ConnectUdpProxyHandlerFactory())
                .server();
        HttpServer secure = HttpServer.compose()
                .listener(new Http2Listener()
                        .port(HTTPS_PROXY_PORT)
                        .addresses(InetAddress.getByName(TEST_HOST))
                        .secure(true)
                        .tls(TestTlsFiles.serverTlsConfig()))
                .streamHandler(new ConnectUdpProxyHandlerFactory())
                .server();
        return Arrays.asList(plaintext, secure);
    }

    @Override
    protected Level getTestLogLevel() {
        return Level.WARNING;
    }

    @BeforeClass
    public static void requireTlsFixtures() {
        TestTlsFiles.assumeAvailable();
    }

    @Before
    public void startUdpEcho() throws Exception {
        UdpTransportFactory factory = new UdpTransportFactory();
        factory.start();
        udpEchoServer = factory.createServerEndpoint(
                gumdrop, InetAddress.getLoopbackAddress(), 0, new UdpEchoHandler(),
                gumdrop.nextWorkerLoop());
        udpEchoPort = ((InetSocketAddress) udpEchoServer.getLocalAddress()).getPort();
    }

    @org.junit.After
    public void stopUdpEcho() {
        if (udpEchoServer != null && udpEchoServer.isOpen()) {
            udpEchoServer.close();
        }
    }

    @Test
    public void testCleartextH2PriorKnowledgeRelaysDatagram() throws Exception {
        EventSink events = new EventSink();
        ConnectUdpClient client = new ConnectUdpClient(TEST_HOST, HTTP_PROXY_PORT);
        client.setSecure(false);
        client.setH3Enabled(false);
        client.setDnsHttpsRecordEnabled(false);
        client.setH2Enabled(true);
        client.setH2WithPriorKnowledge(true);
        client.connect(gumdrop, "127.0.0.1", udpEchoPort, events);

        assertTrue(events.opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNotNull(events.session.get());

        byte[] payload = "udp-via-masque".getBytes(StandardCharsets.US_ASCII);
        events.session.get().sendDatagram(ByteBuffer.wrap(payload));

        assertTrue(events.datagram.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        byte[] received = new byte[events.lastDatagram.get().remaining()];
        events.lastDatagram.get().get(received);
        assertArrayEquals(payload, received);
        assertNull(events.error.get());

        client.close();
    }

    @Test
    public void testHttp2ExtendedConnectRelaysDatagram() throws Exception {
        EventSink events = new EventSink();
        ConnectUdpClient client = newClient(HTTPS_PROXY_PORT, true);
        client.connect(gumdrop, "127.0.0.1", udpEchoPort, events);

        assertTrue(events.opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNotNull(events.session.get());

        byte[] payload = "udp-h2-masque".getBytes(StandardCharsets.US_ASCII);
        events.session.get().sendDatagram(ByteBuffer.wrap(payload));

        assertTrue(events.datagram.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        byte[] received = new byte[events.lastDatagram.get().remaining()];
        events.lastDatagram.get().get(received);
        assertArrayEquals(payload, received);

        client.close();
    }

    private ConnectUdpClient newClient(int port, boolean secure) throws Exception {
        ConnectUdpClient client = new ConnectUdpClient(TEST_HOST, port);
        client.setSecure(secure);
        client.setH3Enabled(false);
        client.setDnsHttpsRecordEnabled(false);
        if (secure) {
            client.setTrustManager(TestTlsFiles.trustManager());
            client.setH2Enabled(true);
        } else {
            client.setH2Enabled(false);
        }
        return client;
    }

    private static final class EventSink implements ConnectUdpEventHandler {
        final CountDownLatch opened = new CountDownLatch(1);
        final CountDownLatch datagram = new CountDownLatch(1);
        final AtomicReference<ConnectUdpSession> session = new AtomicReference<ConnectUdpSession>();
        final AtomicReference<ByteBuffer> lastDatagram = new AtomicReference<ByteBuffer>();
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();

        @Override
        public void opened(ConnectUdpSession session) {
            this.session.set(session);
            opened.countDown();
        }

        @Override
        public void datagramReceived(ByteBuffer payload) {
            lastDatagram.set(payload.duplicate());
            datagram.countDown();
        }

        @Override
        public void closed() {
        }

        @Override
        public void error(Throwable cause) {
            error.set(cause);
            opened.countDown();
            datagram.countDown();
        }
    }

    private static final class UdpEchoHandler implements ProtocolHandler {
        private UdpEndpoint self;

        @Override
        public void connected(Endpoint endpoint) {
            self = (UdpEndpoint) endpoint;
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

    private static final class ConnectUdpProxyHandlerFactory implements HttpStreamHandler {
        private final ConnectUdpPolicy permissive = new ConnectUdpPolicy() {
            @Override
            public boolean isTargetAllowed(InetAddress address, int port) {
                return true;
            }
        };

        @Override
        public HttpRequestHandler openStream(HttpResponseState state) {
            return new ConnectUdpRequestHandler(permissive);
        }
    }
}
