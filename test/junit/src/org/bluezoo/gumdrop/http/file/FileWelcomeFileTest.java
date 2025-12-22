/*
 * FileWelcomeFileTest.java
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

package org.bluezoo.gumdrop.http.file;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import org.bluezoo.gumdrop.SendCallback;
import org.bluezoo.gumdrop.http.HTTPConnection;

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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JUnit 4 test for FileHTTPConnection welcome file functionality.
 * 
 * <p>This test verifies that the welcome file mechanism works correctly,
 * including default behaviour, custom configurations, and order of preference.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FileWelcomeFileTest {
    
    private Path tempRoot;
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
        
        // Set up test file system
        setupTestEnvironment();
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
     * Sets up temporary file system for testing welcome files.
     */
    private void setupTestEnvironment() throws Exception {
        tempRoot = Files.createTempDirectory("welcome-file-test");
        
        // Create directory structure
        Files.createDirectories(tempRoot.resolve("dir1"));
        Files.createDirectories(tempRoot.resolve("dir2"));
        Files.createDirectories(tempRoot.resolve("dir3"));
        Files.createDirectories(tempRoot.resolve("empty"));
        
        // Create various welcome files in dir1 (test order preference)
        Files.write(tempRoot.resolve("dir1/welcome.html"), 
                   "<html><body>Welcome Page</body></html>".getBytes(StandardCharsets.UTF_8));
        Files.write(tempRoot.resolve("dir1/index.html"), 
                   "<html><body>Index Page</body></html>".getBytes(StandardCharsets.UTF_8));
        Files.write(tempRoot.resolve("dir1/default.htm"), 
                   "<html><body>Default Page</body></html>".getBytes(StandardCharsets.UTF_8));
        
        // Create dir4 with files but no welcome files (for directory listing test)
        Files.createDirectories(tempRoot.resolve("dir4"));
        Files.write(tempRoot.resolve("dir4/file1.txt"), 
                   "Content of file 1".getBytes(StandardCharsets.UTF_8));
        Files.write(tempRoot.resolve("dir4/file2.html"), 
                   "<html><body>File 2</body></html>".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(tempRoot.resolve("dir4/subdir"));
        Files.write(tempRoot.resolve("dir4/data.json"), 
                   "{\"name\": \"test\"}".getBytes(StandardCharsets.UTF_8));
        
        // Create only index.html in dir2 (test default)
        Files.write(tempRoot.resolve("dir2/index.html"), 
                   "<html><body>Dir2 Index</body></html>".getBytes(StandardCharsets.UTF_8));
        
        // Create only custom welcome file in dir3
        Files.write(tempRoot.resolve("dir3/start.html"), 
                   "<html><body>Start Page</body></html>".getBytes(StandardCharsets.UTF_8));
                   
        // Root index file
        Files.write(tempRoot.resolve("index.html"), 
                   "<html><body>Root Index</body></html>".getBytes(StandardCharsets.UTF_8));
        
        // dir1 has no welcome files - for testing empty directory behaviour
    }
    
    /**
     * Creates FileHTTPConnection with specific welcome file configuration.
     */
    private FileHTTPConnection createConnection(String welcomeFile) throws Exception {
        MockSocketChannel mockChannel = new MockSocketChannel();
        FileHTTPConnection connection = new FileHTTPConnection(mockChannel, null, false, tempRoot, false, welcomeFile);
        
        // Set up callback to capture send() output
        connection.setSendCallback(new SendCallback() {
            @Override
            public void onSend(org.bluezoo.gumdrop.Connection conn, ByteBuffer buf) {
                if (buf != null && buf.hasRemaining()) {
                    try {
                        // Create a defensive copy to avoid buffer state issues
                        int remaining = buf.remaining();
                        byte[] data = new byte[remaining];
                        buf.duplicate().get(data); // Use duplicate() to avoid modifying original buffer
                        responses.add(new String(data, StandardCharsets.UTF_8));
                    } catch (Exception e) {
                        // Log but don't fail the test for buffer issues
                        System.err.println("SendCallback error: " + e.getMessage());
                    }
                }
            }
        });
        
        responses.clear();
        return connection;
    }
    
    /**
     * Sends an HTTP request and returns all response data.
     */
    private List<String> sendHttpRequest(FileHTTPConnection connection, String request) {
        responses.clear();
        
        try {
            ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));
            connection.receive(buffer);
            
            // Allow processing time
            Thread.sleep(100);
            
        } catch (Exception e) {
            fail("Error sending HTTP request: " + e.getMessage());
        }
        
        // Combine multiple responses (headers + body chunks) like FileHTTPConnectionTest does
        List<String> combinedResponses = new ArrayList<>();
        
        if (!responses.isEmpty()) {
            String headers = responses.get(0);
            StringBuilder fullResponse = new StringBuilder(headers);
            
            // If there are additional responses, they are body content
            for (int i = 1; i < responses.size(); i++) {
                fullResponse.append(responses.get(i));
            }
            
            combinedResponses.add(fullResponse.toString());
        }
        
        return combinedResponses;
    }
    
    /**
     * Extracts HTTP status code from response.
     */
    private int getStatusCode(String response) {
        if (response.startsWith("HTTP/1.1 ") && response.length() > 12) {
            String statusStr = response.substring(9, 12);
            try {
                return Integer.parseInt(statusStr);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }
    
    @Test
    public void testDefaultWelcomeFile() {
        try {
            FileHTTPConnection connection = createConnection(null); // Should use default "index.html"
            
            String request = "GET / HTTP/1.1\r\n" +
                            "Host: localhost\r\n" +
                            "\r\n";
            
            List<String> result = sendHttpRequest(connection, request);
            
            assertFalse("Should receive HTTP response", result.isEmpty());
            String response = result.get(0);
            
        int statusCode = getStatusCode(response);
        assertEquals("Should serve root index.html with 200 status", 200, statusCode);
        assertTrue("Should serve root index.html content", response.contains("Root Index"));
            
        } catch (Exception e) {
            fail("Test failed with exception: " + e.getMessage());
        }
    }
    
    @Test
    public void testDirectoryWithDefaultWelcomeFile() {
        try {
            FileHTTPConnection connection = createConnection("index.html");
            
            String request = "GET /dir2/ HTTP/1.1\r\n" +
                            "Host: localhost\r\n" +
                            "\r\n";
            
            List<String> result = sendHttpRequest(connection, request);
            
            assertFalse("Should receive HTTP response", result.isEmpty());
            String response = result.get(0);
            
            int statusCode = getStatusCode(response);
            assertEquals("Should serve dir2 index.html with 200 status", 200, statusCode);
            assertTrue("Should serve dir2 index.html content", response.contains("Dir2 Index"));
            
        } catch (Exception e) {
            fail("Test failed with exception: " + e.getMessage());
        }
    }
    
    @Test
    public void testMultipleWelcomeFilesOrderPreference() {
        try {
            // Configure welcome files in order: welcome.html, index.html, default.htm
            FileHTTPConnection connection = createConnection("welcome.html,index.html,default.htm");
            
            String request = "GET /dir1/ HTTP/1.1\r\n" +
                            "Host: localhost\r\n" +
                            "\r\n";
            
            List<String> result = sendHttpRequest(connection, request);
            
            assertFalse("Should receive HTTP response", result.isEmpty());
            String response = result.get(0);
            
            int statusCode = getStatusCode(response);
            assertEquals("Should serve welcome.html with 200 status", 200, statusCode);
            assertTrue("Should serve welcome.html (first in preference order)", response.contains("Welcome Page"));
            assertFalse("Should NOT serve index.html", response.contains("Index Page"));
            
        } catch (Exception e) {
            fail("Test failed with exception: " + e.getMessage());
        }
    }
    
    @Test
    public void testWelcomeFileOrderFallback() {
        try {
            // Configure welcome files where first doesn't exist, second does
            FileHTTPConnection connection = createConnection("notfound.html,index.html,default.htm");
            
            String request = "GET /dir2/ HTTP/1.1\r\n" +
                            "Host: localhost\r\n" +
                            "\r\n";
            
            List<String> result = sendHttpRequest(connection, request);
            
            assertFalse("Should receive HTTP response", result.isEmpty());
            String response = result.get(0);
            
            int statusCode = getStatusCode(response);
            assertEquals("Should serve index.html (fallback) with 200 status", 200, statusCode);
            assertTrue("Should serve index.html content", response.contains("Dir2 Index"));
            
        } catch (Exception e) {
            fail("Test failed with exception: " + e.getMessage());
        }
    }
    
    @Test
    public void testCustomWelcomeFile() {
        try {
            FileHTTPConnection connection = createConnection("start.html");
            
            String request = "GET /dir3/ HTTP/1.1\r\n" +
                            "Host: localhost\r\n" +
                            "\r\n";
            
            List<String> result = sendHttpRequest(connection, request);
            
            assertFalse("Should receive HTTP response", result.isEmpty());
            String response = result.get(0);
            
            int statusCode = getStatusCode(response);
            assertEquals("Should serve start.html with 200 status", 200, statusCode);
            assertTrue("Should serve start.html content", response.contains("Start Page"));
            
        } catch (Exception e) {
            fail("Test failed with exception: " + e.getMessage());
        }
    }
    
    @Test
    public void testNoWelcomeFileFound() {
        try {
            FileHTTPConnection connection = createConnection("notfound.html");
            
            String request = "GET /empty/ HTTP/1.1\r\n" +
                            "Host: localhost\r\n" +
                            "\r\n";
            
            List<String> result = sendHttpRequest(connection, request);
            
            assertFalse("Should receive HTTP response", result.isEmpty());
            String response = result.get(0);
            
            int statusCode = getStatusCode(response);
            assertEquals("Should return 200 with directory listing when no welcome file found", 200, statusCode);
            
            // Verify it's an HTML directory listing
            assertTrue("Should have HTML content type", response.contains("Content-Type: text/html"));
            assertTrue("Should contain directory listing HTML", response.contains("Directory listing for"));
            assertTrue("Should contain HTML structure", response.contains("<html>") && response.contains("</html>"));
            
        } catch (Exception e) {
            fail("Test failed with exception: " + e.getMessage());
        }
    }
    
    @Test
    public void testWelcomeFileWithSpaces() {
        try {
            // Test configuration with spaces around commas
            FileHTTPConnection connection = createConnection(" welcome.html , index.html , default.htm ");
            
            String request = "GET /dir1/ HTTP/1.1\r\n" +
                            "Host: localhost\r\n" +
                            "\r\n";
            
            List<String> result = sendHttpRequest(connection, request);
            
            assertFalse("Should receive HTTP response", result.isEmpty());
            String response = result.get(0);
            
            int statusCode = getStatusCode(response);
            assertEquals("Should handle spaces in configuration", 200, statusCode);
            assertTrue("Should serve welcome.html despite spaces", response.contains("Welcome Page"));
            
        } catch (Exception e) {
            fail("Test failed with exception: " + e.getMessage());
        }
    }
    
    @Test
    public void testEmptyWelcomeFileConfig() {
        try {
            FileHTTPConnection connection = createConnection("");
            
            String request = "GET / HTTP/1.1\r\n" +
                            "Host: localhost\r\n" +
                            "\r\n";
            
            List<String> result = sendHttpRequest(connection, request);
            
            assertFalse("Should receive HTTP response", result.isEmpty());
            String response = result.get(0);
            
            int statusCode = getStatusCode(response);
            assertEquals("Should fall back to default index.html for empty config", 200, statusCode);
            assertTrue("Should serve root index.html", response.contains("Root Index"));
            
        } catch (Exception e) {
            fail("Test failed with exception: " + e.getMessage());
        }
    }
    
    @Test
    public void testDirectoryListingWithContents() {
        try {
            FileHTTPConnection connection = createConnection("notfound.html");
            
            String request = "GET /dir4/ HTTP/1.1\r\n" +
                            "Host: localhost\r\n" +
                            "\r\n";
            
            List<String> result = sendHttpRequest(connection, request);
            
            assertFalse("Should receive HTTP response", result.isEmpty());
            String response = result.get(0);
            
            int statusCode = getStatusCode(response);
            assertEquals("Should return 200 with directory listing", 200, statusCode);
            
            
            // Verify it's an HTML directory listing with actual contents
            assertTrue("Should have HTML content type", response.contains("Content-Type: text/html"));
            assertTrue("Should contain directory listing title", response.contains("Directory listing for /dir4/"));
            assertTrue("Should contain file1.txt", response.contains("file1.txt"));
            assertTrue("Should contain file2.html", response.contains("file2.html"));
            assertTrue("Should contain data.json", response.contains("data.json"));
            assertTrue("Should contain subdir/", response.contains("subdir/"));
            assertTrue("Should contain parent directory link", response.contains("../"));
            
        } catch (Exception e) {
            fail("Test failed with exception: " + e.getMessage());
        }
    }
    
    private void cleanup() throws Exception {
        if (tempRoot != null && Files.exists(tempRoot)) {
            deleteRecursively(tempRoot);
        }
    }
    
    private void deleteRecursively(Path path) throws Exception {
        if (Files.isDirectory(path)) {
            Files.list(path).forEach(child -> {
                try {
                    deleteRecursively(child);
                } catch (Exception e) {
                    // Ignore cleanup errors
                }
            });
        }
        Files.deleteIfExists(path);
    }
    
    
    /**
     * Mock SocketChannel for testing.
     */
    private static class MockSocketChannel extends SocketChannel {
        public MockSocketChannel() {
            super(null);
        }
        
        @Override public SocketChannel bind(java.net.SocketAddress local) { return this; }
        @Override public <T> SocketChannel setOption(java.net.SocketOption<T> name, T value) { return this; }
        @Override public SocketChannel shutdownInput() { return this; }
        @Override public SocketChannel shutdownOutput() { return this; }
        @Override public java.net.Socket socket() { return null; }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isConnectionPending() { return false; }
        @Override public boolean connect(java.net.SocketAddress remote) { return true; }
        @Override public boolean finishConnect() { return true; }
        @Override public java.net.SocketAddress getRemoteAddress() { return new InetSocketAddress("localhost", 80); }
        @Override public int read(ByteBuffer dst) { return 0; }
        @Override public long read(ByteBuffer[] dsts, int offset, int length) { return 0; }
        @Override public int write(ByteBuffer src) { return 0; }
        @Override public long write(ByteBuffer[] srcs, int offset, int length) { return 0; }
        @Override public java.net.SocketAddress getLocalAddress() { return new InetSocketAddress("localhost", 80); }
        @Override public <T> T getOption(java.net.SocketOption<T> name) { return null; }
        @Override public java.util.Set<java.net.SocketOption<?>> supportedOptions() { return new java.util.HashSet<>(); }
        @Override protected void implCloseSelectableChannel() {}
        @Override protected void implConfigureBlocking(boolean block) {}
    }
}
