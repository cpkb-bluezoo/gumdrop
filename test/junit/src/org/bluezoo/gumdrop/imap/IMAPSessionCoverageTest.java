/*
 * IMAPSessionCoverageTest.java
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.mailbox.maildir.MaildirMailboxFactory;
import org.bluezoo.gumdrop.testsupport.RecordingStubEndpoint;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;

import static org.junit.Assert.*;

/**
 * Drives {@link ImapProtocolHandler} through full authenticated and
 * selected sessions against an in-memory Maildir, covering the command
 * state implementations (SELECT, FETCH, STORE, SEARCH, SORT, THREAD,
 * COPY, MOVE, EXPUNGE, mailbox management, quota, metadata, ID).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IMAPSessionCoverageTest {

    private MemoryFileSystem mem;
    private Gumdrop gumdrop;
    ImapListener listener;
    ImapProtocolHandler handler;
    RecordingStubEndpoint endpoint;
    int tagCounter;

    @Before
    public void setUp() throws Exception {
        mem = MemoryFileSystem.create();
        gumdrop = TestGumdrop.create();
        Path mailRoot = mem.getPath("/maildir");
        Path userDir = mailRoot.resolve("editor");
        Files.createDirectories(userDir.resolve("cur"));
        Files.createDirectories(userDir.resolve("new"));
        Files.createDirectories(userDir.resolve("tmp"));
        listener = new ImapListener();
        listener.setRealm(new AcceptingRealm("editor", "editor"));
        listener.setMailboxFactory(new MaildirMailboxFactory(mailRoot));
        listener.setAllowPlaintextLogin(true);
        configureListener(listener);
        handler = new ImapProtocolHandler(listener);
        endpoint = newEndpoint();
        endpoint.setSelectorLoop(gumdrop.nextWorkerLoop());
        handler.connected(endpoint);
    }

    /** Lets a subclass adjust the listener before any handler is created. */
    protected void configureListener(ImapListener l) {
    }

    /** Creates the endpoint the handler under test is connected to. */
    protected RecordingStubEndpoint newEndpoint() {
        return new RecordingStubEndpoint(143);
    }

    @After
    public void tearDown() throws Exception {
        if (gumdrop != null && gumdrop.isStarted()) {
            gumdrop.shutdown();
        }
    }

    /** Sends a command with an auto-generated tag; returns the tagged line. */
    String cmd(String command) throws Exception {
        tagCounter++;
        String tag = "t" + tagCounter;
        endpoint.clearResponses();
        send(tag + " " + command + "\r\n");
        return endpoint.awaitLineStartingWith(tag + " ");
    }

    void send(String data) {
        handler.receive(ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8)));
    }

    void ok(String command) throws Exception {
        String line = cmd(command);
        assertTrue(command + " -> " + line, line.contains(" OK"));
    }

    void no(String command) throws Exception {
        String line = cmd(command);
        assertTrue(command + " -> " + line, line.contains(" NO"));
    }

    void bad(String command) throws Exception {
        String line = cmd(command);
        assertTrue(command + " -> " + line, line.contains(" BAD"));
    }

    /** Any tagged completion is acceptable; exercises the code path. */
    void any(String command) throws Exception {
        String line = cmd(command);
        assertNotNull(line);
    }

    void login() throws Exception {
        ok("LOGIN editor editor");
    }

    void append(String mailbox, String flags, String subject,
            String from, String extra) throws Exception {
        String msg = "From: " + from + "\r\n"
                + "To: bob@example.com\r\n"
                + "Cc: carol@example.com\r\n"
                + "Subject: " + subject + "\r\n"
                + "Date: Mon, 05 May 2025 10:00:00 +0000\r\n"
                + "Message-ID: <" + subject.replace(' ', '.') + "@t>\r\n"
                + extra
                + "\r\n"
                + "Body of " + subject + "\r\n";
        int len = msg.getBytes(StandardCharsets.UTF_8).length;
        tagCounter++;
        String tag = "t" + tagCounter;
        endpoint.clearResponses();
        String f = flags == null ? "" : " (" + flags + ")";
        send(tag + " APPEND " + mailbox + f + " {" + len + "+}\r\n" + msg + "\r\n");
        String line = endpoint.awaitLineStartingWith(tag + " ");
        assertTrue(line, line.contains(" OK"));
    }

    void populate() throws Exception {
        login();
        append("INBOX", null, "First topic", "alice@example.com", "");
        append("INBOX", "\\Seen", "Second topic", "dave@example.com",
                "In-Reply-To: <First.topic@t>\r\n");
        append("INBOX", "\\Flagged \\Answered", "Re: First topic",
                "alice@example.com",
                "References: <First.topic@t>\r\n");
        append("INBOX", "\\Deleted", "Third topic", "erin@example.com",
                "Content-Type: text/plain; charset=utf-8\r\n");
    }

    @Test(timeout = 30000)
    public void testNotAuthenticatedStateCommands() throws Exception {
        ok("CAPABILITY");
        ok("NOOP");
        ok("ID NIL");
        ok("ID (\"name\" \"test\" \"version\" \"1\")");
        no("LOGIN editor wrong");
        no("LOGIN nobody x");
        bad("LOGIN");
        no("STARTTLS");
        bad("SELECT INBOX");
        bad("FETCH 1 FLAGS");
        bad("LIST \"\" \"*\"");
        no("AUTHENTICATE BOGUS");
        any("AUTHENTICATE PLAIN");
        send("*\r\n");
        any("COMPRESS DEFLATE");
        bad("UNKNOWNCMD");
    }

    @Test(timeout = 30000)
    public void testAuthenticatePlainInitialResponse() throws Exception {
        endpoint.setSecure(true);
        String ir = java.util.Base64.getEncoder().encodeToString(
                "\u0000editor\u0000editor".getBytes(StandardCharsets.UTF_8));
        ok("AUTHENTICATE PLAIN " + ir);
        bad("LOGIN editor editor");
    }

    @Test(timeout = 30000)
    public void testAuthenticatePlainContinuation() throws Exception {
        endpoint.setSecure(true);
        tagCounter++;
        String tag = "t" + tagCounter;
        send(tag + " AUTHENTICATE PLAIN\r\n");
        endpoint.awaitLineStartingWith("+");
        String ir = java.util.Base64.getEncoder().encodeToString(
                "\u0000editor\u0000editor".getBytes(StandardCharsets.UTF_8));
        send(ir + "\r\n");
        String line = endpoint.awaitLineStartingWith(tag + " ");
        assertTrue(line, line.contains(" OK"));
    }

    @Test(timeout = 30000)
    public void testAuthenticateLoginMechanism() throws Exception {
        endpoint.setSecure(true);
        tagCounter++;
        String tag = "t" + tagCounter;
        send(tag + " AUTHENTICATE LOGIN\r\n");
        endpoint.awaitLineStartingWith("+");
        send(java.util.Base64.getEncoder().encodeToString(
                "editor".getBytes(StandardCharsets.UTF_8)) + "\r\n");
        endpoint.awaitLineStartingWith("+");
        send(java.util.Base64.getEncoder().encodeToString(
                "editor".getBytes(StandardCharsets.UTF_8)) + "\r\n");
        String line = endpoint.awaitLineStartingWith(tag + " ");
        assertTrue(line, line.contains(" OK"));
    }

    @Test(timeout = 30000)
    public void testAuthenticateBadCredentials() throws Exception {
        endpoint.setSecure(true);
        String ir = java.util.Base64.getEncoder().encodeToString(
                "\u0000editor\u0000nope".getBytes(StandardCharsets.UTF_8));
        no("AUTHENTICATE PLAIN " + ir);
        any("AUTHENTICATE PLAIN !!!notbase64");
    }

    @Test(timeout = 30000)
    public void testAuthenticatedStateMailboxManagement() throws Exception {
        login();
        ok("NAMESPACE");
        ok("CREATE Sub");
        no("CREATE Sub");
        ok("CREATE Sub/Child");
        ok("LIST \"\" \"*\"");
        ok("LIST \"\" \"%\"");
        ok("LIST \"\" \"\"");
        ok("LIST \"Sub/\" \"%\"");
        ok("LIST (SUBSCRIBED) \"\" \"*\"");
        ok("LIST \"\" \"*\" RETURN (CHILDREN SPECIAL-USE)");
        ok("SUBSCRIBE Sub");
        ok("LSUB \"\" \"*\"");
        ok("UNSUBSCRIBE Sub");
        ok("STATUS INBOX (MESSAGES UIDNEXT UIDVALIDITY UNSEEN RECENT SIZE)");
        no("STATUS Nonexistent (MESSAGES)");
        ok("RENAME Sub Renamed");
        no("RENAME Missing Other");
        no("RENAME Renamed Renamed");
        ok("DELETE Renamed/Child");
        ok("DELETE Renamed");
        no("DELETE Renamed");
        no("DELETE INBOX");
        no("SELECT Nonexistent");
        no("EXAMINE Nonexistent");
        bad("CLOSE");
        bad("EXPUNGE");
        any("SEARCH ALL");
        bad("STORE 1 +FLAGS (\\Seen)");
        bad("COPY 1 INBOX");
        any("SORT (SUBJECT) UTF-8 ALL");
        any("THREAD REFERENCES UTF-8 ALL");
        bad("UID FETCH 1 FLAGS");
        bad("LOGIN editor editor");
        bad("STATUS");
        bad("CREATE");
        bad("RENAME onlyone");
    }

    @Test(timeout = 30000)
    public void testSelectAndFetchVariants() throws Exception {
        populate();
        ok("SELECT INBOX");
        assertNotNull(endpoint.findLineContaining("EXISTS"));
        ok("FETCH 1:* (FLAGS UID INTERNALDATE RFC822.SIZE)");
        ok("FETCH 1 ENVELOPE");
        ok("FETCH 1:4 ENVELOPE");
        ok("FETCH 1 BODYSTRUCTURE");
        ok("FETCH 1 BODY");
        ok("FETCH 1 BODY[]");
        ok("FETCH 1 BODY.PEEK[]");
        ok("FETCH 1 BODY[HEADER]");
        ok("FETCH 1 BODY[TEXT]");
        ok("FETCH 1 BODY[1]");
        ok("FETCH 1 BODY.PEEK[1.MIME]");
        ok("FETCH 1 BODY[HEADER.FIELDS (Subject From)]");
        ok("FETCH 1 BODY[HEADER.FIELDS.NOT (Subject)]");
        ok("FETCH 1 BODY[]<0.10>");
        ok("FETCH 1 BODY[TEXT]<2.5>");
        ok("FETCH 1 RFC822");
        ok("FETCH 1 RFC822.HEADER");
        ok("FETCH 1 RFC822.TEXT");
        ok("FETCH 1 ALL");
        ok("FETCH 1 FAST");
        ok("FETCH 1 FULL");
        ok("FETCH 2:3 (UID FLAGS BODY.PEEK[HEADER.FIELDS (Subject)])");
        ok("FETCH 1 (BINARY.PEEK[1])");
        ok("FETCH 1 (BINARY.SIZE[1])");
        ok("FETCH 1 (PREVIEW)");
        ok("FETCH 1 (EMAILID THREADID)");
        any("FETCH 1 (MODSEQ)");
        any("FETCH 99 FLAGS");
        bad("FETCH 1 BOGUSITEM");
        bad("FETCH");
        bad("FETCH x FLAGS");
        ok("UID FETCH 1:* (FLAGS)");
        ok("UID FETCH 1 (UID BODY[HEADER])");
        any("UID FETCH 9999 (FLAGS)");
        ok("NOOP");
        bad("CHECK");
    }

    @Test(timeout = 30000)
    public void testStoreVariants() throws Exception {
        populate();
        ok("SELECT INBOX");
        ok("STORE 1 +FLAGS (\\Seen)");
        ok("STORE 1 -FLAGS (\\Seen)");
        ok("STORE 1 FLAGS (\\Seen \\Flagged)");
        ok("STORE 1 +FLAGS.SILENT (\\Answered)");
        ok("STORE 1 -FLAGS.SILENT (\\Answered)");
        ok("STORE 1:2 +FLAGS (\\Draft)");
        ok("STORE 1 +FLAGS (custom1)");
        ok("STORE 1 +FLAGS \\Seen");
        ok("UID STORE 1 +FLAGS (\\Seen)");
        any("STORE 99 +FLAGS (\\Seen)");
        bad("STORE 1 +FLAGS");
        bad("STORE 1 BOGUS (\\Seen)");
        bad("STORE");
        ok("STORE 1 (UNCHANGEDSINCE 99999) +FLAGS (\\Seen)");
    }

    @Test(timeout = 30000)
    public void testSearchSortThread() throws Exception {
        populate();
        ok("SELECT INBOX");
        ok("SEARCH ALL");
        ok("SEARCH UNSEEN");
        ok("SEARCH SEEN");
        ok("SEARCH DELETED");
        ok("SEARCH UNDELETED");
        ok("SEARCH FLAGGED");
        ok("SEARCH ANSWERED");
        ok("SEARCH NEW");
        ok("SEARCH RECENT");
        ok("SEARCH OLD");
        ok("SEARCH DRAFT");
        ok("SEARCH SUBJECT topic");
        ok("SEARCH FROM alice");
        ok("SEARCH TO bob");
        ok("SEARCH CC carol");
        ok("SEARCH BODY Body");
        ok("SEARCH TEXT First");
        ok("SEARCH HEADER Message-ID First");
        ok("SEARCH KEYWORD foo");
        ok("SEARCH UNKEYWORD foo");
        ok("SEARCH LARGER 10");
        ok("SEARCH SMALLER 100000");
        ok("SEARCH SINCE 1-Jan-2020");
        ok("SEARCH BEFORE 1-Jan-2099");
        ok("SEARCH ON 5-May-2025");
        ok("SEARCH SENTSINCE 1-Jan-2020");
        ok("SEARCH SENTBEFORE 1-Jan-2099");
        ok("SEARCH SENTON 5-May-2025");
        ok("SEARCH 1:2");
        ok("SEARCH UID 1:*");
        ok("SEARCH NOT SEEN");
        ok("SEARCH OR SEEN FLAGGED");
        ok("SEARCH (SEEN) (FLAGGED)");
        any("SEARCH CHARSET UTF-8 SUBJECT topic");
        any("SEARCH RETURN (MIN MAX COUNT ALL) ALL");
        any("SEARCH RETURN (SAVE) ALL");
        any("SEARCH $");
        any("SEARCH SAVEDBEFORE 1-Jan-2099");
        any("SEARCH YOUNGER 3600");
        any("SEARCH OLDER 3600");
        any("SEARCH BOGUSKEY");
        bad("SEARCH");
        ok("UID SEARCH ALL");
        ok("UID SEARCH UNSEEN");
        ok("SORT (SUBJECT) UTF-8 ALL");
        ok("SORT (REVERSE DATE) UTF-8 ALL");
        ok("SORT (ARRIVAL) UTF-8 ALL");
        ok("SORT (CC) UTF-8 ALL");
        ok("SORT (FROM) UTF-8 ALL");
        ok("SORT (TO) UTF-8 ALL");
        ok("SORT (SIZE) UTF-8 ALL");
        any("SORT (DISPLAYFROM DISPLAYTO) UTF-8 ALL");
        any("SORT RETURN (COUNT MIN) (SUBJECT) UTF-8 ALL");
        ok("UID SORT (SUBJECT) UTF-8 ALL");
        any("SORT (BOGUS) UTF-8 ALL");
        bad("SORT");
        any("THREAD REFERENCES UTF-8 ALL");
        any("THREAD ORDEREDSUBJECT UTF-8 ALL");
        any("UID THREAD REFERENCES UTF-8 ALL");
        any("THREAD BOGUS UTF-8 ALL");
        bad("THREAD");
    }

    @Test(timeout = 30000)
    public void testCopyMoveExpunge() throws Exception {
        populate();
        ok("CREATE Archive");
        ok("SELECT INBOX");
        ok("COPY 1 Archive");
        ok("COPY 2:3 Archive");
        ok("UID COPY 1 Archive");
        no("COPY 1 Missing");
        any("COPY 99 Archive");
        ok("MOVE 2 Archive");
        ok("UID MOVE 1 Archive");
        no("MOVE 1 Missing");
        ok("EXPUNGE");
        ok("STORE 1 +FLAGS (\\Deleted)");
        ok("UID EXPUNGE 1:*");
        ok("STORE 1:* +FLAGS (\\Deleted)");
        ok("CLOSE");
        ok("SELECT Archive");
        ok("EXAMINE Archive");
        no("STORE 1 +FLAGS (\\Seen)");
        no("EXPUNGE");
        ok("UNSELECT");
        bad("EXPUNGE");
    }

    @Test(timeout = 30000)
    public void testSelectOptionsAndEnable() throws Exception {
        populate();
        ok("ENABLE CONDSTORE");
        ok("SELECT INBOX (CONDSTORE)");
        ok("FETCH 1 (FLAGS MODSEQ)");
        any("SELECT INBOX (QRESYNC (1 1))");
        ok("ENABLE UTF8=ACCEPT");
        any("ENABLE QRESYNC");
        any("ENABLE BOGUS");
        bad("ENABLE");
        ok("EXAMINE INBOX");
        ok("SELECT INBOX");
        ok("SELECT \"INBOX\"");
        ok("SELECT inbox");
    }

    @Test(timeout = 30000)
    public void testQuotaAndMetadata() throws Exception {
        login();
        any("GETQUOTA \"\"");
        any("GETQUOTAROOT INBOX");
        any("SETQUOTA \"\" (STORAGE 1000)");
        any("GETQUOTA \"\"");
        any("SETQUOTA \"\" (MESSAGE 10)");
        any("SETQUOTA \"\" ()");
        any("GETQUOTA");
        any("SETQUOTA");
        any("SETMETADATA INBOX (/private/comment \"hello\")");
        any("GETMETADATA INBOX /private/comment");
        any("GETMETADATA INBOX (/private/comment /shared/comment)");
        any("GETMETADATA (DEPTH infinity) INBOX /private");
        any("GETMETADATA (MAXSIZE 5) INBOX /private/comment");
        any("SETMETADATA INBOX (/private/comment NIL)");
        any("SETMETADATA \"\" (/shared/admin \"x\")");
        bad("GETMETADATA");
        bad("SETMETADATA");
    }

    @Test(timeout = 30000)
    public void testAppendVariants() throws Exception {
        login();
        append("INBOX", "\\Seen", "alpha", "a@x.com", "");
        tagCounter++;
        String tag = "t" + tagCounter;
        endpoint.clearResponses();
        String msg = "Subject: dated\r\n\r\nbody\r\n";
        send(tag + " APPEND INBOX (\\Seen) \"05-May-2025 10:00:00 +0000\" {"
                + msg.length() + "}\r\n");
        endpoint.awaitLineStartingWith("+");
        send(msg + "\r\n");
        String line = endpoint.awaitLineStartingWith(tag + " ");
        assertTrue(line, line.contains(" OK"));
        no("APPEND Missing {1+}\r\nx");
        bad("APPEND");
        bad("APPEND INBOX notaliteral");
    }

    @Test(timeout = 30000)
    public void testIdleAndDone() throws Exception {
        populate();
        ok("SELECT INBOX");
        tagCounter++;
        String tag = "t" + tagCounter;
        endpoint.clearResponses();
        send(tag + " IDLE\r\n");
        endpoint.awaitLineStartingWith("+");
        send("DONE\r\n");
        String line = endpoint.awaitLineStartingWith(tag + " ");
        assertTrue(line, line.contains(" OK"));
        ok("NOOP");
    }

    @Test(timeout = 30000)
    public void testNotifyCommands() throws Exception {
        populate();
        ok("ENABLE NOTIFY");
        any("NOTIFY SET (selected (MessageNew MessageExpunge FlagChange))");
        any("NOTIFY SET (subscribed (MailboxName SubscriptionChange))");
        any("NOTIFY SET (personal (MessageNew))");
        any("NOTIFY SET (inboxes (MessageNew))");
        any("NOTIFY SET (subtree INBOX (MessageNew))");
        any("NOTIFY SET STATUS (selected (MessageNew))");
        any("NOTIFY BOGUS");
        ok("NOTIFY NONE");
    }

    @Test(timeout = 30000)
    public void testLogoutClosesEndpoint() throws Exception {
        populate();
        ok("SELECT INBOX");
        String line = cmd("LOGOUT");
        assertTrue(line, line.contains(" OK"));
    }

    @Test(timeout = 30000)
    public void testSlicedLoginAndLongLine() throws Exception {
        String line = "a1 LOGIN editor editor\r\n";
        byte[] wire = line.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer netIn = ByteBuffer.allocate(65536);
        for (int i = 0; i < wire.length; i++) {
            netIn.put(wire[i]);
            netIn.flip();
            handler.receive(netIn);
            netIn.compact();
        }
        endpoint.awaitLineStartingWith("a1 ");
        StringBuilder sb = new StringBuilder("a2 NOOP ");
        for (int i = 0; i < 70000; i++) {
            sb.append('x');
        }
        sb.append("\r\n");
        send(sb.toString());
        assertNotNull(endpoint.getResponses());
    }

    @Test(timeout = 30000)
    public void testDisconnectedWhileSelected() throws Exception {
        populate();
        ok("SELECT INBOX");
        handler.disconnected();
    }

    static class AcceptingRealm implements Realm {
        private final String user;
        private final String pass;
        private final boolean admin;
        private static final Set<SaslMechanism> SUPPORTED =
                Collections.unmodifiableSet(
                        EnumSet.of(SaslMechanism.PLAIN, SaslMechanism.LOGIN));

        AcceptingRealm(String user, String pass) {
            this(user, pass, false);
        }

        AcceptingRealm(String user, String pass, boolean admin) {
            this.user = user;
            this.pass = pass;
            this.admin = admin;
        }

        @Override
        public Realm forSelectorLoop(SelectorLoop loop) {
            return this;
        }

        @Override
        public Set<SaslMechanism> getSupportedSASLMechanisms() {
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
            return admin && user.equals(username) && "admin".equals(role);
        }
    }
}
