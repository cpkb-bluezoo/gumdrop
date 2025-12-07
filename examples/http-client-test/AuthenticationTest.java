/*
 * AuthenticationTest.java
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

import org.bluezoo.gumdrop.http.client.*;
import org.bluezoo.gumdrop.http.HTTPVersion;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test demonstrating HTTP client authentication capabilities.
 * 
 * This example shows how to use different authentication schemes
 * including Basic, Bearer, and Digest authentication with automatic
 * challenge handling and retry logic.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AuthenticationTest {
    
    private static final String TEST_HOST = "httpbin.org";
    private static final int TEST_PORT = 80;
    
    public static void main(String[] args) {
        System.out.println("Starting HTTP Authentication Test");
        
        try {
            // Test 1: Basic Authentication
            testBasicAuthentication();
            
            // Test 2: Bearer Token Authentication
            testBearerAuthentication();
            
            // Test 3: Digest Authentication (requires challenge)
            testDigestAuthentication();
            
            // Test 4: Multiple Authentication Schemes (fallback)
            testMultipleAuthentication();
            
            System.out.println("\nAll authentication tests completed successfully!");
            
        } catch (Exception e) {
            System.err.println("Authentication test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Tests Basic Authentication using httpbin.org/basic-auth endpoint
     */
    private static void testBasicAuthentication() throws Exception {
        System.out.println("\n=== Testing Basic Authentication ===");
        
        CountDownLatch latch = new CountDownLatch(1);
        
        HTTPClient client = new HTTPClient(TEST_HOST, TEST_PORT);
        
        // Set Basic Authentication credentials
        client.setBasicAuth("user", "passwd");
        
        client.connect(new HTTPClientHandler() {
            @Override
            public void onConnected() {
                System.out.println("✓ Connected to " + TEST_HOST);
            }
            
            @Override
            public void onProtocolNegotiated(HTTPVersion version, HTTPClientConnection conn) {
                System.out.println("✓ Protocol negotiated: " + version);
                
                try {
                    conn.createStream();
                } catch (Exception e) {
                    System.err.println("Failed to create stream: " + e.getMessage());
                    latch.countDown();
                }
            }
            
            @Override
            public void onStreamCreated(HTTPClientStream stream) {
                System.out.println("✓ Stream created: " + stream.getStreamId());
                
                try {
                    // Request protected resource that requires basic auth
                    HTTPRequest request = new HTTPRequest("GET", "/basic-auth/user/passwd");
                    stream.sendRequest(request);
                    stream.completeRequest();
                    
                    System.out.println("✓ Request sent to Basic Auth endpoint");
                    
                } catch (Exception e) {
                    System.err.println("Failed to send request: " + e.getMessage());
                    latch.countDown();
                }
            }
            
            @Override
            public void onStreamResponse(HTTPClientStream stream, HTTPResponse response) {
                System.out.println("✓ Response received: " + response.getStatusCode() + " " + response.getStatusMessage());
                
                if (response.isSuccess()) {
                    System.out.println("  ✓ Basic authentication successful!");
                } else {
                    System.out.println("  ✗ Basic authentication failed");
                }
            }
            
            @Override
            public void onStreamData(HTTPClientStream stream, ByteBuffer data, boolean endStream) {
                String chunk = StandardCharsets.UTF_8.decode(data).toString();
                System.out.println("✓ Received " + chunk.length() + " bytes" + (endStream ? " (final)" : ""));
            }
            
            @Override
            public void onStreamComplete(HTTPClientStream stream) {
                System.out.println("✓ Basic auth test completed");
                latch.countDown();
            }
            
            @Override
            public void onError(Exception e) {
                System.err.println("✗ Error: " + e.getMessage());
                latch.countDown();
            }
            
            @Override
            public void onStreamError(HTTPClientStream stream, Exception error) {
                System.err.println("✗ Stream error: " + error.getMessage());
            }
            
            @Override
            public void onDisconnected() {
                System.out.println("✓ Disconnected");
            }
            
            @Override
            public void onTLSStarted() {
                System.out.println("✓ TLS started");
            }
            
            @Override
            public void onServerSettings(Map<Integer, Long> settings) {
                // Not used for HTTP/1.1
            }
            
            @Override
            public void onGoAway(int lastStreamId, int errorCode, String debugData) {
                // Not used for HTTP/1.1
            }
            
            @Override
            public boolean onPushPromise(HTTPClientStream promisedStream, HTTPRequest promisedRequest) {
                // Not used for HTTP/1.1
                return false;
            }
        });
        
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        if (!completed) {
            throw new Exception("Basic auth test timed out after 30 seconds");
        }
    }
    
    /**
     * Tests Bearer Token Authentication
     */
    private static void testBearerAuthentication() throws Exception {
        System.out.println("\n=== Testing Bearer Authentication ===");
        
        CountDownLatch latch = new CountDownLatch(1);
        
        HTTPClient client = new HTTPClient(TEST_HOST, TEST_PORT);
        
        // Set Bearer Token (using a fake token for demonstration)
        client.setBearerAuth("fake-token-12345");
        
        client.connect(new HTTPClientHandler() {
            @Override
            public void onConnected() {
                System.out.println("✓ Connected to " + TEST_HOST);
            }
            
            @Override
            public void onProtocolNegotiated(HTTPVersion version, HTTPClientConnection conn) {
                System.out.println("✓ Protocol negotiated: " + version);
                
                try {
                    conn.createStream();
                } catch (Exception e) {
                    System.err.println("Failed to create stream: " + e.getMessage());
                    latch.countDown();
                }
            }
            
            @Override
            public void onStreamCreated(HTTPClientStream stream) {
                System.out.println("✓ Stream created: " + stream.getStreamId());
                
                try {
                    // Request endpoint that shows headers (to verify Bearer token was sent)
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Accept", "application/json");
                    
                    HTTPRequest request = new HTTPRequest("GET", "/headers", headers);
                    stream.sendRequest(request);
                    stream.completeRequest();
                    
                    System.out.println("✓ Request sent with Bearer token");
                    
                } catch (Exception e) {
                    System.err.println("Failed to send request: " + e.getMessage());
                    latch.countDown();
                }
            }
            
            @Override
            public void onStreamResponse(HTTPClientStream stream, HTTPResponse response) {
                System.out.println("✓ Response received: " + response.getStatusCode() + " " + response.getStatusMessage());
                System.out.println("  Bearer token should be visible in response body");
            }
            
            @Override
            public void onStreamData(HTTPClientStream stream, ByteBuffer data, boolean endStream) {
                String chunk = StandardCharsets.UTF_8.decode(data).toString();
                
                // Check if the Bearer token appears in the response
                if (chunk.contains("fake-token-12345")) {
                    System.out.println("  ✓ Bearer token found in request headers!");
                }
            }
            
            @Override
            public void onStreamComplete(HTTPClientStream stream) {
                System.out.println("✓ Bearer auth test completed");
                latch.countDown();
            }
            
            @Override
            public void onError(Exception e) {
                System.err.println("✗ Error: " + e.getMessage());
                latch.countDown();
            }
            
            @Override
            public void onStreamError(HTTPClientStream stream, Exception error) {
                System.err.println("✗ Stream error: " + error.getMessage());
            }
            
            @Override
            public void onDisconnected() {
                System.out.println("✓ Disconnected");
            }
            
            @Override
            public void onTLSStarted() {
                System.out.println("✓ TLS started");
            }
            
            @Override
            public void onServerSettings(Map<Integer, Long> settings) {
                // Not used for HTTP/1.1
            }
            
            @Override
            public void onGoAway(int lastStreamId, int errorCode, String debugData) {
                // Not used for HTTP/1.1
            }
            
            @Override
            public boolean onPushPromise(HTTPClientStream promisedStream, HTTPRequest promisedRequest) {
                // Not used for HTTP/1.1
                return false;
            }
        });
        
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        if (!completed) {
            throw new Exception("Bearer auth test timed out after 30 seconds");
        }
    }
    
    /**
     * Tests Digest Authentication with challenge handling
     */
    private static void testDigestAuthentication() throws Exception {
        System.out.println("\n=== Testing Digest Authentication ===");
        
        CountDownLatch latch = new CountDownLatch(1);
        
        HTTPClient client = new HTTPClient(TEST_HOST, TEST_PORT);
        
        // Set Digest Authentication credentials
        client.setDigestAuth("user", "passwd");
        
        client.connect(new HTTPClientHandler() {
            @Override
            public void onConnected() {
                System.out.println("✓ Connected to " + TEST_HOST);
            }
            
            @Override
            public void onProtocolNegotiated(HTTPVersion version, HTTPClientConnection conn) {
                System.out.println("✓ Protocol negotiated: " + version);
                
                try {
                    conn.createStream();
                } catch (Exception e) {
                    System.err.println("Failed to create stream: " + e.getMessage());
                    latch.countDown();
                }
            }
            
            @Override
            public void onStreamCreated(HTTPClientStream stream) {
                System.out.println("✓ Stream created: " + stream.getStreamId());
                
                try {
                    // Request protected resource that requires digest auth
                    // This will trigger a 401 challenge that should be handled automatically
                    HTTPRequest request = new HTTPRequest("GET", "/digest-auth/auth/user/passwd");
                    stream.sendRequest(request);
                    stream.completeRequest();
                    
                    System.out.println("✓ Request sent to Digest Auth endpoint (challenge expected)");
                    
                } catch (Exception e) {
                    System.err.println("Failed to send request: " + e.getMessage());
                    latch.countDown();
                }
            }
            
            @Override
            public void onStreamResponse(HTTPClientStream stream, HTTPResponse response) {
                System.out.println("✓ Response received: " + response.getStatusCode() + " " + response.getStatusMessage());
                
                if (response.getStatusCode() == 401) {
                    System.out.println("  ✓ Digest challenge received (should be handled automatically)");
                } else if (response.isSuccess()) {
                    System.out.println("  ✓ Digest authentication successful after challenge!");
                } else {
                    System.out.println("  ✗ Digest authentication failed");
                }
            }
            
            @Override
            public void onStreamData(HTTPClientStream stream, ByteBuffer data, boolean endStream) {
                String chunk = StandardCharsets.UTF_8.decode(data).toString();
                System.out.println("✓ Received " + chunk.length() + " bytes" + (endStream ? " (final)" : ""));
            }
            
            @Override
            public void onStreamComplete(HTTPClientStream stream) {
                System.out.println("✓ Digest auth test completed");
                latch.countDown();
            }
            
            @Override
            public void onError(Exception e) {
                System.err.println("✗ Error: " + e.getMessage());
                latch.countDown();
            }
            
            @Override
            public void onStreamError(HTTPClientStream stream, Exception error) {
                System.err.println("✗ Stream error: " + error.getMessage());
            }
            
            @Override
            public void onDisconnected() {
                System.out.println("✓ Disconnected");
            }
            
            @Override
            public void onTLSStarted() {
                System.out.println("✓ TLS started");
            }
            
            @Override
            public void onServerSettings(Map<Integer, Long> settings) {
                // Not used for HTTP/1.1
            }
            
            @Override
            public void onGoAway(int lastStreamId, int errorCode, String debugData) {
                // Not used for HTTP/1.1
            }
            
            @Override
            public boolean onPushPromise(HTTPClientStream promisedStream, HTTPRequest promisedRequest) {
                // Not used for HTTP/1.1
                return false;
            }
        });
        
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        if (!completed) {
            throw new Exception("Digest auth test timed out after 30 seconds");
        }
    }
    
    /**
     * Tests multiple authentication schemes with fallback
     */
    private static void testMultipleAuthentication() throws Exception {
        System.out.println("\n=== Testing Multiple Authentication Schemes ===");
        
        CountDownLatch latch = new CountDownLatch(1);
        
        HTTPClient client = new HTTPClient(TEST_HOST, TEST_PORT);
        
        // Add multiple authentication schemes for fallback
        client.addAuthentication(new DigestAuthentication("user", "passwd"));
        client.addAuthentication(new BasicAuthentication("user", "passwd"));
        client.addAuthentication(new BearerAuthentication("fallback-token"));
        
        System.out.println("✓ Configured multiple auth schemes: Digest, Basic, Bearer");
        
        client.connect(new HTTPClientHandler() {
            @Override
            public void onConnected() {
                System.out.println("✓ Connected to " + TEST_HOST);
            }
            
            @Override
            public void onProtocolNegotiated(HTTPVersion version, HTTPClientConnection conn) {
                System.out.println("✓ Protocol negotiated: " + version);
                
                try {
                    conn.createStream();
                } catch (Exception e) {
                    System.err.println("Failed to create stream: " + e.getMessage());
                    latch.countDown();
                }
            }
            
            @Override
            public void onStreamCreated(HTTPClientStream stream) {
                System.out.println("✓ Stream created: " + stream.getStreamId());
                
                try {
                    // Try an endpoint that might prefer different auth types
                    HTTPRequest request = new HTTPRequest("GET", "/basic-auth/user/passwd");
                    stream.sendRequest(request);
                    stream.completeRequest();
                    
                    System.out.println("✓ Request sent (should use first available auth scheme)");
                    
                } catch (Exception e) {
                    System.err.println("Failed to send request: " + e.getMessage());
                    latch.countDown();
                }
            }
            
            @Override
            public void onStreamResponse(HTTPClientStream stream, HTTPResponse response) {
                System.out.println("✓ Response received: " + response.getStatusCode() + " " + response.getStatusMessage());
                
                if (response.isSuccess()) {
                    System.out.println("  ✓ Multi-auth fallback successful!");
                } else {
                    System.out.println("  ~ Multi-auth test (expected to work with Basic auth)");
                }
            }
            
            @Override
            public void onStreamData(HTTPClientStream stream, ByteBuffer data, boolean endStream) {
                // Not logging data for this test
            }
            
            @Override
            public void onStreamComplete(HTTPClientStream stream) {
                System.out.println("✓ Multi-auth test completed");
                latch.countDown();
            }
            
            @Override
            public void onError(Exception e) {
                System.err.println("✗ Error: " + e.getMessage());
                latch.countDown();
            }
            
            @Override
            public void onStreamError(HTTPClientStream stream, Exception error) {
                System.err.println("✗ Stream error: " + error.getMessage());
            }
            
            @Override
            public void onDisconnected() {
                System.out.println("✓ Disconnected");
            }
            
            @Override
            public void onTLSStarted() {
                System.out.println("✓ TLS started");
            }
            
            @Override
            public void onServerSettings(Map<Integer, Long> settings) {
                // Not used for HTTP/1.1
            }
            
            @Override
            public void onGoAway(int lastStreamId, int errorCode, String debugData) {
                // Not used for HTTP/1.1
            }
            
            @Override
            public boolean onPushPromise(HTTPClientStream promisedStream, HTTPRequest promisedRequest) {
                // Not used for HTTP/1.1
                return false;
            }
        });
        
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        if (!completed) {
            throw new Exception("Multi-auth test timed out after 30 seconds");
        }
    }
}
