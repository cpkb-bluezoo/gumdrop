/*
 * SmtpSessionHandlerFlowTest.java
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

package org.bluezoo.gumdrop.smtp;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.smtp.server.AuthenticateState;
import org.bluezoo.gumdrop.smtp.server.ClientConnected;
import org.bluezoo.gumdrop.smtp.server.ConnectedState;
import org.bluezoo.gumdrop.smtp.server.HelloHandler;
import org.bluezoo.gumdrop.smtp.server.HelloState;
import org.bluezoo.gumdrop.smtp.server.MailFromHandler;
import org.bluezoo.gumdrop.smtp.server.MailFromState;
import org.bluezoo.gumdrop.smtp.server.MessageDataHandler;
import org.bluezoo.gumdrop.smtp.server.MessageEndState;
import org.bluezoo.gumdrop.smtp.server.MessageStartState;
import org.bluezoo.gumdrop.smtp.server.RecipientHandler;
import org.bluezoo.gumdrop.smtp.server.RecipientState;
import org.bluezoo.gumdrop.smtp.server.ResetState;

import static org.junit.Assert.*;

/**
 * Drives {@link SmtpProtocolHandler} with a scripted session handler so
 * that each of the staged accept and reject callbacks (connection, hello,
 * sender, recipient, message start, message end, authentication) is
 * exercised.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SmtpSessionHandlerFlowTest {

    private Script script;
    private SmtpListener listener;
    private SmtpProtocolHandler handler;
    private CountingEndpoint endpoint;

    @Before
    public void setUp() {
        script = new Script();
        listener = new SmtpListener();
        endpoint = new CountingEndpoint();
        handler = new SmtpProtocolHandler(listener, script);
    }

    private void connect() {
        handler.connected(endpoint);
        endpoint.sentData.clear();
    }

    private void raw(String text) {
        handler.receive(ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII)));
    }

    private void send(String command) {
        raw(command + "\r\n");
    }

    private String last() {
        List<String> responses = endpoint.getResponses();
        assertFalse("No responses", responses.isEmpty());
        return responses.get(responses.size() - 1);
    }

    private void expect(String command, String code) {
        endpoint.sentData.clear();
        send(command);
        String response = last();
        assertTrue(command + " -> " + response, response.startsWith(code));
    }

    private void toHello() {
        connect();
        expect("EHLO client.example.com", "250");
    }

    private void toMail() {
        toHello();
        expect("MAIL FROM:<a@example.com>", "250");
    }

    private void toRcpt() {
        toMail();
        expect("RCPT TO:<b@example.com>", "250");
    }

    private void checkRcpt(int mode, String code) {
        setUp();
        toMail();
        script.rcptMode = mode;
        expect("RCPT TO:<b@example.com>", code);
    }

    private void checkMail(int mode, String code) {
        setUp();
        toHello();
        script.mailMode = mode;
        expect("MAIL FROM:<a@example.com>", code);
    }

    private void checkEnd(int mode, String code) {
        setUp();
        toRcpt();
        script.endMode = mode;
        expect("DATA", "354");
        raw("hello\r\n.\r\n");
        String response = last();
        assertTrue("end mode " + mode + " -> " + response, response.startsWith(code));
    }

    // -- connection --

    @Test
    public void testAcceptConnection() {
        handler.connected(endpoint);
        assertTrue(script.connected);
        assertTrue(last().startsWith("220"));
        handler.disconnected();
        assertTrue(script.disconnected);
    }

    @Test
    public void testRejectConnection() {
        script.connMode = 1;
        handler.connected(endpoint);
        assertTrue(last().startsWith("554"));
        assertFalse(endpoint.open);
    }

    @Test
    public void testRejectConnectionWithMessage() {
        script.connMode = 2;
        handler.connected(endpoint);
        assertTrue(last().contains("go away"));
        assertFalse(endpoint.open);
    }

    @Test
    public void testServerShuttingDownAtConnect() {
        script.connMode = 3;
        handler.connected(endpoint);
        assertTrue(last().startsWith("421"));
    }

    // -- hello --

    @Test
    public void testHelloAccepted() {
        toHello();
        assertEquals("client.example.com", script.helloName);
        assertTrue(script.helloExtended);
        expect("HELO other.example.com", "250");
        assertFalse(script.helloExtended);
    }

    @Test
    public void testHelloRejections() {
        connect();
        script.helloMode = 1;
        expect("EHLO c.example.com", "4");
        script.helloMode = 2;
        expect("EHLO c.example.com", "5");
        script.helloMode = 0;
        expect("EHLO c.example.com", "250");
    }

    @Test
    public void testHelloRejectAndClose() {
        connect();
        script.helloMode = 3;
        expect("EHLO c.example.com", "5");
        assertFalse(endpoint.open);
    }

    @Test
    public void testHelloServerShuttingDown() {
        connect();
        script.helloMode = 4;
        expect("EHLO c.example.com", "421");
    }

    // -- MAIL FROM --

    @Test
    public void testMailFromAccepted() {
        toMail();
        assertEquals("a@example.com", script.sender);
        assertFalse(script.smtputf8);
        assertNotNull(script.delivery);
    }

    @Test
    public void testMailFromWithRequirements() {
        toHello();
        expect("MAIL FROM:<a@example.com> RET=FULL ENVID=e1 MT-PRIORITY=2 SMTPUTF8", "250");
        assertTrue(script.smtputf8);
        assertEquals(Integer.valueOf(2), script.delivery.getPriority());
        assertEquals("e1", script.delivery.getDsnEnvelopeId());
        assertEquals(DsnReturn.FULL, script.delivery.getDsnReturn());
    }

    @Test
    public void testMailFromRejections() {
        checkMail(1, "450");
        checkMail(2, "450");
        checkMail(3, "452");
        checkMail(4, "5");
        checkMail(5, "5");
        checkMail(6, "5");
        checkMail(7, "5");
        checkMail(8, "5");
        checkMail(9, "421");
    }

    @Test
    public void testResetAccepted() {
        toRcpt();
        script.resetCalls = 0;
        expect("RSET", "250");
        assertEquals(1, script.resetCalls);
        expect("MAIL FROM:<c@example.com>", "250");
    }

    @Test
    public void testResetServerShuttingDown() {
        toRcpt();
        script.resetMode = 1;
        expect("RSET", "421");
    }

    // -- RCPT TO --

    @Test
    public void testRecipientAccepted() {
        toRcpt();
        assertEquals("b@example.com", script.lastRecipient);
        assertNull(script.lastForward);
    }

    @Test
    public void testRecipientRejections() {
        checkRcpt(2, "450");
        checkRcpt(3, "451");
        checkRcpt(4, "452");
        checkRcpt(5, "550");
        checkRcpt(6, "551");
        checkRcpt(7, "552");
        checkRcpt(8, "553");
        checkRcpt(9, "5");
        checkRcpt(10, "5");
        checkRcpt(11, "421");
    }

    @Test
    public void testRecipientForward() {
        toMail();
        script.rcptMode = 1;
        expect("RCPT TO:<b@example.com>", "251");
    }

    // -- DATA split across reads --

    private void checkDataChunked(String message, String trailer, String expectedContent) {
        int total = message.length();
        for (int size = 1; size <= total; size++) {
            setUp();
            toRcpt();
            expect("DATA", "354");
            endpoint.sentData.clear();
            int pos = 0;
            while (pos < total) {
                int end = Math.min(total, pos + size);
                String piece = message.substring(pos, end);
                if (end == total) {
                    // the pipelined trailer rides in the final read
                    piece = piece + trailer;
                }
                raw(piece);
                pos = end;
            }
            List<String> responses = endpoint.getResponses();
            int expectedResponses = trailer.isEmpty() ? 1 : 2;
            assertEquals("responses at chunk size " + size, expectedResponses, responses.size());
            for (int i = 0; i < responses.size(); i++) {
                assertTrue("chunk size " + size + " -> " + responses.get(i),
                        responses.get(i).startsWith("250"));
            }
            assertTrue("completed at chunk size " + size, script.completed);
            String content = new String(script.captured.toByteArray(), StandardCharsets.US_ASCII);
            assertEquals("content at chunk size " + size, expectedContent, content);
        }
    }

    @Test(timeout = 20000)
    public void testDataTerminatorSplitAtEveryChunkSize() {
        checkDataChunked("Subject: x\r\n\r\nline1\r\n.\r\n", "",
                "Subject: x\r\n\r\nline1\r\n");
    }

    @Test(timeout = 20000)
    public void testDataUnstuffingSplitAtEveryChunkSize() {
        checkDataChunked("a\r\n..dot\r\nb\rc\r\n.\r\n", "",
                "a\r\n.dot\r\nb\rc\r\n");
    }

    @Test(timeout = 20000)
    public void testPipelinedCommandAfterSplitTerminator() {
        checkDataChunked("a\r\n.\r\n", "NOOP\r\n", "a\r\n");
    }

    @Test(timeout = 20000)
    public void testDataTerminatorFourByteChunks() {
        toRcpt();
        expect("DATA", "354");
        endpoint.sentData.clear();
        String message = "a\r\n.\r\n";
        for (int i = 0; i < message.length(); i += 4) {
            raw(message.substring(i, Math.min(message.length(), i + 4)));
        }
        assertTrue(last().startsWith("250"));
        assertTrue(script.completed);
    }

    // -- MAILMAX (RFC 9422) --

    @Test
    public void testMaxTransactionsPerSessionEnforced() {
        listener.setMaxTransactionsPerSession(2);
        toHello();
        for (int i = 0; i < 2; i++) {
            expect("MAIL FROM:<a@example.com>", "250");
            expect("RCPT TO:<b@example.com>", "250");
            expect("DATA", "354");
            raw("x\r\n.\r\n");
            assertTrue(last().startsWith("250"));
        }
        expect("MAIL FROM:<a@example.com>", "421");
    }

    // -- DATA --

    @Test
    public void testDataAccepted() {
        toRcpt();
        expect("DATA", "354");
        raw("Subject: hi\r\n\r\nbody\r\n.\r\n");
        assertTrue(last().startsWith("250"));
        assertTrue(script.contentSize > 0);
        assertTrue(script.completed);
    }

    @Test
    public void testStartMessageRejections() {
        int[] modes = {1, 2, 3, 4};
        String[] codes = {"452", "451", "5", "421"};
        for (int i = 0; i < modes.length; i++) {
            setUp();
            toRcpt();
            script.startMode = modes[i];
            expect("DATA", codes[i]);
        }
    }

    @Test
    public void testMessageEndOutcomes() {
        checkEnd(0, "250");
        checkEnd(1, "4");
        checkEnd(2, "5");
        checkEnd(3, "5");
        checkEnd(4, "421");
    }

    @Test
    public void testDataBackPressureAndResume() {
        toRcpt();
        script.pause = true;
        expect("DATA", "354");
        raw("partial content\r\n");
        assertNotNull(script.resume);
        assertTrue(endpoint.pauseCount > 0);
        script.resume.run();
        assertTrue(endpoint.resumeCount > 0);
    }

    @Test
    public void testDisconnectDuringDataAborts() {
        toRcpt();
        expect("DATA", "354");
        raw("partial");
        handler.disconnected();
        assertTrue(script.disconnected);
    }

    // -- BDAT --

    @Test
    public void testBdatThroughHandler() {
        toRcpt();
        endpoint.sentData.clear();
        send("BDAT 5");
        raw("hello");
        assertTrue(last().startsWith("250"));
        send("BDAT 6 LAST");
        raw("world!");
        assertTrue(last().startsWith("250"));
        assertEquals(11, script.contentSize);
        assertTrue(script.completed);
    }

    @Test
    public void testBdatStartRejected() {
        toRcpt();
        script.startMode = 1;
        expect("BDAT 5", "452");
        script.startMode = 2;
        setUp();
        toRcpt();
        script.startMode = 2;
        expect("BDAT 5", "451");
        setUp();
        toRcpt();
        script.startMode = 3;
        expect("BDAT 5", "5");
        setUp();
        toRcpt();
        script.startMode = 4;
        expect("BDAT 5", "421");
    }

    // -- QUIT --

    @Test
    public void testQuitNotifiesHandler() {
        toHello();
        expect("QUIT", "221");
        assertTrue(script.disconnected);
    }

    // -- STARTTLS --

    @Test
    public void testStarttlsAndSecurityEstablished() {
        StartTlsListener tlsListener = new StartTlsListener();
        handler = new SmtpProtocolHandler(tlsListener, script);
        connect();
        expect("EHLO c.example.com", "250");
        endpoint.sentData.clear();
        send("EHLO c.example.com");
        boolean sawStarttls = false;
        List<String> responses = endpoint.getResponses();
        for (int i = 0; i < responses.size(); i++) {
            if (responses.get(i).contains("STARTTLS")) {
                sawStarttls = true;
            }
        }
        assertTrue(sawStarttls);
        expect("STARTTLS", "220");
        handler.securityEstablished(null);
        assertTrue(script.tlsEstablished);
        expect("EHLO c.example.com", "250");
        expect("STARTTLS", "500");
        send("HELP");
    }

    @Test
    public void testStarttlsBadSequence() {
        StartTlsListener tlsListener = new StartTlsListener();
        handler = new SmtpProtocolHandler(tlsListener, script);
        toMail();
        expect("STARTTLS", "503");
    }

    @Test
    public void testImplicitTlsGreeting() {
        endpoint.secure = true;
        handler.connected(endpoint);
        assertTrue(endpoint.getResponses().isEmpty());
        handler.securityEstablished(null);
        assertTrue(last().startsWith("220"));
    }

    @Test
    public void testTransportErrorClosesEndpoint() {
        connect();
        handler.error(new java.io.IOException("boom"));
        assertFalse(endpoint.open);
    }

    // -- XCLIENT --

    @Test
    public void testXclient() {
        XclientListener xl = new XclientListener();
        handler = new SmtpProtocolHandler(xl, script);
        connect();
        expect("EHLO c.example.com", "250");
        expect("XCLIENT NAME=mail.example.org ADDR=192.0.2.1 PORT=2525 PROTO=ESMTP"
                + " HELO=remote.example.org LOGIN=bob DESTADDR=192.0.2.2 DESTPORT=25", "220");
        expect("EHLO remote.example.org", "250");
    }

    @Test
    public void testXclientUnavailableAndErrors() {
        XclientListener xl = new XclientListener();
        handler = new SmtpProtocolHandler(xl, script);
        connect();
        expect("XCLIENT", "501");
        expect("XCLIENT NAME", "501");
        expect("XCLIENT ADDR=not..valid..host!!", "501");
        expect("XCLIENT PORT=99999", "501");
        expect("XCLIENT PORT=abc", "501");
        expect("XCLIENT PROTO=BOGUS", "501");
        expect("XCLIENT DESTADDR=not..valid..host!!", "501");
        expect("XCLIENT DESTPORT=99999", "501");
        expect("XCLIENT DESTPORT=abc", "501");
        expect("XCLIENT BOGUS=1", "501");
        expect("XCLIENT NAME=[UNAVAILABLE] ADDR=[TEMPUNAVAIL] HELO=[UNAVAILABLE]"
                + " DESTADDR=[UNAVAILABLE]", "220");
    }

    @Test
    public void testXclientDuringTransaction() {
        XclientListener xl = new XclientListener();
        handler = new SmtpProtocolHandler(xl, script);
        toMail();
        expect("XCLIENT NAME=x", "503");
    }

    @Test
    public void testHelpMentionsXclientAndAuth() {
        XclientListener xl = new XclientListener();
        xl.setRealm(new SimpleRealm());
        handler = new SmtpProtocolHandler(xl, script);
        connect();
        endpoint.sentData.clear();
        send("HELP");
        boolean sawXclient = false;
        List<String> responses = endpoint.getResponses();
        for (int i = 0; i < responses.size(); i++) {
            if (responses.get(i).contains("XCLIENT")) {
                sawXclient = true;
            }
        }
        assertTrue(sawXclient);
    }

    // -- authentication through the handler --

    @Test
    public void testAuthAcceptedByHandler() {
        listener.setRealm(new SimpleRealm());
        endpoint.secure = true;
        handler = new SmtpProtocolHandler(listener, script);
        handler.connected(endpoint);
        handler.securityEstablished(null);
        raw("EHLO c.example.com\r\n");
        endpoint.sentData.clear();
        String plain = Base64.getEncoder().encodeToString(
                "\0u\0p".getBytes(StandardCharsets.US_ASCII));
        send("AUTH PLAIN " + plain);
        assertTrue(last().startsWith("235"));
        assertEquals("u", script.principalName);
    }

    @Test
    public void testAuthRejectedByHandler() {
        listener.setRealm(new SimpleRealm());
        endpoint.secure = true;
        handler = new SmtpProtocolHandler(listener, script);
        handler.connected(endpoint);
        handler.securityEstablished(null);
        raw("EHLO c.example.com\r\n");
        script.authMode = 1;
        endpoint.sentData.clear();
        String plain = Base64.getEncoder().encodeToString(
                "\0u\0p".getBytes(StandardCharsets.US_ASCII));
        send("AUTH PLAIN " + plain);
        assertTrue(last().startsWith("535"));
        script.authMode = 2;
        endpoint.sentData.clear();
        send("AUTH PLAIN " + plain);
        assertTrue(last().startsWith("535"));
        assertFalse(endpoint.open);
    }

    @Test
    public void testLoginAcceptedByHandler() {
        listener.setRealm(new SimpleRealm());
        endpoint.secure = true;
        handler = new SmtpProtocolHandler(listener, script);
        handler.connected(endpoint);
        handler.securityEstablished(null);
        raw("EHLO c.example.com\r\n");
        expect("AUTH LOGIN", "334");
        expect(Base64.getEncoder().encodeToString("u".getBytes(StandardCharsets.US_ASCII)), "334");
        expect(Base64.getEncoder().encodeToString("p".getBytes(StandardCharsets.US_ASCII)), "235");
        assertEquals("u", script.principalName);
    }

    // -- script and helpers --

    /** Stub endpoint counting read pause and resume calls. */
    static final class CountingEndpoint extends SMTPProtocolHandlerTest.StubEndpoint {
        int pauseCount;
        int resumeCount;

        @Override
        public void pauseRead() {
            pauseCount++;
        }

        @Override
        public void resumeRead() {
            resumeCount++;
        }
    }

    /** Listener with STARTTLS pretended available. */
    static final class StartTlsListener extends SmtpListener {
        @Override
        protected boolean isSTARTTLSAvailable() {
            return true;
        }
    }

    /** Listener authorising XCLIENT. */
    static final class XclientListener extends SmtpListener {
        @Override
        protected boolean isXclientAuthorized(java.net.InetAddress addr) {
            return true;
        }
    }

    /** Realm accepting user "u" with password "p". */
    private static final class SimpleRealm implements Realm {
        @Override
        public Realm forSelectorLoop(SelectorLoop loop) {
            return this;
        }

        @Override
        public java.util.Set<SaslMechanism> getSupportedSASLMechanisms() {
            return java.util.EnumSet.of(SaslMechanism.PLAIN, SaslMechanism.LOGIN);
        }

        @Override
        public boolean passwordMatch(String username, String password) {
            return "u".equals(username) && "p".equals(password);
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
    }

    /** Session handler whose behaviour at each stage is set by mode fields. */
    static final class Script implements ClientConnected, HelloHandler,
            MailFromHandler, RecipientHandler, MessageDataHandler {

        int connMode;
        int helloMode;
        int mailMode;
        int rcptMode;
        int startMode;
        int endMode;
        int resetMode;
        int authMode;
        boolean pause;
        boolean holdCompletion;
        SmtpPipeline pipeline;
        MessageEndState heldEnd;

        boolean connected;
        boolean disconnected;
        boolean tlsEstablished;
        boolean completed;
        boolean helloExtended;
        boolean smtputf8;
        String helloName;
        String sender;
        String lastRecipient;
        String lastForward;
        String principalName;
        int contentSize;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        int resetCalls;
        DeliveryRequirements delivery;
        Runnable resume;

        @Override
        public void connected(ConnectedState state, Endpoint endpoint) {
            connected = true;
            switch (connMode) {
                case 1:
                    state.rejectConnection();
                    break;
                case 2:
                    state.rejectConnection("go away");
                    break;
                case 3:
                    state.serverShuttingDown();
                    break;
                default:
                    state.acceptConnection("test ESMTP ready", this);
                    break;
            }
        }

        @Override
        public void disconnected() {
            disconnected = true;
        }

        @Override
        public void hello(HelloState state, boolean extended, String hostname) {
            helloName = hostname;
            helloExtended = extended;
            switch (helloMode) {
                case 1:
                    state.rejectHelloTemporary("try later", this);
                    break;
                case 2:
                    state.rejectHello("no thanks", this);
                    break;
                case 3:
                    state.rejectHelloAndClose("bye");
                    break;
                case 4:
                    state.serverShuttingDown();
                    break;
                default:
                    state.acceptHello(this);
                    break;
            }
        }

        @Override
        public void tlsEstablished(SecurityInfo securityInfo) {
            tlsEstablished = true;
        }

        @Override
        public void authenticated(AuthenticateState state, Principal principal) {
            principalName = principal.getName();
            switch (authMode) {
                case 1:
                    state.reject(this);
                    break;
                case 2:
                    state.rejectAndClose();
                    break;
                default:
                    state.accept(this);
                    break;
            }
        }

        @Override
        public void quit() {
        }

        @Override
        public SmtpPipeline getPipeline() {
            return pipeline;
        }

        @Override
        public void mailFrom(MailFromState state, EmailAddress senderAddress,
                boolean utf8, DeliveryRequirements requirements) {
            sender = senderAddress != null ? senderAddress.getEnvelopeAddress() : null;
            smtputf8 = utf8;
            delivery = requirements;
            switch (mailMode) {
                case 1:
                    state.rejectSenderGreylist(this);
                    break;
                case 2:
                    state.rejectSenderRateLimit(this);
                    break;
                case 3:
                    state.rejectSenderStorageFull(this);
                    break;
                case 4:
                    state.rejectSenderBlockedDomain(this);
                    break;
                case 5:
                    state.rejectSenderInvalidDomain(this);
                    break;
                case 6:
                    state.rejectSenderPolicy("policy says no", this);
                    break;
                case 7:
                    state.rejectSenderSpam(this);
                    break;
                case 8:
                    state.rejectSenderSyntax(this);
                    break;
                case 9:
                    state.serverShuttingDown();
                    break;
                default:
                    state.acceptSender(this);
                    break;
            }
        }

        @Override
        public void reset(ResetState state) {
            resetCalls++;
            if (resetMode == 1) {
                state.serverShuttingDown();
            } else {
                state.acceptReset(this);
            }
        }

        @Override
        public void rcptTo(RecipientState state, EmailAddress recipient,
                MailboxFactory factory) {
            lastRecipient = recipient.getEnvelopeAddress();
            switch (rcptMode) {
                case 1:
                    lastForward = "fwd@example.net";
                    state.acceptRecipientForward(lastForward, this);
                    break;
                case 2:
                    state.rejectRecipientUnavailable(this);
                    break;
                case 3:
                    state.rejectRecipientSystemError(this);
                    break;
                case 4:
                    state.rejectRecipientStorageFull(this);
                    break;
                case 5:
                    state.rejectRecipientNotFound(this);
                    break;
                case 6:
                    state.rejectRecipientNotLocal(this);
                    break;
                case 7:
                    state.rejectRecipientQuota(this);
                    break;
                case 8:
                    state.rejectRecipientInvalid(this);
                    break;
                case 9:
                    state.rejectRecipientRelayDenied(this);
                    break;
                case 10:
                    state.rejectRecipientPolicy("recipient policy", this);
                    break;
                case 11:
                    state.serverShuttingDown();
                    break;
                default:
                    state.acceptRecipient(this);
                    break;
            }
        }

        @Override
        public void startMessage(MessageStartState state) {
            switch (startMode) {
                case 1:
                    state.rejectMessageStorageFull(this);
                    break;
                case 2:
                    state.rejectMessageProcessingError(this);
                    break;
                case 3:
                    state.rejectMessage("not today", this);
                    break;
                case 4:
                    state.serverShuttingDown();
                    break;
                default:
                    state.acceptMessage(this);
                    break;
            }
        }

        @Override
        public void messageContent(ByteBuffer content) {
            contentSize += content.remaining();
            while (content.hasRemaining()) {
                captured.write(content.get());
            }
        }

        @Override
        public void messageComplete(MessageEndState state) {
            completed = true;
            if (holdCompletion) {
                heldEnd = state;
                return;
            }
            switch (endMode) {
                case 1:
                    state.rejectMessageTemporary("try later", this);
                    break;
                case 2:
                    state.rejectMessagePermanent("rejected", this);
                    break;
                case 3:
                    state.rejectMessagePolicy("policy", this);
                    break;
                case 4:
                    state.serverShuttingDown();
                    break;
                default:
                    state.acceptMessageDelivery("Q1", this);
                    break;
            }
        }

        @Override
        public void messageAborted() {
        }

        @Override
        public boolean wantsPause() {
            return pause;
        }

        @Override
        public void setResumeCallback(Runnable callback) {
            resume = callback;
        }
    }
}
