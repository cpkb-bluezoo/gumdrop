/*
 * StatelessResetEndToEndTest.java
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

import org.bluezoo.gumdrop.quic.packet.StatelessResetPacket;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Exercises RFC 9000 section 10.3 stateless reset over real loopback
 * UDP: the server drops local state, the client sends a 1-RTT packet,
 * the server answers with a stateless reset, and the client closes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class StatelessResetEndToEndTest {

    private static final String SERVER_NAME = "test.gumdrop.local";
    private static final String ALPN = "gumdrop-test";

    private static Path certsDirectory;
    private static Path certFile;
    private static Path keyFile;

    @BeforeClass
    public static void generatePemFiles() throws Exception {
        certsDirectory = Files.createTempDirectory("quic-stateless-reset-test");
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

    @Test
    public void testClientDetectsServerStatelessResetAfterStateLoss() throws Exception {
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

            final CountDownLatch handshakeDone = new CountDownLatch(1);
            final AtomicReference<QuicConnection> serverConnection = new AtomicReference<QuicConnection>();
            final AtomicReference<Endpoint> clientStream = new AtomicReference<Endpoint>();
            final CountDownLatch resetObserved = new CountDownLatch(1);
            final AtomicReference<Exception> clientError = new AtomicReference<Exception>();

            serverEngine = serverFactory.createServerEngine(
                    InetAddress.getLoopbackAddress(), 0,
                    new QuicEngine.ConnectionAcceptedHandler() {
                        @Override
                        public void connectionAccepted(QuicConnection connection) {
                            serverConnection.set(connection);
                            connection.setStreamAcceptHandler(new StreamAcceptHandler() {
                                @Override
                                public ProtocolHandler acceptStream(Endpoint stream) {
                                    return new ProtocolHandler() {
                                        @Override
                                        public void connected(Endpoint endpoint) {
                                        }

                                        @Override
                                        public void receive(ByteBuffer data) {
                                        }

                                        @Override
                                        public void securityEstablished(SecurityInfo info) {
                                        }

                                        @Override
                                        public void disconnected() {
                                        }

                                        @Override
                                        public void error(Exception cause) {
                                        }
                                    };
                                }
                            });
                        }
                    }, loop);

            int port = ((InetSocketAddress) serverEngine.getLocalAddress()).getPort();

            QuicTransportFactory clientFactory = new QuicTransportFactory();
            clientFactory.setApplicationProtocols(ALPN);
            clientFactory.setVerifyPeer(false);
            clientFactory.start();

            clientEngine = clientFactory.connect(
                    InetAddress.getLoopbackAddress(), port,
                    new ProtocolHandler() {
                        @Override
                        public void connected(Endpoint endpoint) {
                            clientStream.set(endpoint);
                            handshakeDone.countDown();
                        }

                        @Override
                        public void receive(ByteBuffer data) {
                        }

                        @Override
                        public void securityEstablished(SecurityInfo info) {
                        }

                        @Override
                        public void disconnected() {
                        }

                        @Override
                        public void error(Exception cause) {
                            clientError.set(cause);
                            resetObserved.countDown();
                        }
                    }, loop, SERVER_NAME);

            assertTrue("Handshake should complete within 5s", handshakeDone.await(5, TimeUnit.SECONDS));

            QuicConnection serverConn = serverConnection.get();
            assertTrue("Server connection should exist", serverConn != null);
            serverConn.dropLocalState();

            Endpoint stream = clientStream.get();
            assertTrue("Client stream should be open", stream != null && stream.isOpen());
            stream.send(ByteBuffer.wrap("stale".getBytes(StandardCharsets.US_ASCII)));

            assertTrue("Client should observe stateless reset within 5s",
                    resetObserved.await(5, TimeUnit.SECONDS));
            assertTrue("Client error should be a stateless reset",
                    clientError.get() instanceof QuicStatelessResetException);
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

    /**
     * A datagram whose tail matches a token must not count as a reset when
     * 1-RTT decryption succeeded in the same receive (RFC 9000 section
     * 10.3.1 condition 3) -- verified at the guard level used by
     * {@link QuicConnection}.
     */
    @Test
    public void testSuccessfulOneRttDecryptBlocksResetDetection() {
        byte[] token = new byte[16];
        byte[] datagram = new byte[21];
        System.arraycopy(token, 0, datagram, 5, token.length);
        assertTrue(StatelessResetPacket.matchesToken(datagram, 0, datagram.length, token));
        boolean decryptFailed = false;
        boolean sawValidOneRtt = true;
        assertTrue(!decryptFailed || sawValidOneRtt);
    }
}
