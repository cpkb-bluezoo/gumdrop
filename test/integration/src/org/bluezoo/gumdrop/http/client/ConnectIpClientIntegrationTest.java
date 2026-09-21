/*
 * ConnectIpClientIntegrationTest.java
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
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.TestTlsFiles;
import org.bluezoo.gumdrop.http.ConnectIpAddress;
import org.bluezoo.gumdrop.http.ConnectIpTarget;
import org.bluezoo.gumdrop.http.HttpServer;
import org.bluezoo.gumdrop.http.server.ConnectIpPolicy;
import org.bluezoo.gumdrop.http.server.ConnectIpRequestHandler;
import org.bluezoo.gumdrop.http.server.ConnectIpSession;
import org.bluezoo.gumdrop.http.server.Http2Listener;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponseState;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.bluezoo.gumdrop.http.server.IpPacketHandler;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end tests for {@link ConnectIpClient} against a real {@link HttpServer}
 * running {@link ConnectIpRequestHandler}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ConnectIpClientIntegrationTest extends AbstractServerIntegrationTest {

    private static final int HTTP_PROXY_PORT = 18105;
    private static final int HTTPS_PROXY_PORT = 18106;
    private static final String TEST_HOST = "::1";
    private static final int TIMEOUT_SECONDS = 8;

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
                .streamHandler(new ConnectIpProxyHandlerFactory())
                .server();
        HttpServer secure = HttpServer.compose()
                .listener(new Http2Listener()
                        .port(HTTPS_PROXY_PORT)
                        .addresses(InetAddress.getByName(TEST_HOST))
                        .secure(true)
                        .tls(TestTlsFiles.serverTlsConfig()))
                .streamHandler(new ConnectIpProxyHandlerFactory())
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

    @Test
    public void testCleartextH2PriorKnowledgeEchoesIpPacket() throws Exception {
        EventSink events = new EventSink();
        ConnectIpClient client = new ConnectIpClient(TEST_HOST, HTTP_PROXY_PORT);
        client.setSecure(false);
        client.setH3Enabled(false);
        client.setDnsHttpsRecordEnabled(false);
        client.setH2Enabled(true);
        client.setH2WithPriorKnowledge(true);
        client.connect(gumdrop, ConnectIpTarget.WILDCARD, ConnectIpTarget.WILDCARD, events);

        assertTrue("CONNECT-IP tunnel should open", events.opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNotNull(events.session.get());

        byte[] outbound = "connect-ip-h2c-pk".getBytes(StandardCharsets.US_ASCII);
        events.session.get().sendPacket(ByteBuffer.wrap(outbound));

        assertTrue("echoed packet should arrive", events.packet.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        byte[] inbound = new byte[events.lastPacket.get().remaining()];
        events.lastPacket.get().get(inbound);
        assertArrayEquals(outbound, inbound);
        assertNull(events.error.get());

        client.close();
    }

    @Test
    public void testHttp2ExtendedConnectEchoesIpPacket() throws Exception {
        EventSink events = new EventSink();
        ConnectIpClient client = newClient(HTTPS_PROXY_PORT, true);
        client.connect(gumdrop, ConnectIpTarget.WILDCARD, ConnectIpTarget.WILDCARD, events);

        assertTrue("CONNECT-IP tunnel should open over h2", events.opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNotNull(events.session.get());
        assertTrue(client.isOpen());

        byte[] outbound = "h2-connect-ip".getBytes(StandardCharsets.US_ASCII);
        events.session.get().sendPacket(ByteBuffer.wrap(outbound));

        assertTrue("echoed packet should arrive", events.packet.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        byte[] inbound = new byte[events.lastPacket.get().remaining()];
        events.lastPacket.get().get(inbound);
        assertArrayEquals(outbound, inbound);

        client.close();
    }

    @Test
    public void testAddressRequestReceivesAssignment() throws Exception {
        EventSink events = new EventSink();
        ConnectIpClient client = newClient(HTTPS_PROXY_PORT, true);
        client.connect(gumdrop, ConnectIpTarget.WILDCARD, ConnectIpTarget.WILDCARD, events);

        assertTrue(events.opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        InetAddress hint = InetAddress.getByName("192.0.2.10");
        events.session.get().sendAddressRequest(
                List.of(new ConnectIpAddress(7, hint, 32)));

        assertTrue("address assignment expected", events.addressAssigned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(1, events.assignments.get().size());
        assertEquals(7, events.assignments.get().get(0).getRequestId());
        assertEquals(hint, events.assignments.get().get(0).getAddress());

        client.close();
    }

    private ConnectIpClient newClient(int port, boolean secure) throws Exception {
        ConnectIpClient client = new ConnectIpClient(TEST_HOST, port);
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

    private static final class EventSink implements ConnectIpEventHandler {
        final CountDownLatch opened = new CountDownLatch(1);
        final CountDownLatch packet = new CountDownLatch(1);
        final CountDownLatch addressAssigned = new CountDownLatch(1);
        final AtomicReference<ConnectIpClientSession> session = new AtomicReference<ConnectIpClientSession>();
        final AtomicReference<ByteBuffer> lastPacket = new AtomicReference<ByteBuffer>();
        final AtomicReference<List<ConnectIpAddress>> assignments =
                new AtomicReference<List<ConnectIpAddress>>();
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();

        @Override
        public void opened(ConnectIpClientSession session) {
            this.session.set(session);
            opened.countDown();
        }

        @Override
        public void packetReceived(ByteBuffer packet) {
            lastPacket.set(packet.duplicate());
            this.packet.countDown();
        }

        @Override
        public void addressAssigned(List<ConnectIpAddress> assignments) {
            this.assignments.set(assignments);
            addressAssigned.countDown();
        }

        @Override
        public void routeAdvertised(List<org.bluezoo.gumdrop.http.ConnectIpRoute> routes) {
        }

        @Override
        public void closed() {
        }

        @Override
        public void error(Throwable cause) {
            error.set(cause);
            opened.countDown();
            packet.countDown();
            addressAssigned.countDown();
        }
    }

    /** Echoes IP packets and echoes address requests back as assignments. */
    private static final class EchoIpPacketHandler implements IpPacketHandler {
        @Override
        public void opened(ConnectIpSession session) {
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
        }

        @Override
        public void failed(ConnectIpSession session, Exception cause) {
        }
    }

    private static final class ConnectIpProxyHandlerFactory implements HttpStreamHandler {
        private final ConnectIpPolicy permissive = new ConnectIpPolicy() {
            @Override
            public boolean isRequestAllowed(ConnectIpTarget target) {
                return true;
            }
        };
        private final IpPacketHandler echo = new EchoIpPacketHandler();

        @Override
        public HttpRequestHandler openStream(HttpResponseState state) {
            return new ConnectIpRequestHandler(permissive, echo);
        }
    }
}
