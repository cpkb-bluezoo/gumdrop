/*
 * SimpleHTTPClientTest.java
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
 * Simple test to verify HTTP client functionality.
 * 
 * This example demonstrates how to use the Gumdrop HTTP client to make
 * basic HTTP requests and handle responses in an event-driven manner.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SimpleHTTPClientTest {
    
    private static final String TEST_HOST = "httpbin.org";
    private static final int TEST_PORT = 80;
    
    public static void main(String[] args) {
        System.out.println("Starting HTTP Client Test");
        
        try {
            // Test 1: Simple GET request
            testSimpleGET();
            
            // Test 2: POST request with body
            testPOST();
            
            System.out.println("All tests completed successfully!");
            
        } catch (Exception e) {
            System.err.println("Test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Tests a simple GET request to httpbin.org/get
     */
    private static void testSimpleGET() throws Exception {
        System.out.println("\n=== Testing Simple GET Request ===");
        
        CountDownLatch latch = new CountDownLatch(1);
        final StringBuilder responseBody = new StringBuilder();
        
        HTTPClient client = new HTTPClient(TEST_HOST, TEST_PORT);
        
        client.connect(new HTTPClientHandler() {
            @Override
            public void onConnected() {
                System.out.println("✓ Connected to " + TEST_HOST);
            }
            
            @Override
            public void onProtocolNegotiated(HTTPVersion version, HTTPClientConnection conn) {
                System.out.println("✓ Protocol negotiated: " + version);
                
                try {
                    // Create a stream for our request
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
                    // Send GET request
                    Map<String, String> headers = new HashMap<>();
                    headers.put("User-Agent", "Gumdrop-HTTP-Client/1.0");
                    headers.put("Accept", "application/json");
                    
                    HTTPRequest request = new HTTPRequest("GET", "/get", headers);
                    stream.sendRequest(request);
                    stream.completeRequest(); // No body to send
                    
                    System.out.println("✓ Request sent: GET /get");
                    
                } catch (Exception e) {
                    System.err.println("Failed to send request: " + e.getMessage());
                    latch.countDown();
                }
            }
            
            @Override
            public void onStreamResponse(HTTPClientStream stream, HTTPResponse response) {
                System.out.println("✓ Response received: " + response.getStatusCode() + " " + response.getStatusMessage());
                System.out.println("  Content-Type: " + response.getContentType());
                System.out.println("  Content-Length: " + response.getContentLength());
            }
            
            @Override
            public void onStreamData(HTTPClientStream stream, ByteBuffer data, boolean endStream) {
                // Convert ByteBuffer to String and append
                String chunk = StandardCharsets.UTF_8.decode(data).toString();
                responseBody.append(chunk);
                
                System.out.println("✓ Received " + chunk.length() + " bytes" + (endStream ? " (final)" : ""));
            }
            
            @Override
            public void onStreamComplete(HTTPClientStream stream) {
                System.out.println("✓ Stream " + stream.getStreamId() + " completed");
                System.out.println("  Response body length: " + responseBody.length() + " characters");
                
                // Print first 200 characters of response
                String preview = responseBody.toString();
                if (preview.length() > 200) {
                    preview = preview.substring(0, 200) + "...";
                }
                System.out.println("  Response preview: " + preview);
                
                latch.countDown();
            }
            
            @Override
            public void onError(Exception e) {
                System.err.println("✗ Error: " + e.getMessage());
                latch.countDown();
            }
            
            @Override
            public void onStreamError(HTTPClientStream stream, Exception error) {
                System.err.println("✗ Stream error on " + stream.getStreamId() + ": " + error.getMessage());
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
        
        // Wait for response (timeout after 30 seconds)
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        if (!completed) {
            throw new Exception("Test timed out after 30 seconds");
        }
        
        System.out.println("✓ GET test completed successfully");
    }
    
    /**
     * Tests a POST request with JSON body to httpbin.org/post
     */
    private static void testPOST() throws Exception {
        System.out.println("\n=== Testing POST Request ===");
        
        CountDownLatch latch = new CountDownLatch(1);
        
        HTTPClient client = new HTTPClient(TEST_HOST, TEST_PORT);
        
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
                    // Prepare JSON body
                    String jsonBody = "{\"name\":\"Gumdrop\",\"type\":\"HTTP Client\",\"version\":\"1.0\"}";
                    byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
                    
                    // Send POST request with headers
                    Map<String, String> headers = new HashMap<>();
                    headers.put("User-Agent", "Gumdrop-HTTP-Client/1.0");
                    headers.put("Content-Type", "application/json");
                    headers.put("Content-Length", String.valueOf(bodyBytes.length));
                    
                    HTTPRequest request = new HTTPRequest("POST", "/post", headers);
                    stream.sendRequest(request);
                    
                    // Send body data
                    ByteBuffer bodyBuffer = ByteBuffer.wrap(bodyBytes);
                    stream.sendData(bodyBuffer, true); // endStream = true
                    
                    System.out.println("✓ Request sent: POST /post (" + bodyBytes.length + " bytes)");
                    
                } catch (Exception e) {
                    System.err.println("Failed to send request: " + e.getMessage());
                    latch.countDown();
                }
            }
            
            @Override
            public void onStreamResponse(HTTPClientStream stream, HTTPResponse response) {
                System.out.println("✓ Response received: " + response.getStatusCode() + " " + response.getStatusMessage());
                
                if (response.isSuccess()) {
                    System.out.println("  ✓ Success status code");
                } else {
                    System.out.println("  ✗ Non-success status code");
                }
            }
            
            @Override
            public void onStreamData(HTTPClientStream stream, ByteBuffer data, boolean endStream) {
                String chunk = StandardCharsets.UTF_8.decode(data).toString();
                System.out.println("✓ Received " + chunk.length() + " bytes" + (endStream ? " (final)" : ""));
            }
            
            @Override
            public void onStreamComplete(HTTPClientStream stream) {
                System.out.println("✓ Stream " + stream.getStreamId() + " completed");
                latch.countDown();
            }
            
            @Override
            public void onError(Exception e) {
                System.err.println("✗ Error: " + e.getMessage());
                latch.countDown();
            }
            
            @Override
            public void onStreamError(HTTPClientStream stream, Exception error) {
                System.err.println("✗ Stream error on " + stream.getStreamId() + ": " + error.getMessage());
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
        
        // Wait for response (timeout after 30 seconds)
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        if (!completed) {
            throw new Exception("Test timed out after 30 seconds");
        }
        
        System.out.println("✓ POST test completed successfully");
    }
}
