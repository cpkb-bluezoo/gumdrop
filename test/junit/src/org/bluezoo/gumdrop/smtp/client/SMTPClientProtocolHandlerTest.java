/*
 * SMTPClientProtocolHandlerTest.java
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

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.smtp.client.handler.*;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Unit tests for SMTPClientProtocolHandler — items 84-87:
 * EHLO capability parsing, MAIL FROM extension parameters,
 * RCPT TO DSN parameters, and VRFY/EXPN commands.
 */
public class SMTPClientProtocolHandlerTest {

    private SMTPClientProtocolHandler handler;
    private StubEndpoint endpoint;
    private final List<String> sentCommands = new ArrayList<>();

    @Before
    public void setUp() {
        sentCommands.clear();
        endpoint = new StubEndpoint(sentCommands);
        handler = new SMTPClientProtocolHandler(new ServerGreeting() {
            @Override
            public void handleGreeting(ClientHelloState hello,
                                       String message, boolean esmtp) {
            }
            @Override
            public void handleServiceUnavailable(String message) {
            }
            @Override
            public void onConnected(Endpoint ep) {
            }
            @Override
            public void onDisconnected() {
            }
            @Override
            public void onSecurityEstablished(SecurityInfo info) {
            }
            @Override
            public void onError(Exception e) {
            }
        });
        handler.connected(endpoint);
        // Simulate greeting
        simulateResponse("220 mail.example.com ESMTP\r\n");
    }

    // ── EHLO Capability Parsing (item 84) ──

    @Test
    public void testEhloBasicCapabilities() {
        List<String> capabilities = new ArrayList<>();
        handler.ehlo("myhost", new TestEhloHandler(capabilities));
        simulateMultilineResponse(250,
                "mail.example.com",
                "STARTTLS",
                "SIZE 52428800",
                "AUTH PLAIN LOGIN CRAM-MD5",
                "PIPELINING",
                "CHUNKING");
        assertTrue(capabilities.contains("starttls"));
        assertTrue(capabilities.contains("pipelining"));
    }

    @Test
    public void testEhlo8BitMime() {
        handler.ehlo("myhost", new TestEhloHandler(null));
        simulateMultilineResponse(250, "host", "8BITMIME");
        assertTrue(handler.has8BitMime());
    }

    @Test
    public void testEhloSmtpUtf8() {
        handler.ehlo("myhost", new TestEhloHandler(null));
        simulateMultilineResponse(250, "host", "SMTPUTF8");
        assertTrue(handler.hasSmtpUtf8());
    }

    @Test
    public void testEhloDsn() {
        handler.ehlo("myhost", new TestEhloHandler(null));
        simulateMultilineResponse(250, "host", "DSN");
        assertTrue(handler.hasDsn());
    }

    @Test
    public void testEhloEnhancedStatusCodes() {
        handler.ehlo("myhost", new TestEhloHandler(null));
        simulateMultilineResponse(250, "host", "ENHANCEDSTATUSCODES");
        assertTrue(handler.hasEnhancedStatusCodes());
    }

    @Test
    public void testEhloBinaryMime() {
        handler.ehlo("myhost", new TestEhloHandler(null));
        simulateMultilineResponse(250, "host", "BINARYMIME");
        assertTrue(handler.hasBinaryMime());
    }

    @Test
    public void testEhloRequireTls() {
        handler.ehlo("myhost", new TestEhloHandler(null));
        simulateMultilineResponse(250, "host", "REQUIRETLS");
        assertTrue(handler.hasRequireTls());
    }

    @Test
    public void testEhloMtPriority() {
        handler.ehlo("myhost", new TestEhloHandler(null));
        simulateMultilineResponse(250, "host", "MT-PRIORITY MIXER");
        assertTrue(handler.hasMtPriority());
    }

    @Test
    public void testEhloFutureRelease() {
        handler.ehlo("myhost", new TestEhloHandler(null));
        simulateMultilineResponse(250, "host", "FUTURERELEASE 604800 2026-01-01T00:00:00Z");
        assertTrue(handler.hasFutureRelease());
    }

