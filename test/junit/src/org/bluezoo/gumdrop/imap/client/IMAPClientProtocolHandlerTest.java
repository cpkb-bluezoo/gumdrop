/*
 * IMAPClientProtocolHandlerTest.java
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

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.imap.client.handler.ClientAppendState;
import org.bluezoo.gumdrop.imap.client.handler.ClientAuthExchange;
import org.bluezoo.gumdrop.imap.client.handler.ClientAuthenticatedState;
import org.bluezoo.gumdrop.imap.client.handler.ClientIdleState;
import org.bluezoo.gumdrop.imap.client.handler.ClientNotAuthenticatedState;
import org.bluezoo.gumdrop.imap.client.handler.ClientPostStarttls;
import org.bluezoo.gumdrop.imap.client.handler.ClientSelectedState;
import org.bluezoo.gumdrop.imap.client.handler.MailboxEventListener;
import org.bluezoo.gumdrop.imap.client.handler.ServerAppendReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerAuthAbortHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerAuthReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerCapabilityReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerCloseReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerCopyReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerExpungeReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerFetchReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerGreeting;
import org.bluezoo.gumdrop.imap.client.handler.ServerIdleEventHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerListReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerLoginReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerMailboxReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerNamespaceReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerNoopReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerSearchReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerSelectReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerStarttlsReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerStatusReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerStoreReplyHandler;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link IMAPClientProtocolHandler}.
 */
public class IMAPClientProtocolHandlerTest {

    private IMAPClientProtocolHandler handler;
    private RecordingGreetingHandler greetingHandler;
    private StubEndpoint endpoint;

    @Before
    public void setUp() {
        greetingHandler = new RecordingGreetingHandler();
        handler = new IMAPClientProtocolHandler(greetingHandler);
        endpoint = new StubEndpoint();
        handler.connected(endpoint);
    }

    private void receiveResponse(String line) {
        byte[] data = (line + "\r\n").getBytes(
                StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(data));
    }

