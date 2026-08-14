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
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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

import org.bluezoo.gumdrop.quic.frame.QuicFrameWriter;
import org.bluezoo.gumdrop.quic.packet.PacketNumberCodec;
import org.bluezoo.gumdrop.quic.packet.PacketProtection;
import org.bluezoo.gumdrop.quic.packet.PacketProtectionKeys;
import org.bluezoo.gumdrop.quic.packet.QuicAeadAlgorithm;
import org.bluezoo.gumdrop.quic.packet.ShortHeaderCodec;
import org.bluezoo.gumdrop.quic.tls.EncryptionLevel;

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

    /**
     * Configures a small initial MAX_STREAM_DATA on the server, then has
     * the client send a much larger payload in small chunks -- without
     * receive-side flow control growth, the server's initial window
     * would permanently stall the transfer once exhausted (see the class
     * documentation on the Stage 4 deadlock this fixes). Proves the
     * server's auto-tuned MAX_STREAM_DATA updates actually unblock it.
     */
    @Test
    public void testFlowControlWindowGrowthUnblocksLargeTransfer() throws Exception {
        SelectorLoop loop = new SelectorLoop(0);
        loop.start();
        QuicEngine serverEngine = null;
        QuicEngine clientEngine = null;
        try {
            final int chunkSize = 10;
            final int chunkCount = 20;
            final int totalSize = chunkSize * chunkCount;

            QuicTransportFactory serverFactory = new QuicTransportFactory();
            serverFactory.setApplicationProtocols(ALPN);
            serverFactory.setCertFile(certFile);
            serverFactory.setKeyFile(keyFile);
            // Deliberately much smaller than totalSize: only auto-tune
            // growth (not the initial window) can get the full transfer through.
            serverFactory.setMaxStreamDataBidiRemote(16);
            serverFactory.setMaxData(1024);
            serverFactory.start();

            final CountDownLatch serverReceivedFin = new CountDownLatch(1);
            final java.io.ByteArrayOutputStream serverReceived = new java.io.ByteArrayOutputStream();

            serverEngine = serverFactory.createServerEngine(
                    InetAddress.getLoopbackAddress(), 0,
                    new StreamAcceptHandler() {
                        @Override
                        public ProtocolHandler acceptStream(Endpoint stream) {
                            return new ProtocolHandler() {
                                @Override
                                public void connected(Endpoint endpoint) {
                                }

                                @Override
                                public void receive(ByteBuffer data) {
                                    byte[] bytes = new byte[data.remaining()];
                                    data.get(bytes);
                                    synchronized (serverReceived) {
                                        serverReceived.write(bytes, 0, bytes.length);
                                    }
                                }

                                @Override
                                public void securityEstablished(SecurityInfo info) {
                                }

                                @Override
                                public void disconnected() {
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

            clientEngine = clientFactory.connect(
                    InetAddress.getLoopbackAddress(), port,
                    new ProtocolHandler() {
                        @Override
                        public void connected(Endpoint endpoint) {
                            byte[] payload = new byte[totalSize];
                            for (int i = 0; i < payload.length; i++) {
                                payload[i] = (byte) ('A' + (i % 26));
                            }
                            for (int i = 0; i < chunkCount; i++) {
                                ByteBuffer chunk = ByteBuffer.wrap(payload, i * chunkSize, chunkSize);
                                endpoint.send(chunk);
                            }
                            endpoint.close();
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
                            fail("Client stream error: " + cause);
                        }
                    }, loop, SERVER_NAME);

            assertTrue("Server should have received the full oversized transfer within 10s "
                    + "(the window must grow past its small initial size to get here)",
                    serverReceivedFin.await(10, TimeUnit.SECONDS));

            byte[] received;
            synchronized (serverReceived) {
                received = serverReceived.toByteArray();
            }
            assertEquals(totalSize, received.length);
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
     * Crafts a single real (correctly encrypted/header-protected) STREAM
     * frame whose offset deliberately exceeds the server's advertised
     * MAX_STREAM_DATA, using the negotiated 1-RTT keys and connection ID
     * from a genuinely completed handshake -- proving the server detects
     * the RFC 9000 section 11 violation and closes the connection, rather
     * than accepting unbounded data from a misbehaving peer.
     *
     * <p>{@link QuicStreamEndpoint#send} can never construct such a frame
     * itself (it self-enforces against the peer's advertised limit), so
     * this reaches directly into the client's negotiated key material via
     * reflection to forge one, the same way a non-conformant/malicious
     * peer's own independent implementation could.
     */
    @Test
    public void testFlowControlViolationClosesConnection() throws Exception {
        SelectorLoop loop = new SelectorLoop(0);
        loop.start();
        QuicEngine serverEngine = null;
        QuicEngine clientEngine = null;
        DatagramChannel forgeChannel = null;
        try {
            QuicTransportFactory serverFactory = new QuicTransportFactory();
            serverFactory.setApplicationProtocols(ALPN);
            serverFactory.setCertFile(certFile);
            serverFactory.setKeyFile(keyFile);
            serverFactory.setMaxStreamDataBidiRemote(100);
            serverFactory.start();

            final CountDownLatch maliciousStreamDisconnected = new CountDownLatch(1);

            serverEngine = serverFactory.createServerEngine(
                    InetAddress.getLoopbackAddress(), 0,
                    new StreamAcceptHandler() {
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
                                    maliciousStreamDisconnected.countDown();
                                }

                                @Override
                                public void error(Exception cause) {
                                }
                            };
                        }
                    }, loop);

            InetSocketAddress serverAddress = (InetSocketAddress) serverEngine.getLocalAddress();

            QuicTransportFactory clientFactory = new QuicTransportFactory();
            clientFactory.setApplicationProtocols(ALPN);
            clientFactory.setVerifyPeer(false);
            clientFactory.start();

            final CountDownLatch clientConnected = new CountDownLatch(1);
            final AtomicReference<Endpoint> clientEndpoint = new AtomicReference<Endpoint>();

            clientEngine = clientFactory.connect(
                    InetAddress.getLoopbackAddress(), serverAddress.getPort(),
                    new ProtocolHandler() {
                        @Override
                        public void connected(Endpoint endpoint) {
                            clientEndpoint.set(endpoint);
                            clientConnected.countDown();
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
                    }, loop, SERVER_NAME);

            assertTrue("Client should have connected within 5s", clientConnected.await(5, TimeUnit.SECONDS));

            // Reach into the client's negotiated ONE_RTT key material and
            // the connection ID it addresses the server with -- nothing
            // else can produce a packet the server will actually decrypt.
            QuicConnection clientConnection = getPrivateField(clientEngine, "clientConnection", QuicConnection.class);
            @SuppressWarnings("unchecked")
            Map<EncryptionLevel, PacketProtectionKeys> sendKeys =
                    getPrivateField(clientConnection, "sendKeys", Map.class);
            PacketProtectionKeys oneRttKeys = sendKeys.get(EncryptionLevel.ONE_RTT);
            byte[] serverConnectionId = getPrivateField(clientConnection, "peerConnectionId", byte[].class);
            long[] sendPacketNumber = getPrivateField(clientConnection, "sendPacketNumber", long[].class);
            long packetNumber = sendPacketNumber[EncryptionLevel.ONE_RTT.ordinal()];

            // RFC 9000 section 11: this stream ID has never been used, so
            // its very first frame already exceeds the server's
            // advertised MAX_STREAM_DATA (100) with room to spare.
            long violatingStreamId = 4;
            long violatingOffset = 200;
            byte[] violatingData = "XXXXXXXXXX".getBytes(StandardCharsets.US_ASCII);

            byte[] forgedPacket = forgeStreamPacket(oneRttKeys, serverConnectionId, packetNumber,
                    violatingStreamId, violatingOffset, violatingData);

            forgeChannel = DatagramChannel.open();
            forgeChannel.send(ByteBuffer.wrap(forgedPacket), serverAddress);

            assertTrue("Server should close the connection on a flow-control violation within 5s",
                    maliciousStreamDisconnected.await(5, TimeUnit.SECONDS));
        } finally {
            if (forgeChannel != null) {
                forgeChannel.close();
            }
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
     * Builds a single real 1-RTT packet containing one STREAM frame,
     * using the same sequence {@code QuicConnection.buildAndSendPacket}
     * and {@code QuicTestPeer.buildPacket} both follow -- but built by
     * hand here since neither can be told to violate flow control.
     */
    private static byte[] forgeStreamPacket(PacketProtectionKeys keys, byte[] destinationConnectionId,
            long packetNumber, long streamId, long offset, byte[] data) throws Exception {
        int pnLength = PacketNumberCodec.encodedLength(packetNumber, -1);
        byte[] header = ShortHeaderCodec.build(destinationConnectionId, false, packetNumber, pnLength);

        int frameBytes = QuicFrameWriter.streamLength(streamId, offset, data.length);
        ByteBuffer payload = ByteBuffer.allocate(frameBytes);
        QuicFrameWriter.writeStream(payload, streamId, offset, data, false);
        payload.flip();
        byte[] plaintext = new byte[payload.remaining()];
        payload.get(plaintext);

        byte[] ciphertext = PacketProtection.seal(keys, packetNumber, header, plaintext);
        byte[] packet = new byte[header.length + ciphertext.length];
        System.arraycopy(header, 0, packet, 0, header.length);
        System.arraycopy(ciphertext, 0, packet, header.length, ciphertext.length);

        int pnOffset = ShortHeaderCodec.packetNumberOffset(destinationConnectionId.length);
        byte[] sample = new byte[QuicAeadAlgorithm.SAMPLE_LENGTH];
        System.arraycopy(packet, pnOffset + 4, sample, 0, sample.length);
        byte[] mask = PacketProtection.headerProtectionMask(keys, sample);
        PacketProtection.xorFirstByte(packet, mask, false);
        PacketProtection.xorPacketNumberBytes(packet, pnOffset, pnLength, mask);

        return packet;
    }

    @SuppressWarnings("unchecked")
    private static <T> T getPrivateField(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }
}
