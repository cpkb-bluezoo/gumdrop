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
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import tech.kwik.agent15.NewSessionTicket;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.StreamAcceptHandler;
import org.bluezoo.gumdrop.TimerHandle;

import org.bluezoo.gumdrop.quic.frame.QuicFrameHandler;
import org.bluezoo.gumdrop.quic.frame.QuicFrameParser;
import org.bluezoo.gumdrop.quic.frame.QuicFrameWriter;
import org.bluezoo.gumdrop.quic.packet.PacketNumberCodec;
import org.bluezoo.gumdrop.quic.packet.PacketProtection;
import org.bluezoo.gumdrop.quic.packet.PacketProtectionKeys;
import org.bluezoo.gumdrop.quic.packet.QuicAeadAlgorithm;
import org.bluezoo.gumdrop.quic.packet.ShortHeaderCodec;
import org.bluezoo.gumdrop.quic.packet.TransportParameters;
import org.bluezoo.gumdrop.quic.tls.EncryptionLevel;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
     * Regression test for a false-positive Probe Timeout retransmit:
     * {@link QuicConnection#flush} used to call its own
     * {@code scheduleLossDetectionTimer()} only on the branch where it
     * actually had bytes to send -- so once an ACK cleared everything a
     * connection had outstanding, leaving a subsequent {@code flush()}
     * nothing to build, the loss detection timer armed for that
     * now-acknowledged data was left running rather than re-evaluated.
     * Left alone, RFC 9002 Appendix A.8's {@code SetLossDetectionTimer}
     * would have returned
     * {@link org.bluezoo.gumdrop.quic.recovery.LossDetector#NO_TIMEOUT}
     * (nothing ack-eliciting remains in flight and the peer's address is
     * validated) and cancelled it -- instead a stale timer could stay
     * armed for its original deadline and, on firing, find nothing lost
     * and nothing in flight, misread that as a Probe Timeout, and send a
     * spurious anti-deadlock PING nothing actually required.
     *
     * <p>Exercised directly rather than by racing real network timing to
     * reproduce the exact "ACK arrives, nothing left pending" moment:
     * once a real client+server handshake settles with no application
     * data ever sent (proven quiescent by polling the client's own
     * private {@code timerHandle} field down to {@code null}, which only
     * happens once a real {@code flush()} call has legitimately found
     * {@code NO_TIMEOUT}), a sentinel {@link TimerHandle} is planted
     * directly into that field and the connection's package-private
     * {@code flush()} is invoked directly -- with nothing whatsoever
     * queued to send, this is exactly the code path the bug lived in.
     * The fix must observably cancel and clear the sentinel; the bug
     * left it untouched.
     */
    @Test
    public void testLossDetectionTimerCancelledOnEmptyFlush() throws Exception {
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
                                }

                                @Override
                                public void error(Exception cause) {
                                }
                            };
                        }
                    }, loop);

            int port = ((InetSocketAddress) serverEngine.getLocalAddress()).getPort();

            QuicTransportFactory clientFactory = new QuicTransportFactory();
            clientFactory.setApplicationProtocols(ALPN);
            clientFactory.setVerifyPeer(false);
            clientFactory.start();

            final CountDownLatch clientConnected = new CountDownLatch(1);

            clientEngine = clientFactory.connect(
                    InetAddress.getLoopbackAddress(), port,
                    new ProtocolHandler() {
                        @Override
                        public void connected(Endpoint endpoint) {
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
                            fail("Client stream error: " + cause);
                        }
                    }, loop, SERVER_NAME);

            assertTrue("Client should have connected within 5s", clientConnected.await(5, TimeUnit.SECONDS));

            QuicConnection clientConnection = getPrivateField(clientEngine, "clientConnection", QuicConnection.class);

            // Wait for the handshake's own tail traffic (HANDSHAKE_DONE,
            // its ACK, etc.) to settle to a genuinely idle connection --
            // every one of those exchanges legitimately calls
            // scheduleLossDetectionTimer() via flush()'s "has bytes"
            // branch (unaffected by the bug), so reaching null here relies
            // on nothing this test is trying to prove.
            TimerHandle timerHandle = getPrivateField(clientConnection, "timerHandle", TimerHandle.class);
            long deadline = System.currentTimeMillis() + 5000;
            while (timerHandle != null && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
                timerHandle = getPrivateField(clientConnection, "timerHandle", TimerHandle.class);
            }
            assertNull("Connection should have gone idle (no timer armed) before "
                    + "this test's own direct flush() check", timerHandle);

            // Plant a sentinel directly, then invoke the exact code path
            // under test: flush() with nothing whatsoever pending.
            SentinelTimerHandle sentinel = new SentinelTimerHandle();
            setPrivateField(clientConnection, "timerHandle", sentinel);
            clientConnection.flush();

            assertTrue("flush() with nothing to send must still re-evaluate (and "
                    + "here, cancel) the loss detection timer rather than leaving "
                    + "a stale one armed", sentinel.cancelled);
            assertNull("The now-cancelled sentinel must also be cleared from the "
                    + "field, not merely cancelled in place",
                    getPrivateField(clientConnection, "timerHandle", TimerHandle.class));
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

    private static final class SentinelTimerHandle implements TimerHandle {
        boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    /**
     * RFC 8446 section 4.6.1: Agent15's server engine automatically
     * issues a session ticket once a handshake completes, to any client
     * that offered {@code psk_dhe_ke} -- which every client does,
     * unconditionally, regardless of whether it is actually resuming
     * anything (confirmed against Agent15's own upstream source).
     * Confirms {@link QuicTlsClientEngine}/{@link QuicConnection}'s newly
     * wired plumbing actually captures that ticket into
     * {@link SessionTicketCache}, keyed by the server name/port used to
     * connect, along with the peer's real transport parameters -- the
     * foundation the 0-RTT work builds on.
     */
    @Test
    public void testSessionTicketCapturedAfterHandshake() throws Exception {
        SessionTicketCache.clear();
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
                                }

                                @Override
                                public void securityEstablished(SecurityInfo info) {
                                }

                                @Override
                                public void disconnected() {
                                    // Close this side too, so the client
                                    // observes its own disconnected()
                                    // once both directions of the stream
                                    // have finished.
                                    serverStreamEndpoint.get().close();
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

            final CountDownLatch clientFin = new CountDownLatch(1);

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
                        }

                        @Override
                        public void securityEstablished(SecurityInfo info) {
                        }

                        @Override
                        public void disconnected() {
                            clientFin.countDown();
                        }

                        @Override
                        public void error(Exception cause) {
                            fail("Client stream error: " + cause);
                        }
                    }, loop, SERVER_NAME);

            assertTrue("Client stream should close within 5s", clientFin.await(5, TimeUnit.SECONDS));

            // The server sends its NewSessionTicketMessage as a separate,
            // slightly later 1-RTT CRYPTO frame once it sees the client's
            // Finished -- not synchronous with the stream exchange above
            // -- so give it a short bounded window to arrive rather than
            // asserting immediately.
            SessionTicketCache.Entry entry = null;
            long deadline = System.currentTimeMillis() + 3000;
            while (entry == null && System.currentTimeMillis() < deadline) {
                entry = SessionTicketCache.get(SERVER_NAME, port);
                if (entry == null) {
                    Thread.sleep(50);
                }
            }

            assertNotNull("A session ticket should have been cached after the handshake", entry);
            byte[] psk = entry.toTicket().getPSK();
            assertNotNull("Cached ticket should carry a PSK", psk);
            assertTrue("Cached ticket PSK should be non-empty", psk.length > 0);
            TransportParameters remembered = entry.toTransportParameters();
            assertTrue("Remembered transport parameters should round-trip a real limit",
                    remembered.getInitialMaxData() > 0);
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
     * RFC 9001 section 4.6.1: with 0-RTT enabled server-side, a client
     * presenting a valid session ticket can send application data
     * (STREAM frames) in 0-RTT packets, coalesced with its Initial
     * packet (RFC 9000 section 12.2 order), before the handshake
     * completes -- and the real production server must both derive
     * usable 0-RTT keys while processing that same Initial's ClientHello
     * and correctly process MULTIPLE 0-RTT packets coalesced after it in
     * the same datagram. The latter is also the regression test for the
     * previous {@code receiveOnePacket} behaviour, which unconditionally
     * returned -1 (abandoning the rest of the datagram) the instant it
     * saw a 0-RTT packet type.
     *
     * <p>The client side of this test is driven by hand via
     * {@link QuicTestPeer} rather than a second real {@link
     * QuicTransportFactory} connection: presenting a ticket before
     * {@code startHandshake} has no seam in the production {@code
     * QuicEngine.connectTo}/{@code QuicTransportFactory.connect} path
     * yet (that seam is Part 3 of this stage) -- this test exercises the
     * server-side machinery in isolation, ahead of that.
     */
    @Test
    public void testZeroRttPacketDeliversStreamDataToServer() throws Exception {
        SessionTicketCache.clear();
        SelectorLoop loop = new SelectorLoop(0);
        loop.start();
        QuicEngine serverEngine = null;
        QuicEngine firstClientEngine = null;
        DatagramChannel rawClientChannel = null;
        try {
            final Map<Long, byte[]> serverReceivedByStream = new ConcurrentHashMap<Long, byte[]>();
            QuicTransportFactory serverFactory = new QuicTransportFactory();
            serverFactory.setApplicationProtocols(ALPN);
            serverFactory.setCertFile(certFile);
            serverFactory.setKeyFile(keyFile);
            serverFactory.setEarlyDataEnabled(true);
            serverFactory.start();
            serverEngine = serverFactory.createServerEngine(
                    InetAddress.getLoopbackAddress(), 0, streamCapturingAcceptHandler(serverReceivedByStream), loop);
            InetSocketAddress serverAddress = (InetSocketAddress) serverEngine.getLocalAddress();

            firstClientEngine = captureSessionTicketViaRealHandshake(serverAddress, loop);
            NewSessionTicket ticket = SessionTicketCache.get(SERVER_NAME, serverAddress.getPort()).toTicket();

            byte[] clientInitialDcid = QuicHandshakeEndToEndTest.randomConnectionId();
            byte[] clientScid = QuicHandshakeEndToEndTest.randomConnectionId();
            TransportParameters clientParams = QuicHandshakeEndToEndTest.defaultTransportParameters(clientScid);
            QuicTestPeer secondClient = QuicTestPeer.newClient(clientInitialDcid, clientParams, ALPN);
            secondClient.presentSessionTicket(ticket);
            secondClient.startHandshake(SERVER_NAME);
            assertTrue("0-RTT keys should be derivable right after ClientHello is built",
                    secondClient.earlySecretsAvailableFired);

            byte[] initialDatagram = secondClient.buildPacket(EncryptionLevel.INITIAL,
                    clientInitialDcid, clientScid, false, false, 1200);

            long streamA = secondClient.openBidiStream();
            secondClient.queueStreamData(streamA, "zero-rtt-a".getBytes(StandardCharsets.US_ASCII), false);
            byte[] zeroRttA = secondClient.buildZeroRttPacket(clientInitialDcid, clientScid, 0);

            long streamB = secondClient.openBidiStream();
            secondClient.queueStreamData(streamB, "zero-rtt-b".getBytes(StandardCharsets.US_ASCII), false);
            byte[] zeroRttB = secondClient.buildZeroRttPacket(clientInitialDcid, clientScid, 0);

            byte[] coalesced = new byte[initialDatagram.length + zeroRttA.length + zeroRttB.length];
            System.arraycopy(initialDatagram, 0, coalesced, 0, initialDatagram.length);
            System.arraycopy(zeroRttA, 0, coalesced, initialDatagram.length, zeroRttA.length);
            System.arraycopy(zeroRttB, 0, coalesced, initialDatagram.length + zeroRttA.length, zeroRttB.length);

            rawClientChannel = DatagramChannel.open();
            rawClientChannel.send(ByteBuffer.wrap(coalesced), serverAddress);

            assertEquals("zero-rtt-a", new String(awaitStreamData(serverReceivedByStream, streamA, 3000),
                    StandardCharsets.US_ASCII));
            assertEquals("zero-rtt-b", new String(awaitStreamData(serverReceivedByStream, streamB, 3000),
                    StandardCharsets.US_ASCII));
        } finally {
            if (rawClientChannel != null) {
                rawClientChannel.close();
            }
            loop.shutdown();
            loop.awaitQuiesce(2000);
            if (firstClientEngine != null) {
                firstClientEngine.close();
            }
            if (serverEngine != null) {
                serverEngine.close();
            }
        }
    }

    /**
     * The negative-path counterpart to {@link
     * #testZeroRttPacketDeliversStreamDataToServer}: with 0-RTT
     * disabled server-side (the default), the same coalesced Initial +
     * 0-RTT datagram must not deliver the 0-RTT stream's data at all --
     * {@code isEarlyDataAccepted()} returning false means the server
     * never derives {@code zeroRttRecvKeys}, so the 0-RTT packet is
     * silently dropped (the same {@code keys == null} path already
     * exercised whenever 0-RTT is never attempted at all -- proving this
     * negative case doesn't require re-proving that the rest of the
     * handshake still completes, since every other test in this suite
     * already completes full handshakes through that exact code path).
     */
    @Test
    public void testZeroRttDisabledServerNeverDeliversStreamData() throws Exception {
        SessionTicketCache.clear();
        SelectorLoop loop = new SelectorLoop(0);
        loop.start();
        QuicEngine serverEngine = null;
        QuicEngine firstClientEngine = null;
        DatagramChannel rawClientChannel = null;
        try {
            final Map<Long, byte[]> serverReceivedByStream = new ConcurrentHashMap<Long, byte[]>();
            QuicTransportFactory serverFactory = new QuicTransportFactory();
            serverFactory.setApplicationProtocols(ALPN);
            serverFactory.setCertFile(certFile);
            serverFactory.setKeyFile(keyFile);
            serverFactory.setEarlyDataEnabled(false);
            serverFactory.start();
            serverEngine = serverFactory.createServerEngine(
                    InetAddress.getLoopbackAddress(), 0, streamCapturingAcceptHandler(serverReceivedByStream), loop);
            InetSocketAddress serverAddress = (InetSocketAddress) serverEngine.getLocalAddress();

            firstClientEngine = captureSessionTicketViaRealHandshake(serverAddress, loop);
            NewSessionTicket ticket = SessionTicketCache.get(SERVER_NAME, serverAddress.getPort()).toTicket();

            byte[] clientInitialDcid = QuicHandshakeEndToEndTest.randomConnectionId();
            byte[] clientScid = QuicHandshakeEndToEndTest.randomConnectionId();
            TransportParameters clientParams = QuicHandshakeEndToEndTest.defaultTransportParameters(clientScid);
            QuicTestPeer secondClient = QuicTestPeer.newClient(clientInitialDcid, clientParams, ALPN);
            secondClient.presentSessionTicket(ticket);
            secondClient.startHandshake(SERVER_NAME);

            byte[] initialDatagram = secondClient.buildPacket(EncryptionLevel.INITIAL,
                    clientInitialDcid, clientScid, false, false, 1200);
            long rejectedStream = secondClient.openBidiStream();
            secondClient.queueStreamData(rejectedStream,
                    "should-not-arrive".getBytes(StandardCharsets.US_ASCII), false);
            byte[] zeroRtt = secondClient.buildZeroRttPacket(clientInitialDcid, clientScid, 0);

            byte[] coalesced = new byte[initialDatagram.length + zeroRtt.length];
            System.arraycopy(initialDatagram, 0, coalesced, 0, initialDatagram.length);
            System.arraycopy(zeroRtt, 0, coalesced, initialDatagram.length, zeroRtt.length);

            rawClientChannel = DatagramChannel.open();
            rawClientChannel.send(ByteBuffer.wrap(coalesced), serverAddress);

            Thread.sleep(500);
            assertFalse("Server must not have processed the rejected 0-RTT stream's data",
                    serverReceivedByStream.containsKey(rejectedStream));
        } finally {
            if (rawClientChannel != null) {
                rawClientChannel.close();
            }
            loop.shutdown();
            loop.awaitQuiesce(2000);
            if (firstClientEngine != null) {
                firstClientEngine.close();
            }
            if (serverEngine != null) {
                serverEngine.close();
            }
        }
    }

    private static StreamAcceptHandler streamCapturingAcceptHandler(final Map<Long, byte[]> receivedByStream) {
        return new StreamAcceptHandler() {
            @Override
            public ProtocolHandler acceptStream(final Endpoint stream) {
                return new ProtocolHandler() {
                    @Override
                    public void connected(Endpoint endpoint) {
                    }

                    @Override
                    public void receive(ByteBuffer data) {
                        byte[] bytes = new byte[data.remaining()];
                        data.get(bytes);
                        receivedByStream.put(((QuicStreamEndpoint) stream).getStreamId(), bytes);
                    }

                    @Override
                    public void securityEstablished(SecurityInfo info) {
                    }

                    @Override
                    public void disconnected() {
                        stream.close();
                    }

                    @Override
                    public void error(Exception cause) {
                        fail("Server stream error: " + cause);
                    }
                };
            }
        };
    }

    // Completes a normal production client handshake against the given
    // server (opening and immediately closing a stream, sending no
    // data), which is enough to make the server issue a session ticket
    // (see testSessionTicketCapturedAfterHandshake) -- polls until it
    // appears in SessionTicketCache. Returns the client engine so the
    // caller can close it; the caller is expected to already have
    // cleared SessionTicketCache and to read the ticket back out itself.
    private static QuicEngine captureSessionTicketViaRealHandshake(InetSocketAddress serverAddress, SelectorLoop loop)
            throws Exception {
        QuicTransportFactory clientFactory = new QuicTransportFactory();
        clientFactory.setApplicationProtocols(ALPN);
        clientFactory.setVerifyPeer(false);
        clientFactory.start();

        final CountDownLatch clientFin = new CountDownLatch(1);
        QuicEngine clientEngine = clientFactory.connect(
                InetAddress.getLoopbackAddress(), serverAddress.getPort(),
                new ProtocolHandler() {
                    @Override
                    public void connected(Endpoint endpoint) {
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
                        clientFin.countDown();
                    }

                    @Override
                    public void error(Exception cause) {
                        fail("Ticket-capture client stream error: " + cause);
                    }
                }, loop, SERVER_NAME);

        assertTrue("Ticket-capture client stream should close within 5s", clientFin.await(5, TimeUnit.SECONDS));

        SessionTicketCache.Entry entry = null;
        long deadline = System.currentTimeMillis() + 3000;
        while (entry == null && System.currentTimeMillis() < deadline) {
            entry = SessionTicketCache.get(SERVER_NAME, serverAddress.getPort());
            if (entry == null) {
                Thread.sleep(50);
            }
        }
        assertNotNull("A session ticket should have been cached after the handshake", entry);
        return clientEngine;
    }

    private static byte[] awaitStreamData(Map<Long, byte[]> received, long streamId, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            byte[] data = received.get(streamId);
            if (data != null) {
                return data;
            }
            Thread.sleep(25);
        }
        fail("Server never received data for stream " + streamId + " within " + timeoutMs + "ms");
        return null; // unreachable
    }

    /**
     * The genuine end-to-end proof of Part 3 of the 0-RTT work: a real
     * second {@link HTTPClient}-shaped connection (production {@link
     * QuicTransportFactory}/{@link QuicEngine} client, not the
     * hand-driven {@link QuicTestPeer} the earlier 0-RTT tests use)
     * automatically presents a cached session ticket and sends data from
     * {@link QuicEngine.EarlyDataHandler#earlyDataReady} -- fired before
     * the handshake otherwise completes -- and that data genuinely
     * arrives server-side while the client's own {@link
     * QuicConnection#isEstablished()} is still false. Not just "fast":
     * a real fewer-round-trips proof.
     */
    @Test
    public void testEarlyDataHandlerSendsBeforeHandshakeCompletes() throws Exception {
        SessionTicketCache.clear();
        SelectorLoop loop = new SelectorLoop(0);
        loop.start();
        QuicEngine serverEngine = null;
        QuicEngine firstClientEngine = null;
        QuicEngine secondClientEngine = null;
        try {
            final Map<Long, byte[]> serverReceivedByStream = new ConcurrentHashMap<Long, byte[]>();
            QuicTransportFactory serverFactory = new QuicTransportFactory();
            serverFactory.setApplicationProtocols(ALPN);
            serverFactory.setCertFile(certFile);
            serverFactory.setKeyFile(keyFile);
            serverFactory.setEarlyDataEnabled(true);
            serverFactory.start();
            serverEngine = serverFactory.createServerEngine(
                    InetAddress.getLoopbackAddress(), 0, streamCapturingAcceptHandler(serverReceivedByStream), loop);
            InetSocketAddress serverAddress = (InetSocketAddress) serverEngine.getLocalAddress();

            firstClientEngine = captureSessionTicketViaRealHandshake(serverAddress, loop);

            QuicTransportFactory secondClientFactory = new QuicTransportFactory();
            secondClientFactory.setApplicationProtocols(ALPN);
            secondClientFactory.setVerifyPeer(false);
            secondClientFactory.setEarlyDataEnabled(true);
            secondClientFactory.start();

            final AtomicReference<Boolean> establishedAtSendTime = new AtomicReference<Boolean>();
            final AtomicReference<QuicConnection> clientConnectionRef = new AtomicReference<QuicConnection>();
            final CountDownLatch earlyDataSent = new CountDownLatch(1);

            secondClientEngine = secondClientFactory.connect(
                    InetAddress.getLoopbackAddress(), serverAddress.getPort(),
                    new QuicEngine.ConnectionAcceptedHandler() {
                        @Override
                        public void connectionAccepted(QuicConnection connection) {
                        }
                    },
                    new QuicEngine.EarlyDataHandler() {
                        @Override
                        public void earlyDataReady(QuicConnection connection) {
                            establishedAtSendTime.set(connection.isEstablished());
                            clientConnectionRef.set(connection);
                            Endpoint endpoint = connection.openStream(new ProtocolHandler() {
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
                                    fail("Client 0-RTT stream error: " + cause);
                                }
                            });
                            endpoint.send(ByteBuffer.wrap("zero-rtt-payload".getBytes(StandardCharsets.US_ASCII)));
                            earlyDataSent.countDown();
                        }
                    },
                    loop, SERVER_NAME);

            assertTrue("earlyDataReady should fire", earlyDataSent.await(5, TimeUnit.SECONDS));
            assertNotNull("earlyDataReady should have observed the connection's established state",
                    establishedAtSendTime.get());
            assertFalse("Connection must not yet be established when 0-RTT data is queued -- "
                    + "otherwise this isn't proving genuine 0-RTT, just a fast handshake",
                    establishedAtSendTime.get().booleanValue());

            assertEquals("zero-rtt-payload", new String(awaitStreamData(serverReceivedByStream, 0L, 3000),
                    StandardCharsets.US_ASCII));

            // Positive-path regression guard for Part 4's rejection
            // handling, added alongside it: accepted 0-RTT must not
            // trigger any resend-as-if-rejected bookkeeping.
            Object zeroRttState = getPrivateField(clientConnectionRef.get(), "zeroRttState", Object.class);
            assertEquals("ACCEPTED", zeroRttState.toString());
            @SuppressWarnings("unchecked")
            Map<Long, ?> sentZeroRttStream = getPrivateField(clientConnectionRef.get(), "sentZeroRttStream", Map.class);
            assertFalse("Accepted 0-RTT data should still be recorded as sent, not discarded",
                    sentZeroRttStream.isEmpty());
        } finally {
            loop.shutdown();
            loop.awaitQuiesce(2000);
            if (secondClientEngine != null) {
                secondClientEngine.close();
            }
            if (firstClientEngine != null) {
                firstClientEngine.close();
            }
            if (serverEngine != null) {
                serverEngine.close();
            }
        }
    }

    /**
     * RFC 9001 section 4.6.1: if the server rejects 0-RTT (early data
     * disabled server-side here, while the client still attempts it),
     * the client's queued stream data must be transparently resent at
     * 1-RTT once the handshake completes -- "as if it had never been
     * sent" -- and must still reach the server, at the same stream ID
     * and offset 0, with the exact original bytes. Verifies both the
     * observable outcome (data still arrives) and the internal
     * bookkeeping (0-RTT keys discarded, nothing left recorded as sent
     * under 0-RTT).
     */
    @Test
    public void testZeroRttRejectedResendsTransparentlyAtOneRtt() throws Exception {
        SessionTicketCache.clear();
        SelectorLoop loop = new SelectorLoop(0);
        loop.start();
        QuicEngine serverEngine = null;
        QuicEngine firstClientEngine = null;
        QuicEngine secondClientEngine = null;
        try {
            final Map<Long, byte[]> serverReceivedByStream = new ConcurrentHashMap<Long, byte[]>();
            QuicTransportFactory serverFactory = new QuicTransportFactory();
            serverFactory.setApplicationProtocols(ALPN);
            serverFactory.setCertFile(certFile);
            serverFactory.setKeyFile(keyFile);
            serverFactory.setEarlyDataEnabled(false); // server declines
            serverFactory.start();
            serverEngine = serverFactory.createServerEngine(
                    InetAddress.getLoopbackAddress(), 0, streamCapturingAcceptHandler(serverReceivedByStream), loop);
            InetSocketAddress serverAddress = (InetSocketAddress) serverEngine.getLocalAddress();

            firstClientEngine = captureSessionTicketViaRealHandshake(serverAddress, loop);

            QuicTransportFactory secondClientFactory = new QuicTransportFactory();
            secondClientFactory.setApplicationProtocols(ALPN);
            secondClientFactory.setVerifyPeer(false);
            secondClientFactory.setEarlyDataEnabled(true); // client still attempts
            secondClientFactory.start();

            final AtomicReference<QuicConnection> clientConnectionRef = new AtomicReference<QuicConnection>();
            final CountDownLatch earlyDataSent = new CountDownLatch(1);
            final CountDownLatch clientHandshakeComplete = new CountDownLatch(1);

            secondClientEngine = secondClientFactory.connect(
                    InetAddress.getLoopbackAddress(), serverAddress.getPort(),
                    new QuicEngine.ConnectionAcceptedHandler() {
                        @Override
                        public void connectionAccepted(QuicConnection connection) {
                            clientHandshakeComplete.countDown();
                        }
                    },
                    new QuicEngine.EarlyDataHandler() {
                        @Override
                        public void earlyDataReady(QuicConnection connection) {
                            clientConnectionRef.set(connection);
                            Endpoint endpoint = connection.openStream(new ProtocolHandler() {
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
                                    fail("Client 0-RTT stream error: " + cause);
                                }
                            });
                            endpoint.send(ByteBuffer.wrap("rejected-zero-rtt".getBytes(StandardCharsets.US_ASCII)));
                            earlyDataSent.countDown();
                        }
                    },
                    loop, SERVER_NAME);

            assertTrue("earlyDataReady should fire", earlyDataSent.await(5, TimeUnit.SECONDS));
            assertTrue("Client handshake should still complete despite the server's rejection",
                    clientHandshakeComplete.await(5, TimeUnit.SECONDS));

            assertEquals("rejected-zero-rtt", new String(awaitStreamData(serverReceivedByStream, 0L, 3000),
                    StandardCharsets.US_ASCII));

            QuicConnection clientConnection = clientConnectionRef.get();
            assertNotNull(clientConnection);
            Object zeroRttState = getPrivateField(clientConnection, "zeroRttState", Object.class);
            assertEquals("REJECTED", zeroRttState.toString());
            assertNull("0-RTT send keys should be discarded once rejected",
                    getPrivateField(clientConnection, "zeroRttSendKeys", Object.class));
            @SuppressWarnings("unchecked")
            Map<Long, ?> sentZeroRttStreamAfterDiscard =
                    getPrivateField(clientConnection, "sentZeroRttStream", Map.class);
            assertTrue("sentZeroRttStream should be empty once its contents are moved back to pendingStream",
                    sentZeroRttStreamAfterDiscard.isEmpty());
        } finally {
            loop.shutdown();
            loop.awaitQuiesce(2000);
            if (secondClientEngine != null) {
                secondClientEngine.close();
            }
            if (firstClientEngine != null) {
                firstClientEngine.close();
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
            final AtomicReference<Exception> maliciousStreamError = new AtomicReference<Exception>();

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
                                }

                                @Override
                                public void error(Exception cause) {
                                    maliciousStreamError.set(cause);
                                    maliciousStreamDisconnected.countDown();
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
            Exception cause = maliciousStreamError.get();
            assertNotNull("An error close must deliver an exception, not the "
                    + "argument-free disconnected() a clean close uses", cause);
            assertTrue("The exception must carry the CONNECTION_CLOSE detail",
                    cause instanceof QuicConnectionCloseException);
            QuicConnectionCloseException qcce = (QuicConnectionCloseException) cause;
            assertFalse("A flow-control violation is a transport-level error, not application-level",
                    qcce.isApplicationError());
            assertEquals("RFC 9000 section 20.1: FLOW_CONTROL_ERROR = 0x3",
                    0x3L, qcce.getErrorCode());
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
     * RFC 9000 section 2.2: STREAM frames carry an explicit byte offset
     * and are not guaranteed to arrive in order -- the underlying
     * transport is UDP. Forges two raw STREAM packets for the same new
     * stream with the second half of a message sent (and thus processed)
     * before the first, and asserts the server's real
     * {@code ProtocolHandler.receive()} sees the bytes reassembled into
     * correct stream order, not arrival order -- proving
     * {@link org.bluezoo.gumdrop.quic.tls.StreamReassembler} is actually
     * wired into the production receive path, not just correct in
     * isolation.
     */
    @Test
    public void testOutOfOrderStreamDataReassembledInOrder() throws Exception {
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
            serverFactory.start();

            final StringBuilder received = new StringBuilder();
            final CountDownLatch fullyReceived = new CountDownLatch(1);

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
                                    byte[] chunk = new byte[data.remaining()];
                                    data.get(chunk);
                                    synchronized (received) {
                                        received.append(new String(chunk, StandardCharsets.US_ASCII));
                                        if (received.length() >= 11) {
                                            fullyReceived.countDown();
                                        }
                                    }
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
                    }, loop);

            InetSocketAddress serverAddress = (InetSocketAddress) serverEngine.getLocalAddress();

            QuicTransportFactory clientFactory = new QuicTransportFactory();
            clientFactory.setApplicationProtocols(ALPN);
            clientFactory.setVerifyPeer(false);
            clientFactory.start();

            final CountDownLatch clientConnected = new CountDownLatch(1);

            clientEngine = clientFactory.connect(
                    InetAddress.getLoopbackAddress(), serverAddress.getPort(),
                    new ProtocolHandler() {
                        @Override
                        public void connected(Endpoint endpoint) {
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

            long streamId = 0; // first client-initiated bidi stream, never opened via the real API
            byte[] firstHalf = "hello ".getBytes(StandardCharsets.US_ASCII);
            byte[] secondHalf = "world".getBytes(StandardCharsets.US_ASCII);

            byte[] secondHalfPacket = forgeStreamPacket(oneRttKeys, serverConnectionId, packetNumber,
                    streamId, firstHalf.length, secondHalf);
            byte[] firstHalfPacket = forgeStreamPacket(oneRttKeys, serverConnectionId, packetNumber + 1,
                    streamId, 0, firstHalf);

            forgeChannel = DatagramChannel.open();
            // Deliberately reversed: the second half of the message is
            // sent (and thus processed by the server) before the first --
            // the reassembler must buffer it rather than deliver it early.
            forgeChannel.send(ByteBuffer.wrap(secondHalfPacket), serverAddress);
            forgeChannel.send(ByteBuffer.wrap(firstHalfPacket), serverAddress);

            assertTrue("Server should reassemble and deliver the complete message within 5s",
                    fullyReceived.await(5, TimeUnit.SECONDS));
            assertEquals("hello world", received.toString());
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
     * {@link QuicTransportFactory#setNamedGroups} is now actually
     * consulted client-side (previously a silent no-op -- see {@link
     * org.bluezoo.gumdrop.quic.tls.QuicTlsClientEngineTest} for the
     * focused resolution-logic coverage). This is the end-to-end proof
     * that explicitly requesting a real, Agent15-supported named group
     * doesn't break a real handshake against a real server.
     */
    @Test
    public void testClientHandshakeCompletesWithConfiguredNamedGroup() throws Exception {
        assertNamedGroupsAllowHandshake("secp256r1");
    }

    /**
     * A configured list whose first entry Agent15 cannot support (a real
     * IANA hybrid PQC group name -- Agent15 has no ML-KEM support at
     * all) must fall back to the first entry it does support, rather
     * than failing the connection outright.
     */
    @Test
    public void testClientHandshakeFallsBackWhenPreferredGroupUnsupported() throws Exception {
        assertNamedGroupsAllowHandshake("X25519MLKEM768:x25519");
    }

    private void assertNamedGroupsAllowHandshake(String namedGroups) throws Exception {
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

            serverEngine = serverFactory.createServerEngine(
                    InetAddress.getLoopbackAddress(), 0,
                    new StreamAcceptHandler() {
                        @Override
                        public ProtocolHandler acceptStream(Endpoint stream) {
                            return null;
                        }
                    }, loop);

            int port = ((InetSocketAddress) serverEngine.getLocalAddress()).getPort();

            QuicTransportFactory clientFactory = new QuicTransportFactory();
            clientFactory.setApplicationProtocols(ALPN);
            clientFactory.setVerifyPeer(false);
            clientFactory.setNamedGroups(namedGroups);
            clientFactory.start();

            final CountDownLatch clientConnected = new CountDownLatch(1);

            clientEngine = clientFactory.connect(
                    InetAddress.getLoopbackAddress(), port,
                    new ProtocolHandler() {
                        @Override
                        public void connected(Endpoint endpoint) {
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

            assertTrue("Handshake with namedGroups=\"" + namedGroups + "\" should complete within 5s",
                    clientConnected.await(5, TimeUnit.SECONDS));
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
     * {@link QuicTransportFactory#setCipherSuites} is now actually
     * consulted by both sides (previously a silent no-op -- see {@link
     * org.bluezoo.gumdrop.quic.tls.QuicCipherSuitesTest} for the focused
     * resolution-logic coverage). Forces the client down to ChaCha20-
     * Poly1305 only and confirms the real negotiated cipher, observed
     * via {@link SecurityInfo#getCipherSuite()} on the client's own
     * {@code securityEstablished} callback, is exactly that -- proving
     * the configured list is actually offered/accepted, not just that
     * the handshake happens to still complete.
     */
    @Test
    public void testClientHandshakeNegotiatesConfiguredCipherSuite() throws Exception {
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

            serverEngine = serverFactory.createServerEngine(
                    InetAddress.getLoopbackAddress(), 0,
                    new StreamAcceptHandler() {
                        @Override
                        public ProtocolHandler acceptStream(Endpoint stream) {
                            return null;
                        }
                    }, loop);

            int port = ((InetSocketAddress) serverEngine.getLocalAddress()).getPort();

            QuicTransportFactory clientFactory = new QuicTransportFactory();
            clientFactory.setApplicationProtocols(ALPN);
            clientFactory.setVerifyPeer(false);
            clientFactory.setCipherSuites("TLS_CHACHA20_POLY1305_SHA256");
            clientFactory.start();

            final CountDownLatch securityEstablished = new CountDownLatch(1);
            final AtomicReference<SecurityInfo> clientSecurityInfo = new AtomicReference<SecurityInfo>();

            clientEngine = clientFactory.connect(
                    InetAddress.getLoopbackAddress(), port,
                    new ProtocolHandler() {
                        @Override
                        public void connected(Endpoint endpoint) {
                        }

                        @Override
                        public void receive(ByteBuffer data) {
                        }

                        @Override
                        public void securityEstablished(SecurityInfo info) {
                            // Fires right after connected() on the same
                            // event-loop thread (see QuicConnection) --
                            // waited on directly, rather than on
                            // connected(), so the test thread can't race
                            // ahead of this call after countDown() wakes it.
                            clientSecurityInfo.set(info);
                            securityEstablished.countDown();
                        }

                        @Override
                        public void disconnected() {
                        }

                        @Override
                        public void error(Exception cause) {
                        }
                    }, loop, SERVER_NAME);

            assertTrue("Handshake forcing ChaCha20-Poly1305 should complete within 5s",
                    securityEstablished.await(5, TimeUnit.SECONDS));
            SecurityInfo info = clientSecurityInfo.get();
            assertNotNull("securityEstablished should have been called", info);
            assertEquals("TLS_CHACHA20_POLY1305_SHA256", info.getCipherSuite());
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
     * RFC 9000 section 19.19: a peer's application-level (0x1d)
     * CONNECTION_CLOSE must reach the stream's {@code error(Exception)}
     * with a {@link QuicConnectionCloseException} carrying
     * {@code applicationError=true} and the peer's code/reason -- not the
     * argument-free {@code disconnected()} a clean close uses. Unlike
     * {@link #testFlowControlViolationClosesConnection}, which exercises
     * the *local* {@code closeWithError} trigger (a transport-level close
     * this endpoint decides on itself), this exercises
     * {@code connectionCloseFrameReceived} -- an application-level close
     * genuinely initiated by the peer.
     */
    @Test
    public void testPeerApplicationCloseDeliversException() throws Exception {
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
            serverFactory.start();

            final CountDownLatch serverStreamRegistered = new CountDownLatch(1);
            final CountDownLatch serverStreamError = new CountDownLatch(1);
            final AtomicReference<Exception> serverError = new AtomicReference<Exception>();

            serverEngine = serverFactory.createServerEngine(
                    InetAddress.getLoopbackAddress(), 0,
                    new StreamAcceptHandler() {
                        @Override
                        public ProtocolHandler acceptStream(Endpoint stream) {
                            return new ProtocolHandler() {
                                @Override
                                public void connected(Endpoint endpoint) {
                                    // Forging the CONNECTION_CLOSE too early
                                    // (before the server has registered this
                                    // stream) would have nothing for
                                    // QuicConnection.close()'s dispatch loop
                                    // to call error() on -- wait for this,
                                    // not just the client-side connected().
                                    serverStreamRegistered.countDown();
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
                                    serverError.set(cause);
                                    serverStreamError.countDown();
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

            clientEngine = clientFactory.connect(
                    InetAddress.getLoopbackAddress(), serverAddress.getPort(),
                    new ProtocolHandler() {
                        @Override
                        public void connected(Endpoint endpoint) {
                            // A QUIC stream doesn't exist from the peer's
                            // point of view until a frame referencing it
                            // actually arrives -- send something so the
                            // server's acceptStream()/connected() fires and
                            // registers a stream for the forged
                            // CONNECTION_CLOSE dispatch to reach.
                            endpoint.send(ByteBuffer.wrap("hello".getBytes(StandardCharsets.US_ASCII)));
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
            assertTrue("Server should have registered the stream within 5s",
                    serverStreamRegistered.await(5, TimeUnit.SECONDS));

            // As in testFlowControlViolationClosesConnection: forge a raw
            // packet using the client's own negotiated key material and
            // connection ID, the same way a non-conformant/malicious
            // peer's own independent implementation could -- this time an
            // application-level CONNECTION_CLOSE rather than a violating
            // STREAM frame.
            QuicConnection clientConnection = getPrivateField(clientEngine, "clientConnection", QuicConnection.class);
            @SuppressWarnings("unchecked")
            Map<EncryptionLevel, PacketProtectionKeys> sendKeys =
                    getPrivateField(clientConnection, "sendKeys", Map.class);
            PacketProtectionKeys oneRttKeys = sendKeys.get(EncryptionLevel.ONE_RTT);
            byte[] serverConnectionId = getPrivateField(clientConnection, "peerConnectionId", byte[].class);
            long[] sendPacketNumber = getPrivateField(clientConnection, "sendPacketNumber", long[].class);
            long packetNumber = sendPacketNumber[EncryptionLevel.ONE_RTT.ordinal()];

            long applicationErrorCode = 0x10c; // arbitrary ALPN-scoped code
            String reason = "server going away";
            byte[] forgedPacket = forgeConnectionClosePacket(oneRttKeys, serverConnectionId,
                    packetNumber, applicationErrorCode, reason);

            forgeChannel = DatagramChannel.open();
            forgeChannel.send(ByteBuffer.wrap(forgedPacket), serverAddress);

            assertTrue("Server should deliver error() on a peer application close within 5s",
                    serverStreamError.await(5, TimeUnit.SECONDS));
            Exception cause = serverError.get();
            assertNotNull(cause);
            assertTrue(cause instanceof QuicConnectionCloseException);
            QuicConnectionCloseException qcce = (QuicConnectionCloseException) cause;
            assertTrue("An application-level CONNECTION_CLOSE must report applicationError=true",
                    qcce.isApplicationError());
            assertEquals(applicationErrorCode, qcce.getErrorCode());
            assertEquals(reason, qcce.getReason());
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
     * Send-side symmetry: {@code QuicConnection.closeWithApplicationError}
     * must actually emit a 0x1d (application-level) CONNECTION_CLOSE, not
     * the 0x1c (transport-level) frame {@code sendConnectionClose} used to
     * hardcode unconditionally. Proven by having the *server* call it and
     * asserting the *client* -- a real, independent peer that decodes the
     * wire bytes itself -- observes {@code applicationError=true} with the
     * right code/reason, rather than inspecting captured bytes directly.
     */
    @Test
    public void testCloseWithApplicationErrorDeliversToPeer() throws Exception {
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

            final CountDownLatch serverStreamRegistered = new CountDownLatch(1);

            serverEngine = serverFactory.createServerEngine(
                    InetAddress.getLoopbackAddress(), 0,
                    new StreamAcceptHandler() {
                        @Override
                        public ProtocolHandler acceptStream(Endpoint stream) {
                            return new ProtocolHandler() {
                                @Override
                                public void connected(Endpoint endpoint) {
                                    serverStreamRegistered.countDown();
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
                    }, loop);

            InetSocketAddress serverAddress = (InetSocketAddress) serverEngine.getLocalAddress();

            QuicTransportFactory clientFactory = new QuicTransportFactory();
            clientFactory.setApplicationProtocols(ALPN);
            clientFactory.setVerifyPeer(false);
            clientFactory.start();

            final CountDownLatch clientStreamError = new CountDownLatch(1);
            final AtomicReference<Exception> clientError = new AtomicReference<Exception>();

            clientEngine = clientFactory.connect(
                    InetAddress.getLoopbackAddress(), serverAddress.getPort(),
                    new ProtocolHandler() {
                        @Override
                        public void connected(Endpoint endpoint) {
                            endpoint.send(ByteBuffer.wrap("hello".getBytes(StandardCharsets.US_ASCII)));
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
                            clientStreamError.countDown();
                        }
                    }, loop, SERVER_NAME);

            assertTrue("Server should have registered the stream within 5s",
                    serverStreamRegistered.await(5, TimeUnit.SECONDS));

            @SuppressWarnings("unchecked")
            Map<String, QuicConnection> serverConnections =
                    getPrivateField(serverEngine, "connections", Map.class);
            QuicConnection serverConnection = serverConnections.values().iterator().next();

            long applicationErrorCode = 0x42;
            String reason = "graceful application shutdown";
            serverConnection.closeWithApplicationError(applicationErrorCode, reason);

            assertTrue("Client should deliver error() from the server's application close within 5s",
                    clientStreamError.await(5, TimeUnit.SECONDS));
            Exception cause = clientError.get();
            assertNotNull(cause);
            assertTrue(cause instanceof QuicConnectionCloseException);
            QuicConnectionCloseException qcce = (QuicConnectionCloseException) cause;
            assertTrue("closeWithApplicationError must be delivered to the peer as "
                    + "an application-level (0x1d) close, not transport-level (0x1c)",
                    qcce.isApplicationError());
            assertEquals(applicationErrorCode, qcce.getErrorCode());
            assertEquals(reason, qcce.getReason());
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
     * Builds a single real 1-RTT application-level (0x1d) CONNECTION_CLOSE
     * packet, mirroring {@link #forgeStreamPacket}'s construction sequence.
     */
    private static byte[] forgeConnectionClosePacket(PacketProtectionKeys keys, byte[] destinationConnectionId,
            long packetNumber, long errorCode, String reason) throws Exception {
        int pnLength = PacketNumberCodec.encodedLength(packetNumber, -1);
        byte[] header = ShortHeaderCodec.build(destinationConnectionId, false, packetNumber, pnLength);

        int frameBytes = QuicFrameWriter.connectionCloseLength(true, errorCode, reason);
        ByteBuffer payload = ByteBuffer.allocate(frameBytes);
        QuicFrameWriter.writeConnectionClose(payload, true, errorCode, 0, reason);
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

    private static void setPrivateField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * RFC 9000 section 8.1: a server must not send more than 3x what it
     * has received from a peer whose address isn't yet validated.
     * Completes a real handshake (after which {@code addressValidated}
     * should already be true, checked as a sanity baseline), then
     * reflectively resets the server connection's amplification state to
     * simulate a not-yet-validated peer with only a small receive budget,
     * and has the client trigger a response far larger than 3x that
     * budget -- proving the server withholds it rather than sending an
     * amplified reply to a peer it hasn't confirmed owns that address.
     */
    @Test
    public void testAntiAmplificationLimitsUnvalidatedServerSending() throws Exception {
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

            final int largeResponseSize = 4000;
            final CountDownLatch serverReceivedFin = new CountDownLatch(1);
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
                                }

                                @Override
                                public void securityEstablished(SecurityInfo info) {
                                }

                                @Override
                                public void disconnected() {
                                    byte[] big = new byte[largeResponseSize];
                                    serverStreamEndpoint.get().send(ByteBuffer.wrap(big));
                                    serverStreamEndpoint.get().close();
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

            final CountDownLatch clientConnected = new CountDownLatch(1);
            final AtomicReference<Endpoint> clientEndpoint = new AtomicReference<Endpoint>();
            final AtomicReference<Integer> clientReceivedBytes = new AtomicReference<Integer>(0);

            clientEngine = clientFactory.connect(
                    InetAddress.getLoopbackAddress(), port,
                    new ProtocolHandler() {
                        @Override
                        public void connected(Endpoint endpoint) {
                            clientEndpoint.set(endpoint);
                            clientConnected.countDown();
                        }

                        @Override
                        public void receive(ByteBuffer data) {
                            clientReceivedBytes.set(clientReceivedBytes.get() + data.remaining());
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

            QuicConnection serverConnection = getOnlyServerConnection(serverEngine);
            assertTrue("A real completed handshake should already have validated the client's address",
                    getPrivateField(serverConnection, "addressValidated", Boolean.class));

            // Simulate a connection that hasn't validated its peer yet and
            // has only received a small amount of data from it.
            long smallReceivedBudget = 20;
            setPrivateField(serverConnection, "addressValidated", Boolean.FALSE);
            setPrivateField(serverConnection, "amplificationBytesReceived", Long.valueOf(smallReceivedBudget));
            setPrivateField(serverConnection, "amplificationBytesSent", Long.valueOf(0L));

            clientEndpoint.get().send(ByteBuffer.wrap("ping".getBytes(StandardCharsets.US_ASCII)));
            clientEndpoint.get().close();
            // The server's disconnected() callback above calls send()/close()
            // synchronously, and both funnel through requestFlush()/flush()
            // on the same call stack -- so by the time this latch fires, the
            // gate has already been evaluated for the oversized response at
            // least once. Checking immediately (rather than after a sleep)
            // captures that moment, before any later retry/regrowth cycle
            // (each of which is separately gate-checked in its own right, so
            // the invariant below holds at any sampled instant, but a delay
            // risks accumulating enough legitimately-received bytes -- e.g.
            // the client's own ACKs -- to make the check trivially true
            // regardless of whether the first attempt was actually withheld).
            assertTrue("Server should still process the client's FIN and attempt its response",
                    serverReceivedFin.await(5, TimeUnit.SECONDS));

            long amplificationBytesReceived = getPrivateField(serverConnection, "amplificationBytesReceived", Long.class);
            long amplificationBytesSent = getPrivateField(serverConnection, "amplificationBytesSent", Long.class);
            assertTrue("Server must not send more than 3x what it has received from an unvalidated peer "
                    + "(sent=" + amplificationBytesSent + ", received=" + amplificationBytesReceived + ")",
                    amplificationBytesSent <= 3 * amplificationBytesReceived);
            assertTrue("The gate should have actually withheld the oversized response at least initially",
                    amplificationBytesSent < largeResponseSize);
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

    @SuppressWarnings("unchecked")
    private static QuicConnection getOnlyServerConnection(QuicEngine serverEngine) throws Exception {
        Map<String, QuicConnection> connections = getPrivateField(serverEngine, "connections", Map.class);
        return connections.values().iterator().next();
    }

    /**
     * RFC 9000 section 12.2: sends one real client Initial packet
     * (crafted with {@link QuicTestPeer}, since it already drives a real
     * Agent15 handshake -- production {@link QuicEngine}/{@link
     * QuicConnection} on the receiving end can't tell the difference from
     * a real client) at a real production server over a real raw socket,
     * then counts how many separate UDP datagrams arrive back within a
     * short quiet period. Before packet coalescing, the server's first
     * flight (an Initial ACK+CRYPTO packet and a Handshake CRYPTO packet)
     * went out as two separate datagrams; it must now be exactly one.
     */
    @Test
    public void testServerFirstFlightIsCoalescedIntoOneDatagram() throws Exception {
        SelectorLoop loop = new SelectorLoop(0);
        loop.start();
        QuicEngine serverEngine = null;
        DatagramChannel clientChannel = null;
        try {
            QuicTransportFactory serverFactory = new QuicTransportFactory();
            serverFactory.setApplicationProtocols(ALPN);
            serverFactory.setCertFile(certFile);
            serverFactory.setKeyFile(keyFile);
            serverFactory.start();

            serverEngine = serverFactory.createServerEngine(
                    InetAddress.getLoopbackAddress(), 0,
                    new StreamAcceptHandler() {
                        @Override
                        public ProtocolHandler acceptStream(Endpoint stream) {
                            return null;
                        }
                    }, loop);
            InetSocketAddress serverAddress = (InetSocketAddress) serverEngine.getLocalAddress();

            byte[] clientInitialDcid = QuicHandshakeEndToEndTest.randomConnectionId();
            byte[] clientScid = QuicHandshakeEndToEndTest.randomConnectionId();
            TransportParameters clientParams = QuicHandshakeEndToEndTest.defaultTransportParameters(clientScid);
            QuicTestPeer client = QuicTestPeer.newClient(clientInitialDcid, clientParams);
            client.startHandshake(SERVER_NAME);
            byte[] clientInitialDatagram = client.buildPacket(EncryptionLevel.INITIAL,
                    clientInitialDcid, clientScid, false, false, 1200);

            clientChannel = DatagramChannel.open();
            clientChannel.send(ByteBuffer.wrap(clientInitialDatagram), serverAddress);

            int datagramCount = countDatagramsWithinQuietPeriod(clientChannel, 500);
            assertEquals("The server's Initial-ACK+CRYPTO and Handshake-CRYPTO packets "
                    + "should now be coalesced into a single UDP datagram (RFC 9000 section 12.2)",
                    1, datagramCount);
        } finally {
            if (clientChannel != null) {
                clientChannel.close();
            }
            loop.shutdown();
            loop.awaitQuiesce(2000);
            if (serverEngine != null) {
                serverEngine.close();
            }
        }
    }

    private static int countDatagramsWithinQuietPeriod(DatagramChannel channel, long quietPeriodMs) throws IOException {
        channel.configureBlocking(false);
        Selector selector = Selector.open();
        try {
            channel.register(selector, SelectionKey.OP_READ);
            int count = 0;
            ByteBuffer buf = ByteBuffer.allocate(4096);
            while (true) {
                int ready = selector.select(quietPeriodMs);
                if (ready == 0) {
                    break;
                }
                selector.selectedKeys().clear();
                buf.clear();
                if (channel.receive(buf) != null) {
                    count++;
                }
            }
            return count;
        } finally {
            selector.close();
        }
    }

    /**
     * With {@link QuicTransportFactory#setRequireRetry} set, a real
     * client+server handshake driven purely through the production
     * classes must still complete (now with the extra Retry round trip)
     * and the resulting connection must be otherwise fully usable --
     * proving the client's Retry handling (integrity verification, token
     * echo, Initial key re-derivation, CRYPTO resend) and the server's
     * stateless token issuance/validation all interoperate correctly.
     */
    @Test
    public void testRetryHandshakeCompletesAndConnectionIsUsable() throws Exception {
        SelectorLoop loop = new SelectorLoop(0);
        loop.start();
        QuicEngine serverEngine = null;
        QuicEngine clientEngine = null;
        try {
            QuicTransportFactory serverFactory = new QuicTransportFactory();
            serverFactory.setApplicationProtocols(ALPN);
            serverFactory.setCertFile(certFile);
            serverFactory.setKeyFile(keyFile);
            serverFactory.setRequireRetry(true);
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

            assertTrue("Server should have received the client's FIN within 5s (after the extra Retry round trip)",
                    serverReceivedFin.await(5, TimeUnit.SECONDS));
            assertTrue("Client should have received the server's FIN within 5s",
                    clientReceivedFin.await(5, TimeUnit.SECONDS));

            assertEquals("ping", new String(serverReceived.get(), StandardCharsets.US_ASCII));
            assertEquals("pong", new String(clientReceived.get(), StandardCharsets.US_ASCII));

            QuicConnection serverConnection = getOnlyServerConnection(serverEngine);
            assertTrue("A connection accepted via a validated Retry token should be marked address-validated",
                    getPrivateField(serverConnection, "addressValidated", Boolean.class));
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
     * With {@link QuicTransportFactory#setRequireRetry} set, a client
     * Initial carrying a garbage (not a real sealed) Retry Token must not
     * be accepted as a new connection -- the server should instead answer
     * with a fresh, well-formed Retry, exactly as it would for a client
     * with no token at all. Crafted with {@link QuicTestPeer} (a real
     * Agent15-driven handshake start), since only its Initial CRYPTO
     * content needs to be genuine -- the token field itself is the thing
     * under test and can't be produced legitimately without the server's
     * own secret key.
     */
    @Test
    public void testInvalidRetryTokenIsRejectedWithFreshRetry() throws Exception {
        SelectorLoop loop = new SelectorLoop(0);
        loop.start();
        QuicEngine serverEngine = null;
        DatagramChannel clientChannel = null;
        try {
            QuicTransportFactory serverFactory = new QuicTransportFactory();
            serverFactory.setApplicationProtocols(ALPN);
            serverFactory.setCertFile(certFile);
            serverFactory.setKeyFile(keyFile);
            serverFactory.setRequireRetry(true);
            serverFactory.start();

            serverEngine = serverFactory.createServerEngine(
                    InetAddress.getLoopbackAddress(), 0,
                    new StreamAcceptHandler() {
                        @Override
                        public ProtocolHandler acceptStream(Endpoint stream) {
                            return null;
                        }
                    }, loop);
            InetSocketAddress serverAddress = (InetSocketAddress) serverEngine.getLocalAddress();

            byte[] clientInitialDcid = QuicHandshakeEndToEndTest.randomConnectionId();
            byte[] clientScid = QuicHandshakeEndToEndTest.randomConnectionId();
            TransportParameters clientParams = QuicHandshakeEndToEndTest.defaultTransportParameters(clientScid);
            QuicTestPeer client = QuicTestPeer.newClient(clientInitialDcid, clientParams);
            client.startHandshake(SERVER_NAME);
            byte[] garbageToken = "not a real sealed retry token".getBytes(StandardCharsets.US_ASCII);
            byte[] clientInitialDatagram = client.buildPacket(EncryptionLevel.INITIAL,
                    clientInitialDcid, clientScid, garbageToken, false, false, 1200);

            clientChannel = DatagramChannel.open();
            clientChannel.send(ByteBuffer.wrap(clientInitialDatagram), serverAddress);

            clientChannel.configureBlocking(false);
            Selector selector = Selector.open();
            byte[] responseBytes;
            try {
                clientChannel.register(selector, SelectionKey.OP_READ);
                assertTrue("Server should have responded within 5s", selector.select(5000) > 0);
                ByteBuffer buf = ByteBuffer.allocate(2048);
                clientChannel.receive(buf);
                buf.flip();
                responseBytes = new byte[buf.remaining()];
                buf.get(responseBytes);
            } finally {
                selector.close();
            }

            int responsePacketType = (responseBytes[0] >>> 4) & 0x03;
            assertEquals("A garbage token should be rejected with a fresh Retry, not accepted",
                    org.bluezoo.gumdrop.quic.packet.LongHeaderCodec.TYPE_RETRY, responsePacketType);

            org.bluezoo.gumdrop.quic.packet.RetryPacket retry =
                    org.bluezoo.gumdrop.quic.packet.LongHeaderCodec.parseRetry(responseBytes);
            // RFC 9000 section 17.2.5.1: the Retry's own Destination
            // Connection ID must echo the client's Source Connection ID,
            // not the (unrelated) DCID the client's Initial used.
            assertArrayEquals(clientScid, retry.getDestinationConnectionId());
            assertTrue("The server's Retry must carry a genuine RFC 9001 section 5.8 integrity tag",
                    org.bluezoo.gumdrop.quic.packet.RetryIntegrityTag.verify(
                            clientInitialDcid, retry.getPacketWithoutTag(), retry.getTag()));

            @SuppressWarnings("unchecked")
            Map<String, QuicConnection> connections = getPrivateField(serverEngine, "connections", Map.class);
            assertTrue("No connection should have been accepted for an invalid Retry token", connections.isEmpty());
        } finally {
            if (clientChannel != null) {
                clientChannel.close();
            }
            loop.shutdown();
            loop.awaitQuiesce(2000);
            if (serverEngine != null) {
                serverEngine.close();
            }
        }
    }

    /**
     * RFC 9000 section 9: passive/reactive connection migration. Completes
     * a real handshake and a ping/pong over the client's normal channel,
     * then simulates a NAT rebind by sending further real,
     * correctly-protected traffic for that same connection from a second,
     * independent local {@link DatagramChannel} bound to a different port
     * -- exactly what happens transparently, from the QUIC connection's
     * own point of view, when a client's networking stack rebinds: same
     * connection, same negotiated keys, just a different source address.
     * Proves the server detects the new address, validates it via
     * PATH_CHALLENGE/PATH_RESPONSE before switching {@code remoteAddress}
     * to it, and keeps delivering stream data once the new path is confirmed.
     */
    @Test
    public void testServerDetectsClientRebindAndValidatesNewPath() throws Exception {
        SelectorLoop loop = new SelectorLoop(0);
        loop.start();
        QuicEngine serverEngine = null;
        QuicEngine clientEngine = null;
        DatagramChannel rebindChannel = null;
        try {
            QuicTransportFactory serverFactory = new QuicTransportFactory();
            serverFactory.setApplicationProtocols(ALPN);
            serverFactory.setCertFile(certFile);
            serverFactory.setKeyFile(keyFile);
            serverFactory.start();

            final CountDownLatch serverReceivedRebindStream = new CountDownLatch(1);
            final AtomicReference<byte[]> serverReceivedRebindData = new AtomicReference<byte[]>();

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
                                    serverReceivedRebindData.set(bytes);
                                    // Close out this stream promptly, same as every
                                    // other test in this file does, rather than
                                    // leaving the connection open and idle for the
                                    // rest of the JVM's lifetime once this test returns.
                                    stream.close();
                                    serverReceivedRebindStream.countDown();
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
                    }, loop);

            InetSocketAddress serverAddress = (InetSocketAddress) serverEngine.getLocalAddress();

            QuicTransportFactory clientFactory = new QuicTransportFactory();
            clientFactory.setApplicationProtocols(ALPN);
            clientFactory.setVerifyPeer(false);
            clientFactory.start();

            final CountDownLatch clientConnected = new CountDownLatch(1);

            clientEngine = clientFactory.connect(
                    InetAddress.getLoopbackAddress(), serverAddress.getPort(),
                    new ProtocolHandler() {
                        @Override
                        public void connected(Endpoint endpoint) {
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

            // Reach into the real, negotiated key material and packet
            // number counter -- the only way to construct further packets
            // for this same connection that the server will actually
            // accept, exactly as a genuine rebinding client's own
            // networking stack would (same connection, same keys, just a
            // different local port).
            QuicConnection clientConnection = getPrivateField(clientEngine, "clientConnection", QuicConnection.class);
            @SuppressWarnings("unchecked")
            Map<EncryptionLevel, PacketProtectionKeys> clientSendKeys =
                    getPrivateField(clientConnection, "sendKeys", Map.class);
            @SuppressWarnings("unchecked")
            Map<EncryptionLevel, PacketProtectionKeys> clientRecvKeys =
                    getPrivateField(clientConnection, "recvKeys", Map.class);
            PacketProtectionKeys clientToServerKeys = clientSendKeys.get(EncryptionLevel.ONE_RTT);
            PacketProtectionKeys serverToClientKeys = clientRecvKeys.get(EncryptionLevel.ONE_RTT);
            byte[] serverConnectionId = getPrivateField(clientConnection, "peerConnectionId", byte[].class);
            byte[] clientConnectionId = getPrivateField(clientConnection, "ourConnectionId", byte[].class);
            long[] clientSendPacketNumber = getPrivateField(clientConnection, "sendPacketNumber", long[].class);

            // From here on, every further packet for this connection is
            // forged by hand and sent from rebindChannel; clientEngine's
            // own channel is simply left idle (it has nothing to
            // proactively send -- an ACK-only packet from the server
            // isn't itself ack-eliciting, RFC 9000 section 13.2, so it
            // won't trigger a reply).
            rebindChannel = DatagramChannel.open();
            // An explicit loopback bind, not bind(null)'s wildcard address --
            // the server observes a datagram's concrete source address
            // (127.0.0.1:port), so the two must match for the address
            // comparison in QuicConnection.receive to work as intended.
            rebindChannel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            rebindChannel.configureBlocking(false);
            InetSocketAddress rebindLocalAddress = (InetSocketAddress) rebindChannel.getLocalAddress();

            // A single real (correctly encrypted/header-protected) PING
            // packet, sent from the new local address -- decrypting
            // successfully with the connection's real 1-RTT keys is
            // exactly the proof the server needs that this isn't spoofed.
            long pingPacketNumber = clientSendPacketNumber[EncryptionLevel.ONE_RTT.ordinal()]++;
            byte[] pingPacket = forgePingPacket(clientToServerKeys, serverConnectionId, pingPacketNumber);
            sendReliably(rebindChannel, pingPacket, serverAddress);

            byte[] challengeData = receivePathChallengeData(rebindChannel, serverToClientKeys,
                    clientConnectionId.length, 10000);
            assertTrue("Server should have sent a PATH_CHALLENGE to validate the new path", challengeData != null);

            long responsePacketNumber = clientSendPacketNumber[EncryptionLevel.ONE_RTT.ordinal()]++;
            byte[] pathResponsePacket = forgePathResponsePacket(clientToServerKeys, serverConnectionId,
                    responsePacketNumber, challengeData);
            sendReliably(rebindChannel, pathResponsePacket, serverAddress);

            QuicConnection serverConnection = getOnlyServerConnection(serverEngine);
            InetSocketAddress switchedRemote = null;
            long deadline = System.currentTimeMillis() + 10000;
            while (System.currentTimeMillis() < deadline) {
                InetSocketAddress currentRemote =
                        getPrivateField(serverConnection, "remoteAddress", InetSocketAddress.class);
                if (rebindLocalAddress.equals(currentRemote)) {
                    switchedRemote = currentRemote;
                    break;
                }
                Thread.sleep(20);
            }
            assertEquals("The server should have switched to the validated new path",
                    rebindLocalAddress, switchedRemote);

            // Prove the connection still works over the newly-validated
            // path: a fresh client-initiated stream, sent from the same
            // new address, must be delivered normally.
            long streamPacketNumber = clientSendPacketNumber[EncryptionLevel.ONE_RTT.ordinal()]++;
            byte[] rebindStreamData = "still-works-after-migration".getBytes(StandardCharsets.US_ASCII);
            byte[] streamPacket = forgeStreamPacket(clientToServerKeys, serverConnectionId, streamPacketNumber,
                    4, 0, rebindStreamData);
            sendReliably(rebindChannel, streamPacket, serverAddress);

            assertTrue("Server should have delivered stream data sent over the new path within 10s",
                    serverReceivedRebindStream.await(10, TimeUnit.SECONDS));
            assertArrayEquals(rebindStreamData, serverReceivedRebindData.get());
        } finally {
            if (rebindChannel != null) {
                rebindChannel.close();
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
     * Sends a datagram on a non-blocking channel, retrying if the OS
     * declines it without error (a non-blocking {@link DatagramChannel#send}
     * returns 0 rather than blocking or throwing when its send buffer is
     * momentarily full -- rare on loopback, but real under the kind of
     * sustained concurrent load a full test-suite run produces) --
     * otherwise a silently dropped packet leaves the rest of the test
     * waiting out its full timeout for a reply that was never actually
     * requested.
     */
    private static void sendReliably(DatagramChannel channel, byte[] packet, java.net.SocketAddress target)
            throws IOException, InterruptedException {
        ByteBuffer buf = ByteBuffer.wrap(packet);
        for (int attempt = 0; attempt < 20; attempt++) {
            if (channel.send(buf, target) > 0) {
                return;
            }
            Thread.sleep(10);
        }
        fail("Failed to send a " + packet.length + "-byte datagram to " + target + " after repeated retries");
    }

    private static byte[] forgePingPacket(PacketProtectionKeys keys, byte[] destinationConnectionId, long packetNumber)
            throws Exception {
        int pnLength = PacketNumberCodec.encodedLength(packetNumber, -1);
        byte[] header = ShortHeaderCodec.build(destinationConnectionId, false, packetNumber, pnLength);

        int frameBytes = QuicFrameWriter.pingLength();
        int paddingBytes = hpSamplePadding(pnLength, frameBytes);
        ByteBuffer payload = ByteBuffer.allocate(frameBytes + paddingBytes);
        QuicFrameWriter.writePing(payload);
        if (paddingBytes > 0) {
            QuicFrameWriter.writePadding(payload, paddingBytes);
        }
        payload.flip();
        byte[] plaintext = new byte[payload.remaining()];
        payload.get(plaintext);

        return sealShortHeaderPacket(keys, header, plaintext, packetNumber, destinationConnectionId.length, pnLength);
    }

    private static byte[] forgePathResponsePacket(PacketProtectionKeys keys, byte[] destinationConnectionId,
            long packetNumber, byte[] data) throws Exception {
        int pnLength = PacketNumberCodec.encodedLength(packetNumber, -1);
        byte[] header = ShortHeaderCodec.build(destinationConnectionId, false, packetNumber, pnLength);

        int frameBytes = QuicFrameWriter.pathResponseLength();
        int paddingBytes = hpSamplePadding(pnLength, frameBytes);
        ByteBuffer payload = ByteBuffer.allocate(frameBytes + paddingBytes);
        QuicFrameWriter.writePathResponse(payload, data);
        if (paddingBytes > 0) {
            QuicFrameWriter.writePadding(payload, paddingBytes);
        }
        payload.flip();
        byte[] plaintext = new byte[payload.remaining()];
        payload.get(plaintext);

        return sealShortHeaderPacket(keys, header, plaintext, packetNumber, destinationConnectionId.length, pnLength);
    }

    // RFC 9001 section 5.4.2: the header-protection sample is taken 4
    // bytes after the packet number field and is SAMPLE_LENGTH bytes
    // long -- a small single-frame packet (PING, PATH_RESPONSE) doesn't
    // naturally carry enough ciphertext for that sample to exist without
    // padding, unlike a real STREAM frame's payload.
    private static int hpSamplePadding(int pnLength, int frameBytes) {
        return Math.max(0, 4 + QuicAeadAlgorithm.SAMPLE_LENGTH - pnLength - QuicAeadAlgorithm.TAG_LENGTH - frameBytes);
    }

    private static byte[] sealShortHeaderPacket(PacketProtectionKeys keys, byte[] header, byte[] plaintext,
            long packetNumber, int dcidLength, int pnLength) throws Exception {
        byte[] ciphertext = PacketProtection.seal(keys, packetNumber, header, plaintext);
        byte[] packet = new byte[header.length + ciphertext.length];
        System.arraycopy(header, 0, packet, 0, header.length);
        System.arraycopy(ciphertext, 0, packet, header.length, ciphertext.length);

        int pnOffset = ShortHeaderCodec.packetNumberOffset(dcidLength);
        byte[] sample = new byte[QuicAeadAlgorithm.SAMPLE_LENGTH];
        System.arraycopy(packet, pnOffset + 4, sample, 0, sample.length);
        byte[] mask = PacketProtection.headerProtectionMask(keys, sample);
        PacketProtection.xorFirstByte(packet, mask, false);
        PacketProtection.xorPacketNumberBytes(packet, pnOffset, pnLength, mask);

        return packet;
    }

    /**
     * Receives one datagram on {@code channel}, decrypts it as a
     * short-header 1-RTT packet with {@code recvKeys} (the same sequence
     * {@link QuicConnection#processPacket} follows, done by hand here
     * since this is the test's own receiving socket, not a production
     * connection), and returns the data of its PATH_CHALLENGE frame -- or
     * {@code null} if no datagram arrived within {@code timeoutMs} or it
     * carried no such frame.
     */
    private static byte[] receivePathChallengeData(DatagramChannel channel, PacketProtectionKeys recvKeys,
            int dcidLength, long timeoutMs) throws Exception {
        Selector selector = Selector.open();
        try {
            channel.register(selector, SelectionKey.OP_READ);
            if (selector.select(timeoutMs) == 0) {
                return null;
            }
            selector.selectedKeys().clear();
            ByteBuffer buf = ByteBuffer.allocate(2048);
            if (channel.receive(buf) == null) {
                return null;
            }
            buf.flip();
            byte[] packet = new byte[buf.remaining()];
            buf.get(packet);

            int pnOffset = ShortHeaderCodec.packetNumberOffset(dcidLength);
            byte[] sample = new byte[QuicAeadAlgorithm.SAMPLE_LENGTH];
            System.arraycopy(packet, pnOffset + 4, sample, 0, sample.length);
            byte[] mask = PacketProtection.headerProtectionMask(recvKeys, sample);
            PacketProtection.xorFirstByte(packet, mask, false);
            int pnLength = (packet[0] & 0x03) + 1;
            PacketProtection.xorPacketNumberBytes(packet, pnOffset, pnLength, mask);

            long truncatedPn = 0;
            for (int i = 0; i < pnLength; i++) {
                truncatedPn = (truncatedPn << 8) | (packet[pnOffset + i] & 0xff);
            }
            long fullPacketNumber = PacketNumberCodec.decode(-1, truncatedPn, pnLength);

            int headerLength = pnOffset + pnLength;
            byte[] aad = java.util.Arrays.copyOfRange(packet, 0, headerLength);
            byte[] ciphertext = java.util.Arrays.copyOfRange(packet, headerLength, packet.length);
            byte[] plaintext = PacketProtection.open(recvKeys, fullPacketNumber, aad, ciphertext);

            final AtomicReference<byte[]> result = new AtomicReference<byte[]>();
            new QuicFrameParser(new NoOpFrameHandler() {
                @Override
                public void pathChallengeFrameReceived(ByteBuffer data) {
                    byte[] bytes = new byte[data.remaining()];
                    data.get(bytes);
                    result.set(bytes);
                }
            }).receive(ByteBuffer.wrap(plaintext));
            return result.get();
        } finally {
            selector.close();
        }
    }

    /** A {@link QuicFrameHandler} whose every callback is a no-op, for tests that only care about one frame type. */
    private static class NoOpFrameHandler implements QuicFrameHandler {

        @Override
        public void paddingFrameReceived(int length) {
        }

        @Override
        public void pingFrameReceived() {
        }

        @Override
        public void ackFrameReceived(long largestAcknowledged, long ackDelay, long[][] ranges) {
        }

        @Override
        public void resetStreamFrameReceived(long streamId, long applicationErrorCode, long finalSize) {
        }

        @Override
        public void stopSendingFrameReceived(long streamId, long applicationErrorCode) {
        }

        @Override
        public void cryptoFrameReceived(long offset, ByteBuffer data) {
        }

        @Override
        public void newTokenFrameReceived(ByteBuffer token) {
        }

        @Override
        public void streamFrameReceived(long streamId, long offset, boolean fin, ByteBuffer data) {
        }

        @Override
        public void maxDataFrameReceived(long maximumData) {
        }

        @Override
        public void maxStreamDataFrameReceived(long streamId, long maximumStreamData) {
        }

        @Override
        public void maxStreamsFrameReceived(boolean bidirectional, long maximumStreams) {
        }

        @Override
        public void dataBlockedFrameReceived(long maximumData) {
        }

        @Override
        public void streamDataBlockedFrameReceived(long streamId, long maximumStreamData) {
        }

        @Override
        public void streamsBlockedFrameReceived(boolean bidirectional, long maximumStreams) {
        }

        @Override
        public void newConnectionIdFrameReceived(long sequenceNumber, long retirePriorTo,
                ByteBuffer connectionId, ByteBuffer statelessResetToken) {
        }

        @Override
        public void retireConnectionIdFrameReceived(long sequenceNumber) {
        }

        @Override
        public void pathChallengeFrameReceived(ByteBuffer data) {
        }

        @Override
        public void pathResponseFrameReceived(ByteBuffer data) {
        }

        @Override
        public void connectionCloseFrameReceived(boolean applicationError, long errorCode,
                long frameType, String reason) {
        }

        @Override
        public void handshakeDoneFrameReceived() {
        }

        @Override
        public void frameError(String message) {
        }
    }
}