    private void receiveMultipleLines(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append("\r\n");
        }
        handler.receive(ByteBuffer.wrap(
                sb.toString().getBytes(StandardCharsets.US_ASCII)));
    }

    private String lastSentLine() {
        List<String> cmds = endpoint.getSentCommands();
        assertFalse("No commands sent", cmds.isEmpty());
        return cmds.get(cmds.size() - 1);
    }

    private String lastSentCommand() {
        String line = lastSentLine();
        int sp = line.indexOf(' ');
        if (sp > 0) {
            return line.substring(sp + 1);
        }
        return line;
    }

    private String lastSentTag() {
        String line = lastSentLine();
        int sp = line.indexOf(' ');
        if (sp > 0) {
            return line.substring(0, sp);
        }
        return line;
    }

    // ── Greeting tests ──

    @Test
    public void testGreetingOk() {
        receiveResponse(
                "* OK [CAPABILITY IMAP4rev1 STARTTLS] Welcome");

        assertTrue(greetingHandler.greetingReceived);
        assertNotNull(greetingHandler.authState);
        assertTrue(greetingHandler.preAuthCapabilities.size() > 0);
        assertTrue(greetingHandler.preAuthCapabilities
                .contains("IMAP4rev1"));
    }

    @Test
    public void testGreetingOkNoCapability() {
        receiveResponse("* OK IMAP server ready");

        assertTrue(greetingHandler.greetingReceived);
        assertNotNull(greetingHandler.authState);
        assertTrue(greetingHandler.preAuthCapabilities.isEmpty());
    }

    @Test
    public void testGreetingBye() {
        receiveResponse("* BYE Server shutting down");

        assertTrue(greetingHandler.serviceUnavailable);
    }

    // ── CAPABILITY tests ──

    @Test
    public void testCapabilitySuccess() {
        receiveResponse("* OK IMAP ready");

        RecordingCapabilityHandler capHandler =
                new RecordingCapabilityHandler();
        greetingHandler.authState.capability(capHandler);
        String tag = lastSentTag();
        assertEquals("CAPABILITY", lastSentCommand());

        receiveMultipleLines(
                "* CAPABILITY IMAP4rev1 STARTTLS AUTH=PLAIN IDLE",
                tag + " OK CAPABILITY completed");

        assertTrue(capHandler.received);
        assertTrue(capHandler.capabilities.contains("IMAP4rev1"));
        assertTrue(capHandler.capabilities.contains("STARTTLS"));
        assertTrue(capHandler.capabilities.contains("AUTH=PLAIN"));
        assertTrue(capHandler.capabilities.contains("IDLE"));
    }

    @Test
    public void testCapabilityError() {
        receiveResponse("* OK IMAP ready");

        RecordingCapabilityHandler capHandler =
                new RecordingCapabilityHandler();
        greetingHandler.authState.capability(capHandler);
        String tag = lastSentTag();

        receiveResponse(tag + " BAD Command not recognized");

        assertTrue(capHandler.errorReceived);
    }

    // ── LOGIN tests ──

    @Test
    public void testLoginSuccess() {
        receiveResponse("* OK IMAP ready");

        RecordingLoginHandler loginHandler =
                new RecordingLoginHandler();
        greetingHandler.authState.login("alice", "secret",
                loginHandler);
        String tag = lastSentTag();
        assertTrue(lastSentCommand().startsWith("LOGIN "));

        receiveResponse(tag + " OK LOGIN completed");

        assertTrue(loginHandler.authenticated);
        assertNotNull(loginHandler.session);
    }

    @Test
    public void testLoginWithCapabilities() {
        receiveResponse("* OK IMAP ready");

        RecordingLoginHandler loginHandler =
                new RecordingLoginHandler();
        greetingHandler.authState.login("alice", "secret",
                loginHandler);
        String tag = lastSentTag();

        receiveResponse(tag
                + " OK [CAPABILITY IMAP4rev1 IDLE] Logged in");

        assertTrue(loginHandler.authenticated);
        assertTrue(loginHandler.capabilities.contains("IMAP4rev1"));
        assertTrue(loginHandler.capabilities.contains("IDLE"));
    }

    @Test
    public void testLoginFailed() {
        receiveResponse("* OK IMAP ready");

        RecordingLoginHandler loginHandler =
                new RecordingLoginHandler();
        greetingHandler.authState.login("alice", "wrong",
                loginHandler);
        String tag = lastSentTag();

        receiveResponse(tag + " NO Invalid credentials");

        assertTrue(loginHandler.authFailed);
        assertEquals("Invalid credentials",
                loginHandler.authFailedMessage);
    }

    // ── AUTHENTICATE (SASL) tests ──

    @Test
    public void testAuthPlainSuccess() {
        receiveResponse("* OK IMAP ready");

        byte[] creds = "\0alice\0secret".getBytes(
                StandardCharsets.US_ASCII);
        RecordingAuthHandler authHandler =
                new RecordingAuthHandler();
        greetingHandler.authState.authenticate("PLAIN", creds,
                authHandler);
        String tag = lastSentTag();
        assertTrue(lastSentCommand().startsWith("AUTHENTICATE PLAIN"));

        receiveResponse(tag + " OK AUTHENTICATE completed");

        assertTrue(authHandler.authSuccess);
        assertNotNull(authHandler.session);
    }

    @Test
    public void testAuthChallengeResponse() {
        receiveResponse("* OK IMAP ready");

        RecordingAuthHandler authHandler =
                new RecordingAuthHandler();
        greetingHandler.authState.authenticate("LOGIN", null,
                authHandler);

        String challenge = Base64.getEncoder().encodeToString(
                "Username:".getBytes(StandardCharsets.US_ASCII));
        receiveResponse("+ " + challenge);

        assertTrue(authHandler.challengeReceived);
        assertArrayEquals(
                "Username:".getBytes(StandardCharsets.US_ASCII),
                authHandler.challenge);
        assertNotNull(authHandler.authExchange);

        authHandler.reset();
        authHandler.authExchange.respond(
                "alice".getBytes(StandardCharsets.US_ASCII),
                authHandler);

        String tag = lastSentTag();
        String passwdChallenge = Base64.getEncoder().encodeToString(
                "Password:".getBytes(StandardCharsets.US_ASCII));
        receiveResponse("+ " + passwdChallenge);

        assertTrue(authHandler.challengeReceived);

        authHandler.reset();
        authHandler.authExchange.respond(
                "secret".getBytes(StandardCharsets.US_ASCII),
                authHandler);

        receiveResponse(
                endpoint.getSentCommands().get(0)
                        .substring(0,
                                endpoint.getSentCommands().get(0)
                                        .indexOf(' '))
                + " OK AUTHENTICATE completed");

        assertTrue(authHandler.authSuccess);
    }

    @Test
    public void testAuthFailed() {
        receiveResponse("* OK IMAP ready");

        byte[] creds = "\0alice\0wrong".getBytes(
                StandardCharsets.US_ASCII);
        RecordingAuthHandler authHandler =
                new RecordingAuthHandler();
        greetingHandler.authState.authenticate("PLAIN", creds,
                authHandler);
        String tag = lastSentTag();

        receiveResponse(tag + " NO Authentication failed");

        assertTrue(authHandler.authFailed);
    }

    @Test
    public void testAuthAbort() {
        receiveResponse("* OK IMAP ready");

        RecordingAuthHandler authHandler =
                new RecordingAuthHandler();
        greetingHandler.authState.authenticate("LOGIN", null,
                authHandler);

        String challenge = Base64.getEncoder().encodeToString(
                "Username:".getBytes(StandardCharsets.US_ASCII));
        receiveResponse("+ " + challenge);

        RecordingAuthAbortHandler abortHandler =
                new RecordingAuthAbortHandler();
        authHandler.authExchange.abort(abortHandler);
        assertEquals("*", lastSentLine());

        String tag = endpoint.getSentCommands().get(0)
                .substring(0,
                        endpoint.getSentCommands().get(0)
                                .indexOf(' '));
        receiveResponse(tag + " BAD AUTHENTICATE aborted");

        assertTrue(abortHandler.aborted);
    }

    // ── STARTTLS tests ──

    @Test
    public void testStarttlsSuccess() {
        receiveResponse("* OK IMAP ready");

        RecordingStarttlsHandler tlsHandler =
                new RecordingStarttlsHandler();
        greetingHandler.authState.starttls(tlsHandler);
        String tag = lastSentTag();
        assertEquals("STARTTLS", lastSentCommand());

        receiveResponse(tag + " OK Begin TLS negotiation");
        assertTrue(endpoint.startTLSCalled);

        handler.securityEstablished(new StubSecurityInfo());

        assertTrue(tlsHandler.tlsEstablished);
        assertNotNull(tlsHandler.postTls);
    }

    @Test
    public void testStarttlsRejected() {
        receiveResponse("* OK IMAP ready");

        RecordingStarttlsHandler tlsHandler =
                new RecordingStarttlsHandler();
        greetingHandler.authState.starttls(tlsHandler);
        String tag = lastSentTag();

        receiveResponse(tag + " NO TLS not available");

        assertTrue(tlsHandler.tlsUnavailable);
    }

    // ── SELECT tests ──

    @Test
    public void testSelectSuccess() {
        enterAuthenticatedState();

        RecordingSelectHandler selectHandler =
                new RecordingSelectHandler();
        greetingHandler.session.select("INBOX", selectHandler);
        String tag = lastSentTag();

        receiveMultipleLines(
                "* 172 EXISTS",
                "* 1 RECENT",
                "* FLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft)",
                "* OK [PERMANENTFLAGS (\\Deleted \\Seen \\*)] Limited",
                "* OK [UIDVALIDITY 3857529045] UIDs valid",
                "* OK [UIDNEXT 4392] Predicted next UID",
                "* OK [UNSEEN 12] Message 12 is first unseen",
                tag + " OK [READ-WRITE] SELECT completed");

        assertTrue(selectHandler.selected);
        assertNotNull(selectHandler.selectedState);
        MailboxInfo info = selectHandler.mailboxInfo;
        assertNotNull(info);
        assertEquals(172, info.getExists());
        assertEquals(1, info.getRecent());
        assertEquals(3857529045L, info.getUidValidity());
        assertEquals(4392L, info.getUidNext());
        assertEquals(12, info.getUnseen());
        assertTrue(info.isReadWrite());
        assertNotNull(info.getFlags());
        assertTrue(info.getFlags().length > 0);
        assertNotNull(info.getPermanentFlags());
    }

    @Test
    public void testSelectFailed() {
        enterAuthenticatedState();

        RecordingSelectHandler selectHandler =
                new RecordingSelectHandler();
        greetingHandler.session.select("NONEXISTENT", selectHandler);
        String tag = lastSentTag();

        receiveResponse(tag + " NO Mailbox does not exist");

        assertTrue(selectHandler.failed);
        assertEquals("Mailbox does not exist",
                selectHandler.failedMessage);
    }

    @Test
    public void testExamineSuccess() {
        enterAuthenticatedState();

        RecordingSelectHandler selectHandler =
                new RecordingSelectHandler();
        greetingHandler.session.examine("INBOX", selectHandler);
        String tag = lastSentTag();

        receiveMultipleLines(
                "* 17 EXISTS",
                "* 2 RECENT",
                "* FLAGS (\\Answered \\Flagged)",
                tag + " OK [READ-ONLY] EXAMINE completed");

        assertTrue(selectHandler.selected);
        assertEquals(17, selectHandler.mailboxInfo.getExists());
        assertFalse(selectHandler.mailboxInfo.isReadWrite());
    }

    // ── LIST tests ──

    @Test
    public void testListSuccess() {
        enterAuthenticatedState();

        RecordingListHandler listHandler =
                new RecordingListHandler();
        greetingHandler.session.list("", "*", listHandler);
        String tag = lastSentTag();

        receiveMultipleLines(
                "* LIST (\\HasNoChildren) \"/\" \"INBOX\"",
                "* LIST (\\HasChildren) \"/\" \"Sent\"",
                "* LIST (\\HasNoChildren) \"/\" \"Trash\"",
                tag + " OK LIST completed");

        assertTrue(listHandler.listComplete);
        assertEquals(3, listHandler.entries.size());
        assertEquals("INBOX", listHandler.entries.get(0).name);
        assertEquals("/", listHandler.entries.get(0).delimiter);
    }

    @Test
    public void testListError() {
        enterAuthenticatedState();

        RecordingListHandler listHandler =
                new RecordingListHandler();
        greetingHandler.session.list("", "*", listHandler);
        String tag = lastSentTag();

        receiveResponse(tag + " NO List failed");

        assertTrue(listHandler.errorReceived);
    }

    // ── STATUS tests ──

    @Test
    public void testStatusSuccess() {
        enterAuthenticatedState();

        RecordingStatusHandler statusHandler =
                new RecordingStatusHandler();
        greetingHandler.session.status("INBOX",
                new String[]{"MESSAGES", "RECENT", "UNSEEN"},
                statusHandler);
        String tag = lastSentTag();

        receiveMultipleLines(
                "* STATUS \"INBOX\" (MESSAGES 17 RECENT 2 UNSEEN 5)",
                tag + " OK STATUS completed");

        assertTrue(statusHandler.received);
        assertEquals("INBOX", statusHandler.mailbox);
        assertEquals(17, statusHandler.messages);
        assertEquals(2, statusHandler.recent);
        assertEquals(5, statusHandler.unseen);
    }

    @Test
    public void testStatusError() {
        enterAuthenticatedState();

        RecordingStatusHandler statusHandler =
                new RecordingStatusHandler();
        greetingHandler.session.status("BAD",
                new String[]{"MESSAGES"}, statusHandler);
        String tag = lastSentTag();

        receiveResponse(tag + " NO Status failed");

        assertTrue(statusHandler.errorReceived);
    }

    // ── CREATE/DELETE/RENAME tests ──

    @Test
    public void testCreateSuccess() {
        enterAuthenticatedState();

        RecordingMailboxHandler mbHandler =
                new RecordingMailboxHandler();
        greetingHandler.session.create("NewFolder", mbHandler);
        String tag = lastSentTag();

        receiveResponse(tag + " OK CREATE completed");

        assertTrue(mbHandler.ok);
    }

    @Test
    public void testDeleteFailed() {
        enterAuthenticatedState();

        RecordingMailboxHandler mbHandler =
                new RecordingMailboxHandler();
        greetingHandler.session.delete("INBOX", mbHandler);
        String tag = lastSentTag();

        receiveResponse(tag + " NO Cannot delete INBOX");

        assertTrue(mbHandler.no);
        assertEquals("Cannot delete INBOX", mbHandler.noMessage);
    }

    @Test
    public void testRenameSuccess() {
        enterAuthenticatedState();

        RecordingMailboxHandler mbHandler =
                new RecordingMailboxHandler();
        greetingHandler.session.rename("Old", "New", mbHandler);
        String tag = lastSentTag();

        receiveResponse(tag + " OK RENAME completed");

        assertTrue(mbHandler.ok);
    }

    // ── NAMESPACE tests ──

    @Test
    public void testNamespaceSuccess() {
        enterAuthenticatedState();

        RecordingNamespaceHandler nsHandler =
                new RecordingNamespaceHandler();
        greetingHandler.session.namespace(nsHandler);
        String tag = lastSentTag();

        receiveMultipleLines(
                "* NAMESPACE ((\"\" \"/\")) NIL NIL",
                tag + " OK NAMESPACE completed");

        assertTrue(nsHandler.received);
        assertEquals("", nsHandler.personal);
        assertEquals("/", nsHandler.personalDelimiter);
    }

    // ── SEARCH tests ──

    @Test
    public void testSearchSuccess() {
        enterSelectedState();

        RecordingSearchHandler searchHandler =
                new RecordingSearchHandler();
        greetingHandler.selectedState.search("ALL", searchHandler);
        String tag = lastSentTag();

        receiveMultipleLines(
                "* SEARCH 2 3 6",
                tag + " OK SEARCH completed");

        assertTrue(searchHandler.received);
        assertArrayEquals(new long[]{2, 3, 6},
                searchHandler.results);
    }

    @Test
    public void testSearchEmpty() {
        enterSelectedState();

        RecordingSearchHandler searchHandler =
                new RecordingSearchHandler();
        greetingHandler.selectedState.search("UNSEEN",
                searchHandler);
        String tag = lastSentTag();

        receiveMultipleLines(
                "* SEARCH",
                tag + " OK SEARCH completed");

        assertTrue(searchHandler.received);
        assertEquals(0, searchHandler.results.length);
    }

    @Test
    public void testUidSearchSuccess() {
        enterSelectedState();

        RecordingSearchHandler searchHandler =
                new RecordingSearchHandler();
        greetingHandler.selectedState.uidSearch("ALL",
                searchHandler);
        assertTrue(lastSentCommand().startsWith("UID SEARCH"));
    }

    @Test
    public void testSearchError() {
        enterSelectedState();

        RecordingSearchHandler searchHandler =
                new RecordingSearchHandler();
        greetingHandler.selectedState.search("BADCRITERIA",
                searchHandler);
        String tag = lastSentTag();

        receiveResponse(tag + " BAD Search failed");

        assertTrue(searchHandler.errorReceived);
    }

    // ── FETCH tests ──

    @Test
    public void testFetchFlags() {
        enterSelectedState();

        RecordingFetchHandler fetchHandler =
                new RecordingFetchHandler();
        greetingHandler.selectedState.fetch("1", "(FLAGS)",
                fetchHandler);
        String tag = lastSentTag();

        receiveMultipleLines(
                "* 1 FETCH (FLAGS (\\Seen \\Answered))",
                tag + " OK FETCH completed");

        assertTrue(fetchHandler.fetchComplete);
        assertEquals(1, fetchHandler.fetchResponses.size());
        FetchData fd = fetchHandler.fetchResponses.get(0).data;
        assertNotNull(fd.getFlags());
        assertEquals(2, fd.getFlags().length);
    }

    @Test
    public void testFetchUidAndSize() {
        enterSelectedState();

        RecordingFetchHandler fetchHandler =
                new RecordingFetchHandler();
        greetingHandler.selectedState.fetch("1",
                "(UID RFC822.SIZE)", fetchHandler);
        String tag = lastSentTag();

        receiveMultipleLines(
                "* 1 FETCH (UID 42 RFC822.SIZE 1234)",
                tag + " OK FETCH completed");

        assertTrue(fetchHandler.fetchComplete);
        FetchData fd = fetchHandler.fetchResponses.get(0).data;
        assertEquals(42, fd.getUid());
        assertEquals(1234, fd.getSize());
    }

    @Test
    public void testFetchWithLiteral() {
        enterSelectedState();

        RecordingFetchHandler fetchHandler =
                new RecordingFetchHandler();
        greetingHandler.selectedState.fetch("1",
                "(BODY[TEXT])", fetchHandler);
        String tag = lastSentTag();

        String fetchLine =
                "* 1 FETCH (BODY[TEXT] {11}";
        byte[] lineBytes = (fetchLine + "\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] literalData = "Hello World".getBytes(
                StandardCharsets.US_ASCII);
        byte[] closingLine = (")\r\n" + tag
                + " OK FETCH completed\r\n")
                .getBytes(StandardCharsets.US_ASCII);

        ByteBuffer combined = ByteBuffer.allocate(
                lineBytes.length + literalData.length
                        + closingLine.length);
        combined.put(lineBytes);
        combined.put(literalData);
        combined.put(closingLine);
        combined.flip();
        handler.receive(combined);

        assertTrue(fetchHandler.literalBeginReceived);
        assertEquals(1, fetchHandler.literalBeginMessageNumber);
        assertEquals("TEXT", fetchHandler.literalSection);
        assertEquals(11, fetchHandler.literalSize);
        assertTrue(fetchHandler.literalContent.size() > 0);
        assertEquals("Hello World",
                fetchHandler.literalContent.toString(
                        StandardCharsets.US_ASCII));
        assertTrue(fetchHandler.literalEndReceived);
        assertTrue(fetchHandler.fetchComplete);
    }

    @Test
    public void testUidFetch() {
        enterSelectedState();

        RecordingFetchHandler fetchHandler =
                new RecordingFetchHandler();
        greetingHandler.selectedState.uidFetch("1:*",
                "(FLAGS)", fetchHandler);
        assertTrue(lastSentCommand().startsWith("UID FETCH"));
    }

    @Test
    public void testFetchError() {
        enterSelectedState();

        RecordingFetchHandler fetchHandler =
                new RecordingFetchHandler();
        greetingHandler.selectedState.fetch("1", "(BODY[])",
                fetchHandler);
        String tag = lastSentTag();

        receiveResponse(tag + " NO Fetch failed");

        assertTrue(fetchHandler.errorReceived);
    }

    // ── STORE tests ──

    @Test
    public void testStoreSuccess() {
        enterSelectedState();

        RecordingStoreHandler storeHandler =
                new RecordingStoreHandler();
        greetingHandler.selectedState.store("1", "+FLAGS",
                new String[]{"\\Seen"}, storeHandler);
        String tag = lastSentTag();

        receiveMultipleLines(
                "* 1 FETCH (FLAGS (\\Seen))",
                tag + " OK STORE completed");

        assertTrue(storeHandler.storeComplete);
        assertEquals(1, storeHandler.responses.size());
        assertEquals(1, storeHandler.responses.get(0).messageNumber);
    }

    @Test
    public void testStoreError() {
        enterSelectedState();

        RecordingStoreHandler storeHandler =
                new RecordingStoreHandler();
        greetingHandler.selectedState.store("999", "+FLAGS",
                new String[]{"\\Seen"}, storeHandler);
        String tag = lastSentTag();

        receiveResponse(tag + " NO Store failed");

        assertTrue(storeHandler.errorReceived);
    }

    // ── COPY/MOVE tests ──

    @Test
    public void testCopySuccess() {
        enterSelectedState();

        RecordingCopyHandler copyHandler =
                new RecordingCopyHandler();
        greetingHandler.selectedState.copy("1:3", "Backup",
                copyHandler);
        String tag = lastSentTag();

        receiveResponse(tag
                + " OK [COPYUID 38505 1:3 100:102] COPY completed");

        assertTrue(copyHandler.complete);
        assertEquals(38505, copyHandler.uidValidity);
        assertEquals("1:3", copyHandler.sourceUids);
        assertEquals("100:102", copyHandler.destUids);
    }

    @Test
    public void testMoveSuccess() {
        enterSelectedState();

        RecordingCopyHandler copyHandler =
                new RecordingCopyHandler();
        greetingHandler.selectedState.move("1", "Trash",
                copyHandler);
        String tag = lastSentTag();
        assertTrue(lastSentCommand().startsWith("MOVE "));

        receiveResponse(tag + " OK MOVE completed");

        assertTrue(copyHandler.complete);
    }

    @Test
    public void testCopyError() {
        enterSelectedState();

        RecordingCopyHandler copyHandler =
                new RecordingCopyHandler();
        greetingHandler.selectedState.copy("1", "NONEXISTENT",
                copyHandler);
        String tag = lastSentTag();

        receiveResponse(tag + " NO Copy failed");

        assertTrue(copyHandler.errorReceived);
    }

    // ── EXPUNGE tests ──

    @Test
    public void testExpungeSuccess() {
        enterSelectedState();

        RecordingExpungeHandler expHandler =
                new RecordingExpungeHandler();
        greetingHandler.selectedState.expunge(expHandler);
        String tag = lastSentTag();

        receiveMultipleLines(
                "* 3 EXPUNGE",
                "* 3 EXPUNGE",
                tag + " OK EXPUNGE completed");

        assertTrue(expHandler.complete);
        assertEquals(2, expHandler.expunged.size());
        assertEquals(Integer.valueOf(3),
                expHandler.expunged.get(0));
    }

    @Test
    public void testExpungeError() {
        enterSelectedState();

        RecordingExpungeHandler expHandler =
                new RecordingExpungeHandler();
        greetingHandler.selectedState.expunge(expHandler);
        String tag = lastSentTag();

        receiveResponse(tag + " NO Expunge failed");

        assertTrue(expHandler.errorReceived);
    }

    // ── CLOSE/UNSELECT tests ──

    @Test
    public void testCloseSuccess() {
        enterSelectedState();

        RecordingCloseHandler closeHandler =
                new RecordingCloseHandler();
        greetingHandler.selectedState.close(closeHandler);
        String tag = lastSentTag();
        assertEquals("CLOSE", lastSentCommand());

        receiveResponse(tag + " OK CLOSE completed");

        assertTrue(closeHandler.closed);
        assertNotNull(closeHandler.session);
    }

    @Test
    public void testUnselectSuccess() {
        enterSelectedState();

        RecordingCloseHandler closeHandler =
                new RecordingCloseHandler();
        greetingHandler.selectedState.unselect(closeHandler);
        String tag = lastSentTag();
        assertEquals("UNSELECT", lastSentCommand());

        receiveResponse(tag + " OK UNSELECT completed");

        assertTrue(closeHandler.closed);
    }

    // ── APPEND tests ──

    @Test
    public void testAppendSuccess() {
        enterAuthenticatedState();

        RecordingAppendHandler appendHandler =
                new RecordingAppendHandler();
        greetingHandler.session.append("INBOX",
                new String[]{"\\Seen"}, null, 11, appendHandler);
        String tag = lastSentTag();

        receiveResponse("+ Ready for literal data");

        assertTrue(appendHandler.readyForData);
        assertNotNull(appendHandler.appendState);

        appendHandler.appendState.writeContent(
                ByteBuffer.wrap("Hello World".getBytes(
                        StandardCharsets.US_ASCII)));
        appendHandler.appendState.endAppend();

        receiveResponse(tag
                + " OK [APPENDUID 38505 3955] APPEND completed");

        assertTrue(appendHandler.appendComplete);
        assertEquals(38505, appendHandler.uidValidity);
        assertEquals(3955, appendHandler.uid);
    }

    @Test
    public void testAppendFailed() {
        enterAuthenticatedState();

        RecordingAppendHandler appendHandler =
                new RecordingAppendHandler();
        greetingHandler.session.append("INBOX", null, null, 100,
                appendHandler);
        String tag = lastSentTag();

        receiveResponse("+ Ready");

        appendHandler.appendState.writeContent(
                ByteBuffer.wrap(new byte[100]));
        appendHandler.appendState.endAppend();

        receiveResponse(tag + " NO APPEND failed");

        assertTrue(appendHandler.failed);
    }

    // ── IDLE tests ──

    @Test
    public void testIdleSuccess() {
        enterAuthenticatedState();

        RecordingIdleHandler idleHandler =
                new RecordingIdleHandler();
        greetingHandler.session.idle(idleHandler);
        String tag = lastSentTag();
        assertEquals("IDLE", lastSentCommand());

        receiveResponse("+ idling");

        assertTrue(idleHandler.idleStarted);
        assertNotNull(idleHandler.idleState);

        receiveResponse("* 5 EXISTS");

        assertEquals(1, idleHandler.existsCounts.size());
        assertEquals(Integer.valueOf(5),
                idleHandler.existsCounts.get(0));

        receiveResponse("* 2 EXPUNGE");

        assertEquals(1, idleHandler.expungeNumbers.size());
        assertEquals(Integer.valueOf(2),
                idleHandler.expungeNumbers.get(0));

        idleHandler.idleState.done();

        receiveResponse(tag + " OK IDLE terminated");

        assertTrue(idleHandler.idleComplete);
    }

    @Test
    public void testIdleWithFlagsUpdate() {
        enterAuthenticatedState();

        RecordingIdleHandler idleHandler =
                new RecordingIdleHandler();
        greetingHandler.session.idle(idleHandler);
        String tag = lastSentTag();

        receiveResponse("+ idling");

        receiveResponse("* 3 FETCH (FLAGS (\\Seen \\Flagged))");

        assertEquals(1, idleHandler.flagsUpdates.size());
        assertEquals(3, idleHandler.flagsUpdates.get(0).messageNumber);
        assertEquals(2,
                idleHandler.flagsUpdates.get(0).flags.length);

        idleHandler.idleState.done();
        receiveResponse(tag + " OK IDLE terminated");

        assertTrue(idleHandler.idleComplete);
    }

    // ── NOOP tests ──

    @Test
    public void testNoopSuccess() {
        enterAuthenticatedState();

        RecordingNoopHandler noopHandler =
                new RecordingNoopHandler();
        greetingHandler.session.noop(noopHandler);
        String tag = lastSentTag();
        assertEquals("NOOP", lastSentCommand());

        receiveResponse(tag + " OK NOOP completed");

        assertTrue(noopHandler.ok);
    }

    // ── LOGOUT tests ──

    @Test
    public void testLogout() {
        receiveResponse("* OK IMAP ready");

        greetingHandler.authState.logout();
        String tag = lastSentTag();
        assertEquals("LOGOUT", lastSentCommand());

        receiveMultipleLines(
                "* BYE IMAP4rev1 server logging out",
                tag + " OK LOGOUT completed");
    }

    // ── Unsolicited event tests ──

    @Test
    public void testUnsolicitedExistsEvent() {
        enterSelectedState();

        RecordingMailboxEventListener listener =
                new RecordingMailboxEventListener();
        handler.setMailboxEventListener(listener);

        RecordingNoopHandler noopHandler =
                new RecordingNoopHandler();
        greetingHandler.selectedState.noop(noopHandler);
        String tag = lastSentTag();

        receiveMultipleLines(
                "* 23 EXISTS",
                "* 3 RECENT",
                tag + " OK NOOP completed");

        assertEquals(1, listener.existsCounts.size());
        assertEquals(Integer.valueOf(23),
                listener.existsCounts.get(0));
        assertEquals(1, listener.recentCounts.size());
        assertEquals(Integer.valueOf(3),
                listener.recentCounts.get(0));
    }

    // ── Full flow tests ──

    @Test
    public void testFullGreetingLoginSelectSearchLogout() {
        receiveResponse(
                "* OK [CAPABILITY IMAP4rev1] IMAP ready");

        RecordingLoginHandler loginHandler =
                new RecordingLoginHandler();
        greetingHandler.authState.login("alice", "secret",
                loginHandler);
        String loginTag = lastSentTag();
        receiveResponse(loginTag + " OK LOGIN completed");

        assertTrue(loginHandler.authenticated);

        RecordingSelectHandler selectHandler =
                new RecordingSelectHandler();
        loginHandler.session.select("INBOX", selectHandler);
        String selectTag = lastSentTag();

        receiveMultipleLines(
                "* 5 EXISTS",
                "* 0 RECENT",
                "* FLAGS (\\Answered \\Flagged \\Deleted \\Seen)",
                selectTag + " OK [READ-WRITE] SELECT completed");

        assertTrue(selectHandler.selected);
        assertEquals(5, selectHandler.mailboxInfo.getExists());

        RecordingSearchHandler searchHandler =
                new RecordingSearchHandler();
        selectHandler.selectedState.search("ALL", searchHandler);
        String searchTag = lastSentTag();

        receiveMultipleLines(
                "* SEARCH 1 2 3 4 5",
                searchTag + " OK SEARCH completed");

        assertTrue(searchHandler.received);
        assertEquals(5, searchHandler.results.length);

        selectHandler.selectedState.logout();
        String logoutTag = lastSentTag();
        receiveMultipleLines(
                "* BYE Logging out",
                logoutTag + " OK LOGOUT completed");
    }

    // ── isOpen / close tests ──

    @Test
    public void testIsOpenAfterConnect() {
        receiveResponse("* OK IMAP ready");
        assertTrue(handler.isOpen());
    }

    @Test
    public void testIsOpenAfterClose() {
        receiveResponse("* OK IMAP ready");
        handler.close();
        assertFalse(handler.isOpen());
    }

    @Test
    public void testNullHandlerThrows() {
        try {
            new IMAPClientProtocolHandler(null);
            fail("Expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
    }

    // ── Helpers ──

    private void enterAuthenticatedState() {
        receiveResponse("* OK IMAP ready");

        RecordingLoginHandler loginHandler =
                new RecordingLoginHandler();
        greetingHandler.authState.login("alice", "secret",
                loginHandler);
        String tag = lastSentTag();
        receiveResponse(tag + " OK LOGIN completed");

        greetingHandler.session = loginHandler.session;
    }

    private void enterSelectedState() {
        enterAuthenticatedState();

        RecordingSelectHandler selectHandler =
                new RecordingSelectHandler();
        greetingHandler.session.select("INBOX", selectHandler);
        String tag = lastSentTag();

        receiveMultipleLines(
                "* 10 EXISTS",
                "* 0 RECENT",
                tag + " OK [READ-WRITE] SELECT completed");

        greetingHandler.selectedState = selectHandler.selectedState;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Stub Endpoint
    // ═══════════════════════════════════════════════════════════════════

    static class StubEndpoint implements Endpoint {

        private final List<byte[]> sentData = new ArrayList<byte[]>();
        private boolean open = true;
        boolean startTLSCalled;

        @Override
        public void send(ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            sentData.add(bytes);
        }

        List<String> getSentCommands() {
            List<String> result = new ArrayList<String>();
            for (byte[] data : sentData) {
                String s = new String(data,
                        StandardCharsets.US_ASCII);
                if (s.endsWith("\r\n")) {
                    s = s.substring(0, s.length() - 2);
                }
                result.add(s);
            }
            return result;
        }

        @Override public boolean isOpen() { return open; }
        @Override public boolean isClosing() { return false; }
        @Override public void close() { open = false; }
        @Override public SocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 12345);
        }
        @Override public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 143);
        }
        @Override public boolean isSecure() { return false; }
        @Override public SecurityInfo getSecurityInfo() {
            return new StubSecurityInfo();
        }
        @Override public void startTLS() {
            startTLSCalled = true;
        }
        @Override public SelectorLoop getSelectorLoop() {
            return null;
        }
        @Override public TimerHandle scheduleTimer(long delayMs,
                                                   Runnable cb) {
            return new TimerHandle() {
                @Override public void cancel() {}
                @Override public boolean isCancelled() {
                    return false;
                }
            };
        }
        @Override public Trace getTrace() { return null; }
        @Override public void setTrace(Trace trace) {}
        @Override public boolean isTelemetryEnabled() {
            return false;
        }
        @Override public TelemetryConfig getTelemetryConfig() {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Stub SecurityInfo
    // ═══════════════════════════════════════════════════════════════════

    static class StubSecurityInfo implements SecurityInfo {
        @Override public String getProtocol() {
            return "TLSv1.3";
        }
        @Override public String getCipherSuite() {
            return "TLS_AES_256_GCM_SHA384";
        }
        @Override public int getKeySize() { return 256; }
        @Override public Certificate[] getPeerCertificates() {
            return null;
        }
        @Override public Certificate[] getLocalCertificates() {
            return null;
        }
        @Override public String getApplicationProtocol() {
            return null;
        }
        @Override public long getHandshakeDurationMs() {
            return 5;
        }
        @Override public boolean isSessionResumed() {
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Recording handlers
    // ═══════════════════════════════════════════════════════════════════

    static class RecordingGreetingHandler
            implements ServerGreeting {
        boolean greetingReceived;
        String greetingMessage;
        List<String> preAuthCapabilities;
        ClientNotAuthenticatedState authState;
        ClientAuthenticatedState session;
        ClientSelectedState selectedState;
        boolean preAuthenticated;
        boolean serviceUnavailable;
        String unavailableMessage;

        @Override
        public void handleGreeting(
                ClientNotAuthenticatedState auth,
                String greeting,
                List<String> preAuthCapabilities) {
            greetingReceived = true;
            greetingMessage = greeting;
            this.preAuthCapabilities = preAuthCapabilities;
            this.authState = auth;
        }

        @Override
        public void handlePreAuthenticated(
                ClientAuthenticatedState auth, String greeting) {
            preAuthenticated = true;
            session = auth;
        }

        @Override
        public void handleServiceUnavailable(String message) {
            serviceUnavailable = true;
            unavailableMessage = message;
        }

        @Override
        public void onConnected(Endpoint endpoint) {}
        @Override
        public void onError(Exception cause) {}
        @Override
        public void onDisconnected() {}
        @Override
        public void onSecurityEstablished(SecurityInfo info) {}
    }

    static class RecordingCapabilityHandler
            implements ServerCapabilityReplyHandler {
        boolean received;
        List<String> capabilities;
        boolean errorReceived;
        String errorMessage;

        @Override
        public void handleCapabilities(
                ClientNotAuthenticatedState auth,
                List<String> capabilities) {
            received = true;
            this.capabilities = capabilities;
        }

        @Override
        public void handleError(
                ClientNotAuthenticatedState auth,
                String message) {
            errorReceived = true;
            errorMessage = message;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class RecordingLoginHandler
            implements ServerLoginReplyHandler {
        boolean authenticated;
        ClientAuthenticatedState session;
        List<String> capabilities;
        boolean authFailed;
        String authFailedMessage;

        @Override
        public void handleAuthenticated(
                ClientAuthenticatedState session,
                List<String> capabilities) {
            authenticated = true;
            this.session = session;
            this.capabilities = capabilities;
        }

        @Override
        public void handleAuthFailed(
                ClientNotAuthenticatedState auth,
                String message) {
            authFailed = true;
            authFailedMessage = message;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class RecordingAuthHandler
            implements ServerAuthReplyHandler {
        boolean authSuccess;
        ClientAuthenticatedState session;
        List<String> capabilities;
        boolean challengeReceived;
        byte[] challenge;
        ClientAuthExchange authExchange;
        boolean authFailed;
        String authFailedMessage;

        void reset() {
            authSuccess = false;
            session = null;
            capabilities = null;
            challengeReceived = false;
            challenge = null;
            authFailed = false;
            authFailedMessage = null;
        }

        @Override
        public void handleAuthSuccess(
                ClientAuthenticatedState session,
                List<String> capabilities) {
            authSuccess = true;
            this.session = session;
            this.capabilities = capabilities;
        }

        @Override
        public void handleChallenge(byte[] challenge,
                                    ClientAuthExchange exchange) {
            challengeReceived = true;
            this.challenge = challenge;
            this.authExchange = exchange;
        }

        @Override
        public void handleAuthFailed(
                ClientNotAuthenticatedState auth,
                String message) {
            authFailed = true;
            authFailedMessage = message;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class RecordingAuthAbortHandler
            implements ServerAuthAbortHandler {
        boolean aborted;

        @Override
        public void handleAborted(
                ClientNotAuthenticatedState auth) {
            aborted = true;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class RecordingStarttlsHandler
            implements ServerStarttlsReplyHandler {
        boolean tlsEstablished;
        ClientPostStarttls postTls;
        boolean tlsUnavailable;
        boolean permanentFailure;

        @Override
        public void handleTlsEstablished(
                ClientPostStarttls postTls) {
            tlsEstablished = true;
            this.postTls = postTls;
        }

        @Override
        public void handleTlsUnavailable(
                ClientNotAuthenticatedState auth) {
            tlsUnavailable = true;
        }

        @Override
        public void handlePermanentFailure(String message) {
            permanentFailure = true;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class RecordingSelectHandler
            implements ServerSelectReplyHandler {
        boolean selected;
        ClientSelectedState selectedState;
        MailboxInfo mailboxInfo;
        boolean failed;
        String failedMessage;

        @Override
        public void handleSelected(ClientSelectedState selected,
                                   MailboxInfo info) {
            this.selected = true;
            this.selectedState = selected;
            this.mailboxInfo = info;
        }

        @Override
        public void handleFailed(ClientAuthenticatedState session,
                                 String message) {
            failed = true;
            failedMessage = message;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class ListEntry {
        String attributes;
        String delimiter;
        String name;
    }

    static class RecordingListHandler
            implements ServerListReplyHandler {
        List<ListEntry> entries = new ArrayList<ListEntry>();
        boolean listComplete;
        boolean errorReceived;

        @Override
        public void handleListEntry(String attributes,
                                    String delimiter, String name) {
            ListEntry e = new ListEntry();
            e.attributes = attributes;
            e.delimiter = delimiter;
            e.name = name;
            entries.add(e);
        }

        @Override
        public void handleListComplete(
                ClientAuthenticatedState session) {
            listComplete = true;
        }

        @Override
        public void handleError(
                ClientAuthenticatedState session,
                String message) {
            errorReceived = true;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class RecordingStatusHandler
            implements ServerStatusReplyHandler {
        boolean received;
        String mailbox;
        int messages;
        int recent;
        long uidNext;
        long uidValidity;
        int unseen;
        boolean errorReceived;

        @Override
        public void handleStatus(
                ClientAuthenticatedState session,
                String mailbox, int messages, int recent,
                long uidNext, long uidValidity, int unseen) {
            received = true;
            this.mailbox = mailbox;
            this.messages = messages;
            this.recent = recent;
            this.uidNext = uidNext;
            this.uidValidity = uidValidity;
            this.unseen = unseen;
        }

        @Override
        public void handleError(
                ClientAuthenticatedState session,
                String message) {
            errorReceived = true;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class RecordingMailboxHandler
            implements ServerMailboxReplyHandler {
        boolean ok;
        boolean no;
        String noMessage;

        @Override
        public void handleOk(ClientAuthenticatedState session) {
            ok = true;
        }

        @Override
        public void handleNo(ClientAuthenticatedState session,
                             String message) {
            no = true;
            noMessage = message;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class RecordingNamespaceHandler
            implements ServerNamespaceReplyHandler {
        boolean received;
        String personal;
        String personalDelimiter;
        boolean errorReceived;

        @Override
        public void handleNamespace(
                ClientAuthenticatedState session,
                String personal, String personalDelimiter) {
            received = true;
            this.personal = personal;
            this.personalDelimiter = personalDelimiter;
        }

        @Override
        public void handleError(
                ClientAuthenticatedState session,
                String message) {
            errorReceived = true;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class RecordingSearchHandler
            implements ServerSearchReplyHandler {
        boolean received;
        long[] results;
        boolean errorReceived;

        @Override
        public void handleSearchResults(
                ClientSelectedState selected, long[] results) {
            received = true;
            this.results = results;
        }

        @Override
        public void handleError(ClientSelectedState selected,
                                String message) {
            errorReceived = true;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class FetchResponse {
        int messageNumber;
        FetchData data;
    }

    static class RecordingFetchHandler
            implements ServerFetchReplyHandler {
        List<FetchResponse> fetchResponses =
                new ArrayList<FetchResponse>();
        boolean literalBeginReceived;
        int literalBeginMessageNumber;
        String literalSection;
        long literalSize;
        ByteArrayOutputStream literalContent =
                new ByteArrayOutputStream();
        boolean literalEndReceived;
        boolean fetchComplete;
        boolean errorReceived;

        @Override
        public void handleFetchResponse(int messageNumber,
                                        FetchData data) {
            FetchResponse fr = new FetchResponse();
            fr.messageNumber = messageNumber;
            fr.data = data;
            fetchResponses.add(fr);
        }

        @Override
        public void handleFetchLiteralBegin(int messageNumber,
                                            String section,
                                            long size) {
            literalBeginReceived = true;
            literalBeginMessageNumber = messageNumber;
            literalSection = section;
            literalSize = size;
        }

        @Override
        public void handleFetchLiteralContent(ByteBuffer content) {
            byte[] bytes = new byte[content.remaining()];
            content.get(bytes);
            literalContent.write(bytes, 0, bytes.length);
        }

        @Override
        public void handleFetchLiteralEnd(int messageNumber) {
            literalEndReceived = true;
        }

        @Override
        public void handleFetchComplete(
                ClientSelectedState selected) {
            fetchComplete = true;
        }

        @Override
        public void handleError(ClientSelectedState selected,
                                String message) {
            errorReceived = true;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class StoreResponse {
        int messageNumber;
        String[] flags;
    }

    static class RecordingStoreHandler
            implements ServerStoreReplyHandler {
        List<StoreResponse> responses =
                new ArrayList<StoreResponse>();
        boolean storeComplete;
        boolean errorReceived;

        @Override
        public void handleStoreResponse(int messageNumber,
                                        String[] flags) {
            StoreResponse sr = new StoreResponse();
            sr.messageNumber = messageNumber;
            sr.flags = flags;
            responses.add(sr);
        }

        @Override
        public void handleStoreComplete(
                ClientSelectedState selected) {
            storeComplete = true;
        }

        @Override
        public void handleError(ClientSelectedState selected,
                                String message) {
            errorReceived = true;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class RecordingCopyHandler
            implements ServerCopyReplyHandler {
        boolean complete;
        long uidValidity;
        String sourceUids;
        String destUids;
        boolean errorReceived;

        @Override
        public void handleCopyComplete(
                ClientAuthenticatedState session,
                long uidValidity, String sourceUids,
                String destUids) {
            complete = true;
            this.uidValidity = uidValidity;
            this.sourceUids = sourceUids;
            this.destUids = destUids;
        }

        @Override
        public void handleError(
                ClientAuthenticatedState session,
                String message) {
            errorReceived = true;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class RecordingExpungeHandler
            implements ServerExpungeReplyHandler {
        List<Integer> expunged = new ArrayList<Integer>();
        boolean complete;
        boolean errorReceived;

        @Override
        public void handleExpunged(int messageNumber) {
            expunged.add(messageNumber);
        }

        @Override
        public void handleExpungeComplete(
                ClientSelectedState selected) {
            complete = true;
        }

        @Override
        public void handleError(ClientSelectedState selected,
                                String message) {
            errorReceived = true;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class RecordingCloseHandler
            implements ServerCloseReplyHandler {
        boolean closed;
        ClientAuthenticatedState session;

        @Override
        public void handleClosed(
                ClientAuthenticatedState session) {
            closed = true;
            this.session = session;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class RecordingAppendHandler
            implements ServerAppendReplyHandler {
        boolean readyForData;
        ClientAppendState appendState;
        boolean appendComplete;
        long uidValidity;
        long uid;
        boolean failed;
        String failedMessage;

        @Override
        public void handleReadyForData(
                ClientAppendState append) {
            readyForData = true;
            appendState = append;
        }

        @Override
        public void handleAppendComplete(
                ClientAuthenticatedState session,
                long uidValidity, long uid) {
            appendComplete = true;
            this.uidValidity = uidValidity;
            this.uid = uid;
        }

        @Override
        public void handleFailed(
                ClientAuthenticatedState session,
                String message) {
            failed = true;
            failedMessage = message;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class FlagsUpdate {
        int messageNumber;
        String[] flags;
    }

    static class RecordingIdleHandler
            implements ServerIdleEventHandler {
        boolean idleStarted;
        ClientIdleState idleState;
        List<Integer> existsCounts = new ArrayList<Integer>();
        List<Integer> recentCounts = new ArrayList<Integer>();
        List<Integer> expungeNumbers = new ArrayList<Integer>();
        List<FlagsUpdate> flagsUpdates =
                new ArrayList<FlagsUpdate>();
        boolean idleComplete;

        @Override
        public void handleIdleStarted(ClientIdleState idle) {
            idleStarted = true;
            idleState = idle;
        }

        @Override
        public void handleExists(int count) {
            existsCounts.add(count);
        }

        @Override
        public void handleRecent(int count) {
            recentCounts.add(count);
        }

        @Override
        public void handleExpunge(int messageNumber) {
            expungeNumbers.add(messageNumber);
        }

        @Override
        public void handleFlagsUpdate(int messageNumber,
                                      String[] flags) {
            FlagsUpdate fu = new FlagsUpdate();
            fu.messageNumber = messageNumber;
            fu.flags = flags;
            flagsUpdates.add(fu);
        }

        @Override
        public void handleIdleComplete(
                ClientAuthenticatedState session) {
            idleComplete = true;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class RecordingNoopHandler
            implements ServerNoopReplyHandler {
        boolean ok;

        @Override
        public void handleOk(ClientAuthenticatedState session) {
            ok = true;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class RecordingMailboxEventListener
            implements MailboxEventListener {
        List<Integer> existsCounts = new ArrayList<Integer>();
        List<Integer> recentCounts = new ArrayList<Integer>();
        List<Integer> expungeNumbers = new ArrayList<Integer>();
        List<FlagsUpdate> flagsUpdates =
                new ArrayList<FlagsUpdate>();

        @Override
        public void onExists(int count) {
            existsCounts.add(count);
        }

        @Override
        public void onRecent(int count) {
            recentCounts.add(count);
        }

        @Override
        public void onExpunge(int messageNumber) {
            expungeNumbers.add(messageNumber);
        }

        @Override
        public void onFlagsUpdate(int messageNumber,
                                  String[] flags) {
            FlagsUpdate fu = new FlagsUpdate();
            fu.messageNumber = messageNumber;
            fu.flags = flags;
            flagsUpdates.add(fu);
        }
    }
}
