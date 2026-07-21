/*
 * AsyncDiskOffloadBoundaryTest.java
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

import org.bluezoo.gumdrop.ftp.FTPListener;
import org.bluezoo.gumdrop.ftp.FTPProtocolHandler;
import org.bluezoo.gumdrop.ftp.file.BasicFTPFileSystem;
import org.bluezoo.gumdrop.ftp.file.SimpleFTPHandler;
import org.bluezoo.gumdrop.http.HTTPRequestHandler;
import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.http.HTTPVersion;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.imap.IMAPListener;
import org.bluezoo.gumdrop.imap.IMAPProtocolHandler;
import org.bluezoo.gumdrop.mailbox.maildir.MaildirMailboxFactory;
import org.bluezoo.gumdrop.pop3.POP3Listener;
import org.bluezoo.gumdrop.pop3.POP3ProtocolHandler;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SASLMechanism;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Phase 4 boundary tests: with a started {@link Gumdrop}, protocol offloads
 * run on {@code gumdrop-storage-*} threads (observed via
 * {@link StorageExecutor#workThreadObserver}), and a saturated pool fails
 * without executing work on the caller.
 */
public class AsyncDiskOffloadBoundaryTest {

    private Path tempRoot;
    private Gumdrop gumdrop;

    @Before
    public void setUp() throws Exception {
        Logger.getLogger("org.bluezoo.gumdrop.webdav.FileHandler")
                .setLevel(Level.SEVERE);
        Logger.getLogger("org.bluezoo.gumdrop.ftp").setLevel(Level.SEVERE);

        tempRoot = Files.createTempDirectory("gumdrop-async-disk-boundary");
        StorageExecutor.workThreadObserver = null;

        System.setProperty("gumdrop.workers", "1");
        gumdrop = Gumdrop.getInstance();
        gumdrop.setDrainTimeoutMs(0);
        if (!gumdrop.isStarted()) {
            gumdrop.start();
        }
        assertNotNull("StorageExecutor must exist after Gumdrop.start()",
                gumdrop.getStorageExecutor());
    }

    @After
    public void tearDown() throws Exception {
        StorageExecutor.workThreadObserver = null;
        if (gumdrop != null && gumdrop.isStarted()) {
            gumdrop.shutdown();
        }
        deleteRecursively(tempRoot);
    }

    @Test(timeout = 15000)
    public void webdavGet_offloadRunsOnStorageThread() throws Exception {
        Path file = tempRoot.resolve("hello.txt");
        Files.write(file, "Hello".getBytes(StandardCharsets.UTF_8));

        final AtomicReference<String> workThread =
                new AtomicReference<String>();
        final CountDownLatch observed = new CountDownLatch(1);
        StorageExecutor.workThreadObserver = new StorageExecutor.WorkThreadObserver() {
            @Override
            public void observed(Thread t) {
                workThread.set(t.getName());
                observed.countDown();
            }
        };

        HTTPRequestHandler handler = newFileHandler(tempRoot, true);
        RecordingState st = new RecordingState();
        Headers req = new Headers();
        req.add(":method", "GET");
        req.add(":path", "/hello.txt");
        handler.headers(st, req);

        assertTrue("storage worker not observed",
                observed.await(5, TimeUnit.SECONDS));
        assertTrue("response did not complete",
                st.await(5, TimeUnit.SECONDS));
        assertEquals(HTTPStatus.OK.code, st.status());
        assertEquals("Hello", new String(st.body(), StandardCharsets.UTF_8));
        assertTrue("WebDAV offload must run on gumdrop-storage-*, was "
                        + workThread.get(),
                workThread.get() != null
                        && workThread.get().startsWith("gumdrop-storage-"));
    }

