/*
 * DTLSAsyncHandshakeTest.java
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
import org.junit.AfterClass;
import org.junit.Before;
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
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;

import static org.junit.Assert.*;

/**
 * Regression coverage for issue #274: {@link DTLSSession} must run
 * {@code SSLEngine} delegated tasks ({@code NEED_TASK} -- the RSA/ECDHE
 * key-exchange math and certificate-chain validation) off the
 * {@code SelectorLoop} thread, mirroring the TCP/TLS fix in
 * {@link SSLStateTest} (issue #262 / PR #273).
 *
 * <p>Unlike {@link DTLSSessionTest} (which drives two engines against each
 * other with a fully synchronous, no-{@link Gumdrop} pump loop), these
 * tests start a real {@link Gumdrop} instance so a real {@link CryptoExecutor}
 * is available, and each peer's {@link DTLSSession} calls only ever happen
 * on that peer's own dedicated "loop" thread (via
 * {@link ThreadedRecordingUDPEndpoint#execute}) -- matching the threading
 * invariant the rest of {@code DTLSSession}/{@code UDPEndpoint} already
 * assume (see the class-level note on {@code UDPEndpoint.dtlsSessions}).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DTLSAsyncHandshakeTest {

    private static final String PASSWORD = "testpass";
    private static final InetSocketAddress CLIENT_ADDR = new InetSocketAddress("127.0.0.1", 1);
    private static final InetSocketAddress SERVER_ADDR = new InetSocketAddress("127.0.0.1", 2);

    private static Path keystorePath;
    private static Path truststorePath;

    private Gumdrop gumdrop;

    @BeforeClass
    public static void generateKeystore() throws Exception {
        keystorePath = Files.createTempFile("dtls-async-test-keystore", ".p12");
        Files.delete(keystorePath); // keytool must create the file itself
        runKeytool("-genkeypair",
                "-alias", "dtlsasynctest",
                "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "30",
                "-dname", "CN=localhost",
                "-keystore", keystorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", PASSWORD,
                "-keypass", PASSWORD);

        Path certPath = Files.createTempFile("dtls-async-test-cert", ".pem");
        runKeytool("-exportcert",
                "-alias", "dtlsasynctest",
                "-keystore", keystorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", PASSWORD,
                "-rfc",
                "-file", certPath.toString());

        truststorePath = Files.createTempFile("dtls-async-test-truststore", ".p12");
        Files.delete(truststorePath);
        runKeytool("-importcert",
                "-alias", "dtlsasynctest",
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

    private static SSLEngine newServerEngine() throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(loadKeyStore(keystorePath), PASSWORD.toCharArray());
        SSLContext context = SSLContext.getInstance("DTLSv1.2");
        context.init(kmf.getKeyManagers(), null, null);
        SSLEngine engine = context.createSSLEngine();
        engine.setUseClientMode(false);
        return engine;
    }

    private static SSLEngine newClientEngine() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(loadKeyStore(truststorePath));
        SSLContext context = SSLContext.getInstance("DTLSv1.2");
        context.init(null, tmf.getTrustManagers(), null);
        SSLEngine engine = context.createSSLEngine("localhost", SERVER_ADDR.getPort());
        engine.setUseClientMode(true);
        return engine;
    }

    @Before
    public void setUp() {
        CryptoExecutor.workThreadObserver = null;
        CryptoExecutor.loopCallbackObserver = null;
        System.setProperty("gumdrop.workers", "1");
        gumdrop = Gumdrop.getInstance();
        gumdrop.setDrainTimeoutMs(0);
        if (!gumdrop.isStarted()) {
            gumdrop.start();
        }
        assertNotNull("CryptoExecutor must be available once Gumdrop has started",
                gumdrop.getCryptoExecutor());
    }

    @After
    public void tearDown() {
        CryptoExecutor.workThreadObserver = null;
        CryptoExecutor.loopCallbackObserver = null;
        if (gumdrop != null && gumdrop.isStarted()) {
            gumdrop.shutdown();
        }
    }

    /**
     * Stub {@link UDPEndpoint} that runs every {@link #execute} task on a
     * single dedicated named thread, standing in for a real
     * {@code SelectorLoop} thread -- {@link DTLSSession} assumes all of its
     * methods (and {@code UDPEndpoint}'s {@code dtlsSessions} map) are only
     * ever touched from that one thread.
     */
    private static final class ThreadedRecordingUDPEndpoint extends UDPEndpoint {
        final ConcurrentLinkedDeque<byte[]> sent = new ConcurrentLinkedDeque<byte[]>();
        final List<byte[]> received = new CopyOnWriteArrayList<byte[]>();
        final CountDownLatch establishedLatch = new CountDownLatch(1);
        final CountDownLatch failedLatch = new CountDownLatch(1);
        volatile SecurityInfo establishedInfo;
        volatile Exception failure;

        private final ExecutorService loop;

        ThreadedRecordingUDPEndpoint(String loopThreadName) {
            super(new ProtocolHandler() {
                @Override public void receive(ByteBuffer data) { }
                @Override public void connected(Endpoint endpoint) { }
                @Override public void disconnected() { }
                @Override public void securityEstablished(SecurityInfo info) { }
                @Override public void error(Exception cause) { }
            });
            this.loop = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, loopThreadName);
                }
            });
        }

        @Override
        void sendRawDatagram(ByteBuffer data, InetSocketAddress dest) {
            byte[] copy = new byte[data.remaining()];
            data.duplicate().get(copy);
            sent.addLast(copy);
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            // Retransmission is not exercised by these tests (each
            // handshake completes well within the 1s initial timeout);
            // a timer that never fires keeps that machinery quiescent.
            return new TimerHandle() {
                @Override public void cancel() { }
                @Override public boolean isCancelled() { return false; }
            };
        }

        @Override
        public void execute(Runnable task) {
            loop.execute(task);
        }

        @Override
        void notifyDtlsHandshakeComplete(InetSocketAddress peer, SecurityInfo info) {
            establishedInfo = info;
            establishedLatch.countDown();
        }

        @Override
        void onDtlsSessionFailed(InetSocketAddress peer, Exception cause) {
            failure = cause;
            failedLatch.countDown();
        }

        void shutdownLoop() {
            loop.shutdownNow();
        }
    }

    /**
     * Drains one queued datagram (if any) from {@code fromEp} and delivers
     * it to {@code toSession} -- on {@code toEp}'s own loop thread, per the
     * threading invariant described on {@link ThreadedRecordingUDPEndpoint}.
     */
    private static void deliverOne(final ThreadedRecordingUDPEndpoint fromEp,
            final ThreadedRecordingUDPEndpoint toEp, final DTLSSession toSession) {
        deliverOne(fromEp, toEp, toSession, null);
    }

    private static void deliverOne(final ThreadedRecordingUDPEndpoint fromEp,
            final ThreadedRecordingUDPEndpoint toEp, final DTLSSession toSession,
            final Runnable afterDelivery) {
        final byte[] datagram = fromEp.sent.pollFirst();
        if (datagram == null) {
            if (afterDelivery != null) {
                toEp.execute(afterDelivery);
            }
            return;
        }
        toEp.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ByteBuffer plaintext = toSession.unwrap(ByteBuffer.wrap(datagram));
                    if (plaintext != null) {
                        byte[] bytes = new byte[plaintext.remaining()];
                        plaintext.get(bytes);
                        toEp.received.add(bytes);
                    }
                } finally {
                    if (afterDelivery != null) {
                        afterDelivery.run();
                    }
                }
            }
        });
    }

    private static void pumpUntilBothEstablished(
            ThreadedRecordingUDPEndpoint clientEp, DTLSSession clientSession,
            ThreadedRecordingUDPEndpoint serverEp, DTLSSession serverSession)
            throws InterruptedException {
        final Runnable[] pumpRound = new Runnable[1];
        pumpRound[0] = new Runnable() {
            @Override
            public void run() {
                if (clientEp.establishedLatch.getCount() == 0
                        && serverEp.establishedLatch.getCount() == 0) {
                    return;
                }
                deliverOne(clientEp, serverEp, serverSession, new Runnable() {
                    @Override
                    public void run() {
                        deliverOne(serverEp, clientEp, clientSession, pumpRound[0]);
                    }
                });
            }
        };
        Runnable previousHook = CryptoExecutor.loopCallbackObserver;
        CryptoExecutor.loopCallbackObserver = new Runnable() {
            @Override
            public void run() {
                clientEp.execute(pumpRound[0]);
            }
        };
        try {
            clientEp.execute(pumpRound[0]);
            clientEp.establishedLatch.await();
            serverEp.establishedLatch.await();
        } finally {
            CryptoExecutor.loopCallbackObserver = previousHook;
        }
    }

    private static void pumpUntilTaskStarts(
            ThreadedRecordingUDPEndpoint clientEp, DTLSSession clientSession,
            ThreadedRecordingUDPEndpoint serverEp, DTLSSession serverSession,
            final CountDownLatch taskStarted) throws InterruptedException {
        final Runnable[] pumpRound = new Runnable[1];
        pumpRound[0] = new Runnable() {
            @Override
            public void run() {
                if (taskStarted.getCount() == 0) {
                    return;
                }
                deliverOne(clientEp, serverEp, serverSession, new Runnable() {
                    @Override
                    public void run() {
                        deliverOne(serverEp, clientEp, clientSession, pumpRound[0]);
                    }
                });
            }
        };
        Runnable previousHook = CryptoExecutor.loopCallbackObserver;
        CryptoExecutor.loopCallbackObserver = new Runnable() {
            @Override
            public void run() {
                clientEp.execute(pumpRound[0]);
            }
        };
        try {
            clientEp.execute(pumpRound[0]);
            taskStarted.await();
        } finally {
            CryptoExecutor.loopCallbackObserver = previousHook;
        }
    }

    private static void beginHandshakes(
            final DTLSSession clientSession, ThreadedRecordingUDPEndpoint clientEp,
            final DTLSSession serverSession, ThreadedRecordingUDPEndpoint serverEp)
            throws InterruptedException {
        final CountDownLatch started = new CountDownLatch(2);
        serverEp.execute(new Runnable() {
            @Override public void run() {
                serverSession.beginHandshake();
                started.countDown();
            }
        });
        clientEp.execute(new Runnable() {
            @Override public void run() {
                clientSession.beginHandshake();
                started.countDown();
            }
        });
        started.await();
    }

    @Test(timeout = 20000)
    public void testHandshakeCompletesAsynchronouslyOffLoop() throws Exception {
        final List<String> observedThreads = new CopyOnWriteArrayList<String>();
        CryptoExecutor.workThreadObserver = new CryptoExecutor.WorkThreadObserver() {
            @Override
            public void observed(Thread worker) {
                observedThreads.add(worker.getName());
            }
        };

        ThreadedRecordingUDPEndpoint clientEp = new ThreadedRecordingUDPEndpoint("dtls-test-client-loop");
        ThreadedRecordingUDPEndpoint serverEp = new ThreadedRecordingUDPEndpoint("dtls-test-server-loop");
        try {
            final DTLSSession clientSession = new DTLSSession(newClientEngine(), clientEp, SERVER_ADDR);
            final DTLSSession serverSession = new DTLSSession(newServerEngine(), serverEp, CLIENT_ADDR);

            beginHandshakes(clientSession, clientEp, serverSession, serverEp);

            pumpUntilBothEstablished(clientEp, clientSession, serverEp, serverSession);

            assertNotNull(clientEp.establishedInfo);
            assertNotNull(serverEp.establishedInfo);
            assertNull(clientEp.failure);
            assertNull(serverEp.failure);
            assertFalse("at least one delegated task should have run off-loop",
                    observedThreads.isEmpty());
            for (String name : observedThreads) {
                assertTrue("delegated task ran on unexpected thread: " + name,
                        name.startsWith("gumdrop-crypto-"));
            }
        } finally {
            clientEp.shutdownLoop();
            serverEp.shutdownLoop();
        }
    }

    /**
     * The actual regression case for issue #274: while a delegated task is
     * in flight for one peer's handshake, that peer's own loop thread must
     * stay free to do other work -- proving the {@code NEED_TASK} handling
     * no longer runs the task inline and blocks the thread for its
     * duration.
     */
    @Test(timeout = 20000)
    public void testLoopThreadStaysResponsiveDuringDelegatedTask() throws Exception {
        final CountDownLatch taskRunning = new CountDownLatch(1);
        final CountDownLatch releaseTask = new CountDownLatch(1);
        final AtomicInteger blockedCount = new AtomicInteger();
        CryptoExecutor.workThreadObserver = new CryptoExecutor.WorkThreadObserver() {
            @Override
            public void observed(Thread worker) {
                if (blockedCount.getAndIncrement() == 0) {
                    taskRunning.countDown();
                    try {
                        releaseTask.await(15, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };

        ThreadedRecordingUDPEndpoint clientEp = new ThreadedRecordingUDPEndpoint("dtls-test-client-loop-2");
        ThreadedRecordingUDPEndpoint serverEp = new ThreadedRecordingUDPEndpoint("dtls-test-server-loop-2");
        try {
            final DTLSSession clientSession = new DTLSSession(newClientEngine(), clientEp, SERVER_ADDR);
            final DTLSSession serverSession = new DTLSSession(newServerEngine(), serverEp, CLIENT_ADDR);

            beginHandshakes(clientSession, clientEp, serverSession, serverEp);

            pumpUntilTaskStarts(clientEp, clientSession, serverEp, serverSession,
                    taskRunning);

            // The server's own loop thread must still be free to run
            // unrelated queued work while its delegated task is blocked --
            // this is the actual bug: before the fix, NEED_TASK ran the
            // task inline on this same thread, so it would still be busy
            // running task.run() right now and this would time out.
            final CountDownLatch unrelatedWorkDone = new CountDownLatch(1);
            serverEp.execute(new Runnable() {
                @Override
                public void run() {
                    unrelatedWorkDone.countDown();
                }
            });
            assertTrue("server's loop thread is still blocked running the "
                    + "delegated task inline -- NEED_TASK is not offloaded",
                    unrelatedWorkDone.await(2, TimeUnit.SECONDS));

            releaseTask.countDown();
            pumpUntilBothEstablished(clientEp, clientSession, serverEp, serverSession);
            assertNull(clientEp.failure);
            assertNull(serverEp.failure);
        } finally {
            releaseTask.countDown();
            clientEp.shutdownLoop();
            serverEp.shutdownLoop();
        }
    }

    /**
     * A datagram arriving from the peer while a delegated task is in
     * flight must not be fed straight into the busy {@code SSLEngine} (the
     * JDK forbids concurrent {@code wrap()}/{@code unwrap()} while a
     * delegated task has not finished running) -- it must be queued and
     * replayed once the task completes, and the handshake must still
     * complete correctly afterwards.
     */
    @Test(timeout = 20000)
    public void testDatagramArrivingDuringInFlightTaskDoesNotCorruptHandshake() throws Exception {
        final CountDownLatch taskRunning = new CountDownLatch(1);
        final CountDownLatch releaseTask = new CountDownLatch(1);
        final AtomicInteger blockedCount = new AtomicInteger();
        CryptoExecutor.workThreadObserver = new CryptoExecutor.WorkThreadObserver() {
            @Override
            public void observed(Thread worker) {
                if (blockedCount.getAndIncrement() == 0) {
                    taskRunning.countDown();
                    try {
                        releaseTask.await(15, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };

        ThreadedRecordingUDPEndpoint clientEp = new ThreadedRecordingUDPEndpoint("dtls-test-client-loop-3");
        ThreadedRecordingUDPEndpoint serverEp = new ThreadedRecordingUDPEndpoint("dtls-test-server-loop-3");
        try {
            final DTLSSession clientSession = new DTLSSession(newClientEngine(), clientEp, SERVER_ADDR);
            final DTLSSession serverSession = new DTLSSession(newServerEngine(), serverEp, CLIENT_ADDR);

            beginHandshakes(clientSession, clientEp, serverSession, serverEp);

            pumpUntilTaskStarts(clientEp, clientSession, serverEp, serverSession,
                    taskRunning);

            // Redeliver whatever the client has sent since (simulating a
            // retransmit / next flight arriving) straight at the server
            // while its task is still in flight.
            deliverOne(clientEp, serverEp, serverSession);
            deliverOne(clientEp, serverEp, serverSession);

            releaseTask.countDown();
            pumpUntilBothEstablished(clientEp, clientSession, serverEp, serverSession);
            assertNull("engine must not have been corrupted by the concurrent datagram",
                    clientEp.failure);
            assertNull("engine must not have been corrupted by the concurrent datagram",
                    serverEp.failure);
        } finally {
            releaseTask.countDown();
            clientEp.shutdownLoop();
            serverEp.shutdownLoop();
        }
    }
}
