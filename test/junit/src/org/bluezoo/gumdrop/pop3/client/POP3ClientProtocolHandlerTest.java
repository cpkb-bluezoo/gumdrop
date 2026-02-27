/*
 * POP3ClientProtocolHandlerTest.java
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

package org.bluezoo.gumdrop.pop3.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import org.bluezoo.gumdrop.pop3.client.handler.ClientAuthExchange;
import org.bluezoo.gumdrop.pop3.client.handler.ClientAuthorizationState;
import org.bluezoo.gumdrop.pop3.client.handler.ClientPasswordState;
import org.bluezoo.gumdrop.pop3.client.handler.ClientPostStls;
import org.bluezoo.gumdrop.pop3.client.handler.ClientTransactionState;
import org.bluezoo.gumdrop.pop3.client.handler.ServerApopReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerAuthAbortHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerAuthReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerCapaReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerDeleReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerGreeting;
import org.bluezoo.gumdrop.pop3.client.handler.ServerListReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerNoopReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerPassReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerRetrReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerRsetReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerStatReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerStlsReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerTopReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerUidlReplyHandler;
import org.bluezoo.gumdrop.pop3.client.handler.ServerUserReplyHandler;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link POP3ClientProtocolHandler}.
 *
 * <p>Tests the POP3 client state machine by simulating server responses
 * through a stub Endpoint and verifying that the correct callbacks are
 * invoked on recording handler implementations.
 */
public class POP3ClientProtocolHandlerTest {

    private POP3ClientProtocolHandler handler;
    private RecordingGreetingHandler greetingHandler;
    private StubEndpoint endpoint;

    @Before
    public void setUp() {
        greetingHandler = new RecordingGreetingHandler();
        handler = new POP3ClientProtocolHandler(greetingHandler);
        endpoint = new StubEndpoint();
        handler.connected(endpoint);
    }

