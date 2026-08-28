/*
 * IMAPFetchBatchingTest.java
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

package org.bluezoo.gumdrop.imap;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.StorageExecutor;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SASLMechanism;
import org.bluezoo.gumdrop.mailbox.maildir.MaildirMailboxFactory;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Regression coverage for issue #294: a content-free FETCH (no ENVELOPE,
 * body, or literal item -- just things already resident in memory, like
 * FLAGS/UID/INTERNALDATE) must not submit one {@link StorageExecutor} job
 * per matched message. A resync-style {@code FETCH 1:N (FLAGS)} against a
 * large mailbox previously did exactly that -- {@code N} individual
 * submissions to a bounded queue shared with every other connection's
 * storage traffic -- risking a {@code RejectedExecutionException} that
 * would fail the whole command outright.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IMAPFetchBatchingTest {

    private static final int MESSAGE_COUNT = 25;

    private Path tempRoot;
    private Gumdrop gumdrop;

    @Before
    public void setUp() throws Exception {
        tempRoot = Files.createTempDirectory("gumdrop-imap-fetch-batching");
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
    public void contentFreeFetchOverManyMessagesUsesOneStorageSubmission()
            throws Exception {
        Path mailRoot = tempRoot.resolve("maildir");
        Path userDir = mailRoot.resolve("editor");
        Files.createDirectories(userDir.resolve("cur"));
        Files.createDirectories(userDir.resolve("new"));
        Files.createDirectories(userDir.resolve("tmp"));

        for (int i = 0; i < MESSAGE_COUNT; i++) {
            String content = "From: sender@example.com\r\n"
                    + "Subject: message " + i + "\r\n"
                    + "\r\n"
                    + "body " + i + "\r\n";
            // No trailing ":2,<flags>" info suffix (issue #287): optional
            // per the Maildir spec, and a literal ":" in a filename throws
            // InvalidPathException on Windows/NTFS.
            String filename = "173335680" + String.format("%04d", i)
                    + ".uidtest." + i + ",S=" + content.length();
            Files.write(userDir.resolve("cur").resolve(filename),
                    content.getBytes(StandardCharsets.US_ASCII));
        }

        IMAPListener listener = new IMAPListener();
        listener.setRealm(new AcceptingRealm("editor", "editor"));
        listener.setMailboxFactory(new MaildirMailboxFactory(mailRoot));
        listener.setAllowPlaintextLogin(true);

        IMAPProtocolHandler handler = new IMAPProtocolHandler(listener);
        StubEndpoint endpoint = new StubEndpoint();
        handler.connected(endpoint);

        endpoint.sentData.clear();
        sendLine(handler, "a1 LOGIN editor editor");
        assertTrue("LOGIN OK not received: " + endpoint.getResponses(),
                awaitLineContaining(endpoint, "a1 OK", 10, TimeUnit.SECONDS));

        endpoint.sentData.clear();
        sendLine(handler, "a2 SELECT INBOX");
        assertTrue("SELECT OK not received: " + endpoint.getResponses(),
                awaitLineContaining(endpoint, "a2 OK", 10, TimeUnit.SECONDS));
        assertTrue("SELECT must report " + MESSAGE_COUNT + " EXISTS: "
                        + endpoint.getResponses(),
                findLineContaining(endpoint,
                        "* " + MESSAGE_COUNT + " EXISTS") != null);

        // Only start counting once LOGIN/SELECT's own storage work is done,
        // so the assertion is purely about the FETCH command itself.
        final AtomicInteger submissions = new AtomicInteger();
        StorageExecutor.workThreadObserver = new StorageExecutor.WorkThreadObserver() {
            @Override
            public void observed(Thread worker) {
                submissions.incrementAndGet();
            }
        };

        endpoint.sentData.clear();
        sendLine(handler, "a3 FETCH 1:" + MESSAGE_COUNT + " (FLAGS)");
        assertTrue("FETCH OK not received: " + endpoint.getResponses(),
                awaitLineContaining(endpoint, "a3 OK", 10, TimeUnit.SECONDS));

        int fetchLines = 0;
        for (String line : endpoint.getResponses()) {
            if (line.contains("FETCH (FLAGS")) {
                fetchLines++;
            }
        }
        assertEquals("every message must still get its own FETCH response line",
                MESSAGE_COUNT, fetchLines);

        assertEquals("a content-free FETCH over " + MESSAGE_COUNT
                + " messages must use a single StorageExecutor submission, "
                + "not one per message",
                1, submissions.get());
    }

    // ── helpers ──

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
            if (findLineContaining(endpoint, fragment) != null) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static String findLineContaining(StubEndpoint endpoint,
            String fragment) {
        for (String line : endpoint.getResponses()) {
            if (line.contains(fragment)) {
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
        @SuppressWarnings("deprecation")
        public String getPassword(String username) {
            return user.equals(username) ? pass : null;
        }

        @Override
        public boolean isUserInRole(String username, String role) {
            return false;
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
            return new InetSocketAddress("127.0.0.1", 143);
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
