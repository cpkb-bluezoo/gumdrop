/*
 * QuicVersionNegotiationEndToEndTest.java
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

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
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
import org.bluezoo.gumdrop.quic.packet.LongHeaderCodec;
import org.bluezoo.gumdrop.quic.packet.LongHeaderInvariants;
import org.bluezoo.gumdrop.quic.packet.VersionNegotiationPacket;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Exercises RFC 9000 section 6 Version Negotiation over loopback UDP,
 * with a raw {@link DatagramSocket} standing in for the peer: the server
 * must answer a large-enough datagram of an unknown version with a
 * Version Negotiation packet advertising version 1 (and never answer a
 * small one, or a Version Negotiation packet), and a client must react
 * only to a Version Negotiation packet that is valid for its connection
 * attempt and does not list the version it used.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicVersionNegotiationEndToEndTest {

    private static final String ALPN = "gumdrop-test";
    private static final int GREASE_VERSION = 0x1a2a3a4a;
    private static final byte[] CLIENT_DCID = { 1, 2, 3, 4, 5, 6, 7, 8 };
    private static final byte[] CLIENT_SCID = { 11, 12, 13, 14, 15 };

    @BeforeClass
    public static void requireTlsFiles() {
        TestTlsFiles.assumeAvailable();
    }

    private static byte[] unsupportedVersionDatagram(int length, int version) {
        byte[] packet = new byte[length];
        packet[0] = (byte) 0xc0;
        packet[1] = (byte) (version >>> 24);
        packet[2] = (byte) (version >>> 16);
        packet[3] = (byte) (version >>> 8);
        packet[4] = (byte) version;
        packet[5] = (byte) CLIENT_DCID.length;
        System.arraycopy(CLIENT_DCID, 0, packet, 6, CLIENT_DCID.length);
        packet[6 + CLIENT_DCID.length] = (byte) CLIENT_SCID.length;
        System.arraycopy(CLIENT_SCID, 0, packet, 7 + CLIENT_DCID.length, CLIENT_SCID.length);
        return packet;
    }

    private static DatagramPacket receiveOrNull(DatagramSocket socket, int timeoutMillis) throws Exception {
        socket.setSoTimeout(timeoutMillis);
        byte[] buf = new byte[2048];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        try {
            socket.receive(p);
            return p;
        } catch (SocketTimeoutException e) {
            return null;
        }
    }

    private static void send(DatagramSocket socket, byte[] bytes, int port) throws Exception {
        socket.send(new DatagramPacket(bytes, bytes.length, InetAddress.getLoopbackAddress(), port));
    }

    private static final class ServerFixture {
        final SelectorLoop loop = new SelectorLoop(0);
        QuicEngine engine;
        int port;

        void start() throws Exception {
            loop.start();
            QuicTransportFactory factory = new QuicTransportFactory();
            factory.setApplicationProtocols(ALPN);
            factory.setCertFile(TestTlsFiles.certFile());
            factory.setKeyFile(TestTlsFiles.keyFile());
            factory.start();
            engine = factory.createServerEngine(InetAddress.getLoopbackAddress(), 0,
                    new StreamAcceptHandler() {
                        @Override
                        public ProtocolHandler acceptStream(Endpoint stream) {
                            return null;
                        }
                    }, loop);
            port = ((InetSocketAddress) engine.getLocalAddress()).getPort();
        }

        void stop() {
            loop.shutdown();
            loop.awaitQuiesce(2000);
            if (engine != null) {
                engine.close();
            }
        }
    }

    @Test
    public void testServerAnswersLargeUnsupportedVersionDatagramWithVersionNegotiation() throws Exception {
        ServerFixture server = new ServerFixture();
        DatagramSocket peer = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        try {
            server.start();
            send(peer, unsupportedVersionDatagram(1200, GREASE_VERSION), server.port);
            DatagramPacket reply = receiveOrNull(peer, 5000);
            assertNotNull("server must answer with a Version Negotiation packet", reply);
            byte[] wire = java.util.Arrays.copyOf(reply.getData(), reply.getLength());
            VersionNegotiationPacket vn = VersionNegotiationPacket.parse(wire);
            assertArrayEquals("VN DCID echoes the client's SCID", CLIENT_SCID, vn.getDestinationConnectionId());
            assertArrayEquals("VN SCID echoes the client's DCID", CLIENT_DCID, vn.getSourceConnectionId());
            boolean offersV1 = false;
            for (int v : vn.getSupportedVersions()) {
                assertTrue("must not offer the version that was rejected", v != GREASE_VERSION);
                offersV1 |= v == 1;
            }
            assertTrue("VN must advertise QUIC version 1", offersV1);
        } finally {
            peer.close();
            server.stop();
        }
    }

    @Test
    public void testServerDropsUndersizedUnsupportedVersionDatagram() throws Exception {
        ServerFixture server = new ServerFixture();
        DatagramSocket peer = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        try {
            server.start();
            send(peer, unsupportedVersionDatagram(1199, GREASE_VERSION), server.port);
            assertNull("RFC 9000 section 5.2: undersized datagram must be dropped silently",
                    receiveOrNull(peer, 500));
            send(peer, unsupportedVersionDatagram(1200, GREASE_VERSION), server.port);
            assertNotNull("server must still be healthy", receiveOrNull(peer, 5000));
        } finally {
            peer.close();
            server.stop();
        }
    }

    @Test
    public void testServerNeverAnswersAVersionNegotiationPacket() throws Exception {
        ServerFixture server = new ServerFixture();
        DatagramSocket peer = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        try {
            server.start();
            byte[] vn = unsupportedVersionDatagram(1200, 0);
            send(peer, vn, server.port);
            assertNull("RFC 9000 section 6.1: no VN in response to a VN", receiveOrNull(peer, 500));
        } finally {
            peer.close();
            server.stop();
        }
    }

    @Test
    public void testServerRateLimitsVersionNegotiationResponses() throws Exception {
        ServerFixture server = new ServerFixture();
        DatagramSocket peer = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        try {
            server.start();
            int sent = 400;
            for (int i = 0; i < sent; i++) {
                send(peer, unsupportedVersionDatagram(1200, GREASE_VERSION), server.port);
            }
            int replies = 0;
            while (receiveOrNull(peer, 500) != null) {
                replies++;
            }
            assertTrue("some responses expected", replies > 0);
            assertTrue("responses must be rate limited: " + replies, replies < sent);
        } finally {
            peer.close();
            server.stop();
        }
    }

    private static final class ClientFixture {
        final SelectorLoop loop = new SelectorLoop(0);
        final DatagramSocket fakeServer;
        final CountDownLatch failed = new CountDownLatch(1);
        final AtomicReference<Exception> error = new AtomicReference<Exception>();
        QuicEngine engine;
        byte[] initial;
        DatagramPacket initialPacket;

        ClientFixture() throws Exception {
            fakeServer = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        }

        void connect() throws Exception {
            loop.start();
            QuicTransportFactory factory = new QuicTransportFactory();
            factory.setApplicationProtocols(ALPN);
            factory.setVerifyPeer(false);
            factory.start();
            engine = factory.connect(InetAddress.getLoopbackAddress(), fakeServer.getLocalPort(),
                    new ProtocolHandler() {
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
                            error.set(cause);
                            failed.countDown();
                        }
                    }, loop, TestTlsFiles.SERVER_NAME);
            initialPacket = receiveOrNull(fakeServer, 5000);
            assertNotNull("client must send an Initial", initialPacket);
            initial = java.util.Arrays.copyOf(initialPacket.getData(), initialPacket.getLength());
        }

        /** A VN for the client's Initial, listing {@code versions}, with the given ids. */
        byte[] versionNegotiation(byte[] dcid, byte[] scid, int... versions) {
            return VersionNegotiationPacket.build(dcid, scid, versions, 0x11);
        }

        byte[] clientScid() {
            return LongHeaderCodec.parseInvariants(initial).getSourceConnectionId();
        }

        byte[] clientDcid() {
            return LongHeaderCodec.parseInvariants(initial).getDestinationConnectionId();
        }

        void reply(byte[] bytes) throws Exception {
            fakeServer.send(new DatagramPacket(bytes, bytes.length, initialPacket.getSocketAddress()));
        }

        void stop() {
            loop.shutdown();
            loop.awaitQuiesce(2000);
            if (engine != null) {
                engine.close();
            }
            fakeServer.close();
        }
    }

    @Test
    public void testClientAbandonsAttemptOnValidVersionNegotiationWithoutVersionOne() throws Exception {
        ClientFixture client = new ClientFixture();
        try {
            client.connect();
            LongHeaderInvariants sent = LongHeaderCodec.parseInvariants(client.initial);
            assertEquals(1, sent.getVersion());
            client.reply(client.versionNegotiation(client.clientScid(), client.clientDcid(), GREASE_VERSION));
            assertTrue("client must give up on a valid VN", client.failed.await(5, TimeUnit.SECONDS));
            assertTrue("failure must be a version negotiation error: " + client.error.get(),
                    client.error.get() instanceof QuicVersionNegotiationException);
        } finally {
            client.stop();
        }
    }

    @Test
    public void testClientIgnoresVersionNegotiationListingItsOwnVersion() throws Exception {
        ClientFixture client = new ClientFixture();
        try {
            client.connect();
            client.reply(client.versionNegotiation(client.clientScid(), client.clientDcid(), GREASE_VERSION, 1));
            assertFalse("RFC 9000 section 6.2: a VN listing the selected version is discarded",
                    client.failed.await(500, TimeUnit.MILLISECONDS));
        } finally {
            client.stop();
        }
    }

    @Test
    public void testClientIgnoresVersionNegotiationWithWrongConnectionIds() throws Exception {
        ClientFixture client = new ClientFixture();
        try {
            client.connect();
            client.reply(client.versionNegotiation(new byte[] { 9, 9, 9 }, client.clientDcid(), GREASE_VERSION));
            client.reply(client.versionNegotiation(client.clientScid(), new byte[] { 9, 9, 9 }, GREASE_VERSION));
            assertFalse("a VN that does not echo both connection IDs is bogus",
                    client.failed.await(500, TimeUnit.MILLISECONDS));
        } finally {
            client.stop();
        }
    }
}
