/*
 * QuicProductionEndToEndTest.java
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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.StreamAcceptHandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Drives a real client+server QUIC handshake and stream exchange purely
 * through the production classes ({@link QuicTransportFactory},
 * {@link QuicEngine}, {@link QuicConnection}, {@link QuicStreamEndpoint})
 * over real loopback UDP sockets on a real {@link SelectorLoop} thread --
 * unlike {@link QuicHandshakeEndToEndTest}/{@link QuicStreamEndToEndTest},
 * which drive the hand-called {@link QuicTestPeer} harness in-process with
 * no actual sockets or event loop involved.
 *
 * <p>This is the parity proof for the Stage 3 rewire: everything
 * {@code QuicTestPeer} exercises manually (Agent15 TLS 1.3, AEAD/header
 * protection, connection ID handling, stream flow control) here runs
 * through the real production I/O path instead.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicProductionEndToEndTest {

    private static final String SERVER_NAME = "test.gumdrop.local";
    private static final String ALPN = "gumdrop-test";

    private static Path certsDirectory;
    private static Path certFile;
    private static Path keyFile;

    @BeforeClass
    public static void generatePemFiles() throws Exception {
        certsDirectory = Files.createTempDirectory("quic-production-e2e-test");
        Path keystorePath = certsDirectory.resolve("server.p12");
        certFile = certsDirectory.resolve("cert.pem");
        keyFile = certsDirectory.resolve("key.pem");

        run(new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "server",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "1",
                "-dname", "CN=" + SERVER_NAME,
                "-keystore", keystorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", "changeit",
                "-keypass", "changeit"), "keytool");

        run(new ProcessBuilder(
                "openssl", "pkcs12", "-in", keystorePath.toString(),
                "-nodes", "-nocerts", "-out", keyFile.toString(),
                "-passin", "pass:changeit"), "openssl (key export)");

        run(new ProcessBuilder(
                "openssl", "pkcs12", "-in", keystorePath.toString(),
                "-nokeys", "-out", certFile.toString(),
                "-passin", "pass:changeit"), "openssl (cert export)");
    }

    private static void run(ProcessBuilder pb, String toolName) throws Exception {
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished || process.exitValue() != 0) {
            fail(toolName + " failed to produce test PEM files");
        }
    }

    @AfterClass
    public static void deletePemFiles() throws IOException {
        if (certsDirectory == null) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(certsDirectory)) {
            for (Path entry : entries) {
                Files.deleteIfExists(entry);
            }
        }
        Files.deleteIfExists(certsDirectory);
    }

    /**
     * Completes a real handshake, then exchanges a request/response pair
     * on a client-opened bidirectional stream -- the same shape DNS-over-
     * QUIC uses (RFC 9250 section 4.2).
     */
    @Test
    public void testHandshakeAndStreamRoundTrip() throws Exception {
        SelectorLoop loop = new SelectorLoop(0);
        loop.start();
        QuicEngine serverEngine = null;
        QuicEngine clientEngine = null;
        try {
            QuicTransportFactory serverFactory = new QuicTransportFactory();
            serverFactory.setApplicationProtocols(ALPN);
            serverFactory.setCertFile(certFile);
            serverFactory.setKeyFile(keyFile);
            serverFactory.start();

            final CountDownLatch serverReceivedFin = new CountDownLatch(1);
            final AtomicReference<byte[]> serverReceived = new AtomicReference<byte[]>();
            final AtomicReference<Endpoint> serverStreamEndpoint = new AtomicReference<Endpoint>();

            serverEngine = serverFactory.createServerEngine(
                    InetAddress.getLoopbackAddress(), 0,
                    new StreamAcceptHandler() {
                        @Override
                        public ProtocolHandler acceptStream(Endpoint stream) {
                            return new ProtocolHandler() {
                                @Override
                                public void connected(Endpoint endpoint) {
                                    serverStreamEndpoint.set(endpoint);
                                }

                                @Override
                                public void receive(ByteBuffer data) {
                                    byte[] bytes = new byte[data.remaining()];
                                    data.get(bytes);
                                    serverReceived.set(bytes);
                                }

                                @Override
                                public void securityEstablished(SecurityInfo info) {
                                }

                                @Override
                                public void disconnected() {
                                    Endpoint endpoint = serverStreamEndpoint.get();
                                    endpoint.send(ByteBuffer.wrap("pong".getBytes(StandardCharsets.US_ASCII)));
                                    endpoint.close();
                                    serverReceivedFin.countDown();
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
            clientFactory.start();

            final CountDownLatch clientReceivedFin = new CountDownLatch(1);
            final AtomicReference<byte[]> clientReceived = new AtomicReference<byte[]>();

            clientEngine = clientFactory.connect(
                    InetAddress.getLoopbackAddress(), port,
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
                        public void disconnected() {
                            clientReceivedFin.countDown();
                        }

                        @Override
                        public void error(Exception cause) {
                            fail("Client stream error: " + cause);
                        }
                    }, loop, SERVER_NAME);

            assertTrue("Server should have received the client's FIN within 5s",
                    serverReceivedFin.await(5, TimeUnit.SECONDS));
            assertTrue("Client should have received the server's FIN within 5s",
                    clientReceivedFin.await(5, TimeUnit.SECONDS));

            assertEquals("ping", new String(serverReceived.get(), StandardCharsets.US_ASCII));
            assertEquals("pong", new String(clientReceived.get(), StandardCharsets.US_ASCII));
        } finally {
            // Stop the loop thread first so closing the engines from this
            // (test) thread doesn't race its own concurrent I/O processing.
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
}
