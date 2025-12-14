/*
 * SMTPProtocolTest.java
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

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.ConnectionInfo;
import org.bluezoo.gumdrop.SendCallback;
import org.bluezoo.gumdrop.TLSInfo;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.smtp.handler.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SASLMechanism;

import javax.net.ssl.SSLEngine;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JUnit 4 test for SMTP protocol implementation.
 * 
 * <p>This test creates an SMTP connection directly and feeds it data via received(),
 * capturing responses via an overridden send() method. This allows us to test
 * the SMTP protocol implementation in isolation without server threading issues.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SMTPProtocolTest {
    
    private TestSMTPConnection connection;
    private List<String> responses;
    private Logger rootLogger;
    private Level originalLogLevel;
    
    @Before
    public void setUp() throws Exception {
        // Set up responses list
        responses = new ArrayList<>();
        
        // Setup logging (less verbose for JUnit)
        rootLogger = Logger.getLogger("");
        originalLogLevel = rootLogger.getLevel();
        rootLogger.setLevel(Level.WARNING);
        
        createSMTPConnection();
    }
    
    @After
    public void tearDown() throws Exception {
        // Restore logging level
        if (rootLogger != null && originalLogLevel != null) {
            rootLogger.setLevel(originalLogLevel);
        }
    }
    
    /**
     * Creates SMTP connection directly for protocol testing.
     */
    private SMTPConnection createSMTPConnection() throws Exception {
        // Create SMTP connector
        SMTPServer server = new SMTPServer();
        server.setPort(2525); // Not actually used, but needed for initialization
        
        // Create basic SMTP handler using staged handler pattern
        TestSMTPHandler handler = new TestSMTPHandler();
        
        // Set handler factory
        ClientConnectedFactory factory = () -> handler;
        server.setHandlerFactory(factory);
        
        // Create the SMTP connection with null channel to test null safety  
        SMTPConnection conn = new SMTPConnection(server, null, null, false, handler);
        
        // Channel is now set by constructor, no need to set fields manually
        
        // Set up test callback to capture send() output
        conn.setSendCallback(new SendCallback() {
            @Override
            public void onSend(Connection connection, ByteBuffer buf) {
                if (buf != null && buf.hasRemaining()) {
                    try {
                        // Create a defensive copy to avoid buffer state issues
                        int remaining = buf.remaining();
                        byte[] data = new byte[remaining];
                        buf.duplicate().get(data); // Use duplicate() to avoid modifying original buffer
                        responses.add(new String(data, StandardCharsets.US_ASCII));
                    } catch (Exception e) {
                        // Log but don't fail the test for buffer issues
                        System.err.println("SendCallback error: " + e.getMessage());
                    }
                }
            }
        });
        
        // Clear responses for clean test state
        responses.clear();
        
        // Initialize the SMTP connection properly - this should send the welcome banner
        try {
            conn.init();
        } catch (Exception e) {
            // For testing, we can ignore some initialization errors
            // The main goal is to get the SMTP greeting sent
        }
        
        return conn;
    }
    
    // Shared connection for state-dependent SMTP sequences
    private SMTPConnection sharedConnection = null;
    
    /**
     * Sends an SMTP command to the connection and returns responses.
     */
    private List<String> sendSmtpCommand(String command) {        
        return sendSmtpCommand(command, false);
    }
    
    /**
     * Sends an SMTP command to the connection and returns responses.
     */
    private List<String> sendSmtpCommand(String command, boolean useSharedConnection) {        
        try {
            SMTPConnection conn;
            if (useSharedConnection) {
                if (sharedConnection == null) {
                    sharedConnection = createSMTPConnection();
                }
                conn = sharedConnection;
            } else {
                // Create fresh connection for each command to avoid state issues                                                                               
                conn = createSMTPConnection();
            }
            
            String smtpCommand = command + "\r\n";
            ByteBuffer buffer = ByteBuffer.wrap(smtpCommand.getBytes(StandardCharsets.US_ASCII));
            
            // Clear any welcome banner from responses before sending command
            responses.clear();
            
            // Use protected received() method directly
            conn.receive(buffer);
            
            // Allow brief processing time for SMTP processing  
            Thread.sleep(10);
            
            return new ArrayList<>(responses);
            
        } catch (Exception e) {
            fail("Error sending SMTP command '" + command + "': " + e.getClass().getSimpleName() + " - " + 
                 (e.getMessage() != null ? e.getMessage() : e.toString()));
            return new ArrayList<>();
        }
    }
    
    @Test
    public void testGreeting() throws Exception {
        // Create connection and check that init() sends the greeting
        SMTPConnection conn = createSMTPConnection();
        
        // The init() call in createSMTPConnection should have sent the welcome banner
        assertFalse("Should have received SMTP greeting from init()", responses.isEmpty());
        String greeting = responses.get(0);
        assertTrue("Greeting should start with 220", greeting.startsWith("220"));
        assertTrue("Greeting should contain server info", greeting.contains("ESMTP") || greeting.contains("SMTP"));
    }
    
    @Test
    public void testHeloCommand() {
        List<String> result = sendSmtpCommand("HELO client.example.com");
        
        assertFalse("Should receive response to HELO", result.isEmpty());
        String response = result.get(0);
        assertTrue("HELO response should start with 250", response.startsWith("250"));
        assertTrue("HELO response should contain server name", response.contains("Hello"));
    }
    
    @Test
    public void testEhloCommand() {
        List<String> result = sendSmtpCommand("EHLO client.example.com");
        
        assertFalse("Should receive response to EHLO", result.isEmpty());
        String response = result.get(0);
        assertTrue("EHLO response should start with 250", response.startsWith("250"));
        
        // EHLO should return multiple lines showing supported extensions
        String fullResponse = String.join("\\n", result);
        assertTrue("EHLO should advertise SIZE", fullResponse.contains("SIZE"));
        assertTrue("EHLO should advertise PIPELINING", fullResponse.contains("PIPELINING"));
    }
    
    @Test
    public void testMailFromCommand() {
        // Need HELO first on shared connection
        sendSmtpCommand("HELO client.example.com", true);
        
        List<String> result = sendSmtpCommand("MAIL FROM:<sender@example.com>", true);
        
        assertFalse("Should receive response to MAIL FROM", result.isEmpty());
        String response = result.get(0);
        assertTrue("MAIL FROM response should start with 250", response.startsWith("250"));
        assertTrue("MAIL FROM response should confirm sender", 
                  response.toLowerCase().contains("ok") || response.toLowerCase().contains("accepted"));
    }
    
    @Test
    public void testRcptToCommand() {
        // Setup transaction on shared connection
        sendSmtpCommand("HELO client.example.com", true);
        sendSmtpCommand("MAIL FROM:<sender@example.com>", true);
        
        List<String> result = sendSmtpCommand("RCPT TO:<recipient@example.com>", true);
        
        assertFalse("Should receive response to RCPT TO", result.isEmpty());
        String response = result.get(0);
        assertTrue("RCPT TO response should start with 250", response.startsWith("250"));
        assertTrue("RCPT TO response should confirm recipient", 
                  response.toLowerCase().contains("ok") || response.toLowerCase().contains("accepted"));
    }
    
    @Test
    public void testDataCommand() {
        // Setup complete transaction on shared connection
        sendSmtpCommand("HELO client.example.com", true);
        sendSmtpCommand("MAIL FROM:<sender@example.com>", true);
        sendSmtpCommand("RCPT TO:<recipient@example.com>", true);
        
        List<String> result = sendSmtpCommand("DATA", true);
        
        assertFalse("Should receive response to DATA", result.isEmpty());
        String response = result.get(0);
        assertTrue("DATA response should start with 354", response.startsWith("354"));
        assertTrue("DATA response should indicate ready for message", 
                  response.toLowerCase().contains("start") || response.toLowerCase().contains("data"));
    }
    
    @Test
    public void testCompleteEmailTransaction() {
        // Complete SMTP transaction on shared connection
        List<String> helo = sendSmtpCommand("HELO client.example.com", true);
        assertTrue("HELO should succeed", helo.get(0).startsWith("250"));
        
        List<String> mailFrom = sendSmtpCommand("MAIL FROM:<sender@example.com>", true);                                                                              
        assertTrue("MAIL FROM should succeed", mailFrom.get(0).startsWith("250"));
        
        List<String> rcptTo = sendSmtpCommand("RCPT TO:<recipient@example.com>", true);
        assertTrue("RCPT TO should succeed", rcptTo.get(0).startsWith("250"));
        
        List<String> data = sendSmtpCommand("DATA", true);
        assertTrue("DATA should succeed", data.get(0).startsWith("354"));
        
        // Send message content (ending with .)
        responses.clear();
        try {
            String messageContent = "Subject: Test Message\r\n" +
                                   "From: sender@example.com\r\n" +
                                   "To: recipient@example.com\r\n" +
                                   "\r\n" +
                                   "This is a test message.\r\n" +
                                   ".\r\n";
            
            ByteBuffer buffer = ByteBuffer.wrap(messageContent.getBytes(StandardCharsets.US_ASCII));
            sharedConnection.receive(buffer);
            Thread.sleep(20);
            
            assertFalse("Should receive response to message data", responses.isEmpty());
            String response = responses.get(0);
            assertTrue("Message should be accepted", response.startsWith("250"));
            
        } catch (Exception e) {
            e.printStackTrace(); // Print full stack trace for debugging
            fail("Error sending message data: " + e.getClass().getSimpleName() + " - " + 
                 (e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }
    
    @Test
    public void testQuitCommand() {
        List<String> result = sendSmtpCommand("QUIT");
        
        assertFalse("Should receive response to QUIT", result.isEmpty());
        String response = result.get(0);
        assertTrue("QUIT response should start with 221", response.startsWith("221"));
        assertTrue("QUIT response should say goodbye", response.toLowerCase().contains("bye"));
    }
    
    @Test
    public void testRsetCommand() {
        // Setup transaction then reset
        sendSmtpCommand("HELO client.example.com");
        sendSmtpCommand("MAIL FROM:<sender@example.com>");
        sendSmtpCommand("RCPT TO:<recipient@example.com>");
        
        List<String> result = sendSmtpCommand("RSET");
        
        assertFalse("Should receive response to RSET", result.isEmpty());
        String response = result.get(0);
        assertTrue("RSET response should start with 250", response.startsWith("250"));
        assertTrue("RSET response should confirm reset", 
                  response.toLowerCase().contains("ok") || response.toLowerCase().contains("reset"));
    }
    
    @Test
    public void testNoopCommand() {
        List<String> result = sendSmtpCommand("NOOP");
        
        assertFalse("Should receive response to NOOP", result.isEmpty());
        String response = result.get(0);
        assertTrue("NOOP response should start with 250", response.startsWith("250"));
    }
    
    @Test
    public void testInvalidCommand() {
        List<String> result = sendSmtpCommand("INVALID");
        
        assertFalse("Should receive response to invalid command", result.isEmpty());
        String response = result.get(0);
        assertTrue("Invalid command response should start with 500", response.startsWith("500"));
        assertTrue("Invalid command response should mention unrecognized", 
                  response.toLowerCase().contains("unrecognized") || response.toLowerCase().contains("command"));
    }
    
    @Test
    public void testCommandWithoutHelo() {
        // Try MAIL FROM without HELO first
        List<String> result = sendSmtpCommand("MAIL FROM:<sender@example.com>");
        
        assertFalse("Should receive response to MAIL FROM without HELO", result.isEmpty());
        String response = result.get(0);
        assertTrue("Should require HELO first", response.startsWith("503") || response.startsWith("502"));
    }
    
    @Test
    public void testPipelining() {
        // Test sending multiple commands together (PIPELINING)
        try {
            SMTPConnection connection = createSMTPConnection();
            // Clear responses AFTER creating connection to skip greeting
            responses.clear();
            
            String commands = "HELO client.example.com\r\n" +
                             "MAIL FROM:<sender@example.com>\r\n" +
                             "RCPT TO:<recipient@example.com>\r\n";
            
            ByteBuffer buffer = ByteBuffer.wrap(commands.getBytes(StandardCharsets.US_ASCII));                                                                  
            connection.receive(buffer);
            Thread.sleep(30);
            
            assertTrue("Should receive multiple responses", responses.size() >= 3);                                                                             
            assertTrue("First response should be HELO response", responses.get(0).startsWith("250"));                                                           
            assertTrue("Second response should be MAIL response", responses.get(1).startsWith("250"));                                                          
            assertTrue("Third response should be RCPT response", responses.get(2).startsWith("250"));
            
        } catch (Exception e) {
            fail("Error testing pipelining: " + e.getClass().getSimpleName() + " - " + 
                 (e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }
    
    /**
     * Helper class that simulates the proper non-blocking buffer management
     * done by Connection.processInbound() and SelectorLoop.
     */
    private static class BufferSimulator {
        private ByteBuffer buffer = ByteBuffer.allocate(4096);
        private final SMTPConnection conn;
        
        BufferSimulator(SMTPConnection conn) {
            this.conn = conn;
        }
        
        void receive(byte[] data) {
            buffer.put(data);
            buffer.flip();
            conn.receive(buffer);
            buffer.compact();
        }
        
        void receive(String data) {
            receive(data.getBytes(StandardCharsets.US_ASCII));
        }
    }
    
    @Test
    public void testFragmentedCommands() {
        // Test that SMTP commands can be sent in fragments
        try {
            SMTPConnection connection = createSMTPConnection();
            // Clear responses AFTER connection is created to skip greeting banner
            Thread.sleep(10); // Allow greeting to be sent
            responses.clear();
            BufferSimulator sim = new BufferSimulator(connection);
            
            // Send HELO command in parts
            sim.receive("HE");
            sim.receive("LO client.exam");
            sim.receive("ple.com\r\n");
            
            assertFalse("Should receive response to fragmented HELO", responses.isEmpty());
            assertTrue("Fragmented HELO should succeed", responses.get(0).startsWith("250"));
            
        } catch (Exception e) {
            fail("Error testing fragmented commands: " + e.getMessage());
        }
    }
    
    @Test
    public void testDotStuffing() {
        // Test dot-stuffing in message content using shared connection
        sendSmtpCommand("HELO client.example.com", true);
        sendSmtpCommand("MAIL FROM:<sender@example.com>", true);
        sendSmtpCommand("RCPT TO:<recipient@example.com>", true);
        sendSmtpCommand("DATA", true);
        
        responses.clear();
        try {
            // Send message content in smaller chunks to avoid buffer management issues
            String[] chunks = {
                "Subject: Dot Stuffing Test\r\n",
                "\r\n",
                "This line is normal.\r\n",
                "..This line starts with a dot and should be unstuffed.\r\n",
                ".\r\n" // End of message
            };
            
            for (String chunk : chunks) {
                ByteBuffer buffer = ByteBuffer.wrap(chunk.getBytes(StandardCharsets.US_ASCII));
                sharedConnection.receive(buffer);
                Thread.sleep(5); // Small delay between chunks
            }
            
            Thread.sleep(20); // Wait for processing
            
            assertFalse("Should receive response to dot-stuffed message", responses.isEmpty());                                                                 
            String response = responses.get(0);
            assertTrue("Dot-stuffed message should be accepted", response.startsWith("250"));                                                                   
            
        } catch (Exception e) {
            fail("Error testing dot stuffing: " + e.getMessage());
        }
    }
    
    /**
     * Test SMTP handler implementation using staged handler pattern.
     */
    private static class TestSMTPHandler implements ClientConnected, HelloHandler, 
            MailFromHandler, RecipientHandler, MessageDataHandler {
        
        @Override
        public void connected(ConnectionInfo info, ConnectedState state) {
            // Accept all connections for testing
            state.acceptConnection("localhost ESMTP Test", this);
        }
        
        // HelloHandler methods
        
        @Override
        public void hello(boolean extended, String clientDomain, HelloState state) {
            // Accept all HELO/EHLO greetings
            state.acceptHello(this);
        }
        
        @Override
        public void tlsEstablished(TLSInfo tlsInfo) {
            // TLS handshake completed - no action needed for test
        }
        
        @Override
        public void authenticated(Principal principal, AuthenticateState state) {
            // Accept any authenticated principal
            state.accept(this);
        }
        
        @Override
        public void quit() {
            // Client sent QUIT - no action needed for test
        }
        
        // MailFromHandler methods
        
        @Override
        public SMTPPipeline getPipeline() {
            // No pipeline for testing
            return null;
        }
        
        @Override
        public void mailFrom(EmailAddress sender, boolean smtputf8, 
                           DeliveryRequirements deliveryRequirements, MailFromState state) {
            // Accept all senders for testing
            state.acceptSender(this);
        }
        
        // RecipientHandler methods
        
        @Override
        public void rcptTo(EmailAddress recipient, MailboxFactory factory, RecipientState state) {
            // Accept all recipients for testing
            state.acceptRecipient(this);
        }
        
        @Override
        public void startMessage(MessageStartState state) {
            // Accept DATA command for testing
            state.acceptMessage(this);
        }
        
        // MessageDataHandler methods
        
        @Override
        public void messageContent(ByteBuffer content) {
            // Accept all message content for testing (no-op)
        }
        
        @Override
        public void messageComplete(MessageEndState state) {
            // Accept all messages for testing
            state.acceptMessageDelivery("test-queue-id", this);
        }
        
        @Override
        public void messageAborted() {
            // Message transfer aborted - no action needed for test
        }
        
        // Shared methods
        
        @Override
        public void reset(ResetState state) {
            // Accept reset and return to ready state
            state.acceptReset(this);
        }
        
        @Override
        public void disconnected() {
            // Connection closed - no action needed for test
        }
    }
    
    /**
     * Test SMTP connection that captures send() calls for verification.
     */
    private class TestSMTPConnection extends SMTPConnection {
        
        public TestSMTPConnection(SocketChannel channel, SSLEngine engine, boolean secure, 
                                SMTPServer smtpServer, ClientConnected handler) {
            super(smtpServer, channel, engine, secure, handler);
            
            // Set the base class channel field (normally done by Server)
            try {
                java.lang.reflect.Field baseChannelField = org.bluezoo.gumdrop.Connection.class.getDeclaredField("channel");
                baseChannelField.setAccessible(true);
                baseChannelField.set(this, channel);
            } catch (Exception e) {
                throw new RuntimeException("Failed to set base class channel field", e);
            }
        }
        
        @Override
        public void send(ByteBuffer buf) {
            // Capture the response for testing
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            String response = new String(data, StandardCharsets.US_ASCII).trim();
            
            responses.add(response);
            
            // Don't actually send over network - we're testing in isolation
        }
    }
    
    /**
     * Mock SocketChannel for testing (we don't actually use network).
     */
    private static class MockSocketChannel extends SocketChannel {
        
        private boolean open = true;
        
        protected MockSocketChannel() {
            super(null);
        }
        
        @Override
        public SocketChannel bind(java.net.SocketAddress local) { return this; }
        
        @Override
        public <T> SocketChannel setOption(java.net.SocketOption<T> name, T value) { return this; }
        
        @Override
        public <T> T getOption(java.net.SocketOption<T> name) { return null; }
        
        @Override
        public java.util.Set<java.net.SocketOption<?>> supportedOptions() { return java.util.Collections.emptySet(); }
        
        @Override
        public SocketChannel shutdownInput() { return this; }
        
        @Override
        public SocketChannel shutdownOutput() { return this; }
        
        @Override
        public java.net.Socket socket() { return null; }
        
        @Override
        public boolean isConnected() { return true; }
        
        @Override
        public boolean isConnectionPending() { return false; }
        
        @Override
        public boolean connect(java.net.SocketAddress remote) { return true; }
        
        @Override
        public boolean finishConnect() { return true; }
        
        @Override
        public java.net.SocketAddress getRemoteAddress() { 
            return new InetSocketAddress("127.0.0.1", 12345); 
        }
        
        @Override
        public java.net.SocketAddress getLocalAddress() { 
            return new InetSocketAddress("127.0.0.1", 25); 
        }
        
        @Override
        public int read(ByteBuffer dst) { return 0; }
        
        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) { return 0; }
        
        @Override
        public int write(ByteBuffer src) { 
            int remaining = src.remaining();
            src.position(src.limit()); // Simulate write
            return remaining;
        }
        
        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) { return 0; }
        
        @Override
        protected void implCloseSelectableChannel() { open = false; }
        
        @Override
        protected void implConfigureBlocking(boolean block) {}
    }
}