    @Test(timeout = 15000)
    public void ftpCwd_offloadRunsOnStorageThread() throws Exception {
        Path sub = Files.createDirectory(tempRoot.resolve("subdir"));
        BasicFTPFileSystem fs = new BasicFTPFileSystem(tempRoot, false);
        SimpleFTPHandler connHandler = new SimpleFTPHandler(fs);
        FTPProtocolHandler handler =
                new FTPProtocolHandler(new FTPListener(), connHandler);
        StubEndpoint endpoint = new StubEndpoint();

        final AtomicReference<String> workThread =
                new AtomicReference<String>();
        final CountDownLatch observed = new CountDownLatch(1);
        StorageExecutor.workThreadObserver = new StorageExecutor.WorkThreadObserver() {
            @Override
            public void observed(Thread t) {
                workThread.set(t.getName());
                observed.countDown();
            }
        };

        handler.connected(endpoint);
        endpoint.sentData.clear();
        sendFtp(handler, "USER test");
        sendFtp(handler, "PASS secret");
        assertTrue("login failed: " + lastFtpResponse(endpoint),
                lastFtpResponse(endpoint).startsWith("230"));

        endpoint.sentData.clear();
        sendFtp(handler, "CWD subdir");

        assertTrue("storage worker not observed for CWD",
                observed.await(5, TimeUnit.SECONDS));
        assertTrue("CWD reply not received",
                awaitFtpReply(endpoint, "250", 5, TimeUnit.SECONDS));
        assertTrue("FTP CWD offload must run on gumdrop-storage-*, was "
                        + workThread.get(),
                workThread.get() != null
                        && workThread.get().startsWith("gumdrop-storage-"));
        assertTrue(Files.isDirectory(sub));
    }

    @Test(timeout = 20000)
    public void imapLogin_offloadRunsOnStorageThread() throws Exception {
        Path mailRoot = tempRoot.resolve("maildir-imap");
        Path userDir = mailRoot.resolve("editor");
        Files.createDirectories(userDir.resolve("cur"));
        Files.createDirectories(userDir.resolve("new"));
        Files.createDirectories(userDir.resolve("tmp"));
        String msg = "From: a@b\r\nSubject: hi\r\n\r\nbody\r\n";
        Files.write(userDir.resolve("cur")
                        .resolve("1000.1.localhost,S=" + msg.length() + ":2,"),
                msg.getBytes(StandardCharsets.US_ASCII));

        IMAPListener listener = new IMAPListener();
        listener.setRealm(new AcceptingRealm("editor", "editor"));
        listener.setMailboxFactory(new MaildirMailboxFactory(mailRoot));
        listener.setAllowPlaintextLogin(true);

        IMAPProtocolHandler handler = new IMAPProtocolHandler(listener);
        StubEndpoint endpoint = new StubEndpoint();

        final AtomicReference<String> workThread =
                new AtomicReference<String>();
        final CountDownLatch observed = new CountDownLatch(1);
        StorageExecutor.workThreadObserver = new StorageExecutor.WorkThreadObserver() {
            @Override
            public void observed(Thread t) {
                workThread.set(t.getName());
                observed.countDown();
            }
        };

        handler.connected(endpoint);
        endpoint.sentData.clear();
        sendLine(handler, "a1 LOGIN editor editor");

        assertTrue("storage worker not observed for IMAP LOGIN",
                observed.await(10, TimeUnit.SECONDS));
        assertTrue("LOGIN OK not received: " + endpoint.getResponses(),
                awaitLineContaining(endpoint, "a1 OK", 10, TimeUnit.SECONDS));
        assertTrue("IMAP LOGIN offload must run on gumdrop-storage-*, was "
                        + workThread.get(),
                workThread.get() != null
                        && workThread.get().startsWith("gumdrop-storage-"));

        // SELECT also offloads mailbox open
        workThread.set(null);
        final CountDownLatch selectObserved = new CountDownLatch(1);
        StorageExecutor.workThreadObserver = new StorageExecutor.WorkThreadObserver() {
            @Override
            public void observed(Thread t) {
                workThread.set(t.getName());
                selectObserved.countDown();
            }
        };
        endpoint.sentData.clear();
        sendLine(handler, "a2 SELECT INBOX");
        assertTrue("storage worker not observed for IMAP SELECT",
                selectObserved.await(10, TimeUnit.SECONDS));
        assertTrue("SELECT OK not received: " + endpoint.getResponses(),
                awaitLineContaining(endpoint, "a2 OK", 10, TimeUnit.SECONDS));
        assertTrue("IMAP SELECT offload must run on gumdrop-storage-*, was "
                        + workThread.get(),
                workThread.get() != null
                        && workThread.get().startsWith("gumdrop-storage-"));
    }

