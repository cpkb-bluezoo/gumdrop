/*
 * ImapSessionHarness.java
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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.After;
import org.junit.Before;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.mailbox.maildir.MaildirMailboxFactory;
import org.bluezoo.gumdrop.testsupport.RecordingStubEndpoint;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;

import static org.junit.Assert.*;

/**
 * Shared fixture for IMAP handler tests: an in-memory Maildir behind a
 * hand-written mock mailbox factory ({@link ImapMockMailboxFactory}), a
 * recording stub endpoint and command helpers that assert on the tagged
 * completion line.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ImapSessionHarness {

    MemoryFileSystem mem;
    Gumdrop gumdrop;
    ImapListener listener;
    ImapProtocolHandler handler;
    RecordingStubEndpoint endpoint;
    ImapMockMailboxFactory mock;
    int tagCounter;
    final ByteArrayOutputStream raw = new ByteArrayOutputStream();

    @Before
    public void setUp() throws Exception {
        mem = MemoryFileSystem.create();
        gumdrop = TestGumdrop.create();
        Path mailRoot = mem.getPath("/maildir");
        Path userDir = mailRoot.resolve("editor");
        Files.createDirectories(userDir.resolve("cur"));
        Files.createDirectories(userDir.resolve("new"));
        Files.createDirectories(userDir.resolve("tmp"));
        mock = new ImapMockMailboxFactory(new MaildirMailboxFactory(mailRoot));
        listener = new ImapListener();
        listener.realm(new IMAPSessionCoverageTest.AcceptingRealm(
                "editor", "editor"));
        listener.mailboxFactory(mock);
        listener.allowPlaintextLogin(true);
        configureListener(listener);
        handler = new ImapProtocolHandler(listener);
        endpoint = new RecordingStubEndpoint(143);
        endpoint.setSelectorLoop(gumdrop.nextWorkerLoop());
        captureRaw();
        handler.connected(endpoint);
    }

    /** Records every byte the endpoint sends (literals included). */
    void captureRaw() {
        endpoint.setSendFilter(new RecordingStubEndpoint.SendFilter() {
            @Override
            public byte[] filter(byte[] outbound) {
                raw.write(outbound, 0, outbound.length);
                return outbound;
            }
        });
    }

    /** Everything sent since the last command, byte for byte. */
    String rawText() {
        return new String(raw.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Lets a test class adjust the listener before the handler exists. */
    protected void configureListener(ImapListener l) {
    }

    @After
    public void tearDown() throws Exception {
        if (gumdrop != null && gumdrop.isStarted()) {
            gumdrop.shutdown();
        }
    }

    /** Starts a second handler on a fresh endpoint of the same listener. */
    void reconnect() throws Exception {
        handler = new ImapProtocolHandler(listener);
        endpoint = new RecordingStubEndpoint(143);
        endpoint.setSelectorLoop(gumdrop.nextWorkerLoop());
        captureRaw();
        handler.connected(endpoint);
    }

    void assertRaw(String fragment) {
        String text = rawText();
        assertTrue("expected [" + fragment + "] in:\n" + text,
                text.contains(fragment));
    }

    String cmd(String command) throws Exception {
        tagCounter++;
        String tag = "t" + tagCounter;
        endpoint.clearResponses();
        raw.reset();
        send(tag + " " + command + "\r\n");
        return endpoint.awaitLineStartingWith(tag + " ");
    }

    void send(String data) {
        handler.receive(ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8)));
    }

    void sendBytes(byte[] data) {
        handler.receive(ByteBuffer.wrap(data));
    }

    String ok(String command) throws Exception {
        String line = cmd(command);
        assertTrue(command + " -> " + line, line.contains(" OK"));
        return line;
    }

    String no(String command) throws Exception {
        String line = cmd(command);
        assertTrue(command + " -> " + line, line.contains(" NO"));
        return line;
    }

    String bad(String command) throws Exception {
        String line = cmd(command);
        assertTrue(command + " -> " + line, line.contains(" BAD"));
        return line;
    }

    /** All lines recorded since the last command, joined by newlines. */
    String transcript() {
        List<String> lines = endpoint.getResponses();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            sb.append(line);
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Asserts a line with the prefix was already sent and returns it. */
    String lineStarting(String prefix) {
        String line = endpoint.findLineStartingWith(prefix);
        assertNotNull("no line starting [" + prefix + "] in:\n"
                + transcript(), line);
        return line;
    }

    static void assertContains(String actual, String fragment) {
        assertTrue("expected [" + fragment + "] in: " + actual,
                actual.contains(fragment));
    }

    static void assertNotContains(String actual, String fragment) {
        assertFalse("unexpected [" + fragment + "] in: " + actual,
                actual.contains(fragment));
    }

    void assertSaw(String fragment) {
        String t = transcript();
        assertTrue("expected [" + fragment + "] in:\n" + t,
                t.contains(fragment));
    }

    void assertNotSaw(String fragment) {
        String t = transcript();
        assertFalse("unexpected [" + fragment + "] in:\n" + t,
                t.contains(fragment));
    }

    void login() throws Exception {
        ok("LOGIN editor editor");
    }

    /** Appends a complete message given as RFC 822 text. */
    void appendRaw(String mailbox, String flags, String message)
            throws Exception {
        int len = message.getBytes(StandardCharsets.UTF_8).length;
        tagCounter++;
        String tag = "t" + tagCounter;
        endpoint.clearResponses();
        String f = flags == null ? "" : " (" + flags + ")";
        send(tag + " APPEND " + mailbox + f + " {" + len + "+}\r\n"
                + message + "\r\n");
        String line = endpoint.awaitLineStartingWith(tag + " ");
        assertTrue(line, line.contains(" OK"));
    }

    void appendSimple(String mailbox, String flags, String subject)
            throws Exception {
        appendRaw(mailbox, flags, "From: alice@example.com\r\n"
                + "To: bob@example.com\r\n"
                + "Subject: " + subject + "\r\n"
                + "Date: Mon, 05 May 2025 10:00:00 +0000\r\n"
                + "Message-ID: <" + subject.replace(' ', '.') + "@t>\r\n"
                + "\r\n"
                + "Body of " + subject + "\r\n");
    }

    /** Logs in, appends three messages and selects INBOX. */
    void selectedInbox() throws Exception {
        login();
        appendSimple("INBOX", null, "one");
        appendSimple("INBOX", "\\Seen", "two");
        appendSimple("INBOX", "\\Flagged", "three");
        ok("SELECT INBOX");
    }
}
