/*
 * QuicVersion2EndToEndTest.java
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

package org.bluezoo.gumdrop.quic;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.BeforeClass;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.StreamAcceptHandler;
import org.bluezoo.gumdrop.TestTlsFiles;
import org.bluezoo.gumdrop.quic.packet.QuicVersion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Real client and server engines over loopback UDP, speaking QUIC
 * version 2 (RFC 9369): a v2-only pair, a dual-stack pair preferring v2,
 * and a client whose v2 attempt against a v1-only server is answered by
 * Version Negotiation (RFC 9000 section 6.2) and falls back to v1.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicVersion2EndToEndTest {

    private static final String ALPN = "gumdrop-test";

    @BeforeClass
    public static void requireTlsFiles() {
        TestTlsFiles.assumeAvailable();
    }

    /**
     * Runs a ping/pong exchange between a server speaking {@code serverVersions}
     * and a client speaking {@code clientVersions}, returning the version the
     * client connection ended up using.
     */
    private static QuicVersion roundTrip(String serverVersions, String clientVersions, boolean requireRetry)
            throws Exception {
        SelectorLoop loop = new SelectorLoop(0);
        loop.start();
        QuicEngine serverEngine = null;
        QuicEngine clientEngine = null;
        try {
            QuicTransportFactory serverFactory = new QuicTransportFactory();
            serverFactory.setApplicationProtocols(ALPN);
            serverFactory.setCertFile(TestTlsFiles.certFile());
            serverFactory.setKeyFile(TestTlsFiles.keyFile());
            serverFactory.setVersions(serverVersions);
            serverFactory.setRequireRetry(requireRetry);
            serverFactory.start();

            final CountDownLatch serverReceivedFin = new CountDownLatch(1);
            final AtomicReference<Endpoint> serverStream = new AtomicReference<Endpoint>();
            serverEngine = serverFactory.createServerEngine(InetAddress.getLoopbackAddress(), 0,
                    new StreamAcceptHandler() {
                        @Override
                        public ProtocolHandler acceptStream(Endpoint stream) {
                            return new ProtocolHandler() {
                                @Override
                                public void connected(Endpoint endpoint) {
                                    serverStream.set(endpoint);
                                }

                                @Override
                                public void receive(ByteBuffer data) {
                                }

                                @Override
                                public void securityEstablished(SecurityInfo info) {
                                }

                                @Override
                                public void readFinished() {
                                    Endpoint endpoint = serverStream.get();
                                    endpoint.send(ByteBuffer.wrap("pong".getBytes(StandardCharsets.US_ASCII)));
                                    endpoint.close();
                                    serverReceivedFin.countDown();
                                }

                                @Override
                                public void disconnected() {
                                }

                                @Override
                                public void error(Exception cause) {
                                    fail("Server stream error: " + cause);
                                }
                            };
                        }
                    }, loop);
            int port = ((InetSocketAddress) serverEngine.getLocalAddress()).getPort();

            QuicTransportFactory clientFactory = new QuicTransportFactory();
            clientFactory.setApplicationProtocols(ALPN);
            clientFactory.setVerifyPeer(false);
            clientFactory.setVerifyHostname(false);
            clientFactory.setVersions(clientVersions);
            clientFactory.start();

            final CountDownLatch clientReceivedFin = new CountDownLatch(1);
            final AtomicReference<byte[]> clientReceived = new AtomicReference<byte[]>();
            clientEngine = clientFactory.connect(InetAddress.getLoopbackAddress(), port,
                    new ProtocolHandler() {
                        @Override
                        public void connected(Endpoint endpoint) {
                            endpoint.send(ByteBuffer.wrap("ping".getBytes(StandardCharsets.US_ASCII)));
                            endpoint.close();
                        }

                        @Override
                        public void receive(ByteBuffer data) {
                            byte[] bytes = new byte[data.remaining()];
                            data.get(bytes);
                            clientReceived.set(bytes);
                        }

                        @Override
                        public void securityEstablished(SecurityInfo info) {
                        }

                        @Override
                        public void readFinished() {
                            clientReceivedFin.countDown();
                        }

                        @Override
                        public void disconnected() {
                        }

                        @Override
                        public void error(Exception cause) {
                            fail("Client stream error: " + cause);
                        }
                    }, loop, TestTlsFiles.SERVER_NAME);

            assertTrue("server must see the client's FIN", serverReceivedFin.await(10, TimeUnit.SECONDS));
            assertTrue("client must see the server's FIN", clientReceivedFin.await(10, TimeUnit.SECONDS));
            assertEquals("pong", new String(clientReceived.get(), StandardCharsets.US_ASCII));

            Field f = QuicEngine.class.getDeclaredField("clientConnection");
            f.setAccessible(true);
            return ((QuicConnection) f.get(clientEngine)).getVersion();
        } finally {
            loop.shutdown();
            loop.awaitQuiesce(2000);
            if (clientEngine != null) {
                clientEngine.close();
            }
            if (serverEngine != null) {
                serverEngine.close();
            }
        }
    }

    @Test
    public void testV2OnlyHandshakeAndStream() throws Exception {
        assertEquals(QuicVersion.V2, roundTrip("2", "2", false));
    }

    @Test
    public void testV2HandshakeWithRetry() throws Exception {
        assertEquals(QuicVersion.V2, roundTrip("2", "2", true));
    }

    @Test
    public void testDualStackPrefersClientVersion() throws Exception {
        assertEquals(QuicVersion.V2, roundTrip("1,2", "2,1", false));
        assertEquals(QuicVersion.V1, roundTrip("1,2", "1,2", false));
    }

    @Test
    public void testV2ProbeFallsBackToV1ViaVersionNegotiation() throws Exception {
        assertEquals(QuicVersion.V1, roundTrip("1", "2,1", false));
    }

    @Test
    public void testV1ProbeFallsBackToV2ViaVersionNegotiation() throws Exception {
        assertEquals(QuicVersion.V2, roundTrip("2", "1,2", false));
    }
}