    @Test(timeout = 20000)
    public void pop3Pass_offloadRunsOnStorageThread() throws Exception {
        Path mailRoot = tempRoot.resolve("maildir-pop3");
        Path userDir = mailRoot.resolve("editor");
        Files.createDirectories(userDir.resolve("cur"));
        Files.createDirectories(userDir.resolve("new"));
        Files.createDirectories(userDir.resolve("tmp"));
        String msg = "From: a@b\r\nSubject: hi\r\n\r\nbody\r\n";
        Files.write(userDir.resolve("cur")
                        .resolve("1000.1.localhost,S=" + msg.length() + ":2,"),
                msg.getBytes(StandardCharsets.US_ASCII));

        POP3Listener listener = new POP3Listener();
        listener.setRealm(new AcceptingRealm("editor", "editor"));
        listener.setMailboxFactory(new MaildirMailboxFactory(mailRoot));

        POP3ProtocolHandler handler = new POP3ProtocolHandler(listener);
        StubEndpoint endpoint = new StubEndpoint();

        final AtomicReference<String> workThread =
                new AtomicReference<String>();
        final CountDownLatch observed = new CountDownLatch(1);
        StorageExecutor.workThreadObserver = new StorageExecutor.WorkThreadObserver() {
            @Override
            public void observed(Thread t) {
                workThread.set(t.getName());
                observed.countDown();
            }
        };

        handler.connected(endpoint);
        endpoint.sentData.clear();
        sendLine(handler, "USER editor");
        assertTrue("USER +OK not received: " + endpoint.getResponses(),
                awaitLineStartingWith(endpoint, "+OK", 5, TimeUnit.SECONDS));
        endpoint.sentData.clear();
        sendLine(handler, "PASS editor");

        assertTrue("storage worker not observed for POP3 PASS",
                observed.await(10, TimeUnit.SECONDS));
        assertTrue("PASS +OK not received: " + endpoint.getResponses(),
                awaitLineStartingWith(endpoint, "+OK", 10, TimeUnit.SECONDS));
        assertTrue("POP3 PASS offload must run on gumdrop-storage-*, was "
                        + workThread.get(),
                workThread.get() != null
                        && workThread.get().startsWith("gumdrop-storage-"));

        // RETR content open also goes through StorageExecutor
        final AtomicReference<String> retrThread =
                new AtomicReference<String>();
        final CountDownLatch retrObserved = new CountDownLatch(1);
        StorageExecutor.workThreadObserver = new StorageExecutor.WorkThreadObserver() {
            @Override
            public void observed(Thread t) {
                retrThread.set(t.getName());
                retrObserved.countDown();
            }
        };
        endpoint.sentData.clear();
        sendLine(handler, "RETR 1");
        assertTrue("storage worker not observed for POP3 RETR; responses="
                        + endpoint.getResponses(),
                retrObserved.await(10, TimeUnit.SECONDS));
        assertTrue("RETR terminator not received: " + endpoint.getResponses(),
                awaitLineEquals(endpoint, ".", 10, TimeUnit.SECONDS));
        assertTrue("POP3 RETR offload must run on gumdrop-storage-*, was "
                        + retrThread.get(),
                retrThread.get() != null
                        && retrThread.get().startsWith("gumdrop-storage-"));
    }

    @Test(timeout = 15000)
    public void webdavGet_saturatedPool_returnsErrorWithoutRunningWork()
            throws Exception {
        Path file = tempRoot.resolve("sat.txt");
        Files.write(file, "x".getBytes(StandardCharsets.UTF_8));

        StorageExecutor tiny = new StorageExecutor(1, 1);
        replaceStorageExecutor(tiny);

        final CountDownLatch block = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        Logger fhLogger = Logger.getLogger(
                "org.bluezoo.gumdrop.webdav.FileHandler");
        Level previousLevel = fhLogger.getLevel();
        fhLogger.setLevel(Level.OFF);

        // Occupy the single worker + fill the queue (2 slots total).
        LoopEndpoint loop = new LoopEndpoint();
        try {
            tiny.submit(loop, new Callable<Void>() {
                @Override
                public Void call() throws InterruptedException {
                    started.countDown();
                    block.await(5, TimeUnit.SECONDS);
                    return null;
                }
            }, new NoopCallback<Void>());
            assertTrue(started.await(5, TimeUnit.SECONDS));
            tiny.submit(loop, new Callable<Void>() {
                @Override
                public Void call() {
                    return null;
                }
            }, new NoopCallback<Void>());

            final AtomicBoolean rejectedWorkRan = new AtomicBoolean(false);
            StorageExecutor.workThreadObserver = new StorageExecutor.WorkThreadObserver() {
                @Override
                public void observed(Thread t) {
                    // Must not run for the rejected WebDAV submission.
                    rejectedWorkRan.set(true);
                }
            };

            HTTPRequestHandler handler = newFileHandler(tempRoot, true);
            RecordingState st = new RecordingState();
            Headers req = new Headers();
            req.add(":method", "GET");
            req.add(":path", "/sat.txt");
            handler.headers(st, req);

            assertTrue("saturated GET must still complete (error path)",
                    st.await(5, TimeUnit.SECONDS));
            assertEquals("saturated offload should surface as 500",
                    HTTPStatus.INTERNAL_SERVER_ERROR.code, st.status());
            assertFalse("rejected WebDAV work must not run on any thread",
                    rejectedWorkRan.get());
        } finally {
            fhLogger.setLevel(previousLevel);
            block.countDown();
            loop.shutdown();
            tiny.shutdown();
        }
    }

