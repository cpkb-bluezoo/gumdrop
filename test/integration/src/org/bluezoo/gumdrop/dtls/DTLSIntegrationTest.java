/*
 * DTLSIntegrationTest.java
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

package org.bluezoo.gumdrop.dtls;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.UdpEndpoint;
import org.bluezoo.gumdrop.TestTlsFiles;
import org.bluezoo.gumdrop.UdpTransportFactory;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * End-to-end test of DTLS support in {@link UdpEndpoint} (issue #190):
 * a real client and server, each with their own {@link UdpTransportFactory},
 * talking DTLS 1.2 over real loopback UDP sockets through a running
 * {@link Gumdrop} instance -- no mocking of the network or the record engine.
 *
 * <p>Server identity comes from {@code etc/tls/cert.pem} and {@code key.pem};
 * the client trusts {@code ca.pem} only ({@link TestTlsFiles}).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DTLSIntegrationTest {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final long TIMEOUT_SECONDS = 10;

    private Gumdrop gumdrop;
    private UdpEndpoint serverEndpoint;
    private UdpEndpoint clientEndpoint;

    @BeforeClass
    public static void requireTlsFixtures() {
        TestTlsFiles.assumeAvailable();
    }

    @Before
    public void setUp() {
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(2));
    }

    private static UdpTransportFactory newServerFactory() throws Exception {
        UdpTransportFactory factory = new UdpTransportFactory();
        factory.setSecure(true);
        factory.setCertFile(TestTlsFiles.certFile());
        factory.setKeyFile(TestTlsFiles.keyFile());
        factory.start();
        return factory;
    }

    private static UdpTransportFactory newClientFactory() throws Exception {
        UdpTransportFactory factory = new UdpTransportFactory();
        factory.setSecure(true);
        factory.setTrustManager(TestTlsFiles.trustManager());
        factory.start();
        return factory;
    }

    @After
    public void tearDown() {
        if (clientEndpoint != null) {
            clientEndpoint.close();
        }
        if (serverEndpoint != null) {
            serverEndpoint.close();
        }
        if (gumdrop != null && gumdrop.isStarted()) {
            gumdrop.shutdown();
        }
    }

    /** Finds a free UDP port for the server to bind rather than hardcoding one. */
    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Echoes every decrypted application datagram straight back to its sender. */
    private static final class EchoHandler implements ProtocolHandler {
        volatile Endpoint endpoint;
        final List<String> received = new CopyOnWriteArrayList<String>();

        @Override
        public void receive(ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            received.add(new String(bytes, StandardCharsets.UTF_8));
            endpoint.send(ByteBuffer.wrap(bytes));
        }

        @Override
        public void connected(Endpoint endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public void disconnected() {
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
        }

        @Override
        public void error(Exception cause) {
            // Server echo handler: failures are logged by the endpoint.
        }
    }

    /** Records the single reply the client expects back from the echo server. */
    private static final class ClientHandler implements ProtocolHandler {
        final CountDownLatch securityLatch = new CountDownLatch(1);
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final AtomicReference<SecurityInfo> securityInfo = new AtomicReference<SecurityInfo>();
        final AtomicReference<String> reply = new AtomicReference<String>();
        final AtomicReference<Exception> error = new AtomicReference<Exception>();
        volatile Endpoint endpoint;

        @Override
        public void receive(ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            reply.set(new String(bytes, StandardCharsets.UTF_8));
            replyLatch.countDown();
        }

        @Override
        public void connected(Endpoint endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public void disconnected() {
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
            securityInfo.set(info);
            securityLatch.countDown();
        }

        @Override
        public void error(Exception cause) {
            error.set(cause);
            securityLatch.countDown();
            replyLatch.countDown();
        }
    }

    @Test
    public void testHandshakeAndEchoRoundTrip() throws Exception {
        int port = freePort();

        UdpTransportFactory serverFactory = newServerFactory();

        EchoHandler echoHandler = new EchoHandler();
        serverEndpoint = serverFactory.createServerEndpoint(gumdrop, LOOPBACK, port, echoHandler);

        UdpTransportFactory clientFactory = newClientFactory();

        ClientHandler clientHandler = new ClientHandler();
        clientEndpoint = clientFactory.connect(gumdrop, LOOPBACK, port, clientHandler);

        assertTrue("DTLS handshake should complete and notify the client",
                clientHandler.securityLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNull("DTLS client error: " + clientHandler.error.get(), clientHandler.error.get());
        assertEquals("DTLSv1.2", clientHandler.securityInfo.get().getProtocol());

        clientEndpoint.send(ByteBuffer.wrap("hello over DTLS".getBytes(StandardCharsets.UTF_8)));

        assertTrue("client should have received the echoed reply",
                clientHandler.replyLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals("hello over DTLS", clientHandler.reply.get());
        assertEquals("server should have decrypted exactly one datagram",
                1, echoHandler.received.size());
        assertEquals("hello over DTLS", echoHandler.received.get(0));
    }

    @Test
    public void testTwoConcurrentClientsGetIndependentSessions() throws Exception {
        int port = freePort();

        UdpTransportFactory serverFactory = newServerFactory();

        EchoHandler echoHandler = new EchoHandler();
        serverEndpoint = serverFactory.createServerEndpoint(gumdrop, LOOPBACK, port, echoHandler);

        UdpTransportFactory clientFactory1 = newClientFactory();
        UdpTransportFactory clientFactory2 = newClientFactory();

        ClientHandler client1Handler = new ClientHandler();
        ClientHandler client2Handler = new ClientHandler();
        clientEndpoint = clientFactory1.connect(gumdrop, LOOPBACK, port, client1Handler);
        UdpEndpoint clientEndpoint2 = clientFactory2.connect(
                gumdrop, LOOPBACK, port, client2Handler);
        try {
            assertTrue(client1Handler.securityLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertNull(client1Handler.error.get());
            assertTrue(client2Handler.securityLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertNull(client2Handler.error.get());

            clientEndpoint.send(ByteBuffer.wrap("from client one".getBytes(StandardCharsets.UTF_8)));
            clientEndpoint2.send(ByteBuffer.wrap("from client two".getBytes(StandardCharsets.UTF_8)));

            assertTrue(client1Handler.replyLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertTrue(client2Handler.replyLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            assertEquals("from client one", client1Handler.reply.get());
            assertEquals("from client two", client2Handler.reply.get());
        } finally {
            clientEndpoint2.close();
        }
    }

}
