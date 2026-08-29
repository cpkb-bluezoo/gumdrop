/*
 * FTPPasswordOffloadTest.java
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

package org.bluezoo.gumdrop.ftp;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.StorageExecutor;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SASLMechanism;
import org.bluezoo.gumdrop.ftp.file.BasicFTPFileSystem;
import org.bluezoo.gumdrop.ftp.file.SimpleFTPHandler;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Regression coverage for issue #344: {@code Realm#passwordMatch} was
 * called directly, synchronously, from the {@code SelectorLoop} thread
 * when the client issued {@code PASS} (and {@code ACCT}). A PBKDF2 cache
 * miss runs a 210,000-iteration derivation there, stalling every other
 * connection multiplexed on the same loop. IMAP/POP3/SMTP already route
 * equivalent work through {@link StorageExecutor} (issues #122/#301); FTP
 * had not.
 *
 * <p>Drives a {@code USER}/{@code PASS} login against a real {@link Realm}
 * that performs a real (though intentionally reduced, for test speed)
 * PBKDF2 derivation, and asserts -- via {@link StorageExecutor#workThreadObserver},
 * the same hook {@code IMAPScramCredentialsOffloadTest} uses -- that
 * password verification runs on a storage worker thread rather than inline
 * on the caller.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FTPPasswordOffloadTest {

    private static final String USERNAME = "ftpuser";
    private static final String PASSWORD = "correct horse battery staple";
    // Real PBKDF2-HMAC-SHA256 work, just fewer iterations than production's
    // 210,000 so the test suite doesn't pay that cost many times over --
    // the offload behaviour being tested does not depend on the count.
    private static final int TEST_ITERATIONS = 20_000;

    private Path tempRoot;
    private Gumdrop gumdrop;

    @Before
    public void setUp() throws Exception {
        tempRoot = Files.createTempDirectory("gumdrop-ftp-password-offload");
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

    @Test(timeout = 20000)
    public void passwordVerificationRunsOffSelectorLoopThread() throws Exception {
        BasicFTPFileSystem fs = new BasicFTPFileSystem(tempRoot, false);
        SimpleFTPHandler connectionHandler = new SimpleFTPHandler(
                fs, new Pbkdf2PasswordRealm(USERNAME, PASSWORD));

        FTPListener listener = new FTPListener();
        FTPProtocolHandler handler = new FTPProtocolHandler(listener, connectionHandler);
        StubEndpoint endpoint = new StubEndpoint();
        handler.connected(endpoint);

        endpoint.sentData.clear();
        sendLine(handler, "USER " + USERNAME);
        assertTrue("USER continuation not received: " + endpoint.getResponses(),
                awaitLineStartingWith(endpoint, "331 ", 10, TimeUnit.SECONDS));

        final List<String> observedThreads = Collections.synchronizedList(new ArrayList<String>());
        StorageExecutor.workThreadObserver = new StorageExecutor.WorkThreadObserver() {
            @Override
            public void observed(Thread worker) {
                observedThreads.add(worker.getName());
            }
        };

        endpoint.sentData.clear();
        sendLine(handler, "PASS " + PASSWORD);
        assertTrue("login success not received: " + endpoint.getResponses(),
                awaitLineStartingWith(endpoint, "230 ", 10, TimeUnit.SECONDS));

        assertFalse("PASS password verification must run through "
                + "StorageExecutor -- the work-thread observer was never "
                + "invoked, meaning it ran inline on the calling thread",
                observedThreads.isEmpty());
        for (String name : observedThreads) {
            assertTrue("password verification ran on unexpected thread: " + name,
                    name.startsWith("gumdrop-storage-"));
        }
    }

    // ── helpers ──

    private static void sendLine(org.bluezoo.gumdrop.ProtocolHandler handler,
            String command) {
        byte[] data = (command + "\r\n").getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(data));
    }

    private static boolean awaitLineStartingWith(StubEndpoint endpoint,
            String prefix, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (findLineStartingWith(endpoint, prefix) != null) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static String findLineStartingWith(StubEndpoint endpoint, String prefix) {
        for (String line : endpoint.getResponses()) {
            if (line.startsWith(prefix)) {
                return line;
            }
        }
        return null;
    }

    private static void deleteRecursively(Path p) throws Exception {
        if (p == null || !Files.exists(p)) {
            return;
        }
        if (Files.isDirectory(p)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                for (Path child : ds) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(p);
    }

    /**
     * Minimal realm supporting only {@link Realm#passwordMatch}, performing
     * a real PBKDF2-HMAC-SHA256 pass (at a reduced iteration count, for
     * test speed) on every call -- i.e. with no cache, so this test
     * exercises the offload at the {@code PASS} call site.
     */
    private static final class Pbkdf2PasswordRealm implements Realm {
        private final String user;
        private final byte[] salt;
        private final byte[] expectedHash;
        private static final Set<SASLMechanism> SUPPORTED =
                Collections.emptySet();

        Pbkdf2PasswordRealm(String user, String password) throws Exception {
            this.user = user;
            salt = new byte[16];
            for (int i = 0; i < salt.length; i++) {
                salt[i] = (byte) i;
            }
            expectedHash = pbkdf2(password, salt, TEST_ITERATIONS);
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
            if (!user.equals(username) || password == null) {
                return false;
            }
            try {
                byte[] computed = pbkdf2(password, salt, TEST_ITERATIONS);
                return MessageDigest.isEqual(expectedHash, computed);
            } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public String getDigestHA1(String username, String realmName) {
            return null;
        }

        @Override
        @SuppressWarnings("deprecation")
        public String getPassword(String username) {
            return null;
        }

        @Override
        public boolean isUserInRole(String username, String role) {
            return false;
        }

        private static byte[] pbkdf2(String password, byte[] salt, int iterations)
                throws NoSuchAlgorithmException, InvalidKeySpecException {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(
                    password.toCharArray(), salt, iterations, 256);
            return factory.generateSecret(spec).getEncoded();
        }
    }

    private static final class StubEndpoint implements Endpoint {
        final List<byte[]> sentData = new ArrayList<byte[]>();
        boolean open = true;

        @Override
        public void send(ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            synchronized (sentData) {
                sentData.add(bytes);
            }
        }

        List<String> getResponses() {
            List<String> result = new ArrayList<String>();
            synchronized (sentData) {
                for (byte[] data : sentData) {
                    String s = new String(data, StandardCharsets.US_ASCII);
                    for (String line : s.split("\r\n", -1)) {
                        if (!line.isEmpty()) {
                            result.add(line);
                        }
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
}