    @Test(timeout = 15000)
    public void storageExecutor_saturated_failsWithoutCallerWork()
            throws Exception {
        // Regression guard matching StorageExecutorTest, using the observer
        // hook to prove rejected work never reaches a storage thread.
        StorageExecutor exec = new StorageExecutor(1, 1);
        LoopEndpoint loop = new LoopEndpoint();
        final CountDownLatch block = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicBoolean rejectedObserved = new AtomicBoolean(false);
        try {
            exec.submit(loop, new Callable<Void>() {
                @Override
                public Void call() throws InterruptedException {
                    started.countDown();
                    block.await(5, TimeUnit.SECONDS);
                    return null;
                }
            }, new NoopCallback<Void>());
            assertTrue(started.await(5, TimeUnit.SECONDS));
            exec.submit(loop, new Callable<Void>() {
                @Override
                public Void call() {
                    return null;
                }
            }, new NoopCallback<Void>());

            StorageExecutor.workThreadObserver = new StorageExecutor.WorkThreadObserver() {
                @Override
                public void observed(Thread t) {
                    rejectedObserved.set(true);
                }
            };

            final AtomicReference<Throwable> error =
                    new AtomicReference<Throwable>();
            final CountDownLatch rejected = new CountDownLatch(1);
            final AtomicBoolean ran = new AtomicBoolean(false);
            exec.submit(loop, new Callable<Void>() {
                @Override
                public Void call() {
                    ran.set(true);
                    return null;
                }
            }, new StorageExecutor.Callback<Void>() {
                @Override
                public void completed(Void r) {
                    rejected.countDown();
                }
                @Override
                public void failed(Throwable t) {
                    error.set(t);
                    rejected.countDown();
                }
            });

            assertTrue(rejected.await(5, TimeUnit.SECONDS));
            assertTrue(error.get() instanceof RejectedExecutionException);
            assertFalse(ran.get());
            assertFalse("observer must not see rejected work",
                    rejectedObserved.get());
        } finally {
            StorageExecutor.workThreadObserver = null;
            block.countDown();
            exec.shutdown();
            loop.shutdown();
        }
    }

    // ── helpers ──

    private static HTTPRequestHandler newFileHandler(Path root,
            boolean allowWrite) throws Exception {
        Class<?> handlerClass =
                Class.forName("org.bluezoo.gumdrop.webdav.FileHandler");
        Class<?> lockClass =
                Class.forName("org.bluezoo.gumdrop.webdav.WebDAVLockManager");
        Class<?> deadClass =
                Class.forName("org.bluezoo.gumdrop.webdav.DeadPropertyStore");
        Constructor<?> ctor = handlerClass.getDeclaredConstructor(
                Path.class, boolean.class, boolean.class, String.class,
                String[].class, Map.class, lockClass, deadClass);
        ctor.setAccessible(true);
        Constructor<?> lockCtor = lockClass.getDeclaredConstructor();
        lockCtor.setAccessible(true);
        Object lockManager = lockCtor.newInstance();
        Map<String, String> types = new HashMap<String, String>();
        types.put("txt", "text/plain");
        return (HTTPRequestHandler) ctor.newInstance(root, allowWrite, true,
                "GET, HEAD, PUT, DELETE, OPTIONS, PROPFIND, MKCOL, COPY, MOVE",
                new String[]{"index.html"}, types, lockManager, null);
    }

