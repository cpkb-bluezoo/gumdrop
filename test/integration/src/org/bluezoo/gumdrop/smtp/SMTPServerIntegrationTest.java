/*
 * SMTPServerIntegrationTest.java
 * Copyright (C) 2025 Chris Burdess
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

import org.bluezoo.gumdrop.AbstractServerIntegrationTest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Integration test for raw SMTPListener.
 *
 * <p>Tests a raw SMTPListener instance (not subclassed) with real network connections.
 * A raw SMTPListener without a handler should:
 * <ul>
 *   <li>Accept connections and send SMTP greeting (220)</li>
 *   <li>Accept HELO/EHLO commands and respond with capabilities</li>
 *   <li>Accept any MAIL FROM addresses</li>
 *   <li>Accept any RCPT TO addresses</li>
 *   <li>Accept DATA and message content</li>
 *   <li>Return success (250) for delivered messages (blackhole mode)</li>
 *   <li>Support RSET to reset transaction</li>
 *   <li>Support QUIT to close connection</li>
 *   <li>Reject commands out of sequence</li>
 *   <li>Reject unknown commands</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SMTPServerIntegrationTest extends AbstractServerIntegrationTest {
    
    private static final int TEST_PORT = 12599;
    
    /**
     * Global timeout for all tests - 10 seconds max per test.
     */
    @Rule
    public Timeout globalTimeout = Timeout.builder()
        .withTimeout(10, TimeUnit.SECONDS)
        .withLookingForStuckThread(true)
        .build();
    
    @Override
    protected File getTestConfigFile() {
        return new File("test/integration/config/smtp-server-test.xml");
    }
    
    // ============== Basic SMTP Functionality Tests ==============
    
    @Test
    public void testServerStartsAndAcceptsConnections() throws Exception {
        assertNotNull("Server should be running", gumdrop);
        assertTrue("Port " + TEST_PORT + " should be listening", isPortListening("127.0.0.1", TEST_PORT));
    }
    
    @Test
    public void testSMTPGreeting() throws Exception {
        try (SMTPClientHelper.SMTPSession session = SMTPClientHelper.connect("127.0.0.1", TEST_PORT)) {
            SMTPClientHelper.SMTPResponse greeting = session.getLastResponse();
            
            assertEquals("Greeting should be 220", 220, greeting.code);
            assertTrue("Greeting should mention ESMTP", 
                greeting.message.contains("ESMTP") || greeting.message.contains("SMTP"));
        }
    }
    
    @Test
    public void testHELOCommand() throws Exception {
        try (SMTPClientHelper.SMTPSession session = SMTPClientHelper.connect("127.0.0.1", TEST_PORT)) {
            SMTPClientHelper.SMTPResponse response = session.sendCommand("HELO test.example.com");
            
            assertEquals("HELO should return 250", 250, response.code);
            assertTrue("HELO response should contain Hello", response.message.contains("Hello"));
        }
    }
    
    @Test
    public void testEHLOCommand() throws Exception {
        try (SMTPClientHelper.SMTPSession session = SMTPClientHelper.connect("127.0.0.1", TEST_PORT)) {
            SMTPClientHelper.SMTPResponse response = session.sendCommand("EHLO test.example.com");
            
            assertEquals("EHLO should return 250", 250, response.code);
            
            // Check for standard capabilities in multiline response
            String fullResponse = String.join("\n", response.lines);
            assertTrue("EHLO should advertise SIZE", fullResponse.contains("SIZE"));
            assertTrue("EHLO should advertise PIPELINING", fullResponse.contains("PIPELINING"));
            assertTrue("EHLO should advertise 8BITMIME", fullResponse.contains("8BITMIME"));
        }
    }
    
    @Test
    public void testMAILFROMCommand() throws Exception {
        try (SMTPClientHelper.SMTPSession session = SMTPClientHelper.connect("127.0.0.1", TEST_PORT)) {
            session.sendCommand("EHLO test.example.com");
            
            SMTPClientHelper.SMTPResponse response = session.sendCommand("MAIL FROM:<sender@example.com>");
            
            assertEquals("MAIL FROM should return 250", 250, response.code);
        }
    }
    
    @Test
    public void testRCPTTOCommand() throws Exception {
        try (SMTPClientHelper.SMTPSession session = SMTPClientHelper.connect("127.0.0.1", TEST_PORT)) {
            session.sendCommand("EHLO test.example.com");
            session.sendCommand("MAIL FROM:<sender@example.com>");
            
            SMTPClientHelper.SMTPResponse response = session.sendCommand("RCPT TO:<recipient@example.com>");
            
            assertEquals("RCPT TO should return 250", 250, response.code);
        }
    }
    
    @Test
    public void testMultipleRecipients() throws Exception {
        try (SMTPClientHelper.SMTPSession session = SMTPClientHelper.connect("127.0.0.1", TEST_PORT)) {
            session.sendCommand("EHLO test.example.com");
            session.sendCommand("MAIL FROM:<sender@example.com>");
            
            SMTPClientHelper.SMTPResponse response1 = session.sendCommand("RCPT TO:<recipient1@example.com>");
            assertEquals("First RCPT TO should return 250", 250, response1.code);
            
            SMTPClientHelper.SMTPResponse response2 = session.sendCommand("RCPT TO:<recipient2@example.com>");
            assertEquals("Second RCPT TO should return 250", 250, response2.code);
            
            SMTPClientHelper.SMTPResponse response3 = session.sendCommand("RCPT TO:<recipient3@example.com>");
            assertEquals("Third RCPT TO should return 250", 250, response3.code);
        }
    }
    
    @Test
    public void testDATACommand() throws Exception {
        try (SMTPClientHelper.SMTPSession session = SMTPClientHelper.connect("127.0.0.1", TEST_PORT)) {
            session.sendCommand("EHLO test.example.com");
            session.sendCommand("MAIL FROM:<sender@example.com>");
            session.sendCommand("RCPT TO:<recipient@example.com>");
            
            SMTPClientHelper.SMTPResponse response = session.sendCommand("DATA");
            
            assertEquals("DATA should return 354", 354, response.code);
        }
    }
    
    @Test
    public void testCompleteEmailTransaction() throws Exception {
        SMTPClientHelper.SMTPResponse response = SMTPClientHelper.sendEmail(
            "127.0.0.1", TEST_PORT,
            "sender@example.com",
            "recipient@example.com",
            "Test Subject",
            "This is a test message body."
        );
        
        assertEquals("Complete transaction should return 250", 250, response.code);
        assertTrue("Response should indicate acceptance", 
            response.message.toLowerCase().contains("accept") || 
            response.message.toLowerCase().contains("ok") ||
            response.message.contains("2.0.0"));
    }
    
    @Test
    public void testDotStuffing() throws Exception {
        try (SMTPClientHelper.SMTPSession session = SMTPClientHelper.connect("127.0.0.1", TEST_PORT)) {
            session.sendCommand("EHLO test.example.com");
            session.sendCommand("MAIL FROM:<sender@example.com>");
            session.sendCommand("RCPT TO:<recipient@example.com>");
            session.sendCommand("DATA");
            
            // Send message with lines starting with dots (need to be dot-stuffed)
            session.sendData("Subject: Dot Test\r\n");
            session.sendData("From: sender@example.com\r\n");
            session.sendData("To: recipient@example.com\r\n");
            session.sendData("\r\n");
            session.sendData("Normal line\r\n");
            session.sendData("..This line starts with a dot\r\n");  // Dot-stuffed
            session.sendData("...Two dots at start\r\n");  // Dot-stuffed
            session.sendData(".\r\n");  // End of message
            
            SMTPClientHelper.SMTPResponse response = session.readResponse();
            
            assertEquals("Dot-stuffed message should be accepted (250)", 250, response.code);
        }
    }
    
    @Test
    public void testRSETCommand() throws Exception {
        try (SMTPClientHelper.SMTPSession session = SMTPClientHelper.connect("127.0.0.1", TEST_PORT)) {
            session.sendCommand("EHLO test.example.com");
            session.sendCommand("MAIL FROM:<sender@example.com>");
            session.sendCommand("RCPT TO:<recipient@example.com>");
            
            SMTPClientHelper.SMTPResponse response = session.sendCommand("RSET");
            
            assertEquals("RSET should return 250", 250, response.code);
            
            // Verify we can start a new transaction
            SMTPClientHelper.SMTPResponse mailResponse = session.sendCommand("MAIL FROM:<new-sender@example.com>");
            assertEquals("MAIL FROM after RSET should succeed", 250, mailResponse.code);
        }
    }
    
    @Test
    public void testNOOPCommand() throws Exception {
        try (SMTPClientHelper.SMTPSession session = SMTPClientHelper.connect("127.0.0.1", TEST_PORT)) {
            SMTPClientHelper.SMTPResponse response = session.sendCommand("NOOP");
            
            assertEquals("NOOP should return 250", 250, response.code);
        }
    }
    
    @Test
    public void testHELPCommand() throws Exception {
        try (SMTPClientHelper.SMTPSession session = SMTPClientHelper.connect("127.0.0.1", TEST_PORT)) {
            SMTPClientHelper.SMTPResponse response = session.sendCommand("HELP");
            
            assertEquals("HELP should return 214", 214, response.code);
        }
    }
    
    @Test
    public void testVRFYCommand() throws Exception {
        try (SMTPClientHelper.SMTPSession session = SMTPClientHelper.connect("127.0.0.1", TEST_PORT)) {
            SMTPClientHelper.SMTPResponse response = session.sendCommand("VRFY user@example.com");
            
            assertEquals("VRFY should return 252 (cannot verify)", 252, response.code);
        }
    }
    
    @Test
    public void testEXPNCommand() throws Exception {
        try (SMTPClientHelper.SMTPSession session = SMTPClientHelper.connect("127.0.0.1", TEST_PORT)) {
            SMTPClientHelper.SMTPResponse response = session.sendCommand("EXPN list@example.com");
            
            assertEquals("EXPN should return 502 (not implemented)", 502, response.code);
        }
    }
    
    // ============== Error Handling Tests ==============
    
    @Test
    public void testUnknownCommand() throws Exception {
        try (SMTPClientHelper.SMTPSession session = SMTPClientHelper.connect("127.0.0.1", TEST_PORT)) {
            SMTPClientHelper.SMTPResponse response = session.sendCommand("INVALID");
            
            assertEquals("Unknown command should return 500", 500, response.code);
        }
    }
    
    @Test
    public void testMAILWithoutHELO() throws Exception {
        try (SMTPClientHelper.SMTPSession session = SMTPClientHelper.connect("127.0.0.1", TEST_PORT)) {
            SMTPClientHelper.SMTPResponse response = session.sendCommand("MAIL FROM:<sender@example.com>");
            
            assertEquals("MAIL without HELO should return 503", 503, response.code);
        }
    }
    
    @Test
    public void testRCPTWithoutMAIL() throws Exception {
        try (SMTPClientHelper.SMTPSession session = SMTPClientHelper.connect("127.0.0.1", TEST_PORT)) {
            session.sendCommand("EHLO test.example.com");
            
            SMTPClientHelper.SMTPResponse response = session.sendCommand("RCPT TO:<recipient@example.com>");
            
            assertEquals("RCPT without MAIL should return 503", 503, response.code);
        }
    }
    
    @Test
    public void testDATAWithoutRCPT() throws Exception {
        try (SMTPClientHelper.SMTPSession session = SMTPClientHelper.connect("127.0.0.1", TEST_PORT)) {
            session.sendCommand("EHLO test.example.com");
            session.sendCommand("MAIL FROM:<sender@example.com>");
            
            SMTPClientHelper.SMTPResponse response = session.sendCommand("DATA");
            
            assertEquals("DATA without RCPT should return 503", 503, response.code);
        }
    }
    
    @Test
    public void testInvalidMAILSyntax() throws Exception {
        try (SMTPClientHelper.SMTPSession session = SMTPClientHelper.connect("127.0.0.1", TEST_PORT)) {
            session.sendCommand("EHLO test.example.com");
            
            SMTPClientHelper.SMTPResponse response = session.sendCommand("MAIL sender@example.com");
            
            assertEquals("Invalid MAIL syntax should return 501", 501, response.code);
        }
    }
    
    @Test
    public void testInvalidRCPTSyntax() throws Exception {
        try (SMTPClientHelper.SMTPSession session = SMTPClientHelper.connect("127.0.0.1", TEST_PORT)) {
            session.sendCommand("EHLO test.example.com");
            session.sendCommand("MAIL FROM:<sender@example.com>");
            
            SMTPClientHelper.SMTPResponse response = session.sendCommand("RCPT recipient@example.com");
            
            assertEquals("Invalid RCPT syntax should return 501", 501, response.code);
        }
    }
    
    @Test
    public void testEmptyHELO() throws Exception {
        try (SMTPClientHelper.SMTPSession session = SMTPClientHelper.connect("127.0.0.1", TEST_PORT)) {
            SMTPClientHelper.SMTPResponse response = session.sendCommand("HELO");
            
            assertEquals("Empty HELO should return 501", 501, response.code);
        }
    }
    
    @Test
    public void testEmptyEHLO() throws Exception {
        try (SMTPClientHelper.SMTPSession session = SMTPClientHelper.connect("127.0.0.1", TEST_PORT)) {
            SMTPClientHelper.SMTPResponse response = session.sendCommand("EHLO");
            
            assertEquals("Empty EHLO should return 501", 501, response.code);
        }
    }
    
    // ============== Pipelining Tests ==============
    
    @Test
    public void testMultipleTransactions() throws Exception {
        try (SMTPClientHelper.SMTPSession session = SMTPClientHelper.connect("127.0.0.1", TEST_PORT)) {
            session.sendCommand("EHLO test.example.com");
            
            // First transaction
            session.sendCommand("MAIL FROM:<sender1@example.com>");
            session.sendCommand("RCPT TO:<recipient1@example.com>");
            session.sendCommand("DATA");
            session.sendData("Subject: Test 1\r\n\r\nBody 1\r\n.\r\n");
            SMTPClientHelper.SMTPResponse response1 = session.readResponse();
            assertEquals("First transaction should succeed", 250, response1.code);
            
            // Second transaction on same connection
            session.sendCommand("MAIL FROM:<sender2@example.com>");
            session.sendCommand("RCPT TO:<recipient2@example.com>");
            session.sendCommand("DATA");
            session.sendData("Subject: Test 2\r\n\r\nBody 2\r\n.\r\n");
            SMTPClientHelper.SMTPResponse response2 = session.readResponse();
            assertEquals("Second transaction should succeed", 250, response2.code);
        }
    }
    
    // ============== Address Format Tests ==============
    
    @Test
    public void testMailFromWithBrackets() throws Exception {
        try (SMTPClientHelper.SMTPSession session = SMTPClientHelper.connect("127.0.0.1", TEST_PORT)) {
            session.sendCommand("EHLO test.example.com");
            
            SMTPClientHelper.SMTPResponse response = session.sendCommand("MAIL FROM:<sender@example.com>");
            assertEquals("MAIL FROM with brackets should succeed", 250, response.code);
        }
    }
    
    @Test
    public void testRcptToWithBrackets() throws Exception {
        try (SMTPClientHelper.SMTPSession session = SMTPClientHelper.connect("127.0.0.1", TEST_PORT)) {
            session.sendCommand("EHLO test.example.com");
            session.sendCommand("MAIL FROM:<sender@example.com>");
            
            SMTPClientHelper.SMTPResponse response = session.sendCommand("RCPT TO:<recipient@example.com>");
            assertEquals("RCPT TO with brackets should succeed", 250, response.code);
        }
    }
    
    @Test
    public void testNullSender() throws Exception {
        try (SMTPClientHelper.SMTPSession session = SMTPClientHelper.connect("127.0.0.1", TEST_PORT)) {
            session.sendCommand("EHLO test.example.com");
            
            // Null sender (bounce address) is valid in SMTP
            SMTPClientHelper.SMTPResponse response = session.sendCommand("MAIL FROM:<>");
            assertEquals("Null sender should be accepted", 250, response.code);
        }
    }
}