    @Test
    public void testEhloDeliverBy() {
        handler.ehlo("myhost", new TestEhloHandler(null));
        simulateMultilineResponse(250, "host", "DELIVERBY 604800");
        assertTrue(handler.hasDeliverBy());
    }

    @Test
    public void testEhloLimits() {
        handler.ehlo("myhost", new TestEhloHandler(null));
        simulateMultilineResponse(250, "host", "LIMITS RCPTMAX=100 MAILMAX=50");
        assertEquals(100, handler.getLimitsRcptMax());
        assertEquals(50, handler.getLimitsMailMax());
    }

    @Test
    public void testEhloLimitsPartial() {
        handler.ehlo("myhost", new TestEhloHandler(null));
        simulateMultilineResponse(250, "host", "LIMITS RCPTMAX=200");
        assertEquals(200, handler.getLimitsRcptMax());
        assertEquals(0, handler.getLimitsMailMax());
    }

    @Test
    public void testEhloAllExtensions() {
        handler.ehlo("myhost", new TestEhloHandler(null));
        simulateMultilineResponse(250, "host",
                "8BITMIME", "SMTPUTF8", "DSN", "ENHANCEDSTATUSCODES",
                "BINARYMIME", "REQUIRETLS", "MT-PRIORITY MIXER",
                "FUTURERELEASE 604800 2026-01-01T00:00:00Z",
                "DELIVERBY 604800", "LIMITS RCPTMAX=50 MAILMAX=10");
        assertTrue(handler.has8BitMime());
        assertTrue(handler.hasSmtpUtf8());
        assertTrue(handler.hasDsn());
        assertTrue(handler.hasEnhancedStatusCodes());
        assertTrue(handler.hasBinaryMime());
        assertTrue(handler.hasRequireTls());
        assertTrue(handler.hasMtPriority());
        assertTrue(handler.hasFutureRelease());
        assertTrue(handler.hasDeliverBy());
        assertEquals(50, handler.getLimitsRcptMax());
        assertEquals(10, handler.getLimitsMailMax());
    }

    // ── MAIL FROM Extension Parameters (item 85) ──

    @Test
    public void testMailFromWithParams() {
        setupConnectedState();
        MailFromParams params = new MailFromParams();
        params.body = "8BITMIME";
        params.smtpUtf8 = true;
        params.ret = "FULL";
        params.envid = "abc123";
        params.requireTls = true;
        params.mtPriority = 3;
        params.holdFor = 3600;
        params.by = "120;R";

        handler.mailFrom(
                addr("sender@example.com"),
                1024,
                params,
                new TestMailFromHandler());

        String lastCmd = getLastSentCommand();
        assertTrue(lastCmd.startsWith("MAIL FROM:<sender@example.com>"));
        assertTrue(lastCmd.contains(" BODY=8BITMIME"));
        assertTrue(lastCmd.contains(" SMTPUTF8"));
        assertTrue(lastCmd.contains(" RET=FULL"));
        assertTrue(lastCmd.contains(" ENVID=abc123"));
        assertTrue(lastCmd.contains(" REQUIRETLS"));
        assertTrue(lastCmd.contains(" MT-PRIORITY=3"));
        assertTrue(lastCmd.contains(" HOLDFOR=3600"));
        assertTrue(lastCmd.contains(" BY=120;R"));
    }

    @Test
    public void testMailFromParamsOnlyWhenServerAdvertises() {
        // Without EHLO advertising DSN, params should not be appended
        handler.ehlo("myhost", new TestEhloHandler(null));
        simulateMultilineResponse(250, "host", "SIZE 10485760");

        MailFromParams params = new MailFromParams();
        params.ret = "HDRS";
        params.envid = "xyz";
        handler.mailFrom(
                addr("sender@example.com"),
                0,
                params,
                new TestMailFromHandler());

        String lastCmd = getLastSentCommand();
        assertFalse(lastCmd.contains("RET="));
        assertFalse(lastCmd.contains("ENVID="));
    }

