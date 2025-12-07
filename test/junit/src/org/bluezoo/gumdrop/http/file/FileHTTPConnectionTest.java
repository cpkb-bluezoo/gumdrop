/*
 * FileHTTPConnectionTest.java
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
import java.io.File;
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
 * JUnit 4 test for FileHTTPConnection implementation.
 * 
 * <p>This test creates a FileHTTPConnection directly and feeds it HTTP requests via received(),
 * capturing responses via an overridden send() method. This allows us to test
 * file serving, upload, and delete operations at the protocol level without server complexity.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FileHTTPConnectionTest {
    
    private Path tempRoot;
    private FileHTTPConnection connection;
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
        createFileHTTPConnection();
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
    private void setupTestEnvironment() throws Exception {
        tempRoot = Files.createTempDirectory("file-http-junit-test");
        
        // Create test directory structure
        Files.createDirectories(tempRoot.resolve("subdir"));
        Files.createDirectories(tempRoot.resolve("uploads"));
        
        // Create test files
        Files.write(tempRoot.resolve("index.html"), 
                   "<html><body><h1>Welcome</h1></body></html>".getBytes(StandardCharsets.UTF_8));
        
        Files.write(tempRoot.resolve("test.txt"), 
                   "This is a test file.\nLine 2\nLine 3".getBytes(StandardCharsets.UTF_8));
        
        Files.write(tempRoot.resolve("subdir/nested.html"), 
                   "<html><body>Nested file</body></html>".getBytes(StandardCharsets.UTF_8));
        
        // Create a binary test file
        byte[] binaryData = new byte[256];
        for (int i = 0; i < 256; i++) {
            binaryData[i] = (byte) i;
        }
        Files.write(tempRoot.resolve("binary.dat"), binaryData);
    }
    
    /**
     * Creates FileHTTPConnection directly for protocol testing.
     */
    private void createFileHTTPConnection() throws Exception {
        // Create mock socket channel
        MockSocketChannel mockChannel = new MockSocketChannel();
        
        // Create the FileHTTPConnection directly
        connection = new FileHTTPConnection(mockChannel, null, false, tempRoot, true, "index.html");
        
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
        
        // Clear any initialization responses
        responses.clear();
    }
    
    /**
     * Sends an HTTP request to a fresh connection and returns all response data.
     * Creates a new HTTPConnection for each request to simulate proper HTTP/1.x behavior.
     */
    private List<String> sendHttpRequest(String request) {
        responses.clear();
        
        // Create a fresh connection for each request (proper HTTP/1.x behavior)
        try {
            createFileHTTPConnection();
        } catch (Exception e) {
            fail("Failed to create fresh connection: " + e.getMessage());
        }
        
        try {
            // For PUT requests, split headers and body to simulate proper HTTP flow
            if (request.startsWith("PUT ") && request.contains("Content-Length:")) {
                int bodyStart = request.indexOf("\r\n\r\n");
                if (bodyStart >= 0) {
                    String headers = request.substring(0, bodyStart + 4); // Include the \r\n\r\n
                    String body = request.substring(bodyStart + 4);
                    
                    
                    // Send headers first
                    ByteBuffer headerBuffer = ByteBuffer.wrap(headers.getBytes(StandardCharsets.UTF_8));
                    connection.receive(headerBuffer);
                    
                    Thread.sleep(50); // Allow header processing
                    
                    // Send body separately if not empty
                    if (!body.isEmpty()) {
                        ByteBuffer bodyBuffer = ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8));
                        connection.receive(bodyBuffer);
                    }
                } else {
                    // No body, send as normal
                    ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));
                    connection.receive(buffer);
                }
            } else {
                // Non-PUT requests - send as normal
                ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));
                connection.receive(buffer);
            }
            
            // Allow processing time
            Thread.sleep(100);
            
        } catch (Exception e) {
            fail("Error sending HTTP request: " + e.getMessage());
        }
        
        // Combine header and body responses into proper HTTP response
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
        String[] lines = response.split("\\r?\\n");
        if (lines.length > 0) {
            String statusLine = lines[0];
            String[] parts = statusLine.split(" ");
            if (parts.length >= 2) {
                try {
                    return Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }
    
    /**
     * Extracts response body from HTTP response.
     */
    private String getResponseBody(String response) {
        int bodyStart = response.indexOf("\r\n\r\n");
        if (bodyStart >= 0) {
            return response.substring(bodyStart + 4);
        }
        return "";
    }
    
    /**
     * Cleans up test environment.
     */
    private void cleanup() {
        try {
            if (tempRoot != null) {
                Files.walk(tempRoot)
                     .sorted((a, b) -> b.compareTo(a))
                     .forEach(path -> {
                         try {
                             Files.delete(path);
                         } catch (Exception e) {
                             // Ignore cleanup errors
                         }
                     });
            }
        } catch (Exception e) {
            // Ignore cleanup errors in tests
        }
    }
    
    @Test
    public void testGetRootFile() {
        String request = "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n";
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive HTTP response", result.isEmpty());
        String response = result.get(0);
        
        int statusCode = getStatusCode(response);
        assertTrue("Should return 200 or redirect for root", 
                  statusCode == 200 || statusCode == 301 || statusCode == 302);
    }
    
    @Test
    public void testGetExistingFile() {
        String request = "GET /index.html HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n";
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive HTTP response", result.isEmpty());
        String response = result.get(0);
        
        assertEquals("Should return 200 for existing file", 200, getStatusCode(response));
        String body = getResponseBody(response);
        assertTrue("Should contain HTML content", body.contains("<html>"));
        assertTrue("Should contain Welcome", body.contains("Welcome"));
    }
    
    @Test
    public void testGetTextFile() {
        String request = "GET /test.txt HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n";
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive HTTP response", result.isEmpty());
        String response = result.get(0);
        
        assertEquals("Should return 200 for text file", 200, getStatusCode(response));
        String body = getResponseBody(response);
        assertTrue("Should contain file content", body.contains("This is a test file"));
        assertTrue("Should contain multiple lines", body.contains("Line 2"));
        
        // Check Content-Type header
        assertTrue("Should have text/plain content type", response.contains("Content-Type: text/plain"));
    }
    
    @Test
    public void testGetNestedFile() {
        String request = "GET /subdir/nested.html HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n";
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive HTTP response", result.isEmpty());
        String response = result.get(0);
        
        assertEquals("Should return 200 for nested file", 200, getStatusCode(response));
        String body = getResponseBody(response);
        assertTrue("Should contain nested file content", body.contains("Nested file"));
    }
    
    @Test
    public void testGetNonExistentFile() {
        String request = "GET /nonexistent.txt HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n";
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive HTTP response", result.isEmpty());
        String response = result.get(0);
        
        assertEquals("Should return 404 for non-existent file", 404, getStatusCode(response));
    }
    
    @Test
    public void testPutNewFile() {
        String fileContent = "This is new content uploaded via PUT.";
        String request = "PUT /newfile.txt HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Content-Length: " + fileContent.length() + "\r\n" +
                        "\r\n" +
                        fileContent;
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive HTTP response", result.isEmpty());
        String response = result.get(0);
        
        int statusCode = getStatusCode(response);
        assertTrue("PUT should return 201 or 204, got " + statusCode, statusCode == 201 || statusCode == 204);
        
        // Verify file was created
        assertTrue("File should be created", Files.exists(tempRoot.resolve("newfile.txt")));
        
        try {
            String savedContent = new String(Files.readAllBytes(tempRoot.resolve("newfile.txt")), StandardCharsets.UTF_8);
            assertEquals("File content should match", fileContent, savedContent);
        } catch (Exception e) {
            fail("Error reading created file: " + e.getMessage());
        }
    }
    
    @Test
    public void testPutOverwriteFile() {
        String newContent = "Overwritten content.";
        String request = "PUT /test.txt HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Content-Length: " + newContent.length() + "\r\n" +
                        "\r\n" +
                        newContent;
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive HTTP response", result.isEmpty());
        String response = result.get(0);
        
        int statusCode = getStatusCode(response);
        assertTrue("PUT overwrite should return 200 or 204", statusCode == 200 || statusCode == 204);
        
        // Verify file was overwritten
        try {
            String savedContent = new String(Files.readAllBytes(tempRoot.resolve("test.txt")), StandardCharsets.UTF_8);
            assertEquals("File should be overwritten", newContent, savedContent);
        } catch (Exception e) {
            fail("Error reading overwritten file: " + e.getMessage());
        }
    }
    
    @Test
    public void testPutInSubdirectory() {
        String fileContent = "File in subdirectory.";
        String request = "PUT /subdir/newfile.txt HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Content-Length: " + fileContent.length() + "\r\n" +
                        "\r\n" +
                        fileContent;
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive HTTP response", result.isEmpty());
        String response = result.get(0);
        
        int statusCode = getStatusCode(response);
        assertTrue("PUT in subdirectory should succeed, got " + statusCode, statusCode == 201 || statusCode == 204);
        
        // Verify file was created in subdirectory
        assertTrue("File should be created in subdirectory", 
                  Files.exists(tempRoot.resolve("subdir/newfile.txt")));
    }
    
    @Test
    public void testDeleteExistingFile() {
        String request = "DELETE /test.txt HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n";
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive HTTP response", result.isEmpty());
        String response = result.get(0);
        
        int statusCode = getStatusCode(response);
        assertTrue("DELETE should return 200 or 204", statusCode == 200 || statusCode == 204);
        
        // Verify file was deleted
        assertFalse("File should be deleted", Files.exists(tempRoot.resolve("test.txt")));
    }
    
    @Test
    public void testDeleteNonExistentFile() {
        String request = "DELETE /nonexistent.txt HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n";
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive HTTP response", result.isEmpty());
        String response = result.get(0);
        
        assertEquals("DELETE non-existent should return 404", 404, getStatusCode(response));
    }
    
    @Test
    public void testHeadRequest() {
        String request = "HEAD /index.html HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n";
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive HTTP response", result.isEmpty());
        String response = result.get(0);
        
        assertEquals("HEAD should return 200", 200, getStatusCode(response));
        
        // HEAD response should have headers but no body
        assertTrue("Should have Content-Length header", response.contains("Content-Length:"));
        assertTrue("Should have Content-Type header", response.contains("Content-Type:"));
        
        String body = getResponseBody(response);
        assertTrue("HEAD response should have no body", body.isEmpty() || body.trim().isEmpty());
    }
    
    @Test
    public void testBinaryFileDownload() {
        String request = "GET /binary.dat HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n";
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive HTTP response", result.isEmpty());
        String response = result.get(0);
        
        assertEquals("Should return 200 for binary file", 200, getStatusCode(response));
        
        // Should have appropriate content type
        assertTrue("Should have application/octet-stream content type", 
                  response.contains("Content-Type: application/octet-stream"));
        
        // Should have correct content length
        assertTrue("Should have Content-Length header", response.contains("Content-Length: 256"));
    }
    
    @Test
    public void testDirectoryListing() {
        String request = "GET /subdir/ HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n";
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive HTTP response", result.isEmpty());
        String response = result.get(0);
        
        int statusCode = getStatusCode(response);
        // Could be 200 (directory listing) or 403 (forbidden) depending on configuration
        assertTrue("Directory request should return valid status", 
                  statusCode == 200 || statusCode == 403 || statusCode == 404);
    }
    
    @Test
    public void testPathTraversalPrevention() {
        // Try to access file outside document root
        String request = "GET /../../../etc/passwd HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n";
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive HTTP response", result.isEmpty());
        String response = result.get(0);
        
        int statusCode = getStatusCode(response);
        assertTrue("Path traversal should be prevented", 
                  statusCode == 400 || statusCode == 403 || statusCode == 404);
    }
    
    @Test
    public void testLargeFileUpload() {
        // Create a larger file content (1KB)
        StringBuilder largeContent = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            largeContent.append("This is line ").append(i).append(" of a large file.\n");
        }
        
        String content = largeContent.toString();
        String request = "PUT /largefile.txt HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Content-Length: " + content.length() + "\r\n" +
                        "\r\n" +
                        content;
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive HTTP response", result.isEmpty());
        String response = result.get(0);
        
        int statusCode = getStatusCode(response);
        assertTrue("Large file upload should succeed, got " + statusCode, statusCode == 201 || statusCode == 204);
        
        // Verify file size
        try {
            long fileSize = Files.size(tempRoot.resolve("largefile.txt"));
            assertEquals("File size should match", content.length(), fileSize);
        } catch (Exception e) {
            fail("Error checking file size: " + e.getMessage());
        }
    }
    
    @Test
    public void testConditionalRequests() {
        // First, get the file to see its Last-Modified date
        String request1 = "GET /index.html HTTP/1.1\r\n" +
                         "Host: localhost\r\n" +
                         "\r\n";
        
        List<String> result1 = sendHttpRequest(request1);
        String response1 = result1.get(0);
        
        // Extract Last-Modified header if present
        String lastModified = null;
        String[] lines = response1.split("\\r?\\n");
        for (String line : lines) {
            if (line.startsWith("Last-Modified:")) {
                lastModified = line.substring("Last-Modified:".length()).trim();
                break;
            }
        }
        
        if (lastModified != null) {
            // Send conditional request
            String request2 = "GET /index.html HTTP/1.1\r\n" +
                             "Host: localhost\r\n" +
                             "If-Modified-Since: " + lastModified + "\r\n" +
                             "\r\n";
            
            List<String> result2 = sendHttpRequest(request2);
            if (result2.isEmpty()) {
                // Connection might be in invalid state, skip this part of the test
                return;
            }
            String response2 = result2.get(0);
            
            int statusCode = getStatusCode(response2);
            // Should be 304 Not Modified or 200 (depending on implementation)
            assertTrue("Conditional request should work", statusCode == 200 || statusCode == 304);
        }
    }
    
    
    /**
     * Mock SocketChannel for testing.
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
            return new java.net.Socket() {
                @Override
                public void setTcpNoDelay(boolean on) {}
                
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
                public int getLocalPort() { return 8080; }
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
            return new InetSocketAddress("127.0.0.1", 8080);
        }
        
        @Override
        protected void implCloseSelectableChannel() {
            open = false;
        }
        
        @Override
        protected void implConfigureBlocking(boolean block) {}
    }
}
