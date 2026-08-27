/*
 * SSLStateTest.java
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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SSLState}'s offload of TLS handshake delegated
 * tasks onto {@link CryptoExecutor}, rather than running them inline on the
 * SelectorLoop thread.
 *
 * <p>Drives two real {@link SSLEngine}s (client + server, real
 * {@code keytool}-generated throwaway certificates, same approach as
 * {@link DTLSSessionTest}) wrapped in real {@link SSLState}s over a pair of
 * no-channel {@link TCPEndpoint}s (see {@link TCPEndpoint#init()}'s
 * {@code channel == null} fast path) -- no sockets. Requires a started
 * {@link Gumdrop} so {@link CryptoExecutor} actually exists (mirrors
 * {@link AsyncDiskOffloadBoundaryTest}'s pattern for {@code StorageExecutor}).
 *
 * <p><b>Deliberately not covered here</b> (see the design plan's own
 * "explicitly deferred" reasoning): a live post-handshake TLS renegotiation
 * or session-ticket-issuance {@code NEED_TASK} (the third, rarer call site
 * in {@code processApplicationData}) is not practically forceable
 * deterministically through the standard {@code SSLEngine} API in a short
 * unit test -- its {@code resumeViaHandshake = false} resume path is
 * exercised by code review/symmetry with the other two sites instead.
 * Likewise the handshake-timeout race: this test never arms
 * {@code TCPEndpoint}'s real handshake timeout (that machinery is driven by
 * a live {@code Gumdrop} timer thread, not exercised via this no-channel
 * setup), so it is not reproduced here.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SSLStateTest {

    private static final String PASSWORD = "testpass";

    private static Path serverKeystorePath;
    private static Path clientKeystorePath;
    /** Trusts the server cert; used to build the client's SSLContext. */
    private static Path clientTruststorePath;
    /** Trusts the client cert; used to build the server's SSLContext for mTLS. */
    private static Path serverTruststorePath;

    private Gumdrop gumdrop;

    @Before
    public void setUp() throws Exception {
        if (serverKeystorePath == null) {
            generateKeystores();
        }
        CryptoExecutor.workThreadObserver = null;
        System.setProperty("gumdrop.workers", "1");
        gumdrop = Gumdrop.getInstance();
        gumdrop.setDrainTimeoutMs(0);
        if (!gumdrop.isStarted()) {
            gumdrop.start();
        }
        assertNotNull("CryptoExecutor must exist after Gumdrop.start()",
                gumdrop.getCryptoExecutor());
    }

    @After
    public void tearDown() throws Exception {
        CryptoExecutor.workThreadObserver = null;
        if (gumdrop != null && gumdrop.isStarted()) {
            gumdrop.shutdown();
        }
    }

    // -- Keystore setup (mirrors DTLSSessionTest's keytool approach, extended
    //    to a second keypair for mutual-TLS tests) --

    private static void generateKeystores() throws Exception {
        serverKeystorePath = generateSelfSignedKeystore("ssl-state-test-server", "server");
        clientKeystorePath = generateSelfSignedKeystore("ssl-state-test-client", "client");
        clientTruststorePath = importCertAsTruststore(
                serverKeystorePath, "server", "ssl-state-test-client-trust");
        serverTruststorePath = importCertAsTruststore(
                clientKeystorePath, "client", "ssl-state-test-server-trust");
    }

    private static Path generateSelfSignedKeystore(String filePrefix, String alias)
            throws Exception {
        Path keystorePath = Files.createTempFile(filePrefix + "-keystore", ".p12");
        Files.delete(keystorePath); // keytool must create the file itself
        runKeytool("-genkeypair",
                "-alias", alias,
                "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "30",
                "-dname", "CN=localhost",
                "-keystore", keystorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", PASSWORD,
                "-keypass", PASSWORD);
        return keystorePath;
    }

    private static Path importCertAsTruststore(Path fromKeystore, String alias,
            String truststorePrefix) throws Exception {
        Path certPath = Files.createTempFile(truststorePrefix + "-cert", ".pem");
        runKeytool("-exportcert",
                "-alias", alias,
                "-keystore", fromKeystore.toString(),
                "-storetype", "PKCS12",
                "-storepass", PASSWORD,
                "-rfc",
                "-file", certPath.toString());

        Path truststorePath = Files.createTempFile(truststorePrefix + "-truststore", ".p12");
        Files.delete(truststorePath);
        runKeytool("-importcert",
                "-alias", alias,
                "-file", certPath.toString(),
                "-keystore", truststorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", PASSWORD,
                "-noprompt");
        Files.delete(certPath);
        return truststorePath;
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

    private static KeyStore loadKeyStore(Path path) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(path)) {
            keyStore.load(in, PASSWORD.toCharArray());
        }
        return keyStore;
    }

    private static SSLEngine newServerEngine(boolean requireClientAuth) throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(loadKeyStore(serverKeystorePath), PASSWORD.toCharArray());
        SSLContext context = SSLContext.getInstance("TLSv1.2");
        if (requireClientAuth) {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(loadKeyStore(serverTruststorePath));
            context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        } else {
            context.init(kmf.getKeyManagers(), null, null);
        }
        SSLEngine engine = context.createSSLEngine();
        engine.setUseClientMode(false);
        if (requireClientAuth) {
            engine.setNeedClientAuth(true);
        }
        return engine;
    }

    private static SSLEngine newClientEngine(boolean presentClientCert) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(loadKeyStore(clientTruststorePath));
        SSLContext context = SSLContext.getInstance("TLSv1.2");
        if (presentClientCert) {
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(loadKeyStore(clientKeystorePath), PASSWORD.toCharArray());
            context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        } else {
            context.init(null, tmf.getTrustManagers(), null);
        }
        SSLEngine engine = context.createSSLEngine("localhost", 443);
        engine.setUseClientMode(true);
        return engine;
    }

    // -- Test fixtures --

    /**
     * A no-channel {@link TCPEndpoint} whose {@link #execute} marshals onto
     * a real dedicated "loop" thread, mirroring how a real SelectorLoop
     * thread receives {@code CryptoExecutor} completion callbacks -- rather
     * than running them inline on whatever thread calls {@code execute},
     * which would defeat the point of proving the callback is delivered on
     * a specific, single thread distinct from the crypto pool.
     */
    private static final class TestTCPEndpoint extends TCPEndpoint {
        private final ExecutorService loop;

        /**
         * @param engine the same {@link SSLEngine} instance the test also
         *      passes to its separately-constructed {@link SSLState} --
         *      must match, since {@link TCPEndpoint#onHandshakeComplete}
         *      builds a {@code JSSESecurityInfo} from its own {@code
         *      engine} field, not from anything {@code SSLState} holds.
         */
        TestTCPEndpoint(ProtocolHandler handler, SSLEngine engine, String loopThreadName) {
            super(handler, engine, true);
            this.loop = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, loopThreadName);
                }
            });
        }

        @Override
        public void execute(Runnable task) {
            loop.execute(task);
        }

        void shutdownLoop() {
            loop.shutdownNow();
        }
    }

    /** Captures every {@link ProtocolHandler} callback for assertions. */
    private static final class RecordingHandler implements ProtocolHandler {
        final List<byte[]> received =
                new java.util.concurrent.CopyOnWriteArrayList<byte[]>();
        final CountDownLatch establishedLatch = new CountDownLatch(1);
        final CountDownLatch closedLatch = new CountDownLatch(1);
        volatile int establishedCount;
        volatile SecurityInfo securityInfo;
        volatile Exception error;

        @Override
        public void receive(ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            received.add(bytes);
        }

        @Override
        public void connected(Endpoint endpoint) { }

        @Override
        public void disconnected() {
            closedLatch.countDown();
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
            securityInfo = info;
            establishedCount++;
            establishedLatch.countDown();
        }

        @Override
        public void error(Exception cause) {
            error = cause;
        }
    }

    /**
     * Schedules, on {@code toEp}'s own loop thread, draining any bytes
     * currently pending in {@code fromEp}'s netOut and applying them to
     * {@code toEp}'s netIn via {@code toState.unwrap()}. Mirrors the exact
     * append/flip/compact discipline {@code SelectorLoop} and {@code
     * SSLState.unwrap()} already use in production (see {@code
     * SelectorLoop.doTcpEndpointRead/Write}).
     *
     * <p>The pause-check, drain, and apply all happen inside the one
     * scheduled {@link Runnable}, run serially on {@code toEp}'s single
     * loop thread -- not split across a check on the calling thread and a
     * later action on the loop thread, which would leave a
     * time-of-check-to-time-of-use gap. A real SelectorLoop never delivers
     * a new read event to a connection while {@code
     * submitDelegatedTasksAsync} has it paused (a delegated task in
     * flight) -- {@code TCPEndpoint.pauseRead()}/{@code resumeRead()} track
     * {@code readPaused} unconditionally even with no live SelectorLoop, so
     * checking {@code toEp.isReadPaused()} right before draining and
     * applying, atomically on the one thread that also runs {@code
     * resumeAfterTask()}'s completion callback, reproduces that invariant
     * exactly. If paused, this attempt is simply skipped (the bytes are
     * left untouched in {@code fromEp}'s netOut) and a later scheduled
     * attempt from the poll loop picks them up once unpaused.
     */
    private static void deliver(final TestTCPEndpoint fromEp, final TestTCPEndpoint toEp,
            final SSLState toState) {
        toEp.execute(new Runnable() {
            @Override
            public void run() {
                // Either side may have closed concurrently (e.g. the
                // connection-closed-during-in-flight-task test); tolerate
                // a null netOut/netIn on either end rather than NPEing.
                if (toEp.getNetOut() == null || toEp.isReadPaused()) {
                    return;
                }
                byte[] bytes;
                synchronized (fromEp.netOutLock) {
                    ByteBuffer out = fromEp.getNetOut();
                    if (out == null) {
                        return;
                    }
                    out.flip();
                    if (!out.hasRemaining()) {
                        out.clear();
                        return;
                    }
                    bytes = new byte[out.remaining()];
                    out.get(bytes);
                    out.clear();
                }
                toEp.netIn.put(bytes);
                toEp.netIn.flip();
                toState.unwrap();
            }
        });
    }

    /**
     * Pumps bytes between both sides, in both directions, until both
     * handshakes have completed (per {@code establishedLatch}) or
     * {@code timeoutMs} elapses. A delegated task suspends {@code unwrap()}
     * asynchronously (see {@code SSLState.submitDelegatedTasksAsync}), so
     * a single synchronous pass is not enough -- this polls at a fixed
     * cadence, since the crypto pool and each endpoint's own loop thread
     * make progress concurrently with this method.
     */
    private static void pumpUntilBothEstablished(
            TestTCPEndpoint clientEp, SSLState clientState, RecordingHandler clientHandler,
            TestTCPEndpoint serverEp, SSLState serverState, RecordingHandler serverHandler,
            long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            deliver(clientEp, serverEp, serverState);
            deliver(serverEp, clientEp, clientState);
            if (clientHandler.establishedLatch.await(10, TimeUnit.MILLISECONDS)
                    && serverHandler.establishedLatch.await(10, TimeUnit.MILLISECONDS)) {
                return;
            }
        }
        fail("handshake did not complete within " + timeoutMs + "ms; client established="
                + (clientHandler.establishedLatch.getCount() == 0) + " server established="
                + (serverHandler.establishedLatch.getCount() == 0)
                + " client error=" + clientHandler.error
                + " server error=" + serverHandler.error);
    }

    private static TestTCPEndpoint newEndpoint(ProtocolHandler handler, SSLEngine engine,
            String loopName) throws IOException {
        TestTCPEndpoint ep = new TestTCPEndpoint(handler, engine, loopName);
        ep.init(); // channel == null: allocates netIn/netOut, no socket needed
        return ep;
    }

    // -- Tests --

    @Test(timeout = 20000)
    public void testHandshakeCompletesAsynchronouslyOffLoop() throws Exception {
        SSLEngine serverEngine = newServerEngine(false);
        SSLEngine clientEngine = newClientEngine(false);

        RecordingHandler clientHandler = new RecordingHandler();
        RecordingHandler serverHandler = new RecordingHandler();
        TestTCPEndpoint clientEp = newEndpoint(clientHandler, clientEngine, "client-loop");
        TestTCPEndpoint serverEp = newEndpoint(serverHandler, serverEngine, "server-loop");
        try {
            SSLState clientState = new SSLState(clientEngine, clientEp);
            SSLState serverState = new SSLState(serverEngine, serverEp);

            final AtomicReference<String> workThread = new AtomicReference<String>();
            CryptoExecutor.workThreadObserver = new CryptoExecutor.WorkThreadObserver() {
                @Override
                public void observed(Thread t) {
                    workThread.compareAndSet(null, t.getName());
                }
            };

            clientState.startClientHandshake();
            pumpUntilBothEstablished(clientEp, clientState, clientHandler,
                    serverEp, serverState, serverHandler, 15000);

            assertNotNull("client should have been notified of security establishment",
                    clientHandler.securityInfo);
            assertNotNull("server should have been notified of security establishment",
                    serverHandler.securityInfo);
            assertNull("no error expected on a clean handshake", clientHandler.error);
            assertNull("no error expected on a clean handshake", serverHandler.error);
            assertNotNull("at least one delegated task should have run", workThread.get());
            assertTrue("delegated task must run on a gumdrop-crypto-* thread, was "
                    + workThread.get(), workThread.get().startsWith("gumdrop-crypto-"));
        } finally {
            clientEp.shutdownLoop();
            serverEp.shutdownLoop();
        }
    }

    @Test(timeout = 20000)
    public void testLoopThreadStaysResponsiveDuringDelegatedTask() throws Exception {
        SSLEngine serverEngine = newServerEngine(false);
        SSLEngine clientEngine = newClientEngine(false);

        RecordingHandler clientHandler = new RecordingHandler();
        RecordingHandler serverHandler = new RecordingHandler();
        TestTCPEndpoint clientEp = newEndpoint(clientHandler, clientEngine, "client-loop");
        TestTCPEndpoint serverEp = newEndpoint(serverHandler, serverEngine, "server-loop");
        try {
            SSLState clientState = new SSLState(clientEngine, clientEp);
            SSLState serverState = new SSLState(serverEngine, serverEp);

            // Block the crypto pool thread the moment it picks up the first
            // delegated task, so the test can prove each endpoint's own
            // loop thread is still free to run unrelated work concurrently
            // -- exactly the scenario this whole change exists to fix.
            final CountDownLatch taskStarted = new CountDownLatch(1);
            final CountDownLatch releaseTask = new CountDownLatch(1);
            CryptoExecutor.workThreadObserver = new CryptoExecutor.WorkThreadObserver() {
                @Override
                public void observed(Thread t) {
                    taskStarted.countDown();
                    try {
                        releaseTask.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };

            clientState.startClientHandshake();
            deliver(clientEp, serverEp, serverState); // ClientHello -> server
            // The server's own handshake processing (certificate validation)
            // should now be blocked inside the observer, off the loop thread.
            assertTrue("delegated task never started", taskStarted.await(10, TimeUnit.SECONDS));

            final AtomicReference<String> otherWorkThread = new AtomicReference<String>();
            final CountDownLatch otherWorkDone = new CountDownLatch(1);
            serverEp.execute(new Runnable() {
                @Override
                public void run() {
                    otherWorkThread.set(Thread.currentThread().getName());
                    otherWorkDone.countDown();
                }
            });
            assertTrue("server loop thread must stay responsive while a delegated "
                    + "task is in flight on the crypto pool",
                    otherWorkDone.await(5, TimeUnit.SECONDS));
            assertEquals("server-loop", otherWorkThread.get());

            releaseTask.countDown();
            pumpUntilBothEstablished(clientEp, clientState, clientHandler,
                    serverEp, serverState, serverHandler, 15000);
            assertNull(clientHandler.error);
            assertNull(serverHandler.error);
        } finally {
            clientEp.shutdownLoop();
            serverEp.shutdownLoop();
        }
    }

    @Test(timeout = 20000)
    public void testWrapDuringInFlightTaskBuffersApplicationData() throws Exception {
        SSLEngine serverEngine = newServerEngine(false);
        SSLEngine clientEngine = newClientEngine(false);

        RecordingHandler clientHandler = new RecordingHandler();
        RecordingHandler serverHandler = new RecordingHandler();
        TestTCPEndpoint clientEp = newEndpoint(clientHandler, clientEngine, "client-loop");
        TestTCPEndpoint serverEp = newEndpoint(serverHandler, serverEngine, "server-loop");
        try {
            SSLState clientState = new SSLState(clientEngine, clientEp);
            SSLState serverState = new SSLState(serverEngine, serverEp);

            // Let the client reach a NEED_TASK point (its own certificate/key
            // processing of the server's response), then call wrap() again
            // with real application data while that task is still in
            // flight -- the scenario TCPEndpoint.send() can trigger from any
            // application thread, per the taskInFlight guard in wrap().
            final CountDownLatch clientTaskStarted = new CountDownLatch(1);
            final CountDownLatch releaseClientTask = new CountDownLatch(1);
            final AtomicReference<Thread> firstTaskThread = new AtomicReference<Thread>();
            CryptoExecutor.workThreadObserver = new CryptoExecutor.WorkThreadObserver() {
                @Override
                public void observed(Thread t) {
                    if (firstTaskThread.compareAndSet(null, t)) {
                        clientTaskStarted.countDown();
                        try {
                            releaseClientTask.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            };

            clientState.startClientHandshake();
            deliver(clientEp, serverEp, serverState); // ClientHello -> server
            deliver(serverEp, clientEp, clientState);  // ServerHello+cert -> client
            assertTrue("client delegated task never started",
                    clientTaskStarted.await(10, TimeUnit.SECONDS));

            byte[] payload = "hello during handshake".getBytes(StandardCharsets.UTF_8);
            clientState.wrap(ByteBuffer.wrap(payload));

            releaseClientTask.countDown();
            pumpUntilBothEstablished(clientEp, clientState, clientHandler,
                    serverEp, serverState, serverHandler, 15000);
            assertNull(clientHandler.error);
            assertNull(serverHandler.error);

            // The buffered application data must have been delivered once
            // the handshake completed, not lost. onHandshakeComplete() (the
            // establishedLatch trigger) runs before flushPendingAppData()
            // in processHandshake()'s completion tail, on the same loop
            // thread, and flushPendingAppData()'s own wrap() call produces
            // new netOut bytes that need one more delivery round to reach
            // the server -- pumpUntilBothEstablished() stops calling
            // deliver() once both sides establish, so this must keep
            // pumping itself while it waits. Reassembled across chunks --
            // a single application-data write is not guaranteed to arrive
            // as exactly one onApplicationData callback (TLS record
            // boundaries may split it).
            boolean delivered = false;
            long deadline = System.currentTimeMillis() + 5000;
            while (!delivered && System.currentTimeMillis() < deadline) {
                deliver(clientEp, serverEp, serverState);
                StringBuilder all = new StringBuilder();
                for (byte[] chunk : serverHandler.received) {
                    all.append(new String(chunk, StandardCharsets.UTF_8));
                }
                delivered = all.toString().contains("hello during handshake");
                if (!delivered) {
                    Thread.sleep(10);
                }
            }
            assertTrue("application data sent during an in-flight delegated task "
                    + "must be buffered and delivered once the handshake completes",
                    delivered);
        } finally {
            clientEp.shutdownLoop();
            serverEp.shutdownLoop();
        }
    }

    @Test(timeout = 20000)
    public void testMutualTlsSequentialDelegatedTaskChain() throws Exception {
        SSLEngine serverEngine = newServerEngine(true);
        SSLEngine clientEngine = newClientEngine(true);

        RecordingHandler clientHandler = new RecordingHandler();
        RecordingHandler serverHandler = new RecordingHandler();
        TestTCPEndpoint clientEp = newEndpoint(clientHandler, clientEngine, "client-loop");
        TestTCPEndpoint serverEp = newEndpoint(serverHandler, serverEngine, "server-loop");
        try {
            SSLState clientState = new SSLState(clientEngine, clientEp);
            SSLState serverState = new SSLState(serverEngine, serverEp);

            final List<String> observedThreads =
                    java.util.Collections.synchronizedList(new ArrayList<String>());
            CryptoExecutor.workThreadObserver = new CryptoExecutor.WorkThreadObserver() {
                @Override
                public void observed(Thread t) {
                    observedThreads.add(t.getName());
                }
            };

            clientState.startClientHandshake();
            pumpUntilBothEstablished(clientEp, clientState, clientHandler,
                    serverEp, serverState, serverHandler, 15000);

            assertNull("mTLS handshake should complete cleanly", clientHandler.error);
            assertNull("mTLS handshake should complete cleanly", serverHandler.error);
            assertNotNull(clientHandler.securityInfo);
            assertNotNull(serverHandler.securityInfo);
            // A client-certificate handshake produces more than one round of
            // delegated-task work (this client's own key exchange, and the
            // server's client-certificate chain validation) -- proving
            // resumeAfterTask() -> processHandshake() correctly re-enters
            // and re-submits for however many rounds are needed, rather
            // than assuming a single round-trip through the pool suffices.
            assertTrue("expected more than one delegated task submission for a "
                    + "mutual-TLS handshake, got " + observedThreads.size(),
                    observedThreads.size() > 1);
            for (String name : observedThreads) {
                assertTrue("every delegated task must run on a gumdrop-crypto-* "
                        + "thread, was " + name, name.startsWith("gumdrop-crypto-"));
            }
        } finally {
            clientEp.shutdownLoop();
            serverEp.shutdownLoop();
        }
    }

    @Test(timeout = 20000)
    public void testConnectionClosedDuringInFlightTaskIsHandledGracefully() throws Exception {
        SSLEngine serverEngine = newServerEngine(false);
        SSLEngine clientEngine = newClientEngine(false);

        RecordingHandler clientHandler = new RecordingHandler();
        RecordingHandler serverHandler = new RecordingHandler();
        TestTCPEndpoint clientEp = newEndpoint(clientHandler, clientEngine, "client-loop");
        TestTCPEndpoint serverEp = newEndpoint(serverHandler, serverEngine, "server-loop");
        try {
            SSLState clientState = new SSLState(clientEngine, clientEp);
            SSLState serverState = new SSLState(serverEngine, serverEp);

            final CountDownLatch taskStarted = new CountDownLatch(1);
            final CountDownLatch releaseTask = new CountDownLatch(1);
            CryptoExecutor.workThreadObserver = new CryptoExecutor.WorkThreadObserver() {
                @Override
                public void observed(Thread t) {
                    taskStarted.countDown();
                    try {
                        releaseTask.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };

            clientState.startClientHandshake();
            deliver(clientEp, serverEp, serverState); // ClientHello -> server
            assertTrue("delegated task never started", taskStarted.await(10, TimeUnit.SECONDS));

            // Close the connection out from under the in-flight task, the
            // way a peer disconnect or idle timeout could race it.
            serverState.handleClosed("test-forced-close");
            assertTrue(serverHandler.closedLatch.await(5, TimeUnit.SECONDS));

            releaseTask.countDown();

            // The completion callback's "if (closed) return;" guard must
            // make this an orderly no-op: no exception, no double-close,
            // no further engine/buffer access. Give it a moment to run and
            // confirm nothing blows up or double-notifies.
            Thread.sleep(200);
            assertNull("closing during an in-flight task must not surface as an error",
                    serverHandler.error);
        } finally {
            clientEp.shutdownLoop();
            serverEp.shutdownLoop();
        }
    }
}
