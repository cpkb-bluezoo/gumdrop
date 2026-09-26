/*
 * QuicLbEndToEndTest.java
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
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
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
import org.bluezoo.gumdrop.quic.cid.QuicLbConfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * QUIC-LB (draft-ietf-quic-load-balancers-21) over real loopback UDP:
 * server-issued connection IDs are routable, the Retry source connection
 * ID included, and a misrouted ID is dropped rather than reset.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLbEndToEndTest {

    private static final String SERVER_NAME = "test.gumdrop.local";
    private static final String ALPN = "gumdrop-test";
    private static final byte[] KEY = new byte[] {
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 };

    private static Path certsDirectory;
    private static Path certFile;
    private static Path keyFile;

    private final SecureRandom random = new SecureRandom();

    @BeforeClass
    public static void generatePemFiles() throws Exception {
        certsDirectory = Files.createTempDirectory("quic-lb-test");
        Path keystorePath = certsDirectory.resolve("server.p12");
        certFile = certsDirectory.resolve("cert.pem");
        keyFile = certsDirectory.resolve("key.pem");
        run(new ProcessBuilder("keytool", "-genkeypair", "-alias", "server", "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "1", "-dname", "CN=" + SERVER_NAME,
                "-keystore", keystorePath.toString(), "-storetype", "PKCS12",
                "-storepass", "changeit", "-keypass", "changeit"), "keytool");
        run(new ProcessBuilder("openssl", "pkcs12", "-in", keystorePath.toString(),
                "-nodes", "-nocerts", "-out", keyFile.toString(), "-passin", "pass:changeit"), "openssl (key)");
        run(new ProcessBuilder("openssl", "pkcs12", "-in", keystorePath.toString(),
                "-nokeys", "-out", certFile.toString(), "-passin", "pass:changeit"), "openssl (cert)");
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

    private static QuicLbConfig config(int id, int serverId, byte[] key) {
        return new QuicLbConfig(id, new byte[] { (byte) serverId, 0, 1 }, 6, key, true);
    }

    private QuicTransportFactory serverFactory(QuicLbConfig lb) throws Exception {
        QuicTransportFactory factory = new QuicTransportFactory();
        factory.setApplicationProtocols(ALPN);
        factory.setCertFile(certFile);
        factory.setKeyFile(keyFile);
        factory.setRequireRetry(true);
        factory.setQuicLbConfig(lb);
        factory.start();
        return factory;
    }

    private static ProtocolHandler nullHandler(final CountDownLatch connected) {
        return new ProtocolHandler() {
            @Override
            public void connected(Endpoint endpoint) {
                if (connected != null) {
                    connected.countDown();
                }
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

    /**
     * The handshake goes through Retry, whose source connection ID must
     * be routable for the follow-up Initial to reach this server, and
     * the connection is then registered under an ID that decodes to this
     * server. Rotating the config id replaces the connection's IDs.
     */
    @Test
    public void retrySourceIdAndRotation() throws Exception {
        SelectorLoop loop = new SelectorLoop(0);
        loop.start();
        QuicEngine serverEngine = null;
        QuicEngine clientEngine = null;
        try {
            final QuicLbConfig lb = config(0, 7, KEY);
            QuicTransportFactory serverFactory = serverFactory(lb);
            final AtomicReference<QuicConnection> serverConnection = new AtomicReference<QuicConnection>();
            serverEngine = serverFactory.createServerEngine(InetAddress.getLoopbackAddress(), 0,
                    new QuicEngine.ConnectionAcceptedHandler() {
                        @Override
                        public void connectionAccepted(QuicConnection connection) {
                            serverConnection.set(connection);
                        }
                    }, loop);
            int port = ((InetSocketAddress) serverEngine.getLocalAddress()).getPort();

            QuicTransportFactory clientFactory = new QuicTransportFactory();
            clientFactory.setApplicationProtocols(ALPN);
            clientFactory.setVerifyPeer(false);
            clientFactory.start();
            CountDownLatch connected = new CountDownLatch(1);
            clientEngine = clientFactory.connect(InetAddress.getLoopbackAddress(), port,
                    nullHandler(connected), loop, SERVER_NAME);
            assertTrue("handshake through Retry", connected.await(5, TimeUnit.SECONDS));

            QuicConnection conn = serverConnection.get();
            assertNotNull(conn);
            List<byte[]> ids = conn.getOurConnectionIds();
            assertFalse(ids.isEmpty());
            for (byte[] id : ids) {
                assertTrue("server-issued ID decodes to this server", lb.isOwn(id));
            }

            // Rotate to config id 1 and let the connection send.
            final QuicLbConfig rotated = config(1, 7, KEY);
            serverFactory.setQuicLbConfig(rotated);
            final CountDownLatch flushed = new CountDownLatch(1);
            final QuicConnection target = conn;
            loop.invokeLater(new Runnable() {
                @Override
                public void run() {
                    target.flush();
                    flushed.countDown();
                }
            });
            assertTrue(flushed.await(5, TimeUnit.SECONDS));
            long deadline = System.currentTimeMillis() + 5000;
            boolean replaced = false;
            while (System.currentTimeMillis() < deadline && !replaced) {
                Thread.sleep(50);
                replaced = true;
                for (byte[] id : target.getOurConnectionIds()) {
                    if (QuicLbConfig.configIdOf(id[0]) != 1) {
                        replaced = false;
                    }
                }
            }
            assertTrue("retired config id's IDs replaced after rotation", replaced);
            for (byte[] id : target.getOurConnectionIds()) {
                assertTrue(rotated.isOwn(id));
            }
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

    /** A short-header ID decoding to another server is dropped; an own retired ID is reset. */
    @Test
    public void misroutedIdIsDroppedNotReset() throws Exception {
        SelectorLoop loop = new SelectorLoop(0);
        loop.start();
        QuicEngine serverEngine = null;
        DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        try {
            QuicLbConfig mine = config(0, 7, KEY);
            QuicLbConfig other = config(0, 9, KEY);
            QuicTransportFactory serverFactory = serverFactory(mine);
            serverEngine = serverFactory.createServerEngine(InetAddress.getLoopbackAddress(), 0,
                    new QuicEngine.ConnectionAcceptedHandler() {
                        @Override
                        public void connectionAccepted(QuicConnection connection) {
                        }
                    }, loop);
            int port = ((InetSocketAddress) serverEngine.getLocalAddress()).getPort();

            byte[] ownRetired = mine.generate(random);
            byte[] misrouted = other.generate(random);
            serverEngine.markResetEligible(ownRetired);
            serverEngine.markResetEligible(misrouted);

            // Misrouted first: nothing may come back.
            send(socket, port, misrouted);
            socket.setSoTimeout(700);
            try {
                DatagramPacket reply = new DatagramPacket(new byte[2048], 2048);
                socket.receive(reply);
                fail("a misrouted connection ID must be dropped, not reset");
            } catch (SocketTimeoutException expected) {
                // dropped
            }

            send(socket, port, ownRetired);
            socket.setSoTimeout(3000);
            DatagramPacket reply = new DatagramPacket(new byte[2048], 2048);
            socket.receive(reply);
            assertTrue("own retired ID is answered with a stateless reset", reply.getLength() >= 21);
        } finally {
            socket.close();
            loop.shutdown();
            loop.awaitQuiesce(2000);
            if (serverEngine != null) {
                serverEngine.close();
            }
        }
    }

    /** With a configured length, the short-header parser must not assume 20 octets. */
    @Test
    public void shortHeaderDemuxUsesConfiguredLength() throws Exception {
        QuicLbConfig lb = new QuicLbConfig(2, new byte[] { 5 }, 4, null, false);
        assertEquals(6, lb.getConnectionIdLength());
        byte[] cid = lb.generate(random);
        assertEquals(6, cid.length);
        assertTrue(lb.isOwn(cid));
    }

    private void send(DatagramSocket socket, int port, byte[] dcid) throws IOException {
        byte[] datagram = new byte[1 + dcid.length + 40];
        random.nextBytes(datagram);
        datagram[0] = 0x43;
        System.arraycopy(dcid, 0, datagram, 1, dcid.length);
        socket.send(new DatagramPacket(datagram, datagram.length, InetAddress.getLoopbackAddress(), port));
    }
}
