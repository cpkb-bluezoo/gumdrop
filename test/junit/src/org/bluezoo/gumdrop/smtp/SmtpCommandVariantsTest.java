/*
 * SmtpCommandVariantsTest.java
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Exercises the command parameter parsing and error branches of
 * {@link SmtpProtocolHandler} with no session handler attached
 * (built-in accept path).
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SmtpCommandVariantsTest {

    private SmtpListener listener;
    private SmtpProtocolHandler handler;
    private SMTPProtocolHandlerTest.StubEndpoint endpoint;

    @Before
    public void setUp() {
        listener = new SmtpListener();
        handler = new SmtpProtocolHandler(listener, null);
        endpoint = new SMTPProtocolHandlerTest.StubEndpoint();
        handler.connected(endpoint);
        endpoint.sentData.clear();
    }

    private void send(String command) {
        byte[] data = (command + "\r\n").getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(data));
    }

    private void raw(String text) {
        handler.receive(ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII)));
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

    private void ehlo() {
        expect("EHLO client.example.com", "250");
    }

    private void mail() {
        ehlo();
        expect("MAIL FROM:<a@example.com>", "250");
    }

    private void rcpt() {
        mail();
        expect("RCPT TO:<b@example.com>", "250");
    }

    @Test
    public void testHelpVariants() {
        expect("HELP", "214");
        String[] topics = {"HELO", "EHLO", "MAIL", "RCPT", "DATA", "BDAT",
            "RSET", "VRFY", "NOOP", "QUIT", "STARTTLS", "AUTH", "XCLIENT",
            "ETRN"};
        for (int i = 0; i < topics.length; i++) {
            expect("HELP " + topics[i], "214");
        }
        expect("HELP BOGUS", "504");
    }

    @Test
    public void testVrfyExpnEtrn() {
        expect("VRFY someone", "252");
        expect("EXPN list", "502");
        expect("ETRN node", "502");
        ehlo();
        expect("ETRN node", "458");
    }

    @Test
    public void testHeloAfterEhloAndRset() {
        ehlo();
        expect("RSET", "250");
        expect("HELO again.example.com", "250");
    }

    @Test
    public void testMailBadSequenceAndSyntax() {
        expect("MAIL FROM:<a@example.com>", "503");
        ehlo();
        expect("MAIL", "501");
        expect("MAIL TO:<a@example.com>", "501");
        expect("MAIL FROM:<a@example.com", "501");
        expect("MAIL FROM:<not an address>", "501");
        expect("MAIL FROM:<a@example.com> SIZE=abc", "501");
        expect("MAIL FROM:<a@example.com> SIZE=-5", "501");
    }

    @Test
    public void testMailUnbracketedAddress() {
        ehlo();
        expect("MAIL FROM:a@example.com", "250");
        expect("RSET", "250");
        expect("MAIL FROM:a@example.com SIZE=10", "250");
    }

    @Test
    public void testMailNullSender() {
        ehlo();
        expect("MAIL FROM:<>", "250");
    }

    @Test
    public void testMailParametersRequireEhlo() {
        expect("HELO c.example.com", "250");
        expect("MAIL FROM:<a@example.com> SMTPUTF8", "503");
        expect("MAIL FROM:<a@example.com> RET=FULL", "503");
        expect("MAIL FROM:<a@example.com> ENVID=abc", "503");
        expect("MAIL FROM:<a@example.com> BODY=8BITMIME", "503");
        expect("MAIL FROM:<a@example.com> REQUIRETLS", "503");
        expect("MAIL FROM:<a@example.com> MT-PRIORITY=1", "503");
        expect("MAIL FROM:<a@example.com> HOLDFOR=10", "503");
        expect("MAIL FROM:<a@example.com> HOLDUNTIL=2030-01-01T00:00:00Z", "503");
        expect("MAIL FROM:<a@example.com> BY=10", "503");
    }

    @Test
    public void testMailParametersValid() {
        ehlo();
        expect("MAIL FROM:<a@example.com> RET=HDRS ENVID=ab+2Bcd BODY=8BITMIME"
                + " MT-PRIORITY=3 HOLDFOR=60 BY=30;N SIZE=100", "250");
        expect("RSET", "250");
        expect("MAIL FROM:<a@example.com> HOLDUNTIL=2030-01-01T00:00:00Z"
                + " BY=30;R SMTPUTF8", "250");
        expect("RSET", "250");
        expect("MAIL FROM:<a@example.com> BY=30", "250");
    }

    @Test
    public void testMailParametersInvalid() {
        ehlo();
        expect("MAIL FROM:<a@example.com> RET=BOGUS", "501");
        expect("MAIL FROM:<a@example.com> ENVID=", "501");
        expect("MAIL FROM:<a@example.com> BODY=BOGUS", "501");
        expect("MAIL FROM:<a@example.com> REQUIRETLS", "530");
        expect("MAIL FROM:<a@example.com> MT-PRIORITY=99", "501");
        expect("MAIL FROM:<a@example.com> MT-PRIORITY=x", "501");
        expect("MAIL FROM:<a@example.com> HOLDFOR=-1", "501");
        expect("MAIL FROM:<a@example.com> HOLDFOR=x", "501");
        expect("MAIL FROM:<a@example.com> HOLDUNTIL=yesterday", "501");
        expect("MAIL FROM:<a@example.com> BY=10;X", "501");
        expect("MAIL FROM:<a@example.com> BY=0", "501");
        expect("MAIL FROM:<a@example.com> BY=x", "501");
    }

    @Test
    public void testMailSizeExceedsMaximum() {
        listener.maxMessageSize(1000);
        ehlo();
        expect("MAIL FROM:<a@example.com> SIZE=5000", "552");
    }

    @Test
    public void testMailRequireTlsOverSecure() {
        endpoint.secure = true;
        ehlo();
        expect("MAIL FROM:<a@example.com> REQUIRETLS", "250");
    }

    @Test
    public void testAuthRequired() {
        listener.authRequired(true);
        ehlo();
        expect("MAIL FROM:<a@example.com>", "530");
        expect("RCPT TO:<a@example.com>", "503");
        expect("DATA", "503");
    }

    @Test
    public void testRcptErrors() {
        expect("RCPT TO:<a@example.com>", "503");
        mail();
        expect("RCPT", "501");
        expect("RCPT FROM:<a@example.com>", "501");
        expect("RCPT TO:<a@example.com", "501");
        expect("RCPT TO:<>", "501");
        expect("RCPT TO:<not an address>", "501");
        expect("RCPT TO:<b@example.com> NOTIFY=BOGUS", "501");
        expect("RCPT TO:<b@example.com> NOTIFY=NEVER,SUCCESS", "501");
        expect("RCPT TO:<b@example.com> ORCPT=nosemicolon", "501");
        expect("RCPT TO:<b@example.com> ORCPT=rfc822;", "501");
    }

    @Test
    public void testRcptParameters() {
        mail();
        expect("RCPT TO:b@example.com", "250");
        expect("RCPT TO:<c@example.com> NOTIFY=SUCCESS,FAILURE,DELAY"
                + " ORCPT=rfc822;c+40example.com", "250");
        expect("RCPT TO:d@example.com NOTIFY=NEVER", "250");
    }

    @Test
    public void testRcptParametersRequireEhlo() {
        expect("HELO c.example.com", "250");
        expect("MAIL FROM:<a@example.com>", "250");
        expect("RCPT TO:<b@example.com> NOTIFY=NEVER", "503");
        expect("RCPT TO:<b@example.com> ORCPT=rfc822;b@example.com", "503");
    }

    @Test
    public void testTooManyRecipients() {
        listener.maxRecipients(1);
        rcpt();
        expect("RCPT TO:<c@example.com>", "452");
    }

    @Test
    public void testDataErrors() {
        expect("DATA", "503");
        mail();
        expect("DATA", "503");
    }

    @Test
    public void testDataBinaryMimeRequiresBdat() {
        ehlo();
        expect("MAIL FROM:<a@example.com> BODY=BINARYMIME", "250");
        expect("RCPT TO:<b@example.com>", "250");
        expect("DATA", "503");
    }

    @Test
    public void testBdatErrors() {
        expect("HELO c.example.com", "250");
        expect("BDAT 10", "503");
        ehlo();
        expect("BDAT 10", "503");
        mail();
        expect("BDAT 10", "503");
        expect("RCPT TO:<b@example.com>", "250");
        expect("BDAT", "501");
        expect("BDAT x", "501");
        expect("BDAT -1", "501");
        expect("BDAT 10 FIRST", "501");
        expect("BDAT 10 LAST extra", "501");
    }

    @Test
    public void testBdatSizeExceedsMaximum() {
        listener.maxMessageSize(100);
        rcpt();
        expect("BDAT 500", "552");
    }

    @Test
    public void testBdatMultipleChunks() {
        rcpt();
        endpoint.sentData.clear();
        send("BDAT 5");
        handler.receive(ByteBuffer.wrap("hello".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(last().startsWith("250"));
        send("BDAT 5 LAST");
        handler.receive(ByteBuffer.wrap("world".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(last().startsWith("250"));
    }

    @Test
    public void testRsetAfterMail() {
        rcpt();
        expect("RSET", "250");
        expect("RCPT TO:<b@example.com>", "503");
    }

    @Test
    public void testXclientUnauthorized() {
        ehlo();
        expect("XCLIENT ADDR=1.2.3.4", "550");
    }

    @Test
    public void testStarttlsWithoutTls() {
        ehlo();
        endpoint.sentData.clear();
        send("STARTTLS");
        String response = last();
        assertTrue(response, response.startsWith("500")
                || response.startsWith("502") || response.startsWith("454")
                || response.startsWith("503"));
    }

    @Test
    public void testAuthWithoutRealm() {
        ehlo();
        endpoint.sentData.clear();
        send("AUTH PLAIN");
        String response = last();
        assertTrue(response, response.startsWith("502")
                || response.startsWith("504") || response.startsWith("503"));
    }

    @Test
    public void testEmptyAndWhitespaceLines() {
        endpoint.sentData.clear();
        send("");
        assertTrue(last().startsWith("500"));
    }

    @Test
    public void testDataSlicedAtSelectedChunkSizes() {
        int[] sizes = {3, 4, 5, 7, 10, 12};
        for (int si = 0; si < sizes.length; si++) {
            int chunk = sizes[si];
            setUp();
            rcpt();
            expect("DATA", "354");
            byte[] wire = "Subject: x\r\n\r\nline1\r\n.\r\n".getBytes(StandardCharsets.US_ASCII);
            ByteBuffer netIn = ByteBuffer.allocate(256);
            int offset = 0;
            while (offset < wire.length) {
                int len = Math.min(chunk, wire.length - offset);
                netIn.put(wire, offset, len);
                offset += len;
                netIn.flip();
                handler.receive(netIn);
                netIn.compact();
            }
            String r = last();
            assertTrue("chunk " + chunk + ": " + endpoint.getResponses(), r.startsWith("250"));
        }
    }

    @Test
    public void testDisconnectedInTransaction() {
        rcpt();
        handler.disconnected();
    }

    @Test
    public void testSecondGreetingCommandsAfterQuit() {
        expect("QUIT", "221");
    }
}
