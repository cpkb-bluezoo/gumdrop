/*
 * SmtpClientSessionFlowTest.java
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

package org.bluezoo.gumdrop.smtp.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;

import static org.junit.Assert.*;

/**
 * Drives {@link SmtpClientProtocolHandler} through complete client
 * conversations (greeting, EHLO/HELO, STARTTLS, AUTH, envelope, DATA and
 * BDAT transfer, RSET, VRFY, QUIT) with a recording reply handler,
 * covering every reply-code branch.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SmtpClientSessionFlowTest {

    private final List<String> sent = new ArrayList<String>();
    private TlsFailingEndpoint endpoint;
    private SmtpClientProtocolHandler handler;
    private Recorder rec;

    @Before
    public void setUp() {
        sent.clear();
        rec = new Recorder();
        endpoint = new TlsFailingEndpoint(sent);
        handler = new SmtpClientProtocolHandler(rec);
        handler.connected(endpoint);
    }

    private void reply(String text) {
        handler.receive(ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII)));
    }

    private static EmailAddress addr(String address) {
        int at = address.indexOf('@');
        return new EmailAddress(null, address.substring(0, at),
                address.substring(at + 1), true);
    }

    private String last() {
        return sent.isEmpty() ? "" : sent.get(sent.size() - 1);
    }

    /** Greeting, then EHLO with the given capability lines. */
    private void ready(String... capabilityLines) {
        reply("220 mx.example.com ESMTP ready\r\n");
        handler.ehlo("client.example.com", rec);
        StringBuilder sb = new StringBuilder();
        sb.append("250-mx.example.com\r\n");
        for (int i = 0; i < capabilityLines.length; i++) {
            sb.append("250-").append(capabilityLines[i]).append("\r\n");
        }
        sb.append("250 HELP\r\n");
        reply(sb.toString());
    }

    private void toEnvelope(String... caps) {
        ready(caps);
        handler.mailFrom(addr("s@example.org"), rec);
        reply("250 ok\r\n");
        handler.rcptTo(addr("r@example.net"), rec);
        reply("250 ok\r\n");
    }

    // -- greeting --

    @Test
    public void testGreetingEsmtp() {
        assertTrue(handler.isConnected());
        reply("220 mx.example.com ESMTP ready\r\n");
        assertTrue(rec.connected);
        assertTrue(rec.greetingEsmtp);
    }

    @Test
    public void testGreetingPlainSmtp() {
        reply("220 mx.example.com hello\r\n");
        assertFalse(rec.greetingEsmtp);
    }

    @Test
    public void testGreetingRefused() {
        reply("554 no service\r\n");
        assertEquals("554 no service", rec.unavailable);
        assertFalse(handler.isConnected());
    }

    @Test
    public void testGreeting421() {
        reply("421 too busy\r\n");
        assertEquals("421 too busy", rec.unavailable);
    }

    @Test
    public void testMultilineGreeting() {
        reply("220-first line\r\n220 ESMTP second line\r\n");
        assertTrue(rec.connected);
    }

    @Test
    public void testMalformedReplyCodeReportsError() {
        reply("2x0 bad\r\n");
        assertNotNull(rec.error);
        reply("22 short\r\n");
        assertNotNull(rec.error);
    }

    @Test
    public void testBlankLineIgnored() {
        reply("\r\n");
        assertNull(rec.error);
    }

    @Test
    public void testSlicedReply() {
        byte[] wire = "220 mx.example.com ESMTP\r\n".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer netIn = ByteBuffer.allocate(256);
        for (int i = 0; i < wire.length; i++) {
            netIn.put(wire[i]);
            netIn.flip();
            handler.receive(netIn);
            netIn.compact();
        }
        assertTrue(rec.connected);
    }

    @Test
    public void testSetters() {
        assertTrue(handler.isChunkingEnabled());
        handler.setChunkingEnabled(false);
        assertFalse(handler.isChunkingEnabled());
        handler.setSecure(true);
        try {
            new SmtpClientProtocolHandler(null);
            fail("null handler");
        } catch (NullPointerException expected) {
            assertNotNull(expected);
        }
    }

    // -- EHLO / HELO --

    @Test
    public void testEhloSuccessCapabilities() {
        ready("STARTTLS", "SIZE 1000", "AUTH PLAIN LOGIN", "PIPELINING", "SIZE bogus",
                "LIMITS RCPTMAX=x MAILMAX=y");
        assertTrue(rec.ehloStarttls);
        assertEquals(1000L, rec.ehloMaxSize);
        assertEquals(2, rec.ehloAuth.size());
        assertTrue(rec.ehloPipelining);
    }

    @Test
    public void testEhloNotSupportedFallsBackToHelo() {
        reply("220 mx ESMTP\r\n");
        handler.ehlo("c.example.com", rec);
        reply("502 not implemented\r\n");
        assertTrue(rec.ehloNotSupported);
        handler.helo("c.example.com", rec);
        assertEquals("HELO c.example.com", last());
        reply("250 hello\r\n");
        assertTrue(rec.heloOk);
    }

    @Test
    public void testEhloFailures() {
        reply("220 mx ESMTP\r\n");
        handler.ehlo("c.example.com", rec);
        reply("550 denied\r\n");
        assertEquals("denied", rec.permanent);
        assertFalse(handler.isConnected());
        setUp();
        reply("220 mx ESMTP\r\n");
        handler.ehlo("c.example.com", rec);
        reply("451 try later\r\n");
        assertEquals("451 try later", rec.permanent);
    }

    @Test
    public void testHeloFailure() {
        reply("220 mx\r\n");
        handler.helo("c.example.com", rec);
        reply("550 denied\r\n");
        assertEquals("denied", rec.permanent);
    }

    @Test
    public void testService421DuringEhlo() {
        reply("220 mx ESMTP\r\n");
        handler.ehlo("c.example.com", rec);
        reply("421 going away\r\n");
        assertEquals("going away", rec.closing);
    }

    // -- STARTTLS --

    @Test
    public void testStarttlsSuccess() {
        ready("STARTTLS");
        handler.starttls(rec);
        assertEquals("STARTTLS", last());
        reply("220 go ahead\r\n");
        handler.securityEstablished(null);
        assertTrue(rec.tlsEstablished);
        handler.ehlo("c.example.com", rec);
        assertEquals("EHLO c.example.com", last());
    }

    @Test
    public void testStarttlsRefused() {
        ready("STARTTLS");
        handler.starttls(rec);
        reply("454 not available\r\n");
        assertTrue(rec.tlsUnavailable);
        handler.starttls(rec);
        reply("502 no\r\n");
        assertEquals(2, rec.tlsUnavailableCount);
    }

    @Test
    public void testStarttlsPermanentFailure() {
        ready("STARTTLS");
        handler.starttls(rec);
        reply("550 forbidden\r\n");
        assertEquals("forbidden", rec.permanent);
    }

    @Test
    public void testStarttlsHandshakeFailureToStart() {
        endpoint.failTls = true;
        ready("STARTTLS");
        handler.starttls(rec);
        reply("220 go\r\n");
        assertTrue(rec.tlsUnavailable);
    }

    @Test
    public void testSecurityEstablishedWithoutPendingStarttls() {
        ready();
        handler.securityEstablished(null);
        assertFalse(rec.tlsEstablished);
    }

    // -- AUTH --

    @Test
    public void testAuthSuccess() {
        ready("AUTH PLAIN");
        handler.auth("PLAIN", "\0u\0p".getBytes(StandardCharsets.US_ASCII), rec);
        assertTrue(last().startsWith("AUTH PLAIN "));
        reply("235 welcome\r\n");
        assertTrue(rec.authOk);
    }

    @Test
    public void testAuthChallengeRespond() {
        ready("AUTH LOGIN");
        handler.auth("LOGIN", null, rec);
        assertEquals("AUTH LOGIN", last());
        reply("334 VXNlcm5hbWU6\r\n");
        assertNotNull(rec.exchange);
        assertEquals("Username:", new String(rec.challenge, StandardCharsets.US_ASCII));
        rec.exchange.respond("alice".getBytes(StandardCharsets.US_ASCII), rec);
        reply("235 ok\r\n");
        assertTrue(rec.authOk);
    }

    @Test
    public void testAuthAbort() {
        ready("AUTH LOGIN");
        handler.auth("LOGIN", null, rec);
        reply("334 VXNlcm5hbWU6\r\n");
        rec.exchange.abort(rec);
        assertEquals("*", last());
        reply("501 aborted\r\n");
        assertTrue(rec.aborted);
    }

    @Test
    public void testAuthFailureCodes() {
        ready("AUTH PLAIN");
        handler.auth("PLAIN", null, rec);
        reply("535 bad\r\n");
        assertEquals(1, rec.authFailed);
        handler.auth("PLAIN", null, rec);
        reply("504 no mech\r\n");
        assertTrue(rec.mechNotSupported);
        handler.auth("PLAIN", null, rec);
        reply("454 temp\r\n");
        assertTrue(rec.authTemporary);
        handler.auth("PLAIN", null, rec);
        reply("500 other\r\n");
        assertEquals(2, rec.authFailed);
    }

    // -- envelope --

    @Test
    public void testMailFromOutcomes() {
        ready();
        handler.mailFrom(addr("s@example.org"), rec);
        assertEquals("MAIL FROM:<s@example.org>", last());
        reply("250 ok\r\n");
        assertTrue(rec.mailOk);
        handler.rset(rec);
        reply("250 reset\r\n");
        assertTrue(rec.resetOk);
        handler.mailFrom(addr("s@example.org"), rec);
        reply("451 later\r\n");
        assertEquals(1, rec.sessionTemp);
        handler.mailFrom(null, rec);
        assertEquals("MAIL FROM:<>", last());
        reply("550 no\r\n");
        assertEquals("no", rec.permanent);
    }

    @Test
    public void testMailFromSizeUsedWhenAdvertised() {
        ready("SIZE 5000");
        handler.mailFrom(addr("s@example.org"), 100L, rec);
        assertEquals("MAIL FROM:<s@example.org> SIZE=100", last());
    }

    @Test
    public void testRcptOutcomes() {
        ready();
        handler.mailFrom(addr("s@example.org"), rec);
        reply("250 ok\r\n");
        assertFalse(rec.envelope.hasAcceptedRecipients());
        handler.rcptTo(addr("a@example.net"), rec);
        reply("451 busy\r\n");
        assertEquals(1, rec.rcptTemp);
        handler.rcptTo(addr("b@example.net"), rec);
        reply("550 unknown\r\n");
        assertEquals(1, rec.rcptRejected);
        handler.rcptTo(addr("c@example.net"), rec);
        reply("251 forwarded\r\n");
        handler.rcptTo(addr("d@example.net"), rec);
        reply("252 cannot verify\r\n");
        assertEquals(2, rec.rcptOk);
        assertTrue(rec.ready.hasAcceptedRecipients());
        handler.rcptTo(addr("e@example.net"), rec);
        reply("450 busy\r\n");
        handler.rcptTo(addr("f@example.net"), rec);
        reply("553 bad\r\n");
        assertEquals(2, rec.rcptTemp);
        assertEquals(2, rec.rcptRejected);
    }

    @Test
    public void testDataWithoutRecipientsIsRefused() {
        ready();
        try {
            handler.data(rec);
            fail("no recipients");
        } catch (IllegalStateException expected) {
            assertNotNull(expected);
        }
    }

    // -- DATA --

    @Test
    public void testDataTransferAccepted() {
        toEnvelope();
        rec.ready.data(rec);
        assertEquals("DATA", last());
        reply("354 go\r\n");
        assertNotNull(rec.data);
        rec.data.writeContent(ByteBuffer.wrap("Subject: hi".getBytes(StandardCharsets.US_ASCII)));
        rec.data.endMessage(rec);
        reply("250 2.0.0 queued as ABC123 for delivery\r\n");
        assertEquals("ABC123", rec.queueId);
    }

    @Test
    public void testDataMessageOutcomes() {
        toEnvelope();
        rec.ready.data(rec);
        reply("354 go\r\n");
        rec.data.endMessage(rec);
        reply("250 plain ok\r\n");
        assertNull(rec.queueId);
        assertTrue(rec.accepted);
        handler.rset(rec);
        reply("250 reset\r\n");
        handler.mailFrom(addr("s@example.org"), rec);
        reply("250 ok\r\n");
        handler.rcptTo(addr("r@example.net"), rec);
        reply("250 ok\r\n");
        rec.ready.data(rec);
        reply("354 go\r\n");
        rec.data.endMessage(rec);
        reply("451 try later\r\n");
        assertEquals(1, rec.sessionTemp);
        handler.mailFrom(addr("s@example.org"), rec);
        reply("250 ok\r\n");
        handler.rcptTo(addr("r@example.net"), rec);
        reply("250 ok\r\n");
        rec.ready.data(rec);
        reply("354 go\r\n");
        rec.data.endMessage(rec);
        reply("554 rejected\r\n");
        assertEquals("rejected", rec.messagePermanent);
    }

    @Test
    public void testDataCommandRefused() {
        toEnvelope();
        rec.ready.data(rec);
        reply("451 busy\r\n");
        assertEquals(1, rec.dataTemp);
        rec.ready.data(rec);
        reply("554 no\r\n");
        assertEquals("no", rec.permanent);
    }

    @Test
    public void testWriteContentOutsideDataModeFails() {
        ready();
        try {
            handler.writeContent(ByteBuffer.wrap(new byte[0]));
            fail("not in data mode");
        } catch (IllegalStateException expected) {
            assertNotNull(expected);
        }
        try {
            handler.endMessage(rec);
            fail("not in data mode");
        } catch (IllegalStateException expected) {
            assertNotNull(expected);
        }
    }

    @Test
    public void testBdatTransfer() {
        toEnvelope("CHUNKING");
        rec.ready.data(rec);
        assertNotNull(rec.data);
        rec.data.writeContent(ByteBuffer.wrap("hello".getBytes(StandardCharsets.US_ASCII)));
        rec.data.writeContent(ByteBuffer.allocate(0));
        assertTrue(sent.contains("BDAT 5"));
        rec.data.endMessage(rec);
        assertEquals("BDAT 0 LAST", last());
        reply("250 chunk ok\r\n");
        reply("250 queued as Q9\r\n");
    }

    @Test
    public void testBdatChunkFailures() {
        toEnvelope("CHUNKING");
        rec.ready.data(rec);
        rec.data.writeContent(ByteBuffer.wrap("x".getBytes(StandardCharsets.US_ASCII)));
        rec.data.endMessage(rec);
        reply("451 temp\r\n");
        assertEquals(1, rec.sessionTemp);
        setUp();
        toEnvelope("CHUNKING");
        rec.ready.data(rec);
        rec.data.writeContent(ByteBuffer.wrap("x".getBytes(StandardCharsets.US_ASCII)));
        rec.data.endMessage(rec);
        reply("554 perm\r\n");
        assertEquals("perm", rec.messagePermanent);
    }

    @Test
    public void testChunkingDisabledUsesData() {
        handler.setChunkingEnabled(false);
        toEnvelope("CHUNKING");
        rec.ready.data(rec);
        assertEquals("DATA", last());
    }

    @Test
    public void testOnWriteReadyDelegates() {
        ready();
        final boolean[] ran = {false};
        handler.onWriteReady(new Runnable() {
            @Override
            public void run() {
                ran[0] = true;
            }
        });
        assertFalse(ran[0]);
    }

    // -- misc commands --

    @Test
    public void testVrfyExpnReplies() {
        ready();
        handler.vrfy("alice", rec);
        reply("252 cannot verify\r\n");
        assertEquals(252, rec.replyCode);
        handler.expn("staff", rec);
        reply("502 no\r\n");
        assertEquals(502, rec.replyCode);
    }

    @Test
    public void testQuitClosesConnection() {
        ready();
        handler.quit();
        assertEquals("QUIT", last());
        reply("221 bye\r\n");
        assertFalse(handler.isConnected());
        assertFalse(handler.isOpen());
        handler.close();
    }

    @Test
    public void testResponsesAfterCloseIgnored() {
        ready();
        handler.close();
        reply("250 late\r\n");
        assertNull(rec.error);
    }

    @Test
    public void testDisconnectedNotifies() {
        handler.disconnected();
        assertTrue(rec.disconnected);
        assertFalse(handler.isConnected());
    }

    @Test
    public void testTransportError() {
        handler.error(new IOException("reset"));
        assertNotNull(rec.error);
    }

    @Test
    public void testCommandsWhenNotConnectedReportError() {
        handler.close();
        handler.ehlo("c.example.com", rec);
        assertNotNull(rec.error);
        try {
            handler.write(ByteBuffer.wrap(new byte[] {1}));
            fail("not connected");
        } catch (IOException expected) {
            assertNotNull(expected);
        }
    }

    @Test
    public void testWriteWhenConnected() throws IOException {
        ready();
        int n = handler.write(ByteBuffer.wrap("abc".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(3, n);
        assertTrue(handler.isOpen());
    }

    @Test
    public void testCrlfInjectionRejected() {
        ready();
        try {
            handler.ehlo("evil\r\nMAIL", rec);
            fail("CRLF must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected);
        }
        try {
            handler.vrfy("a\nb", rec);
            fail("CRLF must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected);
        }
    }

    // -- helpers --

    /** Endpoint whose startTLS can be made to fail. */
    private static final class TlsFailingEndpoint extends SMTPClientProtocolHandlerTest.StubEndpoint {
        boolean failTls;

        TlsFailingEndpoint(List<String> sent) {
            super(sent);
        }

        @Override
        public void startTLS() throws IOException {
            if (failTls) {
                throw new IOException("tls failed");
            }
        }
    }

    /** Records every callback invoked on it. */
    private static final class Recorder implements RemoteGreeting, EhloReplyHandler,
            HeloReplyHandler, StarttlsReplyHandler, AuthReplyHandler, AuthAbortHandler,
            MailFromReplyHandler, RcptToReplyHandler, DataReplyHandler,
            MessageReplyHandler, RsetReplyHandler {

        boolean connected;
        boolean disconnected;
        boolean greetingEsmtp;
        boolean ehloStarttls;
        boolean ehloPipelining;
        boolean ehloNotSupported;
        boolean heloOk;
        boolean tlsEstablished;
        boolean tlsUnavailable;
        boolean authOk;
        boolean aborted;
        boolean mechNotSupported;
        boolean authTemporary;
        boolean mailOk;
        boolean resetOk;
        boolean accepted;
        long ehloMaxSize;
        int tlsUnavailableCount;
        int authFailed;
        int sessionTemp;
        int rcptTemp;
        int rcptRejected;
        int rcptOk;
        int dataTemp;
                int replyCode;
        String unavailable;
        String permanent;
        String closing;
        String messagePermanent;
        String queueId;
        Exception error;
        List<String> ehloAuth = new ArrayList<String>();
        byte[] challenge;
        ClientAuthExchange exchange;
        ClientEnvelope envelope;
        ClientEnvelopeReady ready;
        ClientMessageData data;

        @Override
        public void onConnected(Endpoint ep) {
            connected = true;
        }

        @Override
        public void onDisconnected() {
            disconnected = true;
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
        }

        @Override
        public void onError(Exception cause) {
            error = cause;
        }

        @Override
        public void handleGreeting(ClientHelloState hello, String message, boolean esmtp) {
            greetingEsmtp = esmtp;
        }

        @Override
        public void handleServiceUnavailable(String message) {
            unavailable = message;
        }

        @Override
        public void handleServiceClosing(String message) {
            closing = message;
        }

        @Override
        public void handleEhlo(ClientSession session, boolean starttls, long maxSize,
                List<String> authMethods, boolean pipelining) {
            ehloStarttls = starttls;
            ehloMaxSize = maxSize;
            ehloAuth = authMethods;
            ehloPipelining = pipelining;
        }

        @Override
        public void handleEhloNotSupported(ClientHelloState hello) {
            ehloNotSupported = true;
        }

        @Override
        public void handlePermanentFailure(String message) {
            permanent = message;
        }

        @Override
        public void handleHelo(ClientSession session) {
            heloOk = true;
        }

        @Override
        public void handleTlsEstablished(ClientPostTls postTls) {
            tlsEstablished = true;
        }

        @Override
        public void handleTlsUnavailable(ClientSession session) {
            tlsUnavailable = true;
            tlsUnavailableCount++;
        }

        @Override
        public void handleAuthSuccess(ClientSession session) {
            authOk = true;
        }

        @Override
        public void handleChallenge(byte[] challengeBytes, ClientAuthExchange ex) {
            challenge = challengeBytes;
            exchange = ex;
        }

        @Override
        public void handleAuthFailed(ClientSession session) {
            authFailed++;
        }

        @Override
        public void handleMechanismNotSupported(ClientSession session) {
            mechNotSupported = true;
        }

        @Override
        public void handleAborted(ClientSession session) {
            aborted = true;
        }

        @Override
        public void handleMailFromOk(ClientEnvelope env) {
            mailOk = true;
            envelope = env;
        }

        @Override
        public void handleTemporaryFailure(ClientSession session) {
            sessionTemp++;
            authTemporary = true;
        }

        @Override
        public void handleRcptToOk(ClientEnvelopeReady env) {
            rcptOk++;
            ready = env;
        }

        @Override
        public void handleTemporaryFailure(ClientEnvelopeState state) {
            rcptTemp++;
        }

        @Override
        public void handleRecipientRejected(ClientEnvelopeState state) {
            rcptRejected++;
        }

        @Override
        public void handleReadyForData(ClientMessageData messageData) {
            data = messageData;
        }

        @Override
        public void handleTemporaryFailure(ClientEnvelopeReady env) {
            dataTemp++;
        }

        @Override
        public void handleMessageAccepted(String id, ClientSession session) {
            accepted = true;
            queueId = id;
        }

        @Override
        public void handlePermanentFailure(String message, ClientSession session) {
            messagePermanent = message;
        }

        @Override
        public void handleResetOk(ClientSession session) {
            resetOk = true;
        }

        @Override
        public void handleReply(int code, String message, ClientSession session) {
            replyCode = code;
        }
    }
}