    private void receiveResponse(String line) {
        byte[] data = (line + "\r\n").getBytes(StandardCharsets.US_ASCII);
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

    private String lastSentCommand() {
        List<String> commands = endpoint.getSentCommands();
        assertFalse("No commands sent", commands.isEmpty());
        return commands.get(commands.size() - 1);
    }

    // ── Greeting tests ──

    @Test
    public void testGreetingOk() {
        receiveResponse("+OK POP3 server ready");

        assertTrue(greetingHandler.greetingReceived);
        assertEquals("POP3 server ready", greetingHandler.greetingMessage);
        assertNull(greetingHandler.apopTimestamp);
        assertNotNull(greetingHandler.authState);
    }

    @Test
    public void testGreetingWithApopTimestamp() {
        receiveResponse(
                "+OK POP3 server ready <1896.697170952@dbc.mtview.ca.us>");

        assertTrue(greetingHandler.greetingReceived);
        assertEquals("<1896.697170952@dbc.mtview.ca.us>",
                greetingHandler.apopTimestamp);
    }

    @Test
    public void testGreetingWithoutApopTimestamp() {
        receiveResponse("+OK Hello there");

        assertTrue(greetingHandler.greetingReceived);
        assertNull(greetingHandler.apopTimestamp);
    }

    @Test
    public void testGreetingServiceUnavailable() {
        receiveResponse("-ERR server busy, try later");

        assertTrue(greetingHandler.serviceUnavailable);
        assertEquals("server busy, try later",
                greetingHandler.unavailableMessage);
    }

    // ── CAPA tests ──

    @Test
    public void testCapaSuccess() {
        receiveResponse("+OK POP3 server ready");

        RecordingCapaHandler capaHandler = new RecordingCapaHandler();
        greetingHandler.authState.capa(capaHandler);
        assertEquals("CAPA", lastSentCommand());

        receiveMultipleLines(
                "+OK Capability list follows",
                "TOP",
                "USER",
                "UIDL",
                "STLS",
                "SASL PLAIN LOGIN",
                "PIPELINING",
                "IMPLEMENTATION Gumdrop",
                ".");

        assertTrue(capaHandler.capabilitiesReceived);
        assertTrue(capaHandler.top);
        assertTrue(capaHandler.user);
        assertTrue(capaHandler.uidl);
        assertTrue(capaHandler.stls);
        assertTrue(capaHandler.pipelining);
        assertEquals("Gumdrop", capaHandler.implementation);
        assertEquals(2, capaHandler.saslMechanisms.size());
        assertTrue(capaHandler.saslMechanisms.contains("PLAIN"));
        assertTrue(capaHandler.saslMechanisms.contains("LOGIN"));
    }

    @Test
    public void testCapaError() {
        receiveResponse("+OK POP3 server ready");

        RecordingCapaHandler capaHandler = new RecordingCapaHandler();
        greetingHandler.authState.capa(capaHandler);

        receiveResponse("-ERR CAPA not supported");

        assertTrue(capaHandler.errorReceived);
        assertEquals("CAPA not supported", capaHandler.errorMessage);
    }

    @Test
    public void testCapaMinimalCapabilities() {
        receiveResponse("+OK POP3 server ready");

        RecordingCapaHandler capaHandler = new RecordingCapaHandler();
        greetingHandler.authState.capa(capaHandler);

        receiveMultipleLines("+OK", "USER", ".");

        assertTrue(capaHandler.capabilitiesReceived);
        assertTrue(capaHandler.user);
        assertFalse(capaHandler.stls);
        assertFalse(capaHandler.top);
        assertFalse(capaHandler.uidl);
        assertFalse(capaHandler.pipelining);
        assertNull(capaHandler.implementation);
        assertTrue(capaHandler.saslMechanisms.isEmpty());
    }

    // ── USER/PASS tests ──

    @Test
    public void testUserAccepted() {
        receiveResponse("+OK POP3 server ready");

        RecordingUserHandler userHandler = new RecordingUserHandler();
        greetingHandler.authState.user("alice", userHandler);
        assertEquals("USER alice", lastSentCommand());

        receiveResponse("+OK User accepted");

        assertTrue(userHandler.accepted);
        assertNotNull(userHandler.passwordState);
    }

    @Test
    public void testUserRejected() {
        receiveResponse("+OK POP3 server ready");

        RecordingUserHandler userHandler = new RecordingUserHandler();
        greetingHandler.authState.user("nobody", userHandler);

        receiveResponse("-ERR unknown user");

        assertTrue(userHandler.rejected);
        assertEquals("unknown user", userHandler.rejectedMessage);
    }

    @Test
    public void testPassAuthenticated() {
        receiveResponse("+OK POP3 server ready");

        RecordingUserHandler userHandler = new RecordingUserHandler();
        greetingHandler.authState.user("alice", userHandler);
        receiveResponse("+OK User accepted");

        RecordingPassHandler passHandler = new RecordingPassHandler();
        userHandler.passwordState.pass("secret", passHandler);
        assertEquals("PASS secret", lastSentCommand());

        receiveResponse("+OK Maildrop locked and ready");

        assertTrue(passHandler.authenticated);
        assertNotNull(passHandler.transactionState);
    }

    @Test
    public void testPassAuthFailed() {
        receiveResponse("+OK POP3 server ready");

        RecordingUserHandler userHandler = new RecordingUserHandler();
        greetingHandler.authState.user("alice", userHandler);
        receiveResponse("+OK User accepted");

        RecordingPassHandler passHandler = new RecordingPassHandler();
        userHandler.passwordState.pass("wrong", passHandler);

        receiveResponse("-ERR invalid password");

        assertTrue(passHandler.authFailed);
        assertEquals("invalid password", passHandler.authFailedMessage);
    }

    // ── APOP tests ──

    @Test
    public void testApopSuccess() {
        receiveResponse(
                "+OK POP3 ready <1896.697170952@dbc.mtview.ca.us>");

        RecordingApopHandler apopHandler = new RecordingApopHandler();
        greetingHandler.authState.apop("mrose",
                "c4c9334bac560ecc979e58001b3e22fb", apopHandler);
        assertTrue(lastSentCommand().startsWith("APOP mrose "));

        receiveResponse("+OK maildrop has 2 messages");

        assertTrue(apopHandler.authenticated);
        assertNotNull(apopHandler.transactionState);
    }

    @Test
    public void testApopFailed() {
        receiveResponse(
                "+OK POP3 ready <1896.697170952@dbc.mtview.ca.us>");

        RecordingApopHandler apopHandler = new RecordingApopHandler();
        greetingHandler.authState.apop("mrose", "wrongdigest",
                apopHandler);

        receiveResponse("-ERR permission denied");

        assertTrue(apopHandler.authFailed);
        assertEquals("permission denied",
                apopHandler.authFailedMessage);
    }

    // ── AUTH (SASL) tests ──

    @Test
    public void testAuthPlainSuccess() {
        receiveResponse("+OK POP3 server ready");

        byte[] credentials = "\0alice\0secret".getBytes(
                StandardCharsets.US_ASCII);
        RecordingAuthHandler authHandler = new RecordingAuthHandler();
        greetingHandler.authState.auth("PLAIN", credentials,
                authHandler);

        String cmd = lastSentCommand();
        assertTrue(cmd.startsWith("AUTH PLAIN "));

        receiveResponse("+OK Authentication successful");

        assertTrue(authHandler.authSuccess);
        assertNotNull(authHandler.transactionState);
    }

    @Test
    public void testAuthChallengeResponse() {
        receiveResponse("+OK POP3 server ready");

        RecordingAuthHandler authHandler = new RecordingAuthHandler();
        greetingHandler.authState.auth("LOGIN", null, authHandler);

        String challenge = Base64.getEncoder()
                .encodeToString("Username:".getBytes(
                        StandardCharsets.US_ASCII));
        receiveResponse("+ " + challenge);

        assertTrue(authHandler.challengeReceived);
        assertArrayEquals("Username:".getBytes(StandardCharsets.US_ASCII),
                authHandler.challenge);
        assertNotNull(authHandler.authExchange);

        authHandler.reset();
        authHandler.authExchange.respond(
                "alice".getBytes(StandardCharsets.US_ASCII), authHandler);

        String passwdChallenge = Base64.getEncoder()
                .encodeToString("Password:".getBytes(
                        StandardCharsets.US_ASCII));
        receiveResponse("+ " + passwdChallenge);

        assertTrue(authHandler.challengeReceived);
        assertArrayEquals("Password:".getBytes(StandardCharsets.US_ASCII),
                authHandler.challenge);

        authHandler.reset();
        authHandler.authExchange.respond(
                "secret".getBytes(StandardCharsets.US_ASCII), authHandler);

        receiveResponse("+OK Login successful");

        assertTrue(authHandler.authSuccess);
    }

    @Test
    public void testAuthFailed() {
        receiveResponse("+OK POP3 server ready");

        RecordingAuthHandler authHandler = new RecordingAuthHandler();
        byte[] credentials = "\0alice\0wrong".getBytes(
                StandardCharsets.US_ASCII);
        greetingHandler.authState.auth("PLAIN", credentials,
                authHandler);

        receiveResponse("-ERR Authentication failed");

        assertTrue(authHandler.authFailed);
        assertEquals("Authentication failed",
                authHandler.authFailedMessage);
    }

    @Test
    public void testAuthAbort() {
        receiveResponse("+OK POP3 server ready");

        RecordingAuthHandler authHandler = new RecordingAuthHandler();
        greetingHandler.authState.auth("LOGIN", null, authHandler);

        String challenge = Base64.getEncoder()
                .encodeToString("Username:".getBytes(
                        StandardCharsets.US_ASCII));
        receiveResponse("+ " + challenge);

        RecordingAuthAbortHandler abortHandler =
                new RecordingAuthAbortHandler();
        authHandler.authExchange.abort(abortHandler);
        assertEquals("*", lastSentCommand());

        receiveResponse("-ERR Authentication aborted");

        assertTrue(abortHandler.aborted);
    }

    // ── STLS tests ──

    @Test
    public void testStlsSuccess() {
        receiveResponse("+OK POP3 server ready");

        RecordingStlsHandler stlsHandler = new RecordingStlsHandler();
        greetingHandler.authState.stls(stlsHandler);
        assertEquals("STLS", lastSentCommand());

        receiveResponse("+OK Begin TLS negotiation");
        assertTrue(endpoint.startTLSCalled);

        handler.securityEstablished(new StubSecurityInfo());

        assertTrue(stlsHandler.tlsEstablished);
        assertNotNull(stlsHandler.postStls);
    }

    @Test
    public void testStlsRejected() {
        receiveResponse("+OK POP3 server ready");

        RecordingStlsHandler stlsHandler = new RecordingStlsHandler();
        greetingHandler.authState.stls(stlsHandler);

        receiveResponse("-ERR TLS not available");

        assertTrue(stlsHandler.tlsUnavailable);
    }

    // ── STAT tests ──

    @Test
    public void testStatSuccess() {
        enterTransactionState();

        RecordingStatHandler statHandler = new RecordingStatHandler();
        greetingHandler.transactionState.stat(statHandler);
        assertEquals("STAT", lastSentCommand());

        receiveResponse("+OK 2 320");

        assertTrue(statHandler.statReceived);
        assertEquals(2, statHandler.messageCount);
        assertEquals(320L, statHandler.totalSize);
    }

    @Test
    public void testStatError() {
        enterTransactionState();

        RecordingStatHandler statHandler = new RecordingStatHandler();
        greetingHandler.transactionState.stat(statHandler);

        receiveResponse("-ERR unable to process");

        assertTrue(statHandler.errorReceived);
        assertEquals("unable to process", statHandler.errorMessage);
    }

    // ── LIST tests ──

    @Test
    public void testListSingleMessage() {
        enterTransactionState();

        RecordingListHandler listHandler = new RecordingListHandler();
        greetingHandler.transactionState.list(1, listHandler);
        assertEquals("LIST 1", lastSentCommand());

        receiveResponse("+OK 1 120");

        assertTrue(listHandler.singleReceived);
        assertEquals(1, listHandler.singleMessageNumber);
        assertEquals(120L, listHandler.singleSize);
    }

    @Test
    public void testListAllMessages() {
        enterTransactionState();

        RecordingListHandler listHandler = new RecordingListHandler();
        greetingHandler.transactionState.list(listHandler);
        assertEquals("LIST", lastSentCommand());

        receiveMultipleLines("+OK", "1 120", "2 200", "3 350", ".");

        assertTrue(listHandler.listComplete);
        assertEquals(3, listHandler.entries.size());
        assertEquals(1, listHandler.entries.get(0).number);
        assertEquals(120L, listHandler.entries.get(0).size);
        assertEquals(2, listHandler.entries.get(1).number);
        assertEquals(200L, listHandler.entries.get(1).size);
        assertEquals(3, listHandler.entries.get(2).number);
        assertEquals(350L, listHandler.entries.get(2).size);
    }

    @Test
    public void testListNoSuchMessage() {
        enterTransactionState();

        RecordingListHandler listHandler = new RecordingListHandler();
        greetingHandler.transactionState.list(99, listHandler);

        receiveResponse("-ERR no such message");

        assertTrue(listHandler.noSuchMessage);
    }

    // ── RETR tests ──

    @Test
    public void testRetrSuccess() {
        enterTransactionState();

        RecordingRetrHandler retrHandler = new RecordingRetrHandler();
        greetingHandler.transactionState.retr(1, retrHandler);
        assertEquals("RETR 1", lastSentCommand());

        receiveResponse("+OK 120 octets");

        byte[] bodyData = ("Subject: Test\r\n\r\n"
                + "Hello World\r\n.\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(bodyData));

        assertTrue(retrHandler.messageComplete);
        String content = retrHandler.collectedContent();
        assertEquals("Subject: Test\r\n\r\nHello World\r\n", content);
    }

    @Test
    public void testRetrWithDotUnstuffing() {
        enterTransactionState();

        RecordingRetrHandler retrHandler = new RecordingRetrHandler();
        greetingHandler.transactionState.retr(1, retrHandler);
        receiveResponse("+OK message follows");

        byte[] bodyData = ("From: test@example.com\r\n"
                + "..This is dot-stuffed\r\n.\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(bodyData));

        assertTrue(retrHandler.messageComplete);
        String content = retrHandler.collectedContent();
        assertTrue(content.contains(".This is dot-stuffed"));
        assertFalse(content.contains("..This is dot-stuffed"));
    }

    @Test
    public void testRetrNoSuchMessage() {
        enterTransactionState();

        RecordingRetrHandler retrHandler = new RecordingRetrHandler();
        greetingHandler.transactionState.retr(99, retrHandler);

        receiveResponse("-ERR no such message");

        assertTrue(retrHandler.noSuchMessage);
    }

    @Test
    public void testRetrDeletedMessage() {
        enterTransactionState();

        RecordingRetrHandler retrHandler = new RecordingRetrHandler();
        greetingHandler.transactionState.retr(1, retrHandler);

        receiveResponse("-ERR message 1 already deleted");

        assertTrue(retrHandler.messageDeleted);
    }

    // ── TOP tests ──

    @Test
    public void testTopSuccess() {
        enterTransactionState();

        RecordingTopHandler topHandler = new RecordingTopHandler();
        greetingHandler.transactionState.top(1, 5, topHandler);
        assertEquals("TOP 1 5", lastSentCommand());

        receiveResponse("+OK top of message follows");

        byte[] bodyData = ("Subject: Test\r\n\r\n"
                + "Line 1\r\n.\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(bodyData));

        assertTrue(topHandler.topComplete);
        String content = topHandler.collectedContent();
        assertEquals("Subject: Test\r\n\r\nLine 1\r\n", content);
    }

    @Test
    public void testTopNoSuchMessage() {
        enterTransactionState();

        RecordingTopHandler topHandler = new RecordingTopHandler();
        greetingHandler.transactionState.top(99, 0, topHandler);

        receiveResponse("-ERR no such message");

        assertTrue(topHandler.noSuchMessage);
    }

    @Test
    public void testTopDeletedMessage() {
        enterTransactionState();

        RecordingTopHandler topHandler = new RecordingTopHandler();
        greetingHandler.transactionState.top(1, 0, topHandler);

        receiveResponse("-ERR message 1 already deleted");

        assertTrue(topHandler.messageDeleted);
    }

    // ── DELE tests ──

    @Test
    public void testDeleSuccess() {
        enterTransactionState();

        RecordingDeleHandler deleHandler = new RecordingDeleHandler();
        greetingHandler.transactionState.dele(1, deleHandler);
        assertEquals("DELE 1", lastSentCommand());

        receiveResponse("+OK message 1 deleted");

        assertTrue(deleHandler.deleted);
    }

    @Test
    public void testDeleNoSuchMessage() {
        enterTransactionState();

        RecordingDeleHandler deleHandler = new RecordingDeleHandler();
        greetingHandler.transactionState.dele(99, deleHandler);

        receiveResponse("-ERR no such message");

        assertTrue(deleHandler.noSuchMessage);
    }

    @Test
    public void testDeleAlreadyDeleted() {
        enterTransactionState();

        RecordingDeleHandler deleHandler = new RecordingDeleHandler();
        greetingHandler.transactionState.dele(1, deleHandler);

        receiveResponse("-ERR message 1 already deleted");

        assertTrue(deleHandler.alreadyDeleted);
    }

    // ── UIDL tests ──

    @Test
    public void testUidlSingleMessage() {
        enterTransactionState();

        RecordingUidlHandler uidlHandler = new RecordingUidlHandler();
        greetingHandler.transactionState.uidl(1, uidlHandler);
        assertEquals("UIDL 1", lastSentCommand());

        receiveResponse("+OK 1 whstrstrstrwhq");

        assertTrue(uidlHandler.singleReceived);
        assertEquals(1, uidlHandler.singleNumber);
        assertEquals("whstrstrstrwhq", uidlHandler.singleUid);
    }

    @Test
    public void testUidlAllMessages() {
        enterTransactionState();

        RecordingUidlHandler uidlHandler = new RecordingUidlHandler();
        greetingHandler.transactionState.uidl(uidlHandler);
        assertEquals("UIDL", lastSentCommand());

        receiveMultipleLines("+OK", "1 abc123", "2 def456", ".");

        assertTrue(uidlHandler.uidComplete);
        assertEquals(2, uidlHandler.entries.size());
        assertEquals(1, uidlHandler.entries.get(0).number);
        assertEquals("abc123", uidlHandler.entries.get(0).uid);
        assertEquals(2, uidlHandler.entries.get(1).number);
        assertEquals("def456", uidlHandler.entries.get(1).uid);
    }

    @Test
    public void testUidlNoSuchMessage() {
        enterTransactionState();

        RecordingUidlHandler uidlHandler = new RecordingUidlHandler();
        greetingHandler.transactionState.uidl(99, uidlHandler);

        receiveResponse("-ERR no such message");

        assertTrue(uidlHandler.noSuchMessage);
    }

    // ── RSET tests ──

    @Test
    public void testRset() {
        enterTransactionState();

        RecordingRsetHandler rsetHandler = new RecordingRsetHandler();
        greetingHandler.transactionState.rset(rsetHandler);
        assertEquals("RSET", lastSentCommand());

        receiveResponse("+OK maildrop has 2 messages");

        assertTrue(rsetHandler.resetOk);
    }

    // ── NOOP tests ──

    @Test
    public void testNoop() {
        enterTransactionState();

        RecordingNoopHandler noopHandler = new RecordingNoopHandler();
        greetingHandler.transactionState.noop(noopHandler);
        assertEquals("NOOP", lastSentCommand());

        receiveResponse("+OK");

        assertTrue(noopHandler.ok);
    }

    // ── QUIT test ──

    @Test
    public void testQuit() {
        receiveResponse("+OK POP3 server ready");
        greetingHandler.authState.quit();
        assertEquals("QUIT", lastSentCommand());
    }

    @Test
    public void testQuitFromTransaction() {
        enterTransactionState();
        greetingHandler.transactionState.quit();
        assertEquals("QUIT", lastSentCommand());
    }

    // ── Disconnection test ──

    @Test
    public void testDisconnected() {
        receiveResponse("+OK POP3 server ready");
        handler.disconnected();
        assertTrue(greetingHandler.disconnected);
    }

    // ── Error propagation test ──

    @Test
    public void testTransportError() {
        receiveResponse("+OK POP3 server ready");
        handler.error(new IOException("Connection reset"));
        assertTrue(greetingHandler.errorReceived);
    }

    // ── Full session flow test ──

    @Test
    public void testFullSessionFlow() {
        receiveResponse("+OK POP3 server ready");
        assertTrue(greetingHandler.greetingReceived);

        RecordingUserHandler userHandler = new RecordingUserHandler();
        greetingHandler.authState.user("alice", userHandler);
        receiveResponse("+OK");
        assertTrue(userHandler.accepted);

        RecordingPassHandler passHandler = new RecordingPassHandler();
        userHandler.passwordState.pass("secret", passHandler);
        receiveResponse("+OK maildrop has 3 messages (12345 octets)");
        assertTrue(passHandler.authenticated);

        RecordingStatHandler statHandler = new RecordingStatHandler();
        passHandler.transactionState.stat(statHandler);
        receiveResponse("+OK 3 12345");
        assertEquals(3, statHandler.messageCount);
        assertEquals(12345L, statHandler.totalSize);

        RecordingListHandler listHandler = new RecordingListHandler();
        passHandler.transactionState.list(listHandler);
        receiveMultipleLines("+OK", "1 1000", "2 2000", "3 9345", ".");
        assertTrue(listHandler.listComplete);
        assertEquals(3, listHandler.entries.size());

        RecordingRetrHandler retrHandler = new RecordingRetrHandler();
        passHandler.transactionState.retr(1, retrHandler);
        receiveResponse("+OK 1000 octets");
        handler.receive(ByteBuffer.wrap(
                "From: test@example.com\r\nSubject: Hi\r\n\r\nBody\r\n.\r\n"
                        .getBytes(StandardCharsets.US_ASCII)));
        assertTrue(retrHandler.messageComplete);

        RecordingDeleHandler deleHandler = new RecordingDeleHandler();
        passHandler.transactionState.dele(1, deleHandler);
        receiveResponse("+OK message 1 deleted");
        assertTrue(deleHandler.deleted);

        passHandler.transactionState.quit();
        assertEquals("QUIT", lastSentCommand());
    }

    // ── Multiple commands in sequence ──

    @Test
    public void testMultipleRetrInSequence() {
        enterTransactionState();

        RecordingRetrHandler retrHandler1 = new RecordingRetrHandler();
        greetingHandler.transactionState.retr(1, retrHandler1);
        receiveResponse("+OK");
        handler.receive(ByteBuffer.wrap(
                "Msg1\r\n.\r\n"
                        .getBytes(StandardCharsets.US_ASCII)));
        assertTrue(retrHandler1.messageComplete);
        assertEquals("Msg1\r\n", retrHandler1.collectedContent());

        RecordingRetrHandler retrHandler2 = new RecordingRetrHandler();
        greetingHandler.transactionState.retr(2, retrHandler2);
        receiveResponse("+OK");
        handler.receive(ByteBuffer.wrap(
                "Msg2\r\n.\r\n"
                        .getBytes(StandardCharsets.US_ASCII)));
        assertTrue(retrHandler2.messageComplete);
        assertEquals("Msg2\r\n", retrHandler2.collectedContent());
    }

    // ── isOpen / close tests ──

    @Test
    public void testIsOpenAfterConnect() {
        receiveResponse("+OK POP3 server ready");
        assertTrue(handler.isOpen());
    }

    @Test
    public void testIsOpenAfterClose() {
        receiveResponse("+OK POP3 server ready");
        handler.close();
        assertFalse(handler.isOpen());
    }

    @Test
    public void testNullHandlerThrows() {
        try {
            new POP3ClientProtocolHandler(null);
            fail("Expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
    }

    // ── Helper to get handler into TRANSACTION state ──

    private void enterTransactionState() {
        receiveResponse("+OK POP3 server ready");

        RecordingUserHandler userHandler = new RecordingUserHandler();
        greetingHandler.authState.user("alice", userHandler);
        receiveResponse("+OK");

        RecordingPassHandler passHandler = new RecordingPassHandler();
        userHandler.passwordState.pass("secret", passHandler);
        receiveResponse("+OK Maildrop locked");

        greetingHandler.transactionState = passHandler.transactionState;
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
                String s = new String(data, StandardCharsets.US_ASCII);
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
            return new InetSocketAddress("127.0.0.1", 110);
        }
        @Override public boolean isSecure() { return false; }
        @Override public SecurityInfo getSecurityInfo() {
            return new StubSecurityInfo();
        }
        @Override public void startTLS() { startTLSCalled = true; }
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public TimerHandle scheduleTimer(long delayMs,
                                                   Runnable callback) {
            return new TimerHandle() {
                @Override public void cancel() {}
                @Override public boolean isCancelled() { return false; }
            };
        }
        @Override public Trace getTrace() { return null; }
        @Override public void setTrace(Trace trace) {}
        @Override public boolean isTelemetryEnabled() { return false; }
        @Override public TelemetryConfig getTelemetryConfig() {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Stub SecurityInfo
    // ═══════════════════════════════════════════════════════════════════

    static class StubSecurityInfo implements SecurityInfo {
        @Override public String getProtocol() { return "TLSv1.3"; }
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
        @Override public String getApplicationProtocol() { return null; }
        @Override public long getHandshakeDurationMs() { return 5; }
        @Override public boolean isSessionResumed() { return false; }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Recording handler implementations
    // ═══════════════════════════════════════════════════════════════════

    static class RecordingGreetingHandler implements ServerGreeting {
        boolean greetingReceived;
        String greetingMessage;
        String apopTimestamp;
        ClientAuthorizationState authState;
        ClientTransactionState transactionState;

        boolean serviceUnavailable;
        String unavailableMessage;

        boolean disconnected;
        boolean errorReceived;

        @Override
        public void handleGreeting(ClientAuthorizationState auth,
                                   String message, String apopTimestamp) {
            greetingReceived = true;
            greetingMessage = message;
            this.apopTimestamp = apopTimestamp;
            this.authState = auth;
        }

        @Override
        public void handleServiceUnavailable(String message) {
            serviceUnavailable = true;
            unavailableMessage = message;
        }

        @Override public void onConnected(Endpoint endpoint) {}
        @Override public void onError(Exception cause) {
            errorReceived = true;
        }
        @Override public void onDisconnected() {
            disconnected = true;
        }
        @Override public void onSecurityEstablished(SecurityInfo info) {}
    }

    static class RecordingCapaHandler implements ServerCapaReplyHandler {
        boolean capabilitiesReceived;
        boolean stls, top, uidl, user, pipelining;
        List<String> saslMechanisms;
        String implementation;

        boolean errorReceived;
        String errorMessage;

        @Override
        public void handleCapabilities(ClientAuthorizationState auth,
                                       boolean stls,
                                       List<String> saslMechanisms,
                                       boolean top, boolean uidl,
                                       boolean user, boolean pipelining,
                                       String implementation) {
            capabilitiesReceived = true;
            this.stls = stls;
            this.saslMechanisms = saslMechanisms;
            this.top = top;
            this.uidl = uidl;
            this.user = user;
            this.pipelining = pipelining;
            this.implementation = implementation;
        }

        @Override
        public void handleError(ClientAuthorizationState auth,
                                String message) {
            errorReceived = true;
            errorMessage = message;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class RecordingUserHandler
            implements ServerUserReplyHandler {
        boolean accepted;
        ClientPasswordState passwordState;
        boolean rejected;
        String rejectedMessage;

        @Override
        public void handleUserAccepted(ClientPasswordState pass) {
            accepted = true;
            passwordState = pass;
        }

        @Override
        public void handleRejected(ClientAuthorizationState auth,
                                   String message) {
            rejected = true;
            rejectedMessage = message;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class RecordingPassHandler
            implements ServerPassReplyHandler {
        boolean authenticated;
        ClientTransactionState transactionState;
        boolean authFailed;
        String authFailedMessage;

        @Override
        public void handleAuthenticated(
                ClientTransactionState transaction) {
            authenticated = true;
            transactionState = transaction;
        }

        @Override
        public void handleAuthFailed(ClientAuthorizationState auth,
                                     String message) {
            authFailed = true;
            authFailedMessage = message;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class RecordingApopHandler
            implements ServerApopReplyHandler {
        boolean authenticated;
        ClientTransactionState transactionState;
        boolean authFailed;
        String authFailedMessage;

        @Override
        public void handleAuthenticated(
                ClientTransactionState transaction) {
            authenticated = true;
            transactionState = transaction;
        }

        @Override
        public void handleAuthFailed(ClientAuthorizationState auth,
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
        ClientTransactionState transactionState;
        boolean challengeReceived;
        byte[] challenge;
        ClientAuthExchange authExchange;
        boolean authFailed;
        String authFailedMessage;

        void reset() {
            authSuccess = false;
            transactionState = null;
            challengeReceived = false;
            challenge = null;
            authFailed = false;
            authFailedMessage = null;
        }

        @Override
        public void handleAuthSuccess(
                ClientTransactionState transaction) {
            authSuccess = true;
            transactionState = transaction;
        }

        @Override
        public void handleChallenge(byte[] challenge,
                                    ClientAuthExchange exchange) {
            challengeReceived = true;
            this.challenge = challenge;
            this.authExchange = exchange;
        }

        @Override
        public void handleAuthFailed(ClientAuthorizationState auth,
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
        public void handleAborted(ClientAuthorizationState auth) {
            aborted = true;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class RecordingStlsHandler
            implements ServerStlsReplyHandler {
        boolean tlsEstablished;
        ClientPostStls postStls;
        boolean tlsUnavailable;
        boolean permanentFailure;

        @Override
        public void handleTlsEstablished(ClientPostStls postStls) {
            tlsEstablished = true;
            this.postStls = postStls;
        }

        @Override
        public void handleTlsUnavailable(
                ClientAuthorizationState auth) {
            tlsUnavailable = true;
        }

        @Override
        public void handlePermanentFailure(String message) {
            permanentFailure = true;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class RecordingStatHandler
            implements ServerStatReplyHandler {
        boolean statReceived;
        int messageCount;
        long totalSize;
        boolean errorReceived;
        String errorMessage;

        @Override
        public void handleStat(ClientTransactionState transaction,
                                int messageCount, long totalSize) {
            statReceived = true;
            this.messageCount = messageCount;
            this.totalSize = totalSize;
        }

        @Override
        public void handleError(ClientTransactionState transaction,
                                String message) {
            errorReceived = true;
            errorMessage = message;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class ListEntry {
        final int number;
        final long size;
        ListEntry(int number, long size) {
            this.number = number;
            this.size = size;
        }
    }

    static class RecordingListHandler
            implements ServerListReplyHandler {
        boolean singleReceived;
        int singleMessageNumber;
        long singleSize;
        List<ListEntry> entries = new ArrayList<ListEntry>();
        boolean listComplete;
        boolean noSuchMessage;
        boolean errorReceived;

        @Override
        public void handleListing(ClientTransactionState transaction,
                                  int messageNumber, long size) {
            singleReceived = true;
            singleMessageNumber = messageNumber;
            singleSize = size;
        }

        @Override
        public void handleListEntry(int messageNumber, long size) {
            entries.add(new ListEntry(messageNumber, size));
        }

        @Override
        public void handleListComplete(
                ClientTransactionState transaction) {
            listComplete = true;
        }

        @Override
        public void handleNoSuchMessage(
                ClientTransactionState transaction, String message) {
            noSuchMessage = true;
        }

        @Override
        public void handleError(ClientTransactionState transaction,
                                String message) {
            errorReceived = true;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class RecordingRetrHandler
            implements ServerRetrReplyHandler {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        boolean messageComplete;
        boolean noSuchMessage;
        boolean messageDeleted;

        String collectedContent() {
            return collected.toString(StandardCharsets.US_ASCII);
        }

        @Override
        public void handleMessageContent(ByteBuffer content) {
            byte[] bytes = new byte[content.remaining()];
            content.get(bytes);
            collected.write(bytes, 0, bytes.length);
        }

        @Override
        public void handleMessageComplete(
                ClientTransactionState transaction) {
            messageComplete = true;
        }

        @Override
        public void handleNoSuchMessage(
                ClientTransactionState transaction, String message) {
            noSuchMessage = true;
        }

        @Override
        public void handleMessageDeleted(
                ClientTransactionState transaction, String message) {
            messageDeleted = true;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class RecordingTopHandler
            implements ServerTopReplyHandler {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        boolean topComplete;
        boolean noSuchMessage;
        boolean messageDeleted;

        String collectedContent() {
            return collected.toString(StandardCharsets.US_ASCII);
        }

        @Override
        public void handleTopContent(ByteBuffer content) {
            byte[] bytes = new byte[content.remaining()];
            content.get(bytes);
            collected.write(bytes, 0, bytes.length);
        }

        @Override
        public void handleTopComplete(
                ClientTransactionState transaction) {
            topComplete = true;
        }

        @Override
        public void handleNoSuchMessage(
                ClientTransactionState transaction, String message) {
            noSuchMessage = true;
        }

        @Override
        public void handleMessageDeleted(
                ClientTransactionState transaction, String message) {
            messageDeleted = true;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class RecordingDeleHandler
            implements ServerDeleReplyHandler {
        boolean deleted;
        boolean noSuchMessage;
        boolean alreadyDeleted;

        @Override
        public void handleDeleted(ClientTransactionState transaction) {
            deleted = true;
        }

        @Override
        public void handleNoSuchMessage(
                ClientTransactionState transaction, String message) {
            noSuchMessage = true;
        }

        @Override
        public void handleAlreadyDeleted(
                ClientTransactionState transaction, String message) {
            alreadyDeleted = true;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class UidlEntry {
        final int number;
        final String uid;
        UidlEntry(int number, String uid) {
            this.number = number;
            this.uid = uid;
        }
    }

    static class RecordingUidlHandler
            implements ServerUidlReplyHandler {
        boolean singleReceived;
        int singleNumber;
        String singleUid;
        List<UidlEntry> entries = new ArrayList<UidlEntry>();
        boolean uidComplete;
        boolean noSuchMessage;
        boolean errorReceived;

        @Override
        public void handleUid(ClientTransactionState transaction,
                               int messageNumber, String uid) {
            singleReceived = true;
            singleNumber = messageNumber;
            singleUid = uid;
        }

        @Override
        public void handleUidEntry(int messageNumber, String uid) {
            entries.add(new UidlEntry(messageNumber, uid));
        }

        @Override
        public void handleUidComplete(
                ClientTransactionState transaction) {
            uidComplete = true;
        }

        @Override
        public void handleNoSuchMessage(
                ClientTransactionState transaction, String message) {
            noSuchMessage = true;
        }

        @Override
        public void handleError(ClientTransactionState transaction,
                                String message) {
            errorReceived = true;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class RecordingRsetHandler
            implements ServerRsetReplyHandler {
        boolean resetOk;

        @Override
        public void handleResetOk(ClientTransactionState transaction) {
            resetOk = true;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }

    static class RecordingNoopHandler
            implements ServerNoopReplyHandler {
        boolean ok;

        @Override
        public void handleOk(ClientTransactionState transaction) {
            ok = true;
        }

        @Override
        public void handleServiceClosing(String message) {}
    }
}
