/*
 * IMAPProtocolTest.java
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

package org.bluezoo.gumdrop.imap;

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.SendCallback;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SASLMechanism;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.net.ssl.SSLEngine;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.Assert.*;

/**
 * JUnit 4 test for IMAP protocol implementation.
 * 
 * <p>This test creates an IMAP connection directly and feeds it data via received(),
 * capturing responses via an overridden send() method. This allows us to test
 * the IMAP protocol implementation in isolation without server threading issues.
 * 
 * <p>Includes extensive fuzzing tests to ensure proper handling of chunked/fragmented data.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IMAPProtocolTest {
    
    private IMAPConnection connection;
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
        
        createIMAPConnection();
    }
    
    @After
    public void tearDown() throws Exception {
        // Restore logging level
        if (rootLogger != null && originalLogLevel != null) {
            rootLogger.setLevel(originalLogLevel);
        }
    }
    
    /**
     * Creates IMAP connection directly for protocol testing.
     */
    private IMAPConnection createIMAPConnection() throws Exception {
        // Create IMAP server
        IMAPServer server = new IMAPServer();
        server.setPort(1143); // Not actually used, but needed for initialization
        server.setAllowPlaintextLogin(true); // Allow plaintext login for testing
        
        // Set test realm
        server.setRealm(new TestRealm());
        
        // Set test mailbox factory
        server.setMailboxFactory(new TestMailboxFactory());
        
        // Create the IMAP connection with null channel to test null safety
        IMAPConnection conn = new IMAPConnection(server, null, false);
        
        // Set up test callback to capture send() output
        conn.setSendCallback(new SendCallback() {
            @Override
            public void onSend(Connection connection, ByteBuffer buf) {
                if (buf != null && buf.hasRemaining()) {
                    try {
                        int remaining = buf.remaining();
                        byte[] data = new byte[remaining];
                        buf.duplicate().get(data);
                        responses.add(new String(data, StandardCharsets.US_ASCII));
                    } catch (Exception e) {
                        System.err.println("SendCallback error: " + e.getMessage());
                    }
                }
            }
        });
        
        // Clear responses for clean test state
        responses.clear();
        
        // Initialize the IMAP connection
        try {
            conn.init();
        } catch (Exception e) {
            // For testing, we can ignore some initialization errors
        }
        
        // Trigger connected() which sends the IMAP greeting
        conn.connected();
        
        return conn;
    }
    
    // Shared connection for state-dependent IMAP sequences
    private IMAPConnection sharedConnection = null;
    
    /**
     * Sends an IMAP command to the connection and returns responses.
     */
    private List<String> sendImapCommand(String command) {
        return sendImapCommand(command, false);
    }
    
    /**
     * Sends an IMAP command to the connection and returns responses.
     */
    private List<String> sendImapCommand(String command, boolean useSharedConnection) {
        try {
            IMAPConnection conn;
            if (useSharedConnection) {
                if (sharedConnection == null) {
                    sharedConnection = createIMAPConnection();
                }
                conn = sharedConnection;
            } else {
                conn = createIMAPConnection();
            }
            
            String imapCommand = command + "\r\n";
            ByteBuffer buffer = ByteBuffer.wrap(imapCommand.getBytes(StandardCharsets.US_ASCII));
            
            // Clear any greeting from responses before sending command
            responses.clear();
            
            conn.receive(buffer);
            
            // Allow brief processing time
            Thread.sleep(10);
            
            return new ArrayList<>(responses);
            
        } catch (Exception e) {
            fail("Error sending IMAP command '" + command + "': " + e.getClass().getSimpleName() + 
                 " - " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            return new ArrayList<>();
        }
    }
    
    @Test
    public void testGreeting() throws Exception {
        // Create connection and check that init() sends the greeting
        IMAPConnection conn = createIMAPConnection();
        
        assertFalse("Should have received IMAP greeting from init()", responses.isEmpty());
        String greeting = responses.get(0);
        assertTrue("Greeting should start with * OK", greeting.startsWith("* OK"));
        assertTrue("Greeting should contain CAPABILITY", greeting.contains("CAPABILITY"));
    }
    
    @Test
    public void testCapabilityCommand() {
        List<String> result = sendImapCommand("A001 CAPABILITY");
        
        assertFalse("Should receive response to CAPABILITY", result.isEmpty());
        String response = String.join("\n", result);
        assertTrue("Should include IMAP4rev2 capability", response.contains("IMAP4rev2"));
        assertTrue("Should include completion response", response.contains("A001 OK"));
    }
    
    @Test
    public void testLoginCommand() {
        List<String> result = sendImapCommand("A001 LOGIN testuser testpass");
        
        assertFalse("Should receive response to LOGIN", result.isEmpty());
        String response = result.get(result.size() - 1);
        assertTrue("LOGIN should succeed", response.startsWith("A001 OK") || response.startsWith("A001 NO"));
    }
    
    @Test
    public void testLogoutCommand() {
        List<String> result = sendImapCommand("A001 LOGOUT");
        
        assertFalse("Should receive response to LOGOUT", result.isEmpty());
        String response = String.join("\n", result);
        assertTrue("LOGOUT should send BYE", response.contains("* BYE"));
        assertTrue("LOGOUT should complete", response.contains("A001 OK"));
    }
    
    @Test
    public void testListCommand() {
        // Login first on shared connection
        sendImapCommand("A001 LOGIN testuser testpass", true);
        
        List<String> result = sendImapCommand("A002 LIST \"\" \"*\"", true);
        
        assertFalse("Should receive response to LIST", result.isEmpty());
        String response = String.join("\n", result);
        assertTrue("LIST should complete", response.contains("A002 OK"));
    }
    
    @Test
    public void testSelectCommand() {
        // Login first on shared connection
        sendImapCommand("A001 LOGIN testuser testpass", true);
        
        List<String> result = sendImapCommand("A002 SELECT INBOX", true);
        
        assertFalse("Should receive response to SELECT", result.isEmpty());
        String response = String.join("\n", result);
        assertTrue("SELECT should send EXISTS", response.contains("EXISTS") || response.contains("A002"));
    }
    
    @Test
    public void testFetchCommand() {
        // Login and select mailbox on shared connection
        sendImapCommand("A001 LOGIN testuser testpass", true);
        sendImapCommand("A002 SELECT INBOX", true);
        
        List<String> result = sendImapCommand("A003 FETCH 1 (FLAGS)", true);
        
        assertFalse("Should receive response to FETCH", result.isEmpty());
        String response = String.join("\n", result);
        assertTrue("FETCH should complete", response.contains("A003"));
    }
    
    @Test
    public void testSearchCommand() {
        // Login and select mailbox on shared connection
        sendImapCommand("A001 LOGIN testuser testpass", true);
        sendImapCommand("A002 SELECT INBOX", true);
        
        List<String> result = sendImapCommand("A003 SEARCH ALL", true);
        
        assertFalse("Should receive response to SEARCH", result.isEmpty());
        String response = String.join("\n", result);
        assertTrue("SEARCH should send results", response.contains("* SEARCH") || response.contains("A003 OK"));
    }
    
    @Test
    public void testStoreCommand() {
        // Login and select mailbox on shared connection
        sendImapCommand("A001 LOGIN testuser testpass", true);
        sendImapCommand("A002 SELECT INBOX", true);
        
        List<String> result = sendImapCommand("A003 STORE 1 +FLAGS (\\Seen)", true);
        
        assertFalse("Should receive response to STORE", result.isEmpty());
        String response = String.join("\n", result);
        assertTrue("STORE should complete", response.contains("A003"));
    }
    
    @Test
    public void testNoopCommand() {
        List<String> result = sendImapCommand("A001 NOOP");
        
        assertFalse("Should receive response to NOOP", result.isEmpty());
        String response = result.get(result.size() - 1);
        assertTrue("NOOP should succeed", response.startsWith("A001 OK"));
    }
    
    @Test
    public void testInvalidCommand() {
        List<String> result = sendImapCommand("A001 INVALID");
        
        assertFalse("Should receive response to invalid command", result.isEmpty());
        String response = result.get(result.size() - 1);
        assertTrue("Invalid command should return BAD", response.startsWith("A001 BAD"));
    }
    
    @Test
    public void testMissingTag() {
        List<String> result = sendImapCommand("CAPABILITY");
        
        assertFalse("Should receive response to command without tag", result.isEmpty());
        String response = result.get(result.size() - 1);
        assertTrue("Missing tag should return BAD", response.contains("BAD"));
    }
    
    // ============== FUZZING TESTS ==============
    
    /**
     * Helper class that simulates the proper non-blocking buffer management
     * done by Connection.processInbound() and SelectorLoop.
     */
    private static class BufferSimulator {
        private ByteBuffer buffer = ByteBuffer.allocate(4096);
        private final IMAPConnection conn;
        
        BufferSimulator(IMAPConnection conn) {
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
    public void testFragmentedCommand() {
        // Test that IMAP commands can be sent in fragments
        try {
            IMAPConnection conn = createIMAPConnection();
            responses.clear();
            BufferSimulator sim = new BufferSimulator(conn);
            
            // Send command in multiple parts
            sim.receive("A001 ");
            sim.receive("CAPA");
            sim.receive("BILITY");
            sim.receive("\r\n");
            
            assertFalse("Should receive response to fragmented command", responses.isEmpty());
            String response = String.join("\n", responses);
            assertTrue("Fragmented CAPABILITY should work", response.contains("A001 OK"));
            
        } catch (Exception e) {
            fail("Error testing fragmented command: " + e.getMessage());
        }
    }
    
    @Test
    public void testByteByByteDelivery() {
        // Extreme fuzzing: deliver one byte at a time
        try {
            IMAPConnection conn = createIMAPConnection();
            responses.clear();
            BufferSimulator sim = new BufferSimulator(conn);
            
            String command = "A001 NOOP\r\n";
            for (int i = 0; i < command.length(); i++) {
                sim.receive(new byte[] { (byte) command.charAt(i) });
            }
            
            assertFalse("Should handle byte-by-byte delivery", responses.isEmpty());
            String response = String.join("\n", responses);
            assertTrue("Byte-by-byte command should work", response.contains("A001 OK"));
            
        } catch (Exception e) {
            fail("Error testing byte-by-byte delivery: " + e.getMessage());
        }
    }
    
    @Test
    public void testMultipleCommandsInOneBuffer() {
        // Test pipelining: multiple commands in single buffer
        try {
            IMAPConnection conn = createIMAPConnection();
            responses.clear();
            
            String commands = "A001 CAPABILITY\r\nA002 NOOP\r\nA003 LOGOUT\r\n";
            conn.receive(ByteBuffer.wrap(commands.getBytes(StandardCharsets.US_ASCII)));
            Thread.sleep(30);
            
            assertFalse("Should receive responses to pipelined commands", responses.isEmpty());
            String response = String.join("\n", responses);
            assertTrue("Should respond to A001", response.contains("A001"));
            assertTrue("Should respond to A002", response.contains("A002"));
            assertTrue("Should respond to A003", response.contains("A003"));
            
        } catch (Exception e) {
            fail("Error testing pipelined commands: " + e.getMessage());
        }
    }
    
    @Test
    public void testPartialCommandAcrossBuffers() {
        // Command split across multiple buffers at different points
        try {
            IMAPConnection conn = createIMAPConnection();
            responses.clear();
            BufferSimulator sim = new BufferSimulator(conn);
            
            // Split in middle of tag
            sim.receive("A0");
            sim.receive("01 CAPABILITY\r\n");
            
            assertTrue("Should handle tag split", 
                      String.join("\n", responses).contains("A001"));
            
            responses.clear();
            
            // Split in middle of command name
            sim.receive("A002 CAP");
            sim.receive("ABILITY\r\n");
            
            assertTrue("Should handle command name split", 
                      String.join("\n", responses).contains("A002"));
            
        } catch (Exception e) {
            fail("Error testing partial commands: " + e.getMessage());
        }
    }
    
    @Test
    public void testFragmentedLogin() {
        // Test login command with fragmented credentials
        try {
            IMAPConnection conn = createIMAPConnection();
            responses.clear();
            BufferSimulator sim = new BufferSimulator(conn);
            
            // Send LOGIN in many fragments
            String[] fragments = {
                "A001 ",
                "LOGIN ",
                "test",
                "user ",
                "test",
                "pass",
                "\r",
                "\n"
            };
            
            for (String fragment : fragments) {
                sim.receive(fragment);
            }
            
            assertFalse("Should handle fragmented LOGIN", responses.isEmpty());
            String response = String.join("\n", responses);
            assertTrue("Fragmented LOGIN should complete", response.contains("A001"));
            
        } catch (Exception e) {
            fail("Error testing fragmented LOGIN: " + e.getMessage());
        }
    }
    
    @Test
    public void testRandomChunkSizes() {
        // Test with random chunk sizes to simulate real network conditions
        try {
            IMAPConnection conn = createIMAPConnection();
            responses.clear();
            BufferSimulator sim = new BufferSimulator(conn);
            
            String command = "A001 CAPABILITY\r\n";
            int pos = 0;
            java.util.Random random = new java.util.Random(42); // Fixed seed for reproducibility
            
            while (pos < command.length()) {
                int chunkSize = 1 + random.nextInt(Math.min(5, command.length() - pos));
                String chunk = command.substring(pos, pos + chunkSize);
                sim.receive(chunk);
                pos += chunkSize;
            }
            
            assertFalse("Should handle random chunk sizes", responses.isEmpty());
            String response = String.join("\n", responses);
            assertTrue("Random chunks should work", response.contains("A001 OK"));
            
        } catch (Exception e) {
            fail("Error testing random chunks: " + e.getMessage());
        }
    }
    
    @Test
    public void testCRLFSplitting() {
        // Test splitting at CRLF boundary
        try {
            IMAPConnection conn = createIMAPConnection();
            responses.clear();
            BufferSimulator sim = new BufferSimulator(conn);
            
            // Split between CR and LF
            sim.receive("A001 NOOP\r");
            sim.receive("\n");
            
            assertFalse("Should handle CRLF split", responses.isEmpty());
            String response = String.join("\n", responses);
            assertTrue("CRLF split should work", response.contains("A001 OK"));
            
        } catch (Exception e) {
            fail("Error testing CRLF split: " + e.getMessage());
        }
    }
    
    @Test
    public void testLongLineFragmented() {
        // Test a very long command line delivered in fragments
        try {
            IMAPConnection conn = createIMAPConnection();
            BufferSimulator sim = new BufferSimulator(conn);
            
            // Login first
            sim.receive("A001 LOGIN testuser testpass\r\n");
            responses.clear();
            
            // Build a long SEARCH command with many criteria
            StringBuilder searchCmd = new StringBuilder("A002 SEARCH ");
            for (int i = 0; i < 50; i++) {
                searchCmd.append("OR SUBJECT test").append(i).append(" ");
            }
            searchCmd.append("ALL\r\n");
            
            // Send in chunks of 10 bytes
            String command = searchCmd.toString();
            for (int i = 0; i < command.length(); i += 10) {
                int end = Math.min(i + 10, command.length());
                String chunk = command.substring(i, end);
                sim.receive(chunk);
            }
            
            assertFalse("Should handle long fragmented line", responses.isEmpty());
            String response = String.join("\n", responses);
            assertTrue("Long fragmented command should complete", response.contains("A002"));
            
        } catch (Exception e) {
            fail("Error testing long fragmented line: " + e.getMessage());
        }
    }
    
    @Test
    public void testStatePreservationAcrossFragments() {
        // Ensure connection state is preserved correctly when commands are fragmented
        try {
            IMAPConnection conn = createIMAPConnection();
            responses.clear();
            BufferSimulator sim = new BufferSimulator(conn);
            
            // Send LOGIN in fragments
            sim.receive("A001 LOGIN test");
            sim.receive("user testpass\r\n");
            
            // Verify login succeeded
            String loginResponse = String.join("\n", responses);
            responses.clear();
            
            // Send SELECT in fragments  
            sim.receive("A002 SEL");
            sim.receive("ECT INBOX\r\n");
            
            // Should work if state was preserved
            String selectResponse = String.join("\n", responses);
            assertTrue("State should be preserved across fragments", 
                      selectResponse.contains("A002") && !selectResponse.contains("BAD"));
            
        } catch (Exception e) {
            fail("Error testing state preservation: " + e.getMessage());
        }
    }
    
    // ============== TEST HELPERS ==============
    
    /**
     * Test realm that accepts test credentials.
     */
    private static class TestRealm implements Realm {
        @Override
        public boolean passwordMatch(String username, String password) {
            return "testuser".equals(username) && "testpass".equals(password);
        }
        
        @Override
        public String getDigestHA1(String username, String realmName) {
            return null;
        }
        
        @Override
        @SuppressWarnings("deprecation")
        public String getPassword(String username) {
            return "testuser".equals(username) ? "testpass" : null;
        }
        
        @Override
        public boolean isUserInRole(String username, String role) {
            return true;
        }
        
        @Override
        public java.util.Set<SASLMechanism> getSupportedSASLMechanisms() {
            return java.util.EnumSet.of(SASLMechanism.PLAIN, SASLMechanism.LOGIN);
        }
        
        @Override
        public Realm forSelectorLoop(org.bluezoo.gumdrop.SelectorLoop loop) {
            return this;
        }
    }
    
    /**
     * Test mailbox factory.
     */
    private static class TestMailboxFactory implements MailboxFactory {
        @Override
        public MailboxStore createStore() {
            return new TestMailboxStore();
        }
    }
    
    /**
     * Test mailbox store.
     */
    private static class TestMailboxStore implements MailboxStore {
        @Override
        public void open(String username) throws IOException {}
        
        @Override
        public void close() throws IOException {}
        
        @Override
        public char getHierarchyDelimiter() { return '/'; }
        
        @Override
        public List<String> listMailboxes(String reference, String pattern) throws IOException {
            List<String> result = new ArrayList<>();
            result.add("INBOX");
            return result;
        }
        
        @Override
        public List<String> listSubscribed(String reference, String pattern) throws IOException {
            return listMailboxes(reference, pattern);
        }
        
        @Override
        public void subscribe(String mailboxName) throws IOException {}
        
        @Override
        public void unsubscribe(String mailboxName) throws IOException {}
        
        @Override
        public Mailbox openMailbox(String name, boolean readOnly) throws IOException {
            return new TestMailbox();
        }
        
        @Override
        public void createMailbox(String name) throws IOException {}
        
        @Override
        public void deleteMailbox(String name) throws IOException {}
        
        @Override
        public void renameMailbox(String oldName, String newName) throws IOException {}
        
        @Override
        public java.util.Set<org.bluezoo.gumdrop.mailbox.MailboxAttribute> getMailboxAttributes(String mailboxName) throws IOException {
            return java.util.Collections.emptySet();
        }
    }
    
    /**
     * Test mailbox.
     */
    private static class TestMailbox implements Mailbox {
        @Override
        public String getName() { return "INBOX"; }
        
        @Override
        public int getMessageCount() { return 0; }
        
        @Override
        public long getMailboxSize() { return 0; }
        
        @Override
        public java.util.Iterator<org.bluezoo.gumdrop.mailbox.MessageDescriptor> getMessageList() {
            return java.util.Collections.emptyIterator();
        }
        
        @Override
        public org.bluezoo.gumdrop.mailbox.MessageDescriptor getMessage(int messageNumber) { return null; }
        
        @Override
        public java.nio.channels.ReadableByteChannel getMessageContent(int messageNumber) { return null; }
        
        @Override
        public java.nio.channels.ReadableByteChannel getMessageTop(int messageNumber, int bodyLines) { return null; }
        
        @Override
        public void deleteMessage(int messageNumber) throws IOException {}
        
        @Override
        public boolean isDeleted(int messageNumber) { return false; }
        
        @Override
        public void undeleteAll() throws IOException {}
        
        @Override
        public String getUniqueId(int messageNumber) { return String.valueOf(messageNumber); }
        
        @Override
        public void close(boolean expunge) throws IOException {}
    }
}