    private void replaceStorageExecutor(StorageExecutor replacement)
            throws Exception {
        Field f = Gumdrop.class.getDeclaredField("storageExecutor");
        f.setAccessible(true);
        StorageExecutor previous = (StorageExecutor) f.get(gumdrop);
        if (previous != null && previous != replacement) {
            previous.shutdown();
        }
        f.set(gumdrop, replacement);
    }

    private static void sendFtp(FTPProtocolHandler handler, String command) {
        byte[] data = (command + "\r\n").getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(data));
    }

    private static void sendLine(org.bluezoo.gumdrop.ProtocolHandler handler,
            String command) {
        byte[] data = (command + "\r\n").getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(data));
    }

    private static boolean awaitLineContaining(StubEndpoint endpoint,
            String fragment, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            for (String line : endpoint.getResponses()) {
                if (line.contains(fragment)) {
                    return true;
                }
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static boolean awaitLineStartingWith(StubEndpoint endpoint,
            String prefix, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            for (String line : endpoint.getResponses()) {
                if (line.startsWith(prefix)) {
                    return true;
                }
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static boolean awaitLineEquals(StubEndpoint endpoint,
            String exact, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            for (String line : endpoint.getResponses()) {
                if (exact.equals(line)) {
                    return true;
                }
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static String lastFtpResponse(StubEndpoint endpoint) {
        List<String> responses = endpoint.getResponses();
        assertFalse("No FTP responses", responses.isEmpty());
        return responses.get(responses.size() - 1);
    }

    /** Minimal realm that accepts a single username/password pair. */
    private static final class AcceptingRealm implements Realm {
        private final String user;
        private final String pass;
        private static final Set<SASLMechanism> SUPPORTED =
                Collections.unmodifiableSet(
                        EnumSet.of(SASLMechanism.PLAIN, SASLMechanism.LOGIN));

        AcceptingRealm(String user, String pass) {
            this.user = user;
            this.pass = pass;
        }

        @Override
        public Realm forSelectorLoop(SelectorLoop loop) {
            return this;
        }

        @Override
        public Set<SASLMechanism> getSupportedSASLMechanisms() {
            return SUPPORTED;
        }

        @Override
        public boolean passwordMatch(String username, String password) {
            return user.equals(username) && pass.equals(password);
        }

        @Override
        public String getDigestHA1(String username, String realmName) {
            return null;
        }

        @Override
        public String getPassword(String username) {
            return user.equals(username) ? pass : null;
        }

        @Override
        public boolean isUserInRole(String username, String role) {
            return false;
        }
    }

    private static boolean awaitFtpReply(StubEndpoint endpoint,
            String codePrefix, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            for (String line : endpoint.getResponses()) {
                if (line.startsWith(codePrefix)) {
                    return true;
                }
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static void deleteRecursively(Path p) throws Exception {
        if (p == null || !Files.exists(p)) {
            return;
        }
        if (Files.isDirectory(p)) {
            try (java.nio.file.DirectoryStream<Path> ds =
                    Files.newDirectoryStream(p)) {
                for (Path child : ds) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(p);
    }

    private static final class NoopCallback<T>
            implements StorageExecutor.Callback<T> {
        @Override public void completed(T result) { }
        @Override public void failed(Throwable error) { }
    }

    /**
     * Minimal loop endpoint so StorageExecutor can marshal callbacks.
     */
    private static final class LoopEndpoint implements Endpoint {
        private final ExecutorService loop =
                Executors.newSingleThreadExecutor(new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "test-loop-thread");
                    }
                });

        void shutdown() {
            loop.shutdownNow();
        }

        @Override
        public void execute(Runnable task) {
            loop.execute(task);
        }

        @Override public void send(ByteBuffer data) { }
        @Override public boolean isOpen() { return true; }
        @Override public boolean isClosing() { return false; }
        @Override public void close() { }
        @Override public SocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 0);
        }
        @Override public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 0);
        }
        @Override public boolean isSecure() { return false; }
        @Override public SecurityInfo getSecurityInfo() { return null; }
        @Override public void startTLS() { }
        @Override public void pauseRead() { }
        @Override public void resumeRead() { }
        @Override public void onWriteReady(Runnable callback) { }
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public TimerHandle scheduleTimer(long delayMs, Runnable cb) {
            return null;
        }
        @Override public org.bluezoo.gumdrop.telemetry.Trace getTrace() {
            return null;
        }
        @Override public void setTrace(
                org.bluezoo.gumdrop.telemetry.Trace trace) { }
        @Override public boolean isTelemetryEnabled() { return false; }
        @Override public org.bluezoo.gumdrop.telemetry.TelemetryConfig
                getTelemetryConfig() {
            return null;
        }
    }

    static final class StubEndpoint implements Endpoint {
        final List<byte[]> sentData = new ArrayList<byte[]>();
        boolean open = true;

        @Override
        public void send(ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            sentData.add(bytes);
        }

        List<String> getResponses() {
            List<String> result = new ArrayList<String>();
            for (byte[] data : sentData) {
                String s = new String(data, StandardCharsets.US_ASCII);
                for (String line : s.split("\r\n", -1)) {
                    if (!line.isEmpty()) {
                        result.add(line);
                    }
                }
            }
            return result;
        }

        @Override public boolean isOpen() { return open; }
        @Override public boolean isClosing() { return false; }
        @Override public void close() { open = false; }
        @Override public SocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 21);
        }
        @Override public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 54321);
        }
        @Override public boolean isSecure() { return false; }
        @Override public SecurityInfo getSecurityInfo() { return null; }
        @Override public void startTLS() { }
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public TimerHandle scheduleTimer(long delayMs, Runnable cb) {
            return new TimerHandle() {
                @Override public void cancel() { }
                @Override public boolean isCancelled() { return false; }
            };
        }
        @Override public org.bluezoo.gumdrop.telemetry.Trace getTrace() {
            return null;
        }
        @Override public void setTrace(
                org.bluezoo.gumdrop.telemetry.Trace trace) { }
        @Override public boolean isTelemetryEnabled() { return false; }
        @Override public org.bluezoo.gumdrop.telemetry.TelemetryConfig
                getTelemetryConfig() {
            return null;
        }
        @Override public void pauseRead() { }
        @Override public void resumeRead() { }
        @Override public void onWriteReady(Runnable callback) {
            if (callback != null) {
                callback.run();
            }
        }
    }

    private static final class RecordingState implements HTTPResponseState {
        private final Object lock = new Object();
        private final ByteArrayOutputStream bodyOut =
                new ByteArrayOutputStream();
        private final CountDownLatch done = new CountDownLatch(1);
        private Headers responseHeaders;
        private int statusCode = -1;

        boolean await(long t, TimeUnit u) throws InterruptedException {
            return done.await(t, u);
        }

        int status() {
            synchronized (lock) {
                return statusCode;
            }
        }

        byte[] body() {
            synchronized (lock) {
                return bodyOut.toByteArray();
            }
        }

        @Override
        public void headers(Headers headers) {
            synchronized (lock) {
                this.responseHeaders = headers;
                String s = headers.getValue(":status");
                if (s != null) {
                    try {
                        statusCode = Integer.parseInt(s);
                    } catch (NumberFormatException ignore) {
                    }
                }
            }
        }

        @Override public void startResponseBody() { }

        @Override
        public void responseBodyContent(ByteBuffer data) {
            synchronized (lock) {
                byte[] b = new byte[data.remaining()];
                data.get(b);
                bodyOut.write(b, 0, b.length);
            }
        }

        @Override public void endResponseBody() { }

        @Override
        public void complete() {
            done.countDown();
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public void onWritable(Runnable callback) {
            if (callback != null) {
                callback.run();
            }
        }

        @Override public void pauseRequestBody() { }
        @Override public void resumeRequestBody() { }
        @Override public boolean pushPromise(Headers headers) { return false; }
        @Override public void upgradeToWebSocket(String subprotocol,
                WebSocketEventHandler handler) { }
        @Override public void cancel() { done.countDown(); }
        @Override public SocketAddress getRemoteAddress() { return null; }
        @Override public SocketAddress getLocalAddress() { return null; }
        @Override public boolean isSecure() { return false; }
        @Override public SecurityInfo getSecurityInfo() { return null; }
        @Override public HTTPVersion getVersion() {
            return HTTPVersion.HTTP_1_1;
        }
        @Override public String getScheme() { return "http"; }
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public Principal getPrincipal() { return null; }
    }
}
