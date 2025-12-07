/*
 * FTPProtocolTest.java
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

package org.bluezoo.gumdrop.ftp;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import org.bluezoo.gumdrop.ftp.file.BasicFTPFileSystem;
import org.bluezoo.gumdrop.ftp.file.SimpleFTPHandler;

import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JUnit 4 test for FTP protocol implementation.
 * 
 * <p>This test creates an FTP connection directly and feeds it data via received(),
 * capturing responses via an overridden send() method. This allows us to test
 * the FTP protocol implementation in isolation without server threading issues.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FTPProtocolTest {
    
    private Path tempRoot;
    private FTPConnection connection;
    private List<String> responses;
    private Logger rootLogger;
    private Level originalLogLevel;
    private Handler originalHandler;
    
    @Before
    public void setUp() throws Exception {
        // Set up responses list
        responses = new ArrayList<>();
        
        // Setup logging (but less verbose than the standalone version)
        rootLogger = Logger.getLogger("");
        originalLogLevel = rootLogger.getLevel();
        rootLogger.setLevel(Level.INFO); // Less verbose for JUnit
        
        // Set up test file system
        setupTestEnvironment();
        createFTPConnection();
    }
    
    @After
    public void tearDown() throws Exception {
        // Restore logging level
        if (rootLogger != null && originalLogLevel != null) {
            rootLogger.setLevel(originalLogLevel);
        }
        
        // Clean up test environment
        cleanup();
    }
    
    /**
     * Sets up temporary file system for testing.
     */
    private void setupTestEnvironment() throws IOException {
        tempRoot = Files.createTempDirectory("ftp-junit-test");
        
        // Create test directory structure
        Files.createDirectories(tempRoot.resolve("testdir"));
        Files.createDirectories(tempRoot.resolve("uploads"));
        
        // Create test files
        Files.write(tempRoot.resolve("testfile.txt"), 
                   "This is a test file for FTP JUnit testing.\nLine 2\n".getBytes(StandardCharsets.UTF_8));
        
        Files.write(tempRoot.resolve("testdir/subfile.txt"), 
                   "File in subdirectory.".getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * Creates FTP connection directly for protocol testing.
     */
    private void createFTPConnection() throws Exception {
        // Create FTP connector
        FTPServer server = new FTPServer();
        server.setPort(2121); // Not actually used, but needed for initialization
        
        // Create file system and handler
        BasicFTPFileSystem fileSystem = new BasicFTPFileSystem(tempRoot.toString());
        SimpleFTPHandler handler = new SimpleFTPHandler(fileSystem, null); // No realm for testing
        
        // Set handler factory
        server.setHandlerFactory(() -> handler);
        
        // Create mock socket channel (we won't actually use it for network)
        // Create the FTP connection with null channel to test null safety
        connection = new FTPConnection(server, null, null, false, handler);
        
        // Channel is now set by constructor, no need to set fields manually
        
        // Set up test callback to capture send() output
        connection.setSendCallback(new org.bluezoo.gumdrop.SendCallback() {
            @Override
            public void onSend(org.bluezoo.gumdrop.Connection connection, ByteBuffer buf) {
                if (buf != null) {
                    byte[] data = new byte[buf.remaining()];
                    buf.get(data);
                    responses.add(new String(data, StandardCharsets.US_ASCII));
                }
            }
        });
        
        // Initialize the connection (this should send welcome banner)
        connection.init();
        
        // Clear responses from initialization for clean test state
        responses.clear();
    }
    
    /**
     * Sends a command to the FTP connection and returns responses.
     */
    private List<String> sendCommand(String command) {
        responses.clear();
        
        try {
            String ftpCommand = command + "\r\n";
            ByteBuffer buffer = ByteBuffer.wrap(ftpCommand.getBytes(StandardCharsets.US_ASCII));
            connection.receive(buffer);
            
            // Allow a brief moment for processing
            Thread.sleep(10);
            
        } catch (Exception e) {
            fail("Error sending command '" + command + "': " + e.getMessage());
        }
        
        return new ArrayList<>(responses);
    }
    
    /**
     * Cleans up test environment.
     */
    private void cleanup() {
        try {
            if (tempRoot != null) {
                // Delete test directory recursively
                Files.walk(tempRoot)
                     .sorted((a, b) -> b.compareTo(a)) // Delete files before directories
                     .forEach(path -> {
                         try {
                             Files.delete(path);
                         } catch (IOException e) {
                             // Ignore cleanup errors
                         }
                     });
            }
        } catch (Exception e) {
            // Ignore cleanup errors in tests
        }
    }
    
    @Test
    public void testWelcomeBanner() throws Exception {
        // The welcome banner should have been sent during connection init
        // Let's test by creating a fresh connection and capturing the banner
        responses.clear();
        connection.init(); // This should send the welcome banner again
        
        assertFalse("Should have received welcome banner", responses.isEmpty());
        assertTrue("Welcome banner should start with 220", responses.get(0).startsWith("220"));
        assertTrue("Welcome banner should contain 'FTP server ready'", 
                  responses.get(0).contains("FTP server ready"));
    }
    
    @Test
    public void testUserCommand() {
        List<String> result = sendCommand("USER testuser");
        
        assertFalse("Should receive response to USER command", result.isEmpty());
        String response = result.get(0);
        assertTrue("USER response should start with 331", response.startsWith("331"));
        assertTrue("USER response should mention password needed", 
                  response.toLowerCase().contains("password"));
    }
    
    @Test
    public void testPassCommand() {
        // Need to send USER first
        sendCommand("USER testuser");
        
        List<String> result = sendCommand("PASS testpass");
        
        assertFalse("Should receive response to PASS command", result.isEmpty());
        String response = result.get(0);
        assertTrue("PASS response should start with 230", response.startsWith("230"));
        assertTrue("PASS response should confirm login", 
                  response.toLowerCase().contains("logged in") || response.toLowerCase().contains("proceed"));
    }
    
    @Test
    public void testAuthentication() {
        // Test complete authentication sequence
        List<String> userResult = sendCommand("USER testuser");
        assertTrue("USER command should succeed", userResult.get(0).startsWith("331"));
        
        List<String> passResult = sendCommand("PASS testpass");
        assertTrue("PASS command should succeed", passResult.get(0).startsWith("230"));
    }
    
    @Test
    public void testPwdCommand() {
        // Authenticate first
        sendCommand("USER testuser");
        sendCommand("PASS testpass");
        
        List<String> result = sendCommand("PWD");
        
        assertFalse("Should receive response to PWD command", result.isEmpty());
        String response = result.get(0);
        assertTrue("PWD response should start with 257", response.startsWith("257"));
        assertTrue("PWD response should contain root directory", response.contains("/"));
    }
    
    @Test
    public void testSystCommand() {
        // Authenticate first
        sendCommand("USER testuser");
        sendCommand("PASS testpass");
        
        List<String> result = sendCommand("SYST");
        
        assertFalse("Should receive response to SYST command", result.isEmpty());
        String response = result.get(0);
        assertTrue("SYST response should start with 215", response.startsWith("215"));
        assertTrue("SYST response should mention UNIX", response.contains("UNIX"));
    }
    
    @Test
    public void testCwdCommand() {
        // Authenticate first
        sendCommand("USER testuser");
        sendCommand("PASS testpass");
        
        List<String> result = sendCommand("CWD /testdir");
        
        assertFalse("Should receive response to CWD command", result.isEmpty());
        String response = result.get(0);
        assertTrue("CWD response should start with 250", response.startsWith("250"));
        assertTrue("CWD response should confirm directory change", 
                  response.toLowerCase().contains("directory") && response.toLowerCase().contains("changed"));
    }
    
    @Test
    public void testDirectoryNavigation() {
        // Authenticate first
        sendCommand("USER testuser");
        sendCommand("PASS testpass");
        
        // Change to test directory
        List<String> cwdResult = sendCommand("CWD /testdir");
        assertTrue("Should be able to change to /testdir", cwdResult.get(0).startsWith("250"));
        
        // Check current directory
        List<String> pwdResult = sendCommand("PWD");
        assertTrue("PWD should show /testdir", pwdResult.get(0).contains("/testdir"));
        
        // Change back to root
        List<String> rootResult = sendCommand("CWD /");
        assertTrue("Should be able to change back to root", rootResult.get(0).startsWith("250"));
    }
    
    @Test
    public void testTypeCommand() {
        // Authenticate first
        sendCommand("USER testuser");
        sendCommand("PASS testpass");
        
        List<String> result = sendCommand("TYPE I");
        
        assertFalse("Should receive response to TYPE command", result.isEmpty());
        String response = result.get(0);
        assertTrue("TYPE response should start with 200", response.startsWith("200"));
    }
    
    @Test
    public void testInvalidCommand() {
        // Authenticate first
        sendCommand("USER testuser");
        sendCommand("PASS testpass");
        
        List<String> result = sendCommand("INVALID");
        
        assertFalse("Should receive response to invalid command", result.isEmpty());
        String response = result.get(0);
        assertTrue("Invalid command response should start with 500", response.startsWith("500"));
        assertTrue("Invalid command response should mention unrecognized", 
                  response.toLowerCase().contains("unrecognized") || response.toLowerCase().contains("command"));
    }
    
    @Test
    public void testFragmentedCommands() {
        // Test that commands can be sent in fragments
        responses.clear();
        
        try {
            // Send command in two parts
            ByteBuffer part1 = ByteBuffer.wrap("USER".getBytes(StandardCharsets.US_ASCII));
            ByteBuffer part2 = ByteBuffer.wrap(" testuser\r\n".getBytes(StandardCharsets.US_ASCII));
            
            connection.receive(part1);
            Thread.sleep(5); // Brief pause
            connection.receive(part2);
            Thread.sleep(10); // Allow processing
            
            assertFalse("Should receive response to fragmented USER command", responses.isEmpty());
            assertTrue("Fragmented USER command should succeed", responses.get(0).startsWith("331"));
            
        } catch (Exception e) {
            fail("Error testing fragmented commands: " + e.getMessage());
        }
    }
    
    @Test
    public void testMultipleCommands() {
        // Test multiple commands in single buffer
        responses.clear();
        
        try {
            String commands = "USER testuser\r\nPASS testpass\r\n";
            ByteBuffer buffer = ByteBuffer.wrap(commands.getBytes(StandardCharsets.US_ASCII));
            
            connection.receive(buffer);
            Thread.sleep(20); // Allow processing of both commands
            
            assertEquals("Should receive responses to both commands", 2, responses.size());
            assertTrue("First response should be USER response", responses.get(0).startsWith("331"));
            assertTrue("Second response should be PASS response", responses.get(1).startsWith("230"));
            
        } catch (Exception e) {
            fail("Error testing multiple commands: " + e.getMessage());
        }
    }
    
    @Test
    public void testQuitCommand() {
        // Authenticate first
        sendCommand("USER testuser");
        sendCommand("PASS testpass");
        
        // QUIT may cause connection to close, so we handle this specially
        responses.clear();
        
        try {
            String ftpCommand = "QUIT\r\n";
            ByteBuffer buffer = ByteBuffer.wrap(ftpCommand.getBytes(StandardCharsets.US_ASCII));
            connection.receive(buffer);
            Thread.sleep(10); // Allow processing
            
            // Check if we got a response before any exception
            assertFalse("Should receive response to QUIT command", responses.isEmpty());
            String response = responses.get(0);
            assertTrue("QUIT response should start with 221", response.startsWith("221"));
            assertTrue("QUIT response should say goodbye", response.toLowerCase().contains("goodbye"));
            
        } catch (Exception e) {
            // QUIT causes connection cleanup which can throw exceptions - this is expected
            // Just verify we got the response before the exception
            assertFalse("Should have received QUIT response before exception", responses.isEmpty());
            if (!responses.isEmpty()) {
                assertTrue("QUIT response should start with 221", responses.get(0).startsWith("221"));
            }
        }
    }
    
    // Now using setSendCallback mechanism consistently - no custom subclass needed
    
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
        public java.util.Set<java.net.SocketOption<?>> supportedOptions() { 
            return java.util.Collections.emptySet(); 
        }
        
        @Override
        public SocketChannel shutdownInput() { return this; }
        
        @Override
        public SocketChannel shutdownOutput() { return this; }
        
        @Override
        public java.net.Socket socket() { 
            // Return a mock socket that provides the addresses we need
            return new java.net.Socket() {
                @Override
                public void setTcpNoDelay(boolean on) {
                    // Mock method - do nothing
                }
                
                @Override
                public java.net.InetAddress getInetAddress() {
                    try {
                        return java.net.InetAddress.getByName("127.0.0.1");
                    } catch (Exception e) {
                        return null;
                    }
                }
                
                @Override
                public java.net.InetAddress getLocalAddress() {
                    try {
                        return java.net.InetAddress.getByName("127.0.0.1");
                    } catch (Exception e) {
                        return null;
                    }
                }
                
                @Override
                public int getPort() { return 12345; }
                
                @Override
                public int getLocalPort() { return 2121; }
            };
        }
        
        @Override
        public boolean isConnected() { return open; }
        
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
        public int read(ByteBuffer dst) { return -1; }
        
        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) { return -1; }
        
        @Override
        public int write(ByteBuffer src) { return src.remaining(); }
        
        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) { return 0; }
        
        @Override
        public java.net.SocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 2121);
        }
        
        @Override
        protected void implCloseSelectableChannel() {
            open = false;
        }
        
        @Override
        protected void implConfigureBlocking(boolean block) {}
    }
}
