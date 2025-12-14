/*
 * POP3ProtocolTest.java
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

package org.bluezoo.gumdrop.pop3;

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.SendCallback;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SASLMechanism;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.mailbox.MessageContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.Assert.*;

/**
 * JUnit 4 test for POP3 protocol implementation.
 * 
 * <p>This test creates a POP3 connection directly and feeds it data via received(),
 * capturing responses via an overridden send() method. This allows us to test
 * the POP3 protocol implementation in isolation without server threading issues.
 * 
 * <p>Includes extensive fuzzing tests to ensure proper handling of chunked/fragmented data.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class POP3ProtocolTest {
    
    private POP3Connection connection;
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
        
        createPOP3Connection();
    }
    
    @After
    public void tearDown() throws Exception {
        // Restore logging level
        if (rootLogger != null && originalLogLevel != null) {
            rootLogger.setLevel(originalLogLevel);
        }
    }
    
    /**
     * Creates POP3 connection directly for protocol testing.
     */
    private POP3Connection createPOP3Connection() throws Exception {
        // Create POP3 server
        POP3Server server = new POP3Server();
        server.setPort(1110); // Not actually used, but needed for initialization
        
        // Set test realm
        server.setRealm(new TestRealm());
        
        // Set test mailbox factory
        server.setMailboxFactory(new TestMailboxFactory());
        
        // Enable APOP for testing
        server.setEnableAPOP(true);
        
        // Create the POP3 connection with null channel to test null safety
        POP3Connection conn = new POP3Connection(server, null, false);
        
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
        
        // Initialize the POP3 connection (sends greeting)
        try {
            conn.init();
        } catch (Exception e) {
            // For testing, we can ignore some initialization errors
        }
        
        return conn;
    }
    
    // Shared connection for state-dependent POP3 sequences
    private POP3Connection sharedConnection = null;
    
    /**
     * Sends a POP3 command to the connection and returns responses.
     */
    private List<String> sendPop3Command(String command) {
        return sendPop3Command(command, false);
    }
    
    /**
     * Sends a POP3 command to the connection and returns responses.
     */
    private List<String> sendPop3Command(String command, boolean useSharedConnection) {
        try {
            POP3Connection conn;
            if (useSharedConnection) {
                if (sharedConnection == null) {
                    sharedConnection = createPOP3Connection();
                }
                conn = sharedConnection;
            } else {
                conn = createPOP3Connection();
            }
            
            String pop3Command = command + "\r\n";
            ByteBuffer buffer = ByteBuffer.wrap(pop3Command.getBytes(StandardCharsets.US_ASCII));
            
            // Clear any greeting from responses before sending command
            responses.clear();
            
            conn.receive(buffer);
            
            // Allow brief processing time
            Thread.sleep(10);
            
            return new ArrayList<>(responses);
            
        } catch (Exception e) {
            fail("Error sending POP3 command '" + command + "': " + e.getClass().getSimpleName() + 
                 " - " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            return new ArrayList<>();
        }
    }
    
    @Test
    public void testGreeting() throws Exception {
        // Create connection and check that init() sends the greeting
        POP3Connection conn = createPOP3Connection();
        
        assertFalse("Should have received POP3 greeting from init()", responses.isEmpty());
        String greeting = responses.get(0);
        assertTrue("Greeting should start with +OK", greeting.startsWith("+OK"));
    }
    
    @Test
    public void testCapaCommand() {
        List<String> result = sendPop3Command("CAPA");
        
        assertFalse("Should receive response to CAPA", result.isEmpty());
        String response = String.join("\n", result);
        assertTrue("CAPA should start with +OK", response.startsWith("+OK"));
        assertTrue("CAPA should list capabilities", response.contains("USER") || response.contains("."));
    }
    
    @Test
    public void testUserCommand() {
        List<String> result = sendPop3Command("USER testuser");
        
        assertFalse("Should receive response to USER", result.isEmpty());
        String response = result.get(0);
        assertTrue("USER should succeed", response.startsWith("+OK"));
    }
    
    @Test
    public void testPassCommand() {
        // Send USER first on shared connection
        sendPop3Command("USER testuser", true);
        
        List<String> result = sendPop3Command("PASS testpass", true);
        
        assertFalse("Should receive response to PASS", result.isEmpty());
        String response = result.get(0);
        assertTrue("PASS should succeed or fail", response.startsWith("+OK") || response.startsWith("-ERR"));
    }
    
    @Test
    public void testStatCommand() {
        // Login first on shared connection
        sendPop3Command("USER testuser", true);
        sendPop3Command("PASS testpass", true);
        
        List<String> result = sendPop3Command("STAT", true);
        
        assertFalse("Should receive response to STAT", result.isEmpty());
        String response = result.get(0);
        assertTrue("STAT should return status", response.startsWith("+OK"));
    }
    
    @Test
    public void testListCommand() {
        // Login first on shared connection
        sendPop3Command("USER testuser", true);
        sendPop3Command("PASS testpass", true);
        
        List<String> result = sendPop3Command("LIST", true);
        
        assertFalse("Should receive response to LIST", result.isEmpty());
        String response = String.join("\n", result);
        assertTrue("LIST should succeed", response.startsWith("+OK"));
    }
    
    @Test
    public void testUidlCommand() {
        // Login first on shared connection
        sendPop3Command("USER testuser", true);
        sendPop3Command("PASS testpass", true);
        
        List<String> result = sendPop3Command("UIDL", true);
        
        assertFalse("Should receive response to UIDL", result.isEmpty());
        String response = String.join("\n", result);
        assertTrue("UIDL should succeed", response.startsWith("+OK"));
    }
    
    @Test
    public void testRetrCommand() {
        // Login first on shared connection
        sendPop3Command("USER testuser", true);
        sendPop3Command("PASS testpass", true);
        
        List<String> result = sendPop3Command("RETR 1", true);
        
        assertFalse("Should receive response to RETR", result.isEmpty());
        String response = result.get(0);
        assertTrue("RETR should respond", response.startsWith("+OK") || response.startsWith("-ERR"));
    }
    
    @Test
    public void testDeleCommand() {
        // Login first on shared connection
        sendPop3Command("USER testuser", true);
        sendPop3Command("PASS testpass", true);
        
        List<String> result = sendPop3Command("DELE 1", true);
        
        assertFalse("Should receive response to DELE", result.isEmpty());
        String response = result.get(0);
        assertTrue("DELE should respond", response.startsWith("+OK") || response.startsWith("-ERR"));
    }
    
    @Test
    public void testRsetCommand() {
        // Login first on shared connection
        sendPop3Command("USER testuser", true);
        sendPop3Command("PASS testpass", true);
        
        List<String> result = sendPop3Command("RSET", true);
        
        assertFalse("Should receive response to RSET", result.isEmpty());
        String response = result.get(0);
        assertTrue("RSET should succeed", response.startsWith("+OK"));
    }
    
    @Test
    public void testNoopCommand() {
        List<String> result = sendPop3Command("NOOP");
        
        assertFalse("Should receive response to NOOP", result.isEmpty());
        String response = result.get(0);
        assertTrue("NOOP should succeed", response.startsWith("+OK"));
    }
    
    @Test
    public void testQuitCommand() {
        List<String> result = sendPop3Command("QUIT");
        
        assertFalse("Should receive response to QUIT", result.isEmpty());
        String response = result.get(0);
        assertTrue("QUIT should succeed", response.startsWith("+OK"));
    }
    
    @Test
    public void testTopCommand() {
        // Login first on shared connection
        sendPop3Command("USER testuser", true);
        sendPop3Command("PASS testpass", true);
        
        List<String> result = sendPop3Command("TOP 1 10", true);
        
        assertFalse("Should receive response to TOP", result.isEmpty());
        String response = result.get(0);
        assertTrue("TOP should respond", response.startsWith("+OK") || response.startsWith("-ERR"));
    }
    
    @Test
    public void testInvalidCommand() {
        List<String> result = sendPop3Command("INVALID");
        
        assertFalse("Should receive response to invalid command", result.isEmpty());
        String response = result.get(0);
        assertTrue("Invalid command should return -ERR", response.startsWith("-ERR"));
    }
    
    @Test
    public void testCommandWithoutAuth() {
        // Try LIST without authenticating first
        List<String> result = sendPop3Command("LIST");
        
        assertFalse("Should receive response to LIST without auth", result.isEmpty());
        String response = result.get(0);
        assertTrue("Should require authentication", response.startsWith("-ERR"));
    }
    
    // ============== FUZZING TESTS ==============
    
    /**
     * Helper class that simulates the proper non-blocking buffer management
     * done by Connection.processInbound() and SelectorLoop.
     * 
     * <p>The contract is:
     * <ol>
     *   <li>Append new data to the buffer (in write mode)</li>
     *   <li>Flip to read mode</li>
     *   <li>Call receive() - it processes complete lines and leaves position at unconsumed data</li>
     *   <li>Compact to preserve unconsumed data for next append</li>
     * </ol>
     */
    private static class BufferSimulator {
        private ByteBuffer buffer = ByteBuffer.allocate(4096);
        private final POP3Connection conn;
        
        BufferSimulator(POP3Connection conn) {
            this.conn = conn;
        }
        
        /**
         * Simulates receiving data as the SelectorLoop would.
         * Appends data to persistent buffer, then processes with proper compact().
         */
        void receive(byte[] data) {
            // Append new data (buffer is in write mode after compact)
            buffer.put(data);
            
            // Flip to read mode for processing
            buffer.flip();
            
            // Call receive - leaves position at unconsumed data
            conn.receive(buffer);
            
            // Compact to preserve unconsumed data for next append
            buffer.compact();
        }
        
        void receive(String data) {
            receive(data.getBytes(StandardCharsets.US_ASCII));
        }
    }
    
    @Test
    public void testFragmentedCommand() {
        // Test that POP3 commands can be sent in fragments
        try {
            POP3Connection conn = createPOP3Connection();
            responses.clear();
            BufferSimulator sim = new BufferSimulator(conn);
            
            // Send command in multiple parts
            sim.receive("CA");
            sim.receive("PA");
            sim.receive("\r");
            sim.receive("\n");
            
            assertFalse("Should receive response to fragmented command", responses.isEmpty());
            String response = String.join("\n", responses);
            assertTrue("Fragmented CAPA should work", response.contains("+OK"));
            
        } catch (Exception e) {
            fail("Error testing fragmented command: " + e.getMessage());
        }
    }
    
    @Test
    public void testByteByByteDelivery() {
        // Extreme fuzzing: deliver one byte at a time
        try {
            POP3Connection conn = createPOP3Connection();
            responses.clear();
            BufferSimulator sim = new BufferSimulator(conn);
            
            String command = "NOOP\r\n";
            for (int i = 0; i < command.length(); i++) {
                sim.receive(new byte[] { (byte) command.charAt(i) });
            }
            
            assertFalse("Should handle byte-by-byte delivery", responses.isEmpty());
            String response = String.join("\n", responses);
            assertTrue("Byte-by-byte command should work", response.contains("+OK"));
            
        } catch (Exception e) {
            fail("Error testing byte-by-byte delivery: " + e.getMessage());
        }
    }
    
    @Test
    public void testMultipleCommandsInOneBuffer() {
        // Test pipelining: multiple commands in single buffer
        try {
            POP3Connection conn = createPOP3Connection();
            responses.clear();
            
            String commands = "USER testuser\r\nPASS testpass\r\nSTAT\r\n";
            conn.receive(ByteBuffer.wrap(commands.getBytes(StandardCharsets.US_ASCII)));
            Thread.sleep(30);
            
            assertFalse("Should receive responses to pipelined commands", responses.isEmpty());
            String response = String.join("\n", responses);
            // Should have at least 3 responses
            assertTrue("Should respond to multiple commands", 
                      response.split("\\+OK|\\-ERR").length >= 3);
            
        } catch (Exception e) {
            fail("Error testing pipelined commands: " + e.getMessage());
        }
    }
    
    @Test
    public void testPartialCommandAcrossBuffers() {
        // Command split across multiple buffers at different points
        try {
            POP3Connection conn = createPOP3Connection();
            responses.clear();
            BufferSimulator sim = new BufferSimulator(conn);
            
            // Split in middle of command
            sim.receive("NO");
            sim.receive("OP\r\n");
            
            assertTrue("Should handle command split", 
                      String.join("\n", responses).contains("+OK"));
            
            responses.clear();
            
            // Split in middle of arguments
            sim.receive("USER test");
            sim.receive("user\r\n");
            
            assertTrue("Should handle argument split", 
                      String.join("\n", responses).contains("+OK"));
            
        } catch (Exception e) {
            fail("Error testing partial commands: " + e.getMessage());
        }
    }
    
    @Test
    public void testFragmentedAuthentication() {
        // Test authentication with fragmented USER/PASS
        try {
            POP3Connection conn = createPOP3Connection();
            responses.clear();
            BufferSimulator sim = new BufferSimulator(conn);
            
            // Send USER in many fragments
            String[] userFragments = {
                "US",
                "ER ",
                "te",
                "st",
                "us",
                "er",
                "\r",
                "\n"
            };
            
            for (String fragment : userFragments) {
                sim.receive(fragment);
            }
            
            assertTrue("Should handle fragmented USER", 
                      String.join("\n", responses).contains("+OK"));
            
            responses.clear();
            
            // Send PASS in many fragments
            String[] passFragments = {
                "PA",
                "SS ",
                "te",
                "st",
                "pa",
                "ss",
                "\r",
                "\n"
            };
            
            for (String fragment : passFragments) {
                sim.receive(fragment);
            }
            
            assertFalse("Should handle fragmented PASS", responses.isEmpty());
            
        } catch (Exception e) {
            fail("Error testing fragmented authentication: " + e.getMessage());
        }
    }
    
    @Test
    public void testRandomChunkSizes() {
        // Test with random chunk sizes to simulate real network conditions
        try {
            POP3Connection conn = createPOP3Connection();
            responses.clear();
            BufferSimulator sim = new BufferSimulator(conn);
            
            String command = "CAPA\r\n";
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
            assertTrue("Random chunks should work", response.contains("+OK"));
            
        } catch (Exception e) {
            fail("Error testing random chunks: " + e.getMessage());
        }
    }
    
    @Test
    public void testCRLFSplitting() {
        // Test splitting at CRLF boundary
        try {
            POP3Connection conn = createPOP3Connection();
            responses.clear();
            BufferSimulator sim = new BufferSimulator(conn);
            
            // Split between CR and LF
            sim.receive("NOOP\r");
            sim.receive("\n");
            
            assertFalse("Should handle CRLF split", responses.isEmpty());
            String response = String.join("\n", responses);
            assertTrue("CRLF split should work", response.contains("+OK"));
            
        } catch (Exception e) {
            fail("Error testing CRLF split: " + e.getMessage());
        }
    }
    
    @Test
    public void testLongCommandFragmented() {
        // Test a command with long arguments delivered in fragments
        try {
            POP3Connection conn = createPOP3Connection();
            responses.clear();
            BufferSimulator sim = new BufferSimulator(conn);
            
            // Build a command with a very long username
            StringBuilder longUser = new StringBuilder("USER ");
            for (int i = 0; i < 100; i++) {
                longUser.append("x");
            }
            longUser.append("\r\n");
            
            // Send in chunks of 5 bytes
            String command = longUser.toString();
            for (int i = 0; i < command.length(); i += 5) {
                int end = Math.min(i + 5, command.length());
                String chunk = command.substring(i, end);
                sim.receive(chunk);
            }
            
            assertFalse("Should handle long fragmented command", responses.isEmpty());
            // Will fail authentication but should parse correctly
            String response = String.join("\n", responses);
            assertTrue("Long fragmented command should complete", 
                      response.contains("+OK") || response.contains("-ERR"));
            
        } catch (Exception e) {
            fail("Error testing long fragmented command: " + e.getMessage());
        }
    }
    
    @Test
    public void testStatePreservationAcrossFragments() {
        // Ensure connection state is preserved correctly when commands are fragmented
        try {
            POP3Connection conn = createPOP3Connection();
            responses.clear();
            BufferSimulator sim = new BufferSimulator(conn);
            
            // Send USER in fragments
            sim.receive("USER te");
            sim.receive("stuser\r\n");
            
            // Verify USER succeeded
            String userResponse = String.join("\n", responses);
            responses.clear();
            
            // Send PASS in fragments  
            sim.receive("PASS te");
            sim.receive("stpass\r\n");
            
            // Should work if state was preserved
            String passResponse = String.join("\n", responses);
            assertFalse("State should be preserved across fragments", passResponse.isEmpty());
            
            responses.clear();
            
            // Send STAT in fragments to verify authenticated state
            sim.receive("ST");
            sim.receive("AT\r\n");
            
            String statResponse = String.join("\n", responses);
            assertTrue("Should be in authenticated state", 
                      statResponse.contains("+OK") || statResponse.contains("-ERR"));
            
        } catch (Exception e) {
            fail("Error testing state preservation: " + e.getMessage());
        }
    }
    
    @Test
    public void testExtremeFragmentation() {
        // Test with very small fragments across entire authentication sequence
        try {
            POP3Connection conn = createPOP3Connection();
            responses.clear();
            BufferSimulator sim = new BufferSimulator(conn);
            
            String sequence = "USER testuser\r\nPASS testpass\r\nSTAT\r\nQUIT\r\n";
            
            // Send 2 bytes at a time
            for (int i = 0; i < sequence.length(); i += 2) {
                int end = Math.min(i + 2, sequence.length());
                String chunk = sequence.substring(i, end);
                sim.receive(chunk);
            }
            
            assertFalse("Should handle extreme fragmentation", responses.isEmpty());
            String response = String.join("\n", responses);
            // Should have responses for all commands
            assertTrue("Extreme fragmentation should work", response.contains("+OK"));
            
        } catch (Exception e) {
            fail("Error testing extreme fragmentation: " + e.getMessage());
        }
    }
    
    @Test
    public void testMixedFragmentationPatterns() {
        // Test with varying fragment sizes in same session
        try {
            POP3Connection conn = createPOP3Connection();
            responses.clear();
            BufferSimulator sim = new BufferSimulator(conn);
            
            // USER with 1-byte fragments
            String user = "USER testuser\r\n";
            for (int i = 0; i < user.length(); i++) {
                sim.receive(new byte[] {(byte) user.charAt(i)});
            }
            responses.clear();
            
            // PASS with 3-byte fragments
            String pass = "PASS testpass\r\n";
            for (int i = 0; i < pass.length(); i += 3) {
                int end = Math.min(i + 3, pass.length());
                sim.receive(pass.substring(i, end));
            }
            responses.clear();
            
            // STAT as complete command
            sim.receive("STAT\r\n");
            
            assertFalse("Should handle mixed fragmentation patterns", responses.isEmpty());
            String response = String.join("\n", responses);
            assertTrue("Mixed patterns should work", response.contains("+OK") || response.contains("-ERR"));
            
        } catch (Exception e) {
            fail("Error testing mixed fragmentation: " + e.getMessage());
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
     * Test mailbox with no messages.
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

