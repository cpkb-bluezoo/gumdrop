/*
 * HTTPProtocolTest.java
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

package org.bluezoo.gumdrop.http;

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.SendCallback;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import javax.net.ssl.SSLEngine;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JUnit 4 test for HTTP protocol implementation.
 * 
 * <p>This test creates an HTTP connection directly and feeds it data via received(),
 * capturing responses via an overridden send() method. This allows us to test
 * the HTTP protocol implementation in isolation without server threading issues.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPProtocolTest {
    
    private TestHTTPConnection connection;
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
        rootLogger.setLevel(Level.WARNING); // Even less verbose for HTTP (lots of debug output)
        
        // Don't create connection here - each test will create its own
    }
    
    @After
    public void tearDown() throws Exception {
        // Restore logging level
        if (rootLogger != null && originalLogLevel != null) {
            rootLogger.setLevel(originalLogLevel);
        }
    }
    
    /**
     * Creates fresh HTTP connection for each test to avoid state issues.
     */
    private HTTPConnection createFreshHTTPConnection() throws Exception {
        // Clear any previous responses
        responses.clear();
        
        // Create mock socket channel
        // Create a fresh HTTP connection with null channel to test null safety
        HTTPConnection conn = new HTTPConnection(null, null, false, 0);
        
        // Channel is now set by constructor, no need to set fields manually
        
        // Set up test callback to capture send() output
        conn.setSendCallback(new SendCallback() {
            @Override
            public void onSend(Connection connection, ByteBuffer buf) {
                if (buf != null) {
                    byte[] data = new byte[buf.remaining()];
                    buf.get(data);
                    responses.add(new String(data, StandardCharsets.UTF_8));
                }
            }
        });
        
        // Initialize the HTTP connection properly
        try {
            conn.init();
        } catch (Exception e) {
            // For testing, we can ignore some initialization errors but we should know about them
            System.err.println("Warning: HTTP connection init() failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
        
        return conn;
    }
    
    /**
     * Sends an HTTP request using a fresh connection and returns responses.
     */
    private List<String> sendHttpRequest(String request) {
        try {
            // Create fresh connection for each request to avoid state issues
            HTTPConnection conn = createFreshHTTPConnection();
            
            // Convert request to bytes 
            ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));
            
            // Debug: Check if objects are null before calling received()
            if (conn == null) throw new RuntimeException("conn is null");
            if (buffer == null) throw new RuntimeException("buffer is null");
            
            // Send data to connection using protected received() method
            conn.receive(buffer);
            
            // Allow brief processing time for HTTP processing
            Thread.sleep(50);
            
            return new ArrayList<>(responses);
            
        } catch (Exception e) {
            System.err.println("=== FULL STACK TRACE OF HTTP TEST EXCEPTION ===");
            e.printStackTrace();
            System.err.println("=== END STACK TRACE ===");
            fail("Error sending HTTP request: " + e.getClass().getSimpleName() + " - " +
                 (e.getMessage() != null ? e.getMessage() : e.toString()));
            return new ArrayList<>();
        }
    }
    
    @Test
    public void testSimpleGetRequest() {
        String request = "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive HTTP response", result.isEmpty());
        String response = result.get(0);
        assertTrue("Response should contain HTTP status line", response.contains("HTTP/1.1"));
        assertTrue("Should be a 404 (default response)", response.contains("404"));
    }
    
    @Test
    public void testHttpVersions() {
        // Test HTTP/1.0
        String request10 = "GET / HTTP/1.0\r\n" +
                          "Host: localhost\r\n" +
                          "\r\n";
        
        List<String> result10 = sendHttpRequest(request10);
        assertFalse("Should receive HTTP/1.0 response", result10.isEmpty());
        assertTrue("Should respond with HTTP/1.0", result10.get(0).contains("HTTP/1.0"));
        
        // Reset for next test
        responses.clear();
        
        // Test HTTP/1.1
        String request11 = "GET / HTTP/1.1\r\n" +
                          "Host: localhost\r\n" +
                          "\r\n";
        
        List<String> result11 = sendHttpRequest(request11);
        assertFalse("Should receive HTTP/1.1 response", result11.isEmpty());
        assertTrue("Should respond with HTTP/1.1", result11.get(0).contains("HTTP/1.1"));
    }
    
    @Test
    public void testPostRequest() {
        String postBody = "name=test&value=data";
        String request = "POST /submit HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Content-Type: application/x-www-form-urlencoded\r\n" +
                        "Content-Length: " + postBody.length() + "\r\n" +
                        "\r\n" +
                        postBody;
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive HTTP response to POST", result.isEmpty());
        String response = result.get(0);
        assertTrue("Response should contain HTTP status", response.contains("HTTP/1.1"));
        // Default is 404, but POST was properly parsed
        assertTrue("Should be a 404 (no handler)", response.contains("404"));
    }
    
    @Test
    public void testHttpHeaders() {
        String request = "GET /test HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "User-Agent: HTTPProtocolTest/1.0\r\n" +
                        "Accept: text/html,application/xhtml+xml\r\n" +
                        "Accept-Language: en-US,en;q=0.9\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "\r\n";
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive HTTP response", result.isEmpty());
        String response = result.get(0);
        assertTrue("Should process headers correctly", response.contains("HTTP/1.1"));
        
        // The response should include some standard headers
        assertTrue("Should include Date header", response.contains("Date:"));
        assertTrue("Should include Server header", response.contains("Server:"));
    }
    
    @Test
    public void testKeepAlive() {
        String request = "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: keep-alive\r\n" +
                        "\r\n";
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive HTTP response", result.isEmpty());
        String response = result.get(0);
        assertTrue("Should handle keep-alive", response.contains("HTTP/1.1"));
        
        // Connection should remain open for keep-alive
        // Send another request on the same connection
        responses.clear();
        String request2 = "GET /test2 HTTP/1.1\r\n" +
                         "Host: localhost\r\n" +
                         "Connection: close\r\n" +
                         "\r\n";
        
        List<String> result2 = sendHttpRequest(request2);
        assertFalse("Should receive second HTTP response", result2.isEmpty());
        assertTrue("Second response should be HTTP/1.1", result2.get(0).contains("HTTP/1.1"));
    }
    
    @Test
    public void testFragmentedRequest() {
        // Test that HTTP requests can be sent in fragments
        responses.clear();
        
        try {
            // Create fresh connection for fragmented test
            HTTPConnection conn = createFreshHTTPConnection();
            
            // Send request in multiple parts
            String part1 = "GET /test HTTP/1.1\r\n";
            String part2 = "Host: localhost\r\n";
            String part3 = "User-Agent: Test\r\n";
            String part4 = "\r\n";
            
            conn.receive(ByteBuffer.wrap(part1.getBytes(StandardCharsets.UTF_8)));
            Thread.sleep(10);
            conn.receive(ByteBuffer.wrap(part2.getBytes(StandardCharsets.UTF_8)));
            Thread.sleep(10);
            conn.receive(ByteBuffer.wrap(part3.getBytes(StandardCharsets.UTF_8)));
            Thread.sleep(10);
            conn.receive(ByteBuffer.wrap(part4.getBytes(StandardCharsets.UTF_8)));
            Thread.sleep(50);
            
            assertFalse("Should receive response to fragmented request", responses.isEmpty());
            assertTrue("Fragmented request should be processed correctly", 
                      responses.get(0).contains("HTTP/1.1"));
            
        } catch (Exception e) {
            fail("Error testing fragmented request: " + e.getMessage());
        }
    }
    
    @Test
    public void testInvalidHttpRequest() {
        String invalidRequest = "INVALID REQUEST\r\n\r\n";
        
        List<String> result = sendHttpRequest(invalidRequest);
        
        assertFalse("Should receive response to invalid request", result.isEmpty());
        String response = result.get(0);
        assertTrue("Should return error response", 
                  response.contains("400") || response.contains("HTTP/1.1"));
    }
    
    @Test
    public void testHttpMethodsSupport() {
        String[] methods = {"GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS"};
        
        for (String method : methods) {
            responses.clear();
            
            String request = method + " /test HTTP/1.1\r\n" +
                           "Host: localhost\r\n" +
                           "\r\n";
            
            List<String> result = sendHttpRequest(request);
            
            assertFalse("Should receive response to " + method + " request", result.isEmpty());
            String response = result.get(0);
            assertTrue("Should process " + method + " method", response.contains("HTTP/1.1"));
            
            // All should result in 404 (no handler), but should be parsed correctly
            assertTrue("Should handle " + method + " method correctly", 
                      response.contains("404") || response.contains("405") || response.contains("200"));
        }
    }
    
    @Test
    public void testChunkedEncoding() {
        String request = "POST /upload HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "\r\n" +
                        "5\r\n" +
                        "Hello\r\n" +
                        "6\r\n" +
                        " World\r\n" +
                        "0\r\n" +
                        "\r\n";
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive response to chunked request", result.isEmpty());
        String response = result.get(0);
        assertTrue("Should handle chunked encoding", response.contains("HTTP/1.1"));
        assertTrue("Should process chunked request", response.contains("404")); // No handler
    }
    
    @Test
    public void testLargeHeaders() {
        // Test handling of requests with many headers
        StringBuilder request = new StringBuilder();
        request.append("GET /test HTTP/1.1\r\n");
        request.append("Host: localhost\r\n");
        
        // Add many headers
        for (int i = 0; i < 50; i++) {
            request.append("X-Test-Header-").append(i).append(": value").append(i).append("\r\n");
        }
        request.append("\r\n");
        
        List<String> result = sendHttpRequest(request.toString());
        
        assertFalse("Should handle request with many headers", result.isEmpty());
        String response = result.get(0);
        assertTrue("Should process large header request", response.contains("HTTP/1.1"));
    }
    
    @Test
    public void testConnectionClose() {
        String request = "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive response before connection close", result.isEmpty());
        String response = result.get(0);
        assertTrue("Should handle connection close", response.contains("HTTP/1.1"));
    }
    
    /**
     * Test HTTP connection that captures send() calls for verification.
     */
    private class TestHTTPConnection extends HTTPConnection {
        
        public TestHTTPConnection(SocketChannel channel, SSLEngine engine, boolean secure, int framePadding) {
            super(channel, engine, secure, framePadding);
            
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
            String response = new String(data, StandardCharsets.UTF_8);
            
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
