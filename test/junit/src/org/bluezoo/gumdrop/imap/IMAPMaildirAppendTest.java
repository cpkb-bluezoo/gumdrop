/*
 * IMAPMaildirAppendTest.java
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
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SASLMechanism;
import org.bluezoo.gumdrop.mailbox.maildir.MaildirMailboxFactory;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
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

/**
 * Regression tests for the streaming {@link AsyncMessageWriter} APPEND path
 * (scalability review finding #130 / follow-up architectural review): a
 * Maildir-backed mailbox is the only implementation of
 * {@code openAsyncAppend}, so it is the only path that exercises
 * {@code IMAPProtocolHandler.finishAppendViaWriter}, which chains off the
 * writer's own async completion handlers instead of blocking a
 * StorageExecutor thread on a latch waiting for a different thread pool.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IMAPMaildirAppendTest {

    private Path tempRoot;
    private Gumdrop gumdrop;

    @Before
    public void setUp() throws Exception {
        tempRoot = Files.createTempDirectory("gumdrop-imap-maildir-append");
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
        if (gumdrop != null && gumdrop.isStarted()) {
            gumdrop.shutdown();
        }
        deleteRecursively(tempRoot);
    }

    @Test(timeout = 20000)
    public void testAppendViaAsyncWriterDeliversMessageAndUid()
            throws Exception {
        Path mailRoot = tempRoot.resolve("maildir");
        Path userDir = mailRoot.resolve("editor");
        Files.createDirectories(userDir.resolve("cur"));
        Files.createDirectories(userDir.resolve("new"));
        Files.createDirectories(userDir.resolve("tmp"));

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

        String message = "From: sender@example.com\r\n"
                + "Subject: async append test\r\n"
                + "\r\n"
                + "Body of an async-writer APPEND.\r\n";
        byte[] messageBytes = message.getBytes(StandardCharsets.US_ASCII);

        endpoint.sentData.clear();
        byte[] command = ("a2 APPEND INBOX {" + messageBytes.length + "+}\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        ByteBuffer combined = ByteBuffer.allocate(
                command.length + messageBytes.length + 2);
        combined.put(command);
        combined.put(messageBytes);
        combined.put((byte) '\r');
        combined.put((byte) '\n');
        combined.flip();
        handler.receive(combined);

        assertTrue("APPEND OK not received: " + endpoint.getResponses(),
                awaitLineContaining(endpoint, "a2 OK", 10, TimeUnit.SECONDS));
        String okLine = findLineContaining(endpoint, "a2 OK");
        assertTrue("OK response must carry APPENDUID: " + okLine,
                okLine.contains("APPENDUID"));

        List<Path> delivered = listCurFiles(userDir.resolve("cur"));
        assertEquals("exactly one message must be delivered to cur/, saw "
                        + delivered, 1, delivered.size());
        byte[] onDisk = Files.readAllBytes(delivered.get(0));
        assertArrayEquals("delivered message content must match what was sent",
                messageBytes, onDisk);
        assertTrue("no leftover tmp/ file after finish()",
                listCurFiles(userDir.resolve("tmp")).isEmpty());
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

    private static List<Path> listCurFiles(Path dir) throws Exception {
        List<Path> result = new ArrayList<Path>();
        if (!Files.exists(dir)) {
            return result;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                result.add(p);
            }
        }
        return result;
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
