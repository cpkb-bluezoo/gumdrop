/*
 * QuicPluggableDatagramPathTest.java
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
import java.net.SocketAddress;
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
 * Regression/feature tests for issue #392: {@code QuicEngine} I/O was
 * hard-wired to {@code DatagramChannel}, so a QUIC connection could not
 * run over anything but a bound kernel UDP socket -- no in-memory path
 * for tests, and no way for a CONNECT-UDP client (issue #393) to tunnel
 * QUIC packets as HTTP Datagrams instead.
 *
 * <p>Drives two real {@link QuicEngine}s -- one server, one client --
 * entirely over an in-memory {@link QuicDatagramPath} with no {@code
 * DatagramChannel}/UDP socket anywhere, through a complete handshake and
 * a client-initiated stream request/response, the same shape as {@link
 * QuicProductionEndToEndTest#testHandshakeAndStreamRoundTrip} but with
 * the datagram path swapped out.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicPluggableDatagramPathTest {

    private static final String SERVER_NAME = "test.gumdrop.local";
    private static final String ALPN = "gumdrop-test";

    private static Path certsDirectory;
    private static Path certFile;
    private static Path keyFile;

    @BeforeClass
    public static void generatePemFiles() throws Exception {
        certsDirectory = Files.createTempDirectory("quic-pluggable-path-test");
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
     * Hands received bytes straight to the peer engine's {@link
     * QuicEngine#receivePathDatagram} -- no socket, no serialization,
     * just two engines' worth of {@code QuicDatagramPath} wired directly
     * to each other.
     *
     * <p>{@code send} can run before this test has had a chance to call
     * {@link #setPeer} on the <em>other</em> direction's path (the client
     * engine sends its Initial packet synchronously during construction,
     * racing this test thread's own subsequent {@code setPeer} call for
     * the server-to-client direction against the server's loop thread
     * processing that Initial and replying) -- buffer under a lock rather
     * than assume construction order, so this is deterministic instead of
     * occasionally flaky.
     */
    private static final class InMemoryPath implements QuicDatagramPath {
        private final InetSocketAddress localAddress;
        private QuicEngine peer;
        private final java.util.List<byte[]> pending = new java.util.ArrayList<byte[]>();
        private volatile boolean open = true;

        InMemoryPath(InetSocketAddress localAddress) {
            this.localAddress = localAddress;
        }

        synchronized void setPeer(QuicEngine peer) {
            this.peer = peer;
            for (byte[] bytes : pending) {
                peer.receivePathDatagram(bytes, localAddress);
            }
            pending.clear();
        }

        @Override
        public synchronized int send(SocketAddress address, ByteBuffer packet) {
            if (!open) {
                return 0;
            }
            byte[] bytes = new byte[packet.remaining()];
            packet.get(bytes);
            if (peer == null) {
                pending.add(bytes);
            } else {
                peer.receivePathDatagram(bytes, localAddress);
            }
            return bytes.length;
        }

        @Override
        public SocketAddress getLocalAddress() {
            return localAddress;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }

    @Test
    public void testHandshakeAndStreamRoundTripOverInMemoryPath() throws Exception {
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

            QuicTransportFactory clientFactory = new QuicTransportFactory();
            clientFactory.setApplicationProtocols(ALPN);
            clientFactory.setVerifyPeer(false);
            clientFactory.start();

            InMemoryPath serverPath = new InMemoryPath(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 4433));
            InMemoryPath clientPath = new InMemoryPath(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));

            final CountDownLatch serverReceivedFin = new CountDownLatch(1);
            final AtomicReference<byte[]> serverReceived = new AtomicReference<byte[]>();
            final AtomicReference<Endpoint> serverStreamEndpoint = new AtomicReference<Endpoint>();

            serverEngine = serverFactory.createServerEngine(serverPath,
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
                                public void readFinished() {
                                    Endpoint endpoint = serverStreamEndpoint.get();
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
            clientPath.setPeer(serverEngine);

            final CountDownLatch clientReceivedFin = new CountDownLatch(1);
            final AtomicReference<byte[]> clientReceived = new AtomicReference<byte[]>();

            clientEngine = clientFactory.connect(clientPath, serverPath.localAddress,
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
                    }, loop, SERVER_NAME);
            serverPath.setPeer(clientEngine);

            assertTrue("Server should have received the client's FIN within 5s",
                    serverReceivedFin.await(5, TimeUnit.SECONDS));
            assertTrue("Client should have received the server's FIN within 5s",
                    clientReceivedFin.await(5, TimeUnit.SECONDS));

            assertEquals("ping", new String(serverReceived.get(), StandardCharsets.US_ASCII));
            assertEquals("pong", new String(clientReceived.get(), StandardCharsets.US_ASCII));
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
}
