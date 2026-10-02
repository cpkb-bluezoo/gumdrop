/*
 * IMAPClientCommandsTest.java
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


package org.bluezoo.gumdrop.imap.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;

import static org.junit.Assert.*;

/**
 * Drives the command and response paths of {@link ImapClientProtocolHandler}
 * that the main protocol test does not reach: the less common commands,
 * unsolicited responses, failure completions and malformed input.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IMAPClientCommandsTest {

    private ImapClientProtocolHandler handler;
    private Rec rec;
    private PausingEndpoint endpoint;

    @Before
    public void setUp() {
        rec = new Rec();
        handler = new ImapClientProtocolHandler(rec);
        endpoint = new PausingEndpoint();
        handler.connected(endpoint);
    }

    private void line(String text) {
        byte[] data = (text + "\r\n").getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(data));
    }

    private void lines(String... texts) {
        for (String t : texts) {
            line(t);
        }
    }

    private void greet() {
        line("* OK ready");
    }

    private String tag() {
        return handler.getPendingTag();
    }

    private void done(String status) {
        line(tag() + " " + status);
    }

    private String lastSent() {
        List<String> sent = endpoint.getSentCommands();
        int n = sent.size();
        return sent.get(n - 1);
    }

    private void selectInbox() {
        greet();
        handler.select("INBOX", rec);
        done("OK [READ-WRITE] selected");
        rec.events.clear();
    }

    private boolean saw(String event) {
        return rec.events.contains(event);
    }

    private String lastEvent() {
        int n = rec.events.size();
        return rec.events.get(n - 1);
    }

    // ── Greeting and authentication ──

    @Test
    public void testPreauthGreeting() {
        line("* PREAUTH logged in");
        assertTrue(saw("preauth:logged in"));
    }

    @Test
    public void testGreetingNoIsServiceUnavailable() {
        line("* NO overloaded");
        assertTrue(saw("unavailable:overloaded"));
        assertFalse(handler.isOpen());
    }

    @Test
    public void testLoginFailedAndCapabilityCode() {
        greet();
        handler.login("a", "b", rec);
        done("NO bad credentials");
        assertTrue(saw("authFailed:bad credentials"));

        handler.login("a", "b", rec);
        done("OK [CAPABILITY IMAP4rev2 IDLE] welcome");
        assertTrue(saw("authenticated:IMAP4rev2,IDLE"));
    }

    @Test
    public void testAuthenticateSuccessWithCapabilityAndFailure() {
        greet();
        handler.authenticate("PLAIN", new byte[] {1, 2}, rec);
        assertTrue(lastSent().contains("AUTHENTICATE PLAIN AQI="));
        done("OK [CAPABILITY IMAP4rev2] ok");
        assertTrue(saw("authSuccess:IMAP4rev2"));

        handler.authenticate("PLAIN", null, rec);
        assertTrue(lastSent().endsWith("AUTHENTICATE PLAIN"));
        line("+ ");
        assertTrue(saw("challenge:0"));
        handler.respond(new byte[] {9}, rec);
        done("NO denied");
        assertTrue(saw("authFailed:denied"));
    }

    @Test
    public void testAuthAbort() {
        greet();
        handler.authenticate("X", null, rec);
        handler.abort(rec);
        assertEquals("*", lastSent());
        done("BAD aborted");
        assertTrue(saw("aborted"));
    }

    @Test
    public void testStarttlsOkBadAndNo() {
        greet();
        handler.starttls(rec);
        done("OK begin TLS");
        assertTrue(endpoint.startTLSCalled);
        handler.securityEstablished(new IMAPClientProtocolHandlerTest.StubSecurityInfo());
        assertTrue(saw("tlsEstablished"));

        handler.starttls(rec);
        done("NO not now");
        assertTrue(saw("tlsUnavailable"));

        handler.starttls(rec);
        done("BAD unsupported");
        assertTrue(saw("permanent:unsupported"));
    }

    @Test
    public void testStarttlsEndpointFailure() {
        endpoint.failStartTls = true;
        greet();
        handler.starttls(rec);
        done("OK begin TLS");
        assertTrue(saw("permanent:no tls"));
    }

    @Test
    public void testSecurityEstablishedWithoutStarttlsCallback() {
        greet();
        handler.setSecure(true);
        handler.securityEstablished(new IMAPClientProtocolHandlerTest.StubSecurityInfo());
        assertTrue(saw("secured"));
        assertFalse(saw("tlsEstablished"));
    }

    // ── Mailbox management ──

    @Test
    public void testExamineWithAllStatusCodes() {
        greet();
        handler.examine("Arch\"ive", rec);
        assertTrue(lastSent().contains("EXAMINE \"Arch\\\"ive\""));
        lines("* FLAGS (\\Seen \\Deleted)",
                "* OK [PERMANENTFLAGS (\\Seen \\*)] ok",
                "* OK [UIDVALIDITY 77] ok",
                "* OK [UIDNEXT 5] ok",
                "* OK [UNSEEN 2] ok",
                "* OK [MAILBOXID (mb1)] ok",
                "* OK [UIDVALIDITY zz] bad",
                "* OK [UIDNEXT zz] bad",
                "* OK [UNSEEN zz] bad",
                "* OK [READ-ONLY] ro",
                "* OK no code here",
                "* 4 EXISTS",
                "* 1 RECENT");
        done("OK [READ-ONLY] examined");
        assertNotNull(rec.info);
        assertEquals(77L, rec.info.getUidValidity());
        assertEquals(5L, rec.info.getUidNext());
        assertEquals(2, rec.info.getUnseen());
        assertEquals("mb1", rec.info.getMailboxId());
        assertFalse(rec.info.isReadWrite());
        assertEquals(4, rec.info.getExists());
        assertEquals(1, rec.info.getRecent());
        assertEquals(2, rec.info.getFlags().length);
        assertEquals(2, rec.info.getPermanentFlags().length);
    }

    @Test
    public void testSelectReadWriteCodeOnTaggedAndFailure() {
        greet();
        handler.select("A", rec);
        lines("* OK [READ-WRITE] rw");
        done("OK [READ-WRITE] done");
        assertTrue(rec.info.isReadWrite());

        handler.select("B", rec);
        done("NO no such mailbox");
        assertTrue(saw("failed:no such mailbox"));
    }

    @Test
    public void testMailboxCommands() {
        greet();
        handler.create("c", rec);
        assertTrue(lastSent().contains("CREATE \"c\""));
        done("OK");
        handler.delete("c", rec);
        assertTrue(lastSent().contains("DELETE \"c\""));
        done("NO in use");
        assertTrue(saw("mailboxNo:in use"));
        handler.rename("a", "b", rec);
        assertTrue(lastSent().contains("RENAME \"a\" \"b\""));
        done("OK");
        handler.subscribe("s", rec);
        assertTrue(lastSent().contains("SUBSCRIBE \"s\""));
        done("OK");
        handler.unsubscribe("s", rec);
        assertTrue(lastSent().contains("UNSUBSCRIBE \"s\""));
        done("OK");
        int oks = 0;
        for (String e : rec.events) {
            if ("ok".equals(e)) {
                oks++;
            }
        }
        assertEquals(4, oks);
    }

    @Test
    public void testListAndLsubParsing() {
        greet();
        handler.list("", "*", rec);
        lines("* LIST (\\HasChildren) \"/\" \"Archive\"",
                "* LIST NIL");
        done("OK");
        assertTrue(saw("list:\\HasChildren|/|Archive"));
        assertTrue(saw("list:||"));
        assertTrue(saw("listComplete"));

        handler.lsub("", "%", rec);
        assertTrue(lastSent().contains("LSUB \"\" \"%\""));
        line("* LSUB () \".\" \"x\"");
        done("NO denied");
        assertTrue(saw("list:|.|x"));
        assertTrue(saw("authError:denied"));
    }

    @Test
    public void testListLineIgnoredWithoutListCallback() {
        greet();
        handler.noop(new PlainNoop());
        line("* LIST (\\Noselect) \"/\" \"x\"");
        done("OK");
        assertFalse(lastEvent().startsWith("list"));
        assertFalse(lastEvent().startsWith("quota"));
    }

    @Test
    public void testStatusParsing() {
        greet();
        handler.status("INBOX", new String[] {"MESSAGES", "UIDNEXT"}, rec);
        assertTrue(lastSent().contains("STATUS \"INBOX\" (MESSAGES UIDNEXT)"));
        lines("* STATUS \"INBOX\" (MESSAGES 5 RECENT 2 UIDNEXT 9 "
                + "UIDVALIDITY 3 UNSEEN x MAILBOXID (abc) FOO 1 MESSAGES z)",
                "* STATUS noparens");
        done("OK");
        assertTrue(saw("status:INBOX:5:2:9:3:0:abc"));

        handler.status("INBOX", new String[] {"MESSAGES"}, rec);
        done("NO nope");
        assertTrue(saw("authError:nope"));
    }

    @Test
    public void testUnsolicitedStatusReachesListener() {
        greet();
        handler.setMailboxEventListener(rec);
        line("* STATUS \"Other\" (MESSAGES 12 UIDNEXT 40)");
        assertTrue(saw("l:status:Other:12:40"));
    }

    @Test
    public void testNamespace() {
        greet();
        handler.namespace(rec);
        line("* NAMESPACE ((\"\" \"/\")) NIL NIL");
        done("OK");
        assertTrue(saw("namespace:|/"));

        handler.namespace(rec);
        line("* NAMESPACE NIL NIL NIL");
        done("NO denied");
        assertTrue(saw("authError:denied"));
    }

    @Test
    public void testQuota() {
        greet();
        handler.getQuota("", rec);
        lines("* QUOTA \"\" (STORAGE 10 512 MESSAGE 3 100)",
                "* QUOTA noparens");
        done("OK");
        assertTrue(saw("quota::STORAGE:10:512"));
        assertTrue(saw("quota::MESSAGE:3:100"));
        assertTrue(saw("quotaComplete"));

        handler.getQuotaRoot("INBOX", rec);
        line("* QUOTAROOT INBOX \"\" r2");
        done("NO denied");
        assertTrue(saw("quotaRoot:INBOX:,r2"));
        assertTrue(saw("quotaError:denied"));
    }

    @Test
    public void testQuotaLinesIgnoredWithoutQuotaCallback() {
        greet();
        handler.noop(new PlainNoop());
        lines("* QUOTA \"\" (STORAGE 1 2)", "* QUOTAROOT INBOX x");
        done("OK");
        assertFalse(lastEvent().startsWith("list"));
        assertFalse(lastEvent().startsWith("quota"));
    }

    // ── APPEND, IDLE, NOOP ──

    @Test
    public void testAppendWithFlagsDateAndAppendUid() {
        greet();
        handler.append("Sent", new String[] {"\\Seen", "\\Draft"},
                "01-Jan-2026 00:00:00 +0000", 5, rec);
        assertTrue(lastSent().contains(
                "APPEND \"Sent\" (\\Seen \\Draft) \"01-Jan-2026 00:00:00 +0000\" {5}"));
        line("+ go ahead");
        assertTrue(saw("readyForData"));
        handler.writeContent(ByteBuffer.wrap("hello".getBytes(StandardCharsets.US_ASCII)));
        handler.endAppend();
        done("OK [APPENDUID 38505 3955] done");
        assertTrue(saw("appended:38505:3955"));
    }

    @Test
    public void testAppendWithoutFlagsFailureAndBadAppendUid() {
        greet();
        handler.append("Sent", null, null, 3, rec);
        assertTrue(lastSent().endsWith("APPEND \"Sent\" {3}"));
        line("+ ok");
        done("OK [APPENDUID 1] odd");
        assertTrue(saw("appended:0:0"));

        handler.append("Sent", new String[0], null, 3, rec);
        line("+ ok");
        done("NO quota");
        assertTrue(saw("failed:quota"));
    }

    @Test
    public void testWriteContentIgnoredOutsideAppendData() {
        greet();
        int before = endpoint.getSentCommands().size();
        handler.writeContent(ByteBuffer.wrap(new byte[] {1}));
        handler.endAppend();
        assertEquals(before, endpoint.getSentCommands().size());
        handler.onWriteReady(new Runnable() {
            @Override
            public void run() {
                rec.events.add("writeReady");
            }
        });
        assertTrue(saw("writeReady"));
    }

    @Test
    public void testIdleEventsAndDone() {
        greet();
        handler.idle(rec);
        line("+ idling");
        assertTrue(saw("idleStarted"));
        lines("* 7 EXISTS", "* 2 RECENT", "* 4 EXPUNGE",
                "* 5 FETCH (FLAGS (\\Seen))");
        assertTrue(saw("idle:exists:7"));
        assertTrue(saw("idle:recent:2"));
        assertTrue(saw("idle:expunge:4"));
        assertTrue(saw("idle:flags:5:\\Seen"));
        handler.done();
        assertEquals("DONE", lastSent());
        done("OK idle terminated");
        assertTrue(saw("idleComplete"));
    }

    @Test
    public void testUnsolicitedEventsToListenerOutsideIdle() {
        greet();
        handler.setMailboxEventListener(rec);
        lines("* 3 EXISTS", "* 1 RECENT", "* 2 EXPUNGE",
                "* 9 FETCH (FLAGS (\\Flagged))",
                "* 9 FETCH (UID 4)");
        assertTrue(saw("l:exists:3"));
        assertTrue(saw("l:recent:1"));
        assertTrue(saw("l:expunge:2"));
        assertTrue(saw("l:flags:9:\\Flagged"));
    }

    @Test
    public void testUnsolicitedEventsWithoutListenerAreDropped() {
        greet();
        lines("* 3 EXISTS", "* 1 RECENT", "* 2 EXPUNGE",
                "* 9 FETCH (FLAGS (\\Flagged))", "* 2 BOGUS");
        assertFalse(saw("l:exists:3"));
    }

    @Test
    public void testNoopNoStateAndNoopFromSelected() {
        selectInbox();
        handler.noop(rec);
        done("OK noop");
        assertTrue(saw("ok"));
    }

    // ── COMPRESS, ENABLE, NOTIFY, METADATA ──

    @Test
    public void testCompressRejectedAndAlreadyActive() {
        greet();
        handler.compress(rec);
        done("NO not supported");
        assertTrue(saw("authError:not supported"));
        assertFalse(handler.isDeflateActive());

        handler.compress(rec);
        done("OK active");
        assertTrue(handler.isDeflateActive());
        handler.compress(rec);
        assertTrue(lastEvent().startsWith("authError:"));
    }

    @Test
    public void testEnable() {
        greet();
        handler.enable(null, rec);
        assertTrue(lastEvent().startsWith("authError:"));
        handler.enable(new String[0], rec);
        assertTrue(lastEvent().startsWith("authError:"));

        handler.enable(new String[] {"UTF8=ACCEPT", "NOTIFY"}, rec);
        assertTrue(lastSent().endsWith("ENABLE UTF8=ACCEPT NOTIFY"));
        line("* ENABLED UTF8=ACCEPT NOTIFY");
        done("OK");
        assertTrue(saw("enabled:UTF8=ACCEPT,NOTIFY"));
        assertTrue(handler.isUtf8AcceptEnabled());
        assertTrue(handler.isNotifyEnabled());

        handler.enable(new String[] {"CONDSTORE"}, rec);
        line("* ENABLED CONDSTORE");
        done("NO refused");
        assertTrue(saw("authError:refused"));
    }

    @Test
    public void testEnabledIgnoredOutsideEnable() {
        greet();
        handler.noop(rec);
        line("* ENABLED UTF8=ACCEPT");
        done("OK");
        assertFalse(handler.isUtf8AcceptEnabled());
    }

    @Test
    public void testNotify() {
        greet();
        handler.notifySet(null, rec);
        assertTrue(lastEvent().startsWith("notifyError:"));
        handler.notifySet("   ", rec);
        assertTrue(lastEvent().startsWith("notifyError:"));
        handler.notifySet("SET (selected (MessageNew))", rec);
        assertTrue(lastSent().endsWith("NOTIFY SET (selected (MessageNew))"));
        done("OK");
        assertTrue(saw("notifyComplete"));
        handler.notifyNone(rec);
        assertTrue(lastSent().endsWith("NOTIFY NONE"));
        done("NO unsupported");
        assertTrue(saw("notifyError:unsupported"));
    }

    @Test
    public void testGetMetadata() {
        greet();
        handler.getMetadata(null, rec);
        assertTrue(lastEvent().startsWith("metadataError:"));
        handler.getMetadata(" ", rec);
        assertTrue(lastEvent().startsWith("metadataError:"));
        handler.getMetadata("INBOX (/private/comment)", rec);
        lines("* METADATA \"INBOX\" (/private/comment \"hello world\" "
                + "/shared/x NIL \"quoted\" \"v\\\"q\")",
                "* METADATA noparens");
        done("OK");
        assertEquals("INBOX", rec.metaMailbox);
        assertEquals("hello world", rec.metaEntries.get("/private/comment"));
        assertEquals("NIL", rec.metaEntries.get("/shared/x"));
        assertTrue(rec.metaEntries.containsKey("quoted"));

        handler.getMetadata("INBOX (/x)", rec);
        done("NO denied");
        assertTrue(saw("metadataError:denied"));
    }

    @Test
    public void testMetadataLineIgnoredOutsideGetMetadata() {
        greet();
        handler.noop(rec);
        line("* METADATA \"INBOX\" (/a b)");
        done("OK");
        assertNull(rec.metaMailbox);
    }

    @Test
    public void testSetMetadata() {
        greet();
        handler.setMetadata(null, rec);
        assertTrue(lastEvent().startsWith("metadataError:"));
        handler.setMetadata("INBOX (/private/x \"v\")", rec);
        assertTrue(lastSent().contains("SETMETADATA INBOX"));
        done("OK");
        assertTrue(saw("metadataSet"));
        handler.setMetadata("INBOX (/private/x NIL)", rec);
        done("NO denied");
        assertTrue(saw("metadataError:denied"));
    }

    // ── Selected state ──

    @Test
    public void testCloseAndUnselect() {
        selectInbox();
        handler.close(rec);
        assertTrue(lastSent().endsWith("CLOSE"));
        done("OK");
        assertTrue(saw("closed"));
        handler.unselect(rec);
        assertTrue(lastSent().endsWith("UNSELECT"));
        done("OK");
    }

    @Test
    public void testExpungeSuccessAndFailure() {
        selectInbox();
        handler.expunge(rec);
        lines("* 3 EXPUNGE", "* 3 EXPUNGE");
        done("OK");
        assertTrue(saw("expunged:3"));
        assertTrue(saw("expungeComplete"));
        handler.expunge(rec);
        done("NO read-only");
        assertTrue(saw("selError:read-only"));
    }

    @Test
    public void testSearchSortAndThread() {
        selectInbox();
        handler.search("ALL", rec);
        lines("* SEARCH 1 2 x 3");
        done("OK");
        assertTrue(saw("search:1,2,3"));

        handler.uidSearch("UNSEEN", rec);
        assertTrue(lastSent().contains("UID SEARCH UNSEEN"));
        line("* SEARCH");
        done("OK");
        assertTrue(saw("search:"));

        handler.sort("(DATE) UTF-8 ALL", rec);
        assertTrue(lastSent().contains("SORT (DATE)"));
        line("* SORT 3 1 2");
        done("OK");
        assertTrue(saw("search:3,1,2"));

        handler.uidSort("(DATE) UTF-8 ALL", rec);
        assertTrue(lastSent().contains("UID SORT"));
        line("* SORT");
        done("OK");
        assertTrue(saw("search:"));

        handler.search("BAD", rec);
        line("* SEARCH 5");
        done("BAD syntax");
        assertTrue(saw("selError:syntax"));
    }

    @Test
    public void testThread() {
        selectInbox();
        handler.thread("REFERENCES UTF-8 ALL", rec);
        assertTrue(lastSent().contains("THREAD REFERENCES"));
        line("* THREAD (1 2)(3)");
        done("OK");
        assertTrue(saw("thread:(1 2)(3)"));

        handler.uidThread("ORDEREDSUBJECT UTF-8 ALL", rec);
        assertTrue(lastSent().contains("UID THREAD"));
        line("* THREAD");
        done("OK");
        assertTrue(saw("thread:"));

        handler.thread("X", rec);
        done("NO unsupported");
        assertTrue(saw("selError:unsupported"));
    }

    @Test
    public void testFetchParsesAllItems() {
        selectInbox();
        handler.uidFetch("1:*", "(FLAGS UID ENVELOPE)", rec);
        assertTrue(lastSent().contains("UID FETCH 1:* (FLAGS UID ENVELOPE)"));
        line("* 1 FETCH (UID 42 RFC822.SIZE 1024 FLAGS (\\Seen \\Answered) "
                + "INTERNALDATE \"01-Jan-2026 10:00:00 +0000\" "
                + "ENVELOPE (\"Mon, 1 Jan 2026\" \"Subject\" ((\"A\" NIL \"a\" \"x.org\")) NIL NIL "
                + "NIL NIL NIL NIL \"<id@x>\") EMAILID (M42))");
        done("OK");
        FetchData fd = rec.lastFetch;
        assertNotNull(fd);
        assertEquals(42L, fd.getUid());
        assertEquals(1024L, fd.getSize());
        assertEquals(2, fd.getFlags().length);
        assertEquals("01-Jan-2026 10:00:00 +0000", fd.getInternalDate());
        assertEquals("M42", fd.getEmailId());
        FetchData.Envelope env = fd.getEnvelope();
        assertEquals("Mon, 1 Jan 2026", env.getDate());
        assertEquals("Subject", env.getSubject());
        assertNotNull(env.getFrom());
        assertNull(env.getSender());
        assertEquals("<id@x>", env.getMessageId());
        assertTrue(saw("fetchComplete"));
    }

    @Test
    public void testFetchBadNumbersAndEnvelopeEdges() {
        selectInbox();
        handler.fetch("1", "ALL", rec);
        line("* 1 FETCH (UID zz RFC822.SIZE yy INTERNALDATE broken EMAILID broken "
                + "ENVELOPE broken)");
        assertEquals(0L, rec.lastFetch.getUid());
        assertEquals(0L, rec.lastFetch.getSize());
        assertNull(rec.lastFetch.getInternalDate());
        assertNull(rec.lastFetch.getEmailId());
        assertNotNull(rec.lastFetch.getEnvelope());
        done("NO failed");
        assertTrue(saw("selError:failed"));
    }

    @Test
    public void testFetchFullEnvelopeFields() {
        selectInbox();
        handler.fetch("1", "ENVELOPE", rec);
        line("* 1 FETCH (ENVELOPE (\"d\" \"s\" \"f\" \"se\" \"r\" \"t\" \"c\" \"b\" "
                + "\"irt\" \"mid\"))");
        FetchData.Envelope env = rec.lastFetch.getEnvelope();
        assertEquals("f", env.getFrom());
        assertEquals("se", env.getSender());
        assertEquals("r", env.getReplyTo());
        assertEquals("t", env.getTo());
        assertEquals("c", env.getCc());
        assertEquals("b", env.getBcc());
        assertEquals("irt", env.getInReplyTo());
        assertEquals("mid", env.getMessageId());
    }

    @Test
    public void testFetchLiteralWithPauseAndResume() {
        selectInbox();
        rec.pauseOnLiteral = true;
        handler.fetch("1", "BODY[TEXT]", rec);
        byte[] data = "* 1 FETCH (BODY[TEXT] {5}\r\nhello)\r\n"
                .getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(data));
        assertEquals(1, endpoint.pauses);
        assertNotNull(rec.resume);
        rec.resume.run();
        assertEquals(1, endpoint.resumes);
        assertTrue(saw("literalEnd:1"));
        assertEquals("TEXT", rec.literalSection);
    }

    @Test
    public void testFetchTwoLiteralsSplitAcrossReceives() {
        selectInbox();
        handler.fetch("1", "(BODY[1] BODY[2])", rec);
        String wire = "* 1 FETCH (BODY[1] {3}\r\nabc BODY[2] {2}\r\nde UID 8)\r\n";
        byte[] all = wire.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer netIn = ByteBuffer.allocate(4096);
        for (int i = 0; i < all.length; i++) {
            netIn.put(all[i]);
            netIn.flip();
            handler.receive(netIn);
            netIn.compact();
        }
        done("OK");
        assertEquals("abcde", rec.literalBytes.toString());
        assertEquals(2, rec.literalEnds);
        assertEquals(8L, rec.lastFetch.getUid());
        assertTrue(saw("fetchComplete"));
    }

    @Test
    public void testStoreResponsesAndFailure() {
        selectInbox();
        handler.store("1", "+FLAGS", new String[] {"\\Seen", "\\Flagged"}, rec);
        assertTrue(lastSent().contains("STORE 1 +FLAGS (\\Seen \\Flagged)"));
        lines("* 1 FETCH (FLAGS (\\Seen \\Flagged))", "* 1 FETCH (UID 3)");
        done("OK");
        assertTrue(saw("store:1:\\Seen,\\Flagged"));
        assertTrue(saw("storeComplete"));

        handler.uidStore("5", "-FLAGS", new String[] {"\\Seen"}, rec);
        assertTrue(lastSent().contains("UID STORE 5 -FLAGS (\\Seen)"));
        done("NO denied");
        assertTrue(saw("selError:denied"));
    }

    @Test
    public void testCopyAndMove() {
        selectInbox();
        handler.copy("1:2", "Archive", rec);
        assertTrue(lastSent().contains("COPY 1:2 \"Archive\""));
        done("OK [COPYUID 7 1:2 11:12] copied");
        assertTrue(saw("copy:7:1:2:11:12"));

        handler.uidCopy("1", "Archive", rec);
        assertTrue(lastSent().contains("UID COPY 1"));
        done("OK [COPYUID zz 1 2] odd");
        assertTrue(saw("copy:0:1:2"));

        handler.move("1", "Trash", rec);
        assertTrue(lastSent().contains("MOVE 1 \"Trash\""));
        done("OK moved");
        assertTrue(saw("copy:0:null:null"));

        handler.uidMove("1", "Trash", rec);
        assertTrue(lastSent().contains("UID MOVE 1"));
        done("NO nope");
        assertTrue(saw("authError:nope"));
    }

    // ── Lifecycle, errors and malformed input ──

    @Test
    public void testByeWithCallbackFiresServiceClosing() {
        greet();
        handler.noop(rec);
        line("* BYE shutting down");
        assertTrue(saw("closing:shutting down"));
        assertFalse(handler.isOpen());
    }

    @Test
    public void testByeDuringLogoutIsQuiet() {
        greet();
        handler.logout();
        line("* BYE bye");
        assertFalse(saw("closing:bye"));
        done("OK logged out");
        assertFalse(handler.isOpen());
    }

    @Test
    public void testTransportErrorAndDisconnect() {
        greet();
        handler.error(new IOException("boom"));
        assertTrue(saw("error:boom"));
        assertFalse(handler.isOpen());
        handler.disconnected();
        assertTrue(saw("disconnected"));
    }

    @Test
    public void testDisconnectClosesDeflate() {
        greet();
        handler.compress(rec);
        done("OK active");
        assertTrue(handler.isDeflateActive());
        handler.disconnected();
        assertFalse(handler.isDeflateActive());
    }

    @Test
    public void testCommandOnClosedConnectionReportsError() {
        greet();
        handler.close();
        handler.close();
        handler.noop(rec);
        assertTrue(saw("error:Not connected"));
        handler.done();
        int errors = 0;
        for (String e : rec.events) {
            if ("error:Not connected".equals(e)) {
                errors++;
            }
        }
        assertEquals(2, errors);
    }

    @Test
    public void testCorruptDeflateStreamClosesConnection() {
        greet();
        handler.compress(rec);
        done("OK active");
        byte[] garbage = new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};
        handler.receive(ByteBuffer.wrap(garbage));
        assertTrue(saw("error:DEFLATE decompression failed"));
        assertFalse(handler.isOpen());
    }

    @Test
    public void testTaggedMismatchAndUnexpectedResponsesAreIgnored() {
        greet();
        handler.noop(rec);
        line("zzz OK stray");
        assertFalse(saw("ok"));
        line("+ unexpected continuation");
        line("garbage");
        line("* WHATEVER 1 2 3");
        lines("* OK");
        done("OK");
        assertTrue(saw("ok"));
        line(tag() + " OK again");
        line("a99 OK late");
    }

    @Test
    public void testTaggedResponseInUnexpectedState() {
        greet();
        handler.noop(rec);
        String t = tag();
        handler.close();
        line(t + " OK late");
        assertFalse(saw("ok"));
    }

    @Test
    public void testBadContinuationBase64ReportsError() {
        greet();
        handler.authenticate("PLAIN", null, rec);
        line("+ !!!notbase64");
        assertTrue(lastEvent().startsWith("error:"));
    }

    @Test
    public void testUnterminatedQuotedQuotarootDoesNotRaiseError() {
        greet();
        handler.getQuotaRoot("INBOX", rec);
        line("* QUOTAROOT INBOX \"unterminated");
        for (String e : rec.events) {
            assertFalse(e, e.startsWith("error:"));
        }
        assertTrue(saw("quotaRoot:INBOX:\"unterminated"));
    }

    @Test
    public void testQuoteStringAndParseFlagsHelpers() {
        assertEquals("\"\"", ImapClientProtocolHandler.quoteString(null));
        assertEquals("\"ab\"", ImapClientProtocolHandler.quoteString("ab"));
        assertEquals("\"a\\\\b\"", ImapClientProtocolHandler.quoteString("a\\b"));
        assertEquals("\"ab\"", ImapClientProtocolHandler.quoteString("a\u0001b"));
        assertEquals(0, ImapClientProtocolHandler.parseFlags("()").length);
        assertEquals(2, ImapClientProtocolHandler.parseFlags("(\\a \\b)").length);
        assertEquals(1, ImapClientProtocolHandler.parseFlags("\\a").length);
    }

    @Test
    public void testHandlerRejectsNullGreetingHandler() {
        try {
            new ImapClientProtocolHandler(null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            assertEquals("handler", expected.getMessage());
        }
    }

    @Test
    public void testTokenTooLongIsLoggedOnly() {
        greet();
        handler.tokenTooLong();
        assertTrue(handler.isOpen());
    }

    // ── Endpoint and handler fixtures ──

    static class PlainNoop implements NoopReplyHandler {
        @Override
        public void handleOk(ClientAuthenticatedState session) {
        }

        @Override
        public void handleServiceClosing(String message) {
        }
    }

    static class PausingEndpoint extends IMAPClientProtocolHandlerTest.StubEndpoint {
        int pauses;
        int resumes;
        boolean failStartTls;

        @Override
        public void startTLS() throws IOException {
            if (failStartTls) {
                throw new IOException("no tls");
            }
            super.startTLS();
            startTLSCalled = true;
        }

        @Override
        public void pauseRead() {
            pauses++;
        }

        @Override
        public void resumeRead() {
            resumes++;
        }
    }

    static class Rec implements RemoteGreeting, CapabilityReplyHandler,
            LoginReplyHandler, AuthReplyHandler, AuthAbortHandler,
            StarttlsReplyHandler, SelectReplyHandler, MailboxReplyHandler,
            ListReplyHandler, StatusReplyHandler, NamespaceReplyHandler,
            QuotaReplyHandler, AppendReplyHandler, IdleEventHandler,
            NoopReplyHandler, CompressReplyHandler, EnableReplyHandler,
            NotifyReplyHandler, MetadataReplyHandler, CloseReplyHandler,
            ExpungeReplyHandler, SearchReplyHandler, ThreadReplyHandler,
            FetchReplyHandler, StoreReplyHandler, CopyReplyHandler,
            MailboxEventListener {

        final List<String> events = new ArrayList<String>();
        MailboxInfo info;
        FetchData lastFetch;
        Runnable resume;
        boolean pauseOnLiteral;
        String literalSection;
        final StringBuilder literalBytes = new StringBuilder();
        int literalEnds;
        String metaMailbox;
        Map<String, String> metaEntries;

        private String join(List<String> items) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(items.get(i));
            }
            return sb.toString();
        }

        private String join(String[] items) {
            List<String> list = new ArrayList<String>();
            for (String s : items) {
                list.add(s);
            }
            return join(list);
        }

        // ClientHandler / RemoteGreeting
        @Override public void onConnected(Endpoint e) { }
        @Override public void onError(Exception c) { events.add("error:" + c.getMessage()); }
        @Override public void onDisconnected() { events.add("disconnected"); }
        @Override public void onSecurityEstablished(SecurityInfo i) { events.add("secured"); }
        @Override public void handleGreeting(ClientNotAuthenticatedState a, String g, List<String> c) { events.add("greeting"); }
        @Override public void handlePreAuthenticated(ClientAuthenticatedState a, String g) { events.add("preauth:" + g); }
        @Override public void handleServiceUnavailable(String m) { events.add("unavailable:" + m); }
        @Override public void handleServiceClosing(String m) { events.add("closing:" + m); }

        // Capability / login / auth
        @Override public void handleCapabilities(ClientNotAuthenticatedState a, List<String> c) { events.add("caps:" + join(c)); }
        @Override public void handleError(ClientNotAuthenticatedState a, String m) { events.add("capError:" + m); }
        @Override public void handleAuthenticated(ClientAuthenticatedState s, List<String> c) { events.add("authenticated:" + join(c)); }
        @Override public void handleAuthFailed(ClientNotAuthenticatedState a, String m) { events.add("authFailed:" + m); }
        @Override public void handleAuthSuccess(ClientAuthenticatedState s, List<String> c) { events.add("authSuccess:" + join(c)); }
        @Override public void handleChallenge(byte[] c, ClientAuthExchange x) { events.add("challenge:" + c.length); }
        @Override public void handleAborted(ClientNotAuthenticatedState a) { events.add("aborted"); }

        // STARTTLS
        @Override public void handleTlsEstablished(ClientPostStarttls p) { events.add("tlsEstablished"); }
        @Override public void handleTlsUnavailable(ClientNotAuthenticatedState a) { events.add("tlsUnavailable"); }
        @Override public void handlePermanentFailure(String m) { events.add("permanent:" + m); }

        // SELECT
        @Override public void handleSelected(ClientSelectedState s, MailboxInfo i) { info = i; events.add("selected"); }
        @Override public void handleFailed(ClientAuthenticatedState s, String m) { events.add("failed:" + m); }

        // Mailbox / noop / compress (shared signature)
        @Override public void handleOk(ClientAuthenticatedState s) { events.add("ok"); }
        @Override public void handleNo(ClientAuthenticatedState s, String m) { events.add("mailboxNo:" + m); }

        // Errors on authenticated session (list, status, namespace, compress, enable, copy)
        @Override public void handleError(ClientAuthenticatedState s, String m) { events.add("authError:" + m); }

        // List
        @Override public void handleListEntry(String a, String d, String n) { events.add("list:" + a + "|" + d + "|" + n); }
        @Override public void handleListComplete(ClientAuthenticatedState s) { events.add("listComplete"); }

        // Status / namespace
        @Override public void handleStatus(ClientAuthenticatedState s, String mb, int m, int r, long un, long uv, int u, String id) {
            events.add("status:" + mb + ":" + m + ":" + r + ":" + un + ":" + uv + ":" + u + ":" + id);
        }
        @Override public void handleNamespace(ClientAuthenticatedState s, String p, String d) { events.add("namespace:" + p + "|" + d); }

        // Quota
        @Override public void handleQuota(String q, String n, long u, long l) { events.add("quota:" + q + ":" + n + ":" + u + ":" + l); }
        @Override public void handleQuotaRoot(String m, String[] r) { events.add("quotaRoot:" + m + ":" + join(r)); }
        @Override public void handleQuotaComplete() { events.add("quotaComplete"); }
        @Override public void handleQuotaError(String m) { events.add("quotaError:" + m); }

        // Append
        @Override public void handleReadyForData(ClientAppendState a) { events.add("readyForData"); }
        @Override public void handleAppendComplete(ClientAuthenticatedState s, long v, long u) { events.add("appended:" + v + ":" + u); }

        // Idle
        @Override public void handleIdleStarted(ClientIdleState i) { events.add("idleStarted"); }
        @Override public void handleExists(int c) { events.add("idle:exists:" + c); }
        @Override public void handleRecent(int c) { events.add("idle:recent:" + c); }
        @Override public void handleExpunge(int n) { events.add("idle:expunge:" + n); }
        @Override public void handleFlagsUpdate(int n, String[] f) { events.add("idle:flags:" + n + ":" + join(f)); }
        @Override public void handleIdleComplete(ClientAuthenticatedState s) { events.add("idleComplete"); }

        // Enable
        @Override public void handleEnabled(ClientAuthenticatedState s, List<String> e) { events.add("enabled:" + join(e)); }

        // Notify
        @Override public void handleNotifyComplete(ClientAuthenticatedState s) { events.add("notifyComplete"); }
        @Override public void handleNotifyError(ClientAuthenticatedState s, String m) { events.add("notifyError:" + m); }

        // Metadata
        @Override public void handleGetMetadata(ClientAuthenticatedState s, String mb, Map<String, String> e) { metaMailbox = mb; metaEntries = e; events.add("metadataGot"); }
        @Override public void handleSetMetadata(ClientAuthenticatedState s) { events.add("metadataSet"); }
        @Override public void handleMetadataError(ClientAuthenticatedState s, String m) { events.add("metadataError:" + m); }

        // Close / expunge / search / thread / fetch / store / copy
        @Override public void handleClosed(ClientAuthenticatedState s) { events.add("closed"); }
        @Override public void handleExpunged(int n) { events.add("expunged:" + n); }
        @Override public void handleExpungeComplete(ClientSelectedState s) { events.add("expungeComplete"); }
        @Override public void handleError(ClientSelectedState s, String m) { events.add("selError:" + m); }
        @Override public void handleSearchResults(ClientSelectedState s, long[] r) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < r.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(r[i]);
            }
            events.add("search:" + sb);
        }
        @Override public void handleThread(ClientSelectedState s, String t) { events.add("thread:" + t); }
        @Override public void handleFetchResponse(int n, FetchData d) { lastFetch = d; events.add("fetch:" + n); }
        @Override public void handleFetchLiteralBegin(int n, String section, long size) { literalSection = section; }
        @Override public void handleFetchLiteralContent(ByteBuffer c) {
            byte[] b = new byte[c.remaining()];
            c.get(b);
            literalBytes.append(new String(b, StandardCharsets.US_ASCII));
        }
        @Override public void handleFetchLiteralEnd(int n) { literalEnds++; events.add("literalEnd:" + n); }
        @Override public void handleFetchComplete(ClientSelectedState s) { events.add("fetchComplete"); }
        @Override public boolean wantsPause() { return pauseOnLiteral; }
        @Override public void setResumeCallback(Runnable r) { resume = r; }
        @Override public void handleStoreResponse(int n, String[] f) { events.add("store:" + n + ":" + join(f)); }
        @Override public void handleStoreComplete(ClientSelectedState s) { events.add("storeComplete"); }
        @Override public void handleCopyComplete(ClientAuthenticatedState s, long v, String src, String dst) { events.add("copy:" + v + ":" + src + ":" + dst); }

        // MailboxEventListener
        @Override public void onExists(int c) { events.add("l:exists:" + c); }
        @Override public void onRecent(int c) { events.add("l:recent:" + c); }
        @Override public void onExpunge(int n) { events.add("l:expunge:" + n); }
        @Override public void onFlagsUpdate(int n, String[] f) { events.add("l:flags:" + n + ":" + join(f)); }
        @Override public void onMailboxStatus(String m, int n, long u) { events.add("l:status:" + m + ":" + n + ":" + u); }
    }
}