    @Test
    public void testMailFromNullParams() {
        setupConnectedState();
        handler.mailFrom(
                addr("sender@example.com"),
                512,
                null,
                new TestMailFromHandler());
        String lastCmd = getLastSentCommand();
        assertTrue(lastCmd.startsWith("MAIL FROM:<sender@example.com>"));
        assertTrue(lastCmd.contains(" SIZE=512"));
    }

    // ── RCPT TO DSN Parameters (item 86) ──

    @Test
    public void testRcptToWithDsnParams() {
        setupConnectedState();
        handler.rcptTo(
                addr("rcpt@example.com"),
                "SUCCESS,FAILURE",
                "rfc822;rcpt@example.com",
                new TestRcptToHandler());
        String lastCmd = getLastSentCommand();
        assertTrue(lastCmd.startsWith("RCPT TO:<rcpt@example.com>"));
        assertTrue(lastCmd.contains(" NOTIFY=SUCCESS,FAILURE"));
        assertTrue(lastCmd.contains(" ORCPT=rfc822;rcpt@example.com"));
    }

    @Test
    public void testRcptToWithoutDsn() {
        // Without DSN advertised, params should not appear
        handler.ehlo("myhost", new TestEhloHandler(null));
        simulateMultilineResponse(250, "host", "SIZE 1024");
        simulateMailFromAccepted();

        handler.rcptTo(
                addr("rcpt@example.com"),
                "NEVER",
                "rfc822;user@example.com",
                new TestRcptToHandler());
        String lastCmd = getLastSentCommand();
        assertFalse(lastCmd.contains("NOTIFY="));
        assertFalse(lastCmd.contains("ORCPT="));
    }

    @Test
    public void testRcptToNullDsnParams() {
        setupConnectedState();
        handler.rcptTo(
                addr("rcpt@example.com"),
                null,
                null,
                new TestRcptToHandler());
        String lastCmd = getLastSentCommand();
        assertEquals("RCPT TO:<rcpt@example.com>", lastCmd.trim());
    }

    // ── VRFY/EXPN Commands (item 87) ──

    @Test
    public void testVrfyCommand() {
        setupConnectedState();
        AtomicReference<String> replyMsg = new AtomicReference<>();
        handler.vrfy("alice", new TestGenericReplyHandler(replyMsg));
        String lastCmd = getLastSentCommand();
        assertEquals("VRFY alice", lastCmd.trim());
    }

    @Test
    public void testExpnCommand() {
        setupConnectedState();
        AtomicReference<String> replyMsg = new AtomicReference<>();
        handler.expn("admins", new TestGenericReplyHandler(replyMsg));
        String lastCmd = getLastSentCommand();
        assertEquals("EXPN admins", lastCmd.trim());
    }

    // ── Helpers ──

    private void setupConnectedState() {
        handler.ehlo("myhost", new TestEhloHandler(null));
        simulateMultilineResponse(250, "host",
                "8BITMIME", "SMTPUTF8", "DSN", "BINARYMIME",
                "REQUIRETLS", "MT-PRIORITY MIXER",
                "FUTURERELEASE 604800 2026-01-01T00:00:00Z",
                "DELIVERBY 604800", "SIZE 52428800",
                "LIMITS RCPTMAX=100 MAILMAX=50");
    }

    private void simulateMailFromAccepted() {
        handler.mailFrom(addr("s@e.com"), new TestMailFromHandler());
        simulateResponse("250 2.1.0 OK\r\n");
    }

