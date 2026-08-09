/*
 * DTLSSessionTest.java
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

package org.bluezoo.gumdrop;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DTLSSession} (issue #190).
 *
 * <p>Drives two real {@link SSLEngine}s (one client, one server, both
 * DTLSv1.2) wrapped in {@link DTLSSession} against each other entirely
 * in-process: a {@link RecordingUDPEndpoint} stub intercepts every
 * {@code sendRawDatagram} call and feeds the bytes straight into the
 * peer's {@code DTLSSession.unwrap}, and intercepts timer scheduling so
 * retransmission can be driven deterministically by the test rather than
 * real wall-clock time. No sockets, no {@link Gumdrop} bootstrap, and no
 * checked-in test fixtures: a throwaway self-signed keystore, and a
 * matching truststore that trusts exactly that one certificate (not an
 * accept-all {@code TrustManager} -- CodeQL flags that pattern for good
 * reason, and a real trust store is no more code here), are generated
 * fresh via {@code keytool} in {@link #generateKeystore}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DTLSSessionTest {

    private static final String PASSWORD = "testpass";
    private static final InetSocketAddress CLIENT_ADDR = new InetSocketAddress("127.0.0.1", 1);
    private static final InetSocketAddress SERVER_ADDR = new InetSocketAddress("127.0.0.1", 2);

    private static Path keystorePath;
    private static Path truststorePath;

    @BeforeClass
    public static void generateKeystore() throws Exception {
        keystorePath = Files.createTempFile("dtls-session-test-keystore", ".p12");
        Files.delete(keystorePath); // keytool must create the file itself
        runKeytool("-genkeypair",
                "-alias", "dtlstest",
                "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "30",
                "-dname", "CN=localhost",
                "-keystore", keystorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", PASSWORD,
                "-keypass", PASSWORD);

        Path certPath = Files.createTempFile("dtls-session-test-cert", ".pem");
        runKeytool("-exportcert",
                "-alias", "dtlstest",
                "-keystore", keystorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", PASSWORD,
                "-rfc",
                "-file", certPath.toString());

        truststorePath = Files.createTempFile("dtls-session-test-truststore", ".p12");
        Files.delete(truststorePath);
        runKeytool("-importcert",
                "-alias", "dtlstest",
                "-file", certPath.toString(),
                "-keystore", truststorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", PASSWORD,
                "-noprompt");
        Files.delete(certPath);
    }

    private static void runKeytool(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "keytool";
        System.arraycopy(args, 0, command, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            output.append(line).append('\n');
        }
        int exitCode = process.waitFor();
        assertEquals("keytool failed: " + output, 0, exitCode);
    }

    @AfterClass
    public static void deleteKeystore() throws Exception {
        if (keystorePath != null) {
            Files.deleteIfExists(keystorePath);
        }
        if (truststorePath != null) {
            Files.deleteIfExists(truststorePath);
        }
    }

    private static KeyStore loadKeyStore(Path path) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(path)) {
            keyStore.load(in, PASSWORD.toCharArray());
        }
        return keyStore;
    }

    private static SSLContext buildServerContext() throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(loadKeyStore(keystorePath), PASSWORD.toCharArray());

        SSLContext context = SSLContext.getInstance("DTLSv1.2");
        context.init(kmf.getKeyManagers(), null, null);
        return context;
    }

    private static SSLContext buildClientContext() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(loadKeyStore(truststorePath));

        SSLContext context = SSLContext.getInstance("DTLSv1.2");
        context.init(null, tmf.getTrustManagers(), null);
        return context;
    }

    /**
     * Stub {@link UDPEndpoint} that intercepts every network/timer/
     * callback touchpoint {@link DTLSSession} uses, so a handshake can be
     * driven entirely in-memory. {@code peer} is wired up after both
     * sides are constructed (see {@link #testHandshakeAndDataRoundTrip}).
     */
    private static final class RecordingUDPEndpoint extends UDPEndpoint {
        final Deque<ByteBuffer> sent = new ArrayDeque<ByteBuffer>();
        final List<TestTimer> timers = new ArrayList<TestTimer>();
        final List<byte[]> received = new ArrayList<byte[]>();
        SecurityInfo establishedInfo;
        Exception failure;
        boolean sessionRemoved;

        RecordingUDPEndpoint() {
            super(new ProtocolHandler() {
                @Override
                public void receive(ByteBuffer data) {
                }

                @Override
                public void connected(Endpoint endpoint) {
                }

                @Override
                public void disconnected() {
                }

                @Override
                public void securityEstablished(SecurityInfo info) {
                }

                @Override
                public void error(Exception cause) {
                }
            });
        }

        @Override
        void sendRawDatagram(ByteBuffer data, InetSocketAddress dest) {
            ByteBuffer copy = ByteBuffer.allocate(data.remaining());
            copy.put(data);
            copy.flip();
            sent.addLast(copy);
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            TestTimer timer = new TestTimer(callback);
            timers.add(timer);
            return timer;
        }

        @Override
        void notifyDtlsHandshakeComplete(InetSocketAddress peer, SecurityInfo info) {
            establishedInfo = info;
        }

        @Override
        void onDtlsSessionFailed(InetSocketAddress peer, Exception cause) {
            failure = cause;
        }

        @Override
        void removeDtlsSession(InetSocketAddress peer) {
            sessionRemoved = true;
        }
    }

    /** A timer the test fires manually instead of waiting on the clock. */
    private static final class TestTimer implements TimerHandle {
        final Runnable callback;
        private boolean cancelled;

        TestTimer(Runnable callback) {
            this.callback = callback;
        }

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
     * Pumps every datagram {@code from} has queued through {@code to}'s
     * {@code unwrap}, and every datagram that in turn produces through
     * {@code from}, back and forth, until both queues are empty (i.e.
     * until the flight exchange settles). Application-layer plaintext
     * returned by either side's {@code unwrap} is collected onto the
     * corresponding endpoint's {@code received} list.
     */
    private static void pumpUntilQuiescent(RecordingUDPEndpoint clientEp, DTLSSession clientSession,
            RecordingUDPEndpoint serverEp, DTLSSession serverSession) {
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            while (!clientEp.sent.isEmpty()) {
                ByteBuffer datagram = clientEp.sent.pollFirst();
                ByteBuffer plaintext = serverSession.unwrap(datagram);
                if (plaintext != null) {
                    byte[] bytes = new byte[plaintext.remaining()];
                    plaintext.get(bytes);
                    serverEp.received.add(bytes);
                }
                progressed = true;
            }
            while (!serverEp.sent.isEmpty()) {
                ByteBuffer datagram = serverEp.sent.pollFirst();
                ByteBuffer plaintext = clientSession.unwrap(datagram);
                if (plaintext != null) {
                    byte[] bytes = new byte[plaintext.remaining()];
                    plaintext.get(bytes);
                    clientEp.received.add(bytes);
                }
                progressed = true;
            }
        }
    }

    @Test
    public void testHandshakeCompletesAndEstablishesSecurity() throws Exception {
        SSLEngine serverEngine = buildServerContext().createSSLEngine();
        serverEngine.setUseClientMode(false);
        SSLEngine clientEngine = buildClientContext().createSSLEngine("localhost", SERVER_ADDR.getPort());
        clientEngine.setUseClientMode(true);

        RecordingUDPEndpoint clientEp = new RecordingUDPEndpoint();
        RecordingUDPEndpoint serverEp = new RecordingUDPEndpoint();
        DTLSSession clientSession = new DTLSSession(clientEngine, clientEp, SERVER_ADDR);
        DTLSSession serverSession = new DTLSSession(serverEngine, serverEp, CLIENT_ADDR);

        serverSession.beginHandshake(); // puts the server engine into NEED_UNWRAP
        clientSession.beginHandshake(); // sends ClientHello

        pumpUntilQuiescent(clientEp, clientSession, serverEp, serverSession);

        assertTrue("client handshake should have completed", clientSession.isHandshakeComplete());
        assertTrue("server handshake should have completed", serverSession.isHandshakeComplete());
        assertNotNull("client should have been notified of security establishment",
                clientEp.establishedInfo);
        assertNotNull("server should have been notified of security establishment",
                serverEp.establishedInfo);
        assertEquals("DTLSv1.2", clientEp.establishedInfo.getProtocol());
        assertNull("no failure expected on a clean handshake", clientEp.failure);
        assertNull("no failure expected on a clean handshake", serverEp.failure);
    }

    @Test
    public void testApplicationDataRoundTripAfterHandshake() throws Exception {
        SSLEngine serverEngine = buildServerContext().createSSLEngine();
        serverEngine.setUseClientMode(false);
        SSLEngine clientEngine = buildClientContext().createSSLEngine("localhost", SERVER_ADDR.getPort());
        clientEngine.setUseClientMode(true);

        RecordingUDPEndpoint clientEp = new RecordingUDPEndpoint();
        RecordingUDPEndpoint serverEp = new RecordingUDPEndpoint();
        DTLSSession clientSession = new DTLSSession(clientEngine, clientEp, SERVER_ADDR);
        DTLSSession serverSession = new DTLSSession(serverEngine, serverEp, CLIENT_ADDR);

        serverSession.beginHandshake();
        clientSession.beginHandshake();
        pumpUntilQuiescent(clientEp, clientSession, serverEp, serverSession);
        assertTrue(clientSession.isHandshakeComplete());
        assertTrue(serverSession.isHandshakeComplete());

        byte[] payload = "hello over DTLS".getBytes(StandardCharsets.UTF_8);
        ByteBuffer encrypted = clientSession.wrap(ByteBuffer.wrap(payload));
        assertNotNull(encrypted);

        ByteBuffer decrypted = serverSession.unwrap(encrypted);
        assertNotNull("server should have decrypted application data", decrypted);
        byte[] decryptedBytes = new byte[decrypted.remaining()];
        decrypted.get(decryptedBytes);
        assertArrayEquals(payload, decryptedBytes);
    }

    @Test
    public void testUnexpectedFlightIsRetransmittedThenGivesUp() throws Exception {
        SSLEngine clientEngine = buildClientContext().createSSLEngine("localhost", SERVER_ADDR.getPort());
        clientEngine.setUseClientMode(true);

        RecordingUDPEndpoint clientEp = new RecordingUDPEndpoint();
        DTLSSession clientSession = new DTLSSession(clientEngine, clientEp, SERVER_ADDR);

        clientSession.beginHandshake(); // sends ClientHello, arms a retransmit timer
        assertFalse("ClientHello should have been sent", clientEp.sent.isEmpty());
        clientEp.sent.clear();
        assertFalse("a retransmit timer should have been armed", clientEp.timers.isEmpty());

        // Fire every retransmit timer as it gets (re-)armed, simulating total
        // silence from the peer, until the session gives up.
        int firedCount = 0;
        int guard = 0;
        while (clientEp.failure == null && guard++ < 20) {
            TestTimer timer = clientEp.timers.get(clientEp.timers.size() - 1);
            if (timer.isCancelled()) {
                break;
            }
            timer.callback.run();
            firedCount++;
        }

        assertNotNull("session should have failed after exhausting retransmit attempts",
                clientEp.failure);
        assertTrue("ClientHello should have been retransmitted at least once", firedCount > 1);
        assertFalse("retransmitted flight should have been sent again", clientEp.sent.isEmpty());
    }

}
