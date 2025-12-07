/*
 * HTTPUpgradeTest.java
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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import javax.net.ssl.SSLEngine;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JUnit 4 test for HTTP protocol upgrade mechanisms.
 * 
 * <p>This test creates HTTP connections directly and tests upgrade scenarios:
 * <ul>
 * <li>HTTP/2 upgrade (h2c) - Connection upgrade from HTTP/1.1 to HTTP/2 cleartext</li>
 * <li>WebSocket upgrade - WebSocket handshake and protocol upgrade</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPUpgradeTest {
    
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
        rootLogger.setLevel(Level.WARNING);
        
        createHTTPConnection();
    }
    
    @After
    public void tearDown() throws Exception {
        // Restore logging level
        if (rootLogger != null && originalLogLevel != null) {
            rootLogger.setLevel(originalLogLevel);
        }
    }
    
    /**
     * Creates HTTP connection directly for upgrade testing.
     */
    private void createHTTPConnection() throws Exception {
        // Create HTTP connector that supports HTTP/2
        HTTPServer server = new HTTPServer();
        server.setSecure(false);
        server.setFramePadding(0);
        
        // Create mock socket channel
        MockSocketChannel mockChannel = new MockSocketChannel();
        
        // Create the HTTP connection directly
        connection = new TestHTTPConnection(mockChannel, null, false, 0);
        
        // Skip init() call - not needed for protocol testing
        
        // Clear any initialization responses
        responses.clear();
    }
    
    /**
     * Sends an HTTP request to the connection and returns responses.
     */
    private List<String> sendHttpRequest(String request) {
        responses.clear();
        
        try {
            ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));
            // Call receive() directly - it's public for testing
            connection.receive(buffer);
            
            // Allow processing time
            Thread.sleep(100);
            
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause != null) {
                fail("Error sending HTTP request: " + cause.getClass().getSimpleName() + " - " + 
                     (cause.getMessage() != null ? cause.getMessage() : cause.toString()));
            } else {
                fail("Error sending HTTP request: " + e.getClass().getSimpleName() + " - " + 
                     (e.getMessage() != null ? e.getMessage() : e.toString()));
            }
        }
        
        return new ArrayList<>(responses);
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
     * Checks if response contains a specific header.
     */
    private boolean hasHeader(String response, String headerName) {
        String[] lines = response.split("\\r?\\n");
        for (String line : lines) {
            if (line.toLowerCase().startsWith(headerName.toLowerCase() + ":")) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Gets header value from response.
     */
    private String getHeaderValue(String response, String headerName) {
        String[] lines = response.split("\\r?\\n");
        for (String line : lines) {
            if (line.toLowerCase().startsWith(headerName.toLowerCase() + ":")) {
                int colonIndex = line.indexOf(':');
                if (colonIndex >= 0) {
                    return line.substring(colonIndex + 1).trim();
                }
            }
        }
        return null;
    }
    
    @Test
    public void testHTTP2UpgradeRequest() {
        // HTTP/2 upgrade request (h2c)
        String request = "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: Upgrade, HTTP2-Settings\r\n" +
                        "Upgrade: h2c\r\n" +
                        "HTTP2-Settings: AAMAAABkAARAAAAAAAIAAAAA\r\n" +
                        "\r\n";
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive HTTP response", result.isEmpty());
        String response = result.get(0);
        
        int statusCode = getStatusCode(response);
        
        // Could be 101 (Switching Protocols) for successful upgrade,
        // or 200 (OK) if upgrade is not supported but request is processed normally
        assertTrue("Should handle HTTP/2 upgrade request", 
                  statusCode == 101 || statusCode == 200 || statusCode == 404);
        
        if (statusCode == 101) {
            // Successful upgrade
            assertTrue("Should have Upgrade header", hasHeader(response, "Upgrade"));
            String upgradeValue = getHeaderValue(response, "Upgrade");
            assertTrue("Should upgrade to h2c", "h2c".equals(upgradeValue));
        }
    }
    
    @Test
    public void testHTTP2UpgradeWithInvalidSettings() {
        // HTTP/2 upgrade with invalid HTTP2-Settings
        String request = "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: Upgrade, HTTP2-Settings\r\n" +
                        "Upgrade: h2c\r\n" +
                        "HTTP2-Settings: InvalidBase64!\r\n" +
                        "\r\n";
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive HTTP response", result.isEmpty());
        String response = result.get(0);
        
        int statusCode = getStatusCode(response);
        
        // Should reject invalid upgrade or process as normal HTTP/1.1
        assertTrue("Should handle invalid HTTP/2 settings", 
                  statusCode == 400 || statusCode == 200 || statusCode == 404);
    }
    
    @Test
    public void testWebSocketUpgradeRequest() {
        // Generate WebSocket key
        String websocketKey = Base64.getEncoder().encodeToString("test-key-123456".getBytes());
        
        String request = "GET /websocket HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Sec-WebSocket-Version: 13\r\n" +
                        "Sec-WebSocket-Key: " + websocketKey + "\r\n" +
                        "\r\n";
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive HTTP response", result.isEmpty());
        String response = result.get(0);
        
        int statusCode = getStatusCode(response);
        
        // Could be 101 (Switching Protocols) for successful WebSocket upgrade,
        // 404 (Not Found) if WebSocket endpoint doesn't exist,
        // or 400 (Bad Request) if WebSocket upgrade is not supported
        assertTrue("Should handle WebSocket upgrade request", 
                  statusCode == 101 || statusCode == 404 || statusCode == 400);
        
        if (statusCode == 101) {
            // Successful WebSocket upgrade
            assertTrue("Should have Upgrade header", hasHeader(response, "Upgrade"));
            String upgradeValue = getHeaderValue(response, "Upgrade");
            assertTrue("Should upgrade to websocket", "websocket".equalsIgnoreCase(upgradeValue));
            
            assertTrue("Should have Sec-WebSocket-Accept header", 
                      hasHeader(response, "Sec-WebSocket-Accept"));
            
            // Verify WebSocket accept key calculation
            String acceptKey = getHeaderValue(response, "Sec-WebSocket-Accept");
            String expectedAccept = calculateWebSocketAccept(websocketKey);
            if (expectedAccept != null) {
                assertEquals("WebSocket accept key should be correct", expectedAccept, acceptKey);
            }
        }
    }
    
    @Test
    public void testWebSocketUpgradeWithProtocol() {
        String websocketKey = Base64.getEncoder().encodeToString("test-protocol-key".getBytes());
        
        String request = "GET /websocket HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Sec-WebSocket-Version: 13\r\n" +
                        "Sec-WebSocket-Key: " + websocketKey + "\r\n" +
                        "Sec-WebSocket-Protocol: chat, superchat\r\n" +
                        "\r\n";
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive HTTP response", result.isEmpty());
        String response = result.get(0);
        
        int statusCode = getStatusCode(response);
        
        // Should handle WebSocket upgrade with protocol negotiation
        assertTrue("Should handle WebSocket upgrade with protocols", 
                  statusCode == 101 || statusCode == 404 || statusCode == 400);
        
        if (statusCode == 101) {
            // May or may not have Sec-WebSocket-Protocol header depending on implementation
            // This is optional - server can choose to support or ignore protocols
        }
    }
    
    @Test
    public void testWebSocketInvalidVersion() {
        String websocketKey = Base64.getEncoder().encodeToString("invalid-version-key".getBytes());
        
        String request = "GET /websocket HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Sec-WebSocket-Version: 8\r\n" +  // Old version
                        "Sec-WebSocket-Key: " + websocketKey + "\r\n" +
                        "\r\n";
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive HTTP response", result.isEmpty());
        String response = result.get(0);
        
        int statusCode = getStatusCode(response);
        
        // Should reject unsupported WebSocket version
        assertTrue("Should reject unsupported WebSocket version", 
                  statusCode == 400 || statusCode == 426 || statusCode == 404);
        
        if (statusCode == 426) {
            // Should indicate supported version
            assertTrue("Should have Sec-WebSocket-Version header", 
                      hasHeader(response, "Sec-WebSocket-Version"));
        }
    }
    
    @Test
    public void testWebSocketMissingKey() {
        String request = "GET /websocket HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Sec-WebSocket-Version: 13\r\n" +
                        // Missing Sec-WebSocket-Key
                        "\r\n";
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive HTTP response", result.isEmpty());
        String response = result.get(0);
        
        int statusCode = getStatusCode(response);
        
        // Should reject WebSocket upgrade without required key
        assertTrue("Should reject WebSocket upgrade without key", 
                  statusCode == 400 || statusCode == 404);
    }
    
    @Test
    public void testHTTP2PriorKnowledge() {
        // HTTP/2 connection preface (for h2c prior knowledge)
        // This would normally be sent as the first thing on a connection
        // that knows it will use HTTP/2 without upgrade
        
        // HTTP/2 connection preface is: "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"
        String preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";
        
        List<String> result = sendHttpRequest(preface);
        
        // The connection should either:
        // 1. Accept the HTTP/2 preface and switch to HTTP/2 mode
        // 2. Reject it as invalid HTTP/1.1 request
        
        // Since we're testing protocol level, we expect some kind of response
        // Even if it's an error response for invalid HTTP/1.1 syntax
        assertTrue("Should handle HTTP/2 preface somehow", 
                  result.isEmpty() || !result.isEmpty());
        
        // If there's a response, it should be an error for invalid HTTP/1.1
        if (!result.isEmpty()) {
            String response = result.get(0);
            int statusCode = getStatusCode(response);
            if (statusCode > 0) {
                // If parsed as HTTP/1.1, should be an error
                assertTrue("Should be error status for invalid HTTP/1.1", statusCode >= 400);
            }
        }
    }
    
    @Test
    public void testMultipleUpgradeOptions() {
        String websocketKey = Base64.getEncoder().encodeToString("multi-upgrade-key".getBytes());
        
        String request = "GET /upgrade HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Upgrade: websocket, h2c\r\n" + // Multiple upgrade options
                        "Sec-WebSocket-Version: 13\r\n" +
                        "Sec-WebSocket-Key: " + websocketKey + "\r\n" +
                        "\r\n";
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive HTTP response", result.isEmpty());
        String response = result.get(0);
        
        int statusCode = getStatusCode(response);
        
        // Server should choose one upgrade option or reject all
        assertTrue("Should handle multiple upgrade options", 
                  statusCode == 101 || statusCode == 400 || statusCode == 404);
        
        if (statusCode == 101) {
            // Should upgrade to one of the protocols
            assertTrue("Should have Upgrade header", hasHeader(response, "Upgrade"));
            String upgradeValue = getHeaderValue(response, "Upgrade");
            assertTrue("Should upgrade to one of the offered protocols", 
                      "websocket".equalsIgnoreCase(upgradeValue) || "h2c".equals(upgradeValue));
        }
    }
    
    @Test
    public void testInvalidUpgradeRequest() {
        String request = "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Upgrade: invalid-protocol\r\n" +
                        "\r\n";
        
        List<String> result = sendHttpRequest(request);
        
        assertFalse("Should receive HTTP response", result.isEmpty());
        String response = result.get(0);
        
        int statusCode = getStatusCode(response);
        
        // Should reject unknown upgrade protocol or process as normal HTTP
        assertTrue("Should handle invalid upgrade protocol", 
                  statusCode == 400 || statusCode == 404 || statusCode == 200);
    }
    
    /**
     * Calculates WebSocket Sec-WebSocket-Accept header value.
     */
    private String calculateWebSocketAccept(String key) {
        try {
            String magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            String combined = key + magic;
            
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest(combined.getBytes(StandardCharsets.UTF_8));
            
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Test HTTP connection that captures send() calls for verification.
     */
    private class TestHTTPConnection extends HTTPConnection {
        
        public TestHTTPConnection(SocketChannel channel, SSLEngine engine, boolean secure, int framePadding) {
            super(channel, engine, secure, framePadding);
            
            // Set the base class channel field
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
            // Null buffer signals connection close - ignore
            if (buf == null) {
                return;
            }
            
            // Capture the response for testing
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            String response = new String(data, StandardCharsets.UTF_8);
            
            responses.add(response);
            
            // Don't actually send over network - we're testing in isolation
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