    private void simulateResponse(String response) {
        byte[] bytes = response.getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(bytes));
    }

    private void simulateMultilineResponse(int code, String... lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i < lines.length - 1) {
                sb.append(code).append("-").append(lines[i]).append("\r\n");
            } else {
                sb.append(code).append(" ").append(lines[i]).append("\r\n");
            }
        }
        simulateResponse(sb.toString());
    }

    private String getLastSentCommand() {
        if (sentCommands.isEmpty()) {
            return "";
        }
        return sentCommands.get(sentCommands.size() - 1);
    }

    private static EmailAddress addr(String address) {
        int at = address.indexOf('@');
        return new EmailAddress(null, address.substring(0, at),
                address.substring(at + 1), true);
    }

    // ── Stub Implementations ──

    static class StubEndpoint implements Endpoint {
        private final List<String> sentCommands;

        StubEndpoint(List<String> sentCommands) {
            this.sentCommands = sentCommands;
        }

        @Override
        public void send(ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            String cmd = new String(bytes, StandardCharsets.US_ASCII);
            String trimmed = cmd.replace("\r\n", "");
            if (!trimmed.isEmpty()) {
                sentCommands.add(trimmed);
            }
        }

        @Override
        public boolean isOpen() { return true; }

        @Override
        public boolean isClosing() { return false; }

        @Override
        public void close() { }

        @Override
        public SocketAddress getLocalAddress() {
            return new InetSocketAddress("localhost", 25);
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 50000);
        }

        @Override
        public boolean isSecure() { return false; }

        @Override
        public SecurityInfo getSecurityInfo() { return null; }

        @Override
        public void startTLS() throws IOException { }

        @Override
        public void pauseRead() { }

        @Override
        public void resumeRead() { }

        @Override
        public void onWriteReady(Runnable callback) { }

        @Override
        public org.bluezoo.gumdrop.SelectorLoop getSelectorLoop() { return null; }

        @Override
        public void execute(Runnable task) { task.run(); }

        @Override
        public void setTrace(org.bluezoo.gumdrop.telemetry.Trace trace) { }

        @Override
        public org.bluezoo.gumdrop.telemetry.Trace getTrace() { return null; }

        @Override
        public boolean isTelemetryEnabled() { return false; }

        @Override
        public org.bluezoo.gumdrop.telemetry.TelemetryConfig getTelemetryConfig() { return null; }

        @Override
        public org.bluezoo.gumdrop.TimerHandle scheduleTimer(long delayMs, Runnable callback) { return null; }
    }

    static class TestEhloHandler implements ServerEhloReplyHandler {
        private final List<String> caps;

        TestEhloHandler(List<String> caps) {
            this.caps = caps;
        }

        @Override
        public void handleEhlo(ClientSession session, boolean starttls,
                                long maxSize, List<String> authMethods,
                                boolean pipelining) {
            if (caps != null) {
                if (starttls) caps.add("starttls");
                if (pipelining) caps.add("pipelining");
                caps.addAll(authMethods);
            }
        }

        @Override
        public void handleEhloNotSupported(ClientHelloState hello) { }

        @Override
        public void handlePermanentFailure(String message) { }

        @Override
        public void handleServiceClosing(String message) { }
    }

    static class TestMailFromHandler implements ServerMailFromReplyHandler {
        @Override
        public void handleMailFromOk(ClientEnvelope envelope) { }

        @Override
        public void handleTemporaryFailure(ClientSession session) { }

        @Override
        public void handlePermanentFailure(String message) { }

        @Override
        public void handleServiceClosing(String message) { }
    }

    static class TestRcptToHandler implements ServerRcptToReplyHandler {
        @Override
        public void handleRcptToOk(ClientEnvelopeReady envelope) { }

        @Override
        public void handleTemporaryFailure(ClientEnvelopeState state) { }

        @Override
        public void handleRecipientRejected(ClientEnvelopeState state) { }

        @Override
        public void handleServiceClosing(String message) { }
    }

    static class TestGenericReplyHandler implements ServerReplyHandler {
        private final AtomicReference<String> msg;

        TestGenericReplyHandler(AtomicReference<String> msg) {
            this.msg = msg;
        }

        @Override
        public void handleServiceClosing(String message) { }

        @Override
        public void handleReply(int code, String message, ClientSession session) {
            if (msg != null) {
                msg.set(code + " " + message);
            }
        }
    }

}
