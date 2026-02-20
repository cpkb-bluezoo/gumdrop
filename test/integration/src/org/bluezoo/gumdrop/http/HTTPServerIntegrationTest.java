/*
 * HTTPServerIntegrationTest.java
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

import org.bluezoo.gumdrop.AbstractServerIntegrationTest;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

/**
 * Integration test for raw HTTPListener.
 *
 * <p>Tests a raw HTTPListener instance (not subclassed) with real network connections.
 * A raw HTTPListener should:
 * <ul>
 *   <li>Accept connections and respond to valid HTTP requests</li>
 *   <li>Return 404 for any resources (it has nothing to serve)</li>
 *   <li>Handle OPTIONS requests</li>
 *   <li>Support HTTP/1.0 and HTTP/1.1</li>
 *   <li>Support HTTP/2 upgrade</li>
 *   <li>Detect and reject protocol errors</li>
 *   <li>Support HTTPS with SSL/TLS</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPServerIntegrationTest extends AbstractServerIntegrationTest {
    
    @Override
    protected File getTestConfigFile() {
        return new File("test/integration/config/http-server-test.xml");
    }
    
    // ============== Basic HTTP Functionality Tests ==============
    
    @Test
    public void testServerStartsAndAcceptsConnections() throws Exception {
        // If we got here, the server started successfully
        assertNotNull("Server should be running", gumdrop);
        assertTrue("Port 18080 should be listening", isPortListening("127.0.0.1", 18080));
    }
    
    @Test
    public void testGETReturns404() throws Exception {
        String request = "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";
        
        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", 18080, request);
        
        assertEquals("Should return 404 Not Found", 404, response.statusCode);
        assertTrue("Status line should contain HTTP version", response.statusLine.startsWith("HTTP/1.1"));
        assertTrue("Should have Server header", response.hasHeader("Server"));
        assertTrue("Should have Date header", response.hasHeader("Date"));
    }
    
    @Test
    public void testPOSTReturns404() throws Exception {
        String body = "test=data";
        String request = "POST /submit HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Content-Type: application/x-www-form-urlencoded\r\n" +
                        "Content-Length: " + body.length() + "\r\n" +
                        "Connection: close\r\n" +
                        "\r\n" +
                        body;
        
        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", 18080, request);
        
        assertEquals("POST should also return 404", 404, response.statusCode);
    }
    
    @Test
    public void testHEADReturns404WithoutBody() throws Exception {
        String request = "HEAD / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";
        
        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", 18080, request);
        
        assertEquals("HEAD should return 404", 404, response.statusCode);
        assertTrue("HEAD response should have no body", response.body.isEmpty() || response.body.trim().isEmpty());
    }
    
    @Test
    public void testOPTIONSRequest() throws Exception {
        String request = "OPTIONS * HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";
        
        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", 18080, request);
        
        // OPTIONS * should be handled specially or return 404
        assertTrue("OPTIONS should return success or 404", 
                  response.statusCode == 200 || response.statusCode == 404);
        
        // If 200, should have Allow header
        if (response.statusCode == 200) {
            assertTrue("OPTIONS 200 should have Allow header", response.hasHeader("Allow"));
        }
    }
    
    @Test
    public void testOPTIONSForResource() throws Exception {
        String request = "OPTIONS /some/resource HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";
        
        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", 18080, request);
        
        // OPTIONS for a specific resource that doesn't exist should return 404
        assertEquals("OPTIONS for nonexistent resource should return 404", 404, response.statusCode);
    }
    
    // ============== HTTP Protocol Version Tests ==============
    
    @Test
    public void testHTTP10Request() throws Exception {
        String request = "GET / HTTP/1.0\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n";
        
        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", 18080, request);
        
        assertEquals("HTTP/1.0 request should work", 404, response.statusCode);
        assertTrue("Should respond with HTTP/1.0", response.statusLine.contains("HTTP/1.0"));
    }
    
    @Test
    public void testHTTP11Request() throws Exception {
        String request = "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";
        
        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", 18080, request);
        
        assertEquals("HTTP/1.1 request should work", 404, response.statusCode);
        assertTrue("Should respond with HTTP/1.1", response.statusLine.contains("HTTP/1.1"));
    }
    
    @Test
    public void testHTTP11KeepAlive() throws Exception {
        String request = "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: keep-alive\r\n" +
                        "\r\n";
        
        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", 18080, request);
        
        assertEquals("Keep-alive request should work", 404, response.statusCode);
        // Connection should remain open (but we close it from client side in helper)
    }
    
    // ============== HTTP/2 Upgrade Tests ==============
    
    @Test
    public void testHTTP2UpgradeRequest() throws Exception {
        String request = "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: Upgrade, HTTP2-Settings\r\n" +
                        "Upgrade: h2c\r\n" +
                        "HTTP2-Settings: AAMAAABkAARAAAAAAAIAAAAA\r\n" +
                        "\r\n";
        
        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", 18080, request);
        
        // Server should either:
        // 1. Accept upgrade with 101 Switching Protocols, OR
        // 2. Ignore upgrade and return 404
        assertTrue("HTTP/2 upgrade should return 101 or 404",
                  response.statusCode == 101 || response.statusCode == 404);
        
        if (response.statusCode == 101) {
            assertTrue("101 response should have Upgrade header", response.hasHeader("Upgrade"));
            String upgrade = response.getHeader("Upgrade");
            assertEquals("Upgrade header should be h2c", "h2c", upgrade);
        }
    }
    
    @Test
    public void testHTTP2PriorKnowledge() throws Exception {
        // HTTP/2 with prior knowledge starts with connection preface
        String request = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";
        
        try {
            HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", 18080, request);
            
            // If server supports HTTP/2, it will respond with HTTP/2 frames
            // If not, it should reject with 400 or close connection  
            // Status code 0, 400, 404, or 505 are all acceptable
            assertTrue("HTTP/2 prior knowledge should be handled",
                      response.statusCode == 0 || response.statusCode == 400 || 
                      response.statusCode == 404 || response.statusCode == 505);
        } catch (Exception e) {
            // Connection closed is acceptable - server doesn't support HTTP/2 prior knowledge
        }
    }
    
    // ============== Protocol Error Detection Tests ==============
    
    @Test
    public void testInvalidHTTPMethod() throws Exception {
        String request = "INVALID / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";
        
        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", 18080, request);
        
        // Should return 400 Bad Request or 501 Not Implemented
        assertTrue("Invalid method should return error",
                  response.statusCode == 400 || response.statusCode == 501);
    }
    
    @Test
    public void testMalformedRequestLine() throws Exception {
        String request = "GET\r\n" + // Missing URI and version
                        "Host: localhost\r\n" +
                        "\r\n";
        
        try {
            HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", 18080, request);
            
            assertEquals("Malformed request should return 400", 400, response.statusCode);
        } catch (Exception e) {
            // Connection closed is also acceptable
        }
    }
    
    @Test
    public void testMissingHostHeader() throws Exception {
        // HTTP/1.1 requires Host header
        String request = "GET / HTTP/1.1\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";
        
        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", 18080, request);
        
        assertEquals("Missing Host header should return 400", 400, response.statusCode);
    }
    
    @Test
    public void testInvalidHTTPVersion() throws Exception {
        String request = "GET / HTTP/9.9\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n";
        
        try {
            HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", 18080, request);
            
            // Should return 505 HTTP Version Not Supported or 400
            assertTrue("Invalid HTTP version should return error",
                      response.statusCode == 400 || response.statusCode == 505);
        } catch (Exception e) {
            // Connection closed is also acceptable
        }
    }
    
    @Test
    public void testMalformedHeaders() throws Exception {
        String request = "GET / HTTP/1.1\r\n" +
                        "Host localhost\r\n" + // Missing colon
                        "Connection: close\r\n" +
                        "\r\n";
        
        try {
            HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", 18080, request);
            
            assertEquals("Malformed header should return 400", 400, response.statusCode);
        } catch (Exception e) {
            // Connection closed is also acceptable
        }
    }
    
    @Test
    public void testRequestWithoutCRLF() throws Exception {
        // HTTP requires CRLF line endings, not just LF
        String request = "GET / HTTP/1.1\n" +
                        "Host: localhost\n" +
                        "\n";
        
        try {
            HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", 18080, request);
            
            // Server should either accept LF or reject with 400
            // Status code 0 means timeout/no response, which is acceptable for malformed request
            assertTrue("LF-only request should be handled",
                      response.statusCode == 0 || response.statusCode == 400 || response.statusCode == 404);
        } catch (Exception e) {
            // Connection closed is also acceptable
        }
    }
    
    @Test
    public void testOversizedRequestLine() throws Exception {
        // Very long URI
        StringBuilder longUri = new StringBuilder("/");
        for (int i = 0; i < 10000; i++) {
            longUri.append("a");
        }
        
        String request = "GET " + longUri + " HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";
        
        try {
            HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", 18080, request);
            
            // Should return 414 URI Too Long or 400
            assertTrue("Oversized URI should return error",
                      response.statusCode == 400 || response.statusCode == 414);
        } catch (Exception e) {
            // Connection closed is also acceptable
        }
    }
    
    // ============== Multiple Requests Tests ==============
    
    @Test
    public void testMultipleSequentialRequests() throws Exception {
        for (int i = 0; i < 5; i++) {
            String request = "GET /test" + i + " HTTP/1.1\r\n" +
                            "Host: localhost\r\n" +
                            "Connection: close\r\n" +
                            "\r\n";
            
            HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", 18080, request);
            
            assertEquals("Request " + i + " should return 404", 404, response.statusCode);
        }
    }
    
    @Test
    public void testConcurrentRequests() throws Exception {
        int numThreads = 10;
        Thread[] threads = new Thread[numThreads];
        final boolean[] results = new boolean[numThreads];
        
        for (int i = 0; i < numThreads; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                try {
                    String request = "GET /concurrent" + index + " HTTP/1.1\r\n" +
                                    "Host: localhost\r\n" +
                                    "Connection: close\r\n" +
                                    "\r\n";
                    
                    HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", 18080, request);
                    results[index] = (response.statusCode == 404);
                } catch (Exception e) {
                    results[index] = false;
                }
            });
            threads[i].start();
        }
        
        // Wait for all threads
        for (Thread thread : threads) {
            thread.join(5000);
        }
        
        // Check results
        for (int i = 0; i < numThreads; i++) {
            assertTrue("Concurrent request " + i + " should succeed", results[i]);
        }
    }
    
    // ============== Header Handling Tests ==============
    
    @Test
    public void testUserAgentHeader() throws Exception {
        String request = "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "User-Agent: HTTPServerIntegrationTest/1.0\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";
        
        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", 18080, request);
        
        assertEquals("Should handle User-Agent header", 404, response.statusCode);
    }
    
    @Test
    public void testAcceptHeader() throws Exception {
        String request = "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";
        
        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", 18080, request);
        
        assertEquals("Should handle Accept header", 404, response.statusCode);
    }
    
    @Test
    public void testCustomHeaders() throws Exception {
        String request = "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "X-Custom-Header: test-value\r\n" +
                        "X-Another-Header: another-value\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";
        
        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", 18080, request);
        
        assertEquals("Should handle custom headers", 404, response.statusCode);
    }
}

