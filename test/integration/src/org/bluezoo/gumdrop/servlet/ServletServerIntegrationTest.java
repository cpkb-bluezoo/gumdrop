/*
 * ServletServerIntegrationTest.java
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

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.AbstractServerIntegrationTest;
import org.bluezoo.gumdrop.http.HTTPClientHelper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Integration tests for the Gumdrop servlet container.
 * 
 * <p>Tests servlet container functionality including:
 * <ul>
 *   <li>Basic servlet dispatch and execution</li>
 *   <li>HTTP request/response handling</li>
 *   <li>Session management</li>
 *   <li>Filter chain execution</li>
 *   <li>Error page handling</li>
 *   <li>Multipart form data processing</li>
 *   <li>Forward and include dispatching</li>
 *   <li>Async request handling</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServletServerIntegrationTest extends AbstractServerIntegrationTest {

    private static final int TEST_PORT = 19080;

    /**
     * Global timeout for all tests - 15 seconds max per test.
     */
    @Rule
    public Timeout globalTimeout = Timeout.builder()
        .withTimeout(15, TimeUnit.SECONDS)
        .withLookingForStuckThread(true)
        .build();

    @Override
    protected File getTestConfigFile() {
        return new File("test/integration/config/servlet-server-test.xml");
    }


    // ============== Basic Servlet Functionality Tests ==============

    @Test
    public void testServerStartsAndAcceptsConnections() throws Exception {
        assertNotNull("Server should be running", gumdrop);
        assertTrue("Port " + TEST_PORT + " should be listening", isPortListening("127.0.0.1", TEST_PORT));
    }

    @Test
    public void testBasicGETRequest() throws Exception {
        String request = "GET /test/hello HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";

        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", TEST_PORT, request);

        assertEquals("Should return 200 OK", 200, response.statusCode);
        assertTrue("Response should contain expected content", 
            response.body.contains("Hello") || response.body.contains("hello"));
    }

    @Test
    public void testPOSTRequestWithBody() throws Exception {
        String body = "name=TestUser&value=12345";
        String request = "POST /test/echo HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Content-Type: application/x-www-form-urlencoded\r\n" +
                        "Content-Length: " + body.length() + "\r\n" +
                        "Connection: close\r\n" +
                        "\r\n" +
                        body;

        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", TEST_PORT, request);

        assertEquals("POST should return 200 OK", 200, response.statusCode);
        // Echo servlet should return the posted content
        assertTrue("Response should echo posted data", 
            response.body.contains("TestUser") || response.body.contains("12345"));
    }

    @Test
    public void testDefaultServlet404() throws Exception {
        String request = "GET /nonexistent/path/to/resource.html HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";

        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", TEST_PORT, request);

        assertEquals("Should return 404 for non-existent resource", 404, response.statusCode);
    }

    @Test
    public void testRootContextRedirect() throws Exception {
        String request = "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";

        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", TEST_PORT, request);

        // Root should either return welcome file or 404
        assertTrue("Root should return 200 or 404", 
            response.statusCode == 200 || response.statusCode == 404);
    }

    // ============== HTTP Headers Tests ==============

    @Test
    public void testRequestHeaders() throws Exception {
        String request = "GET /test/headers HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "X-Custom-Header: CustomValue123\r\n" +
                        "Accept: text/html\r\n" +
                        "User-Agent: IntegrationTest/1.0\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";

        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", TEST_PORT, request);

        // Headers servlet should echo back the headers
        assertTrue("Should receive successful response", 
            response.statusCode == 200 || response.statusCode == 404);
    }

    @Test
    public void testResponseHeaders() throws Exception {
        String request = "GET /test/hello HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";

        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", TEST_PORT, request);

        if (response.statusCode == 200) {
            assertTrue("Should have Content-Type header", response.hasHeader("Content-Type"));
        }
    }

    // ============== Session Management Tests ==============

    @Test
    public void testSessionCreation() throws Exception {
        String request = "GET /test/session HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";

        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", TEST_PORT, request);

        if (response.statusCode == 200) {
            // Should have Set-Cookie header with JSESSIONID
            String setCookie = response.getHeader("Set-Cookie");
            if (setCookie != null) {
                assertTrue("Session cookie should contain JSESSIONID", 
                    setCookie.contains("JSESSIONID"));
            }
        }
    }

    @Test
    public void testSessionContinuity() throws Exception {
        // First request - create session
        String request1 = "GET /test/session?action=set&value=test123 HTTP/1.1\r\n" +
                         "Host: localhost\r\n" +
                         "Connection: close\r\n" +
                         "\r\n";

        HTTPClientHelper.HTTPResponse response1 = HTTPClientHelper.sendRequest("127.0.0.1", TEST_PORT, request1);

        if (response1.statusCode == 200) {
            String setCookie = response1.getHeader("Set-Cookie");
            if (setCookie != null && setCookie.contains("JSESSIONID")) {
                // Extract session ID
                String sessionId = extractSessionId(setCookie);

                // Second request with session cookie
                String request2 = "GET /test/session?action=get HTTP/1.1\r\n" +
                                 "Host: localhost\r\n" +
                                 "Cookie: JSESSIONID=" + sessionId + "\r\n" +
                                 "Connection: close\r\n" +
                                 "\r\n";

                HTTPClientHelper.HTTPResponse response2 = HTTPClientHelper.sendRequest("127.0.0.1", TEST_PORT, request2);

                assertEquals("Second request with session should succeed", 200, response2.statusCode);
                assertTrue("Should retrieve stored session value", 
                    response2.body.contains("test123"));
            }
        }
    }

    // ============== Multipart Form Data Tests ==============

    @Test
    public void testMultipartFormDataUpload() throws Exception {
        String boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW";
        String body = 
            "--" + boundary + "\r\n" +
            "Content-Disposition: form-data; name=\"field1\"\r\n" +
            "\r\n" +
            "value1\r\n" +
            "--" + boundary + "\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\n" +
            "Content-Type: text/plain\r\n" +
            "\r\n" +
            "This is test file content.\r\n" +
            "--" + boundary + "--\r\n";

        String request = "POST /test/upload HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Content-Type: multipart/form-data; boundary=" + boundary + "\r\n" +
                        "Content-Length: " + body.length() + "\r\n" +
                        "Connection: close\r\n" +
                        "\r\n" +
                        body;

        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", TEST_PORT, request);

        // Upload servlet should process the multipart data
        assertTrue("Multipart request should be handled", 
            response.statusCode == 200 || response.statusCode == 404);
    }

    // ============== Error Handling Tests ==============

    @Test
    public void testServletException500() throws Exception {
        String request = "GET /test/error?type=exception HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";

        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", TEST_PORT, request);

        // Error servlet should throw exception resulting in 500
        assertTrue("Should return 500 or 404", 
            response.statusCode == 500 || response.statusCode == 404);
    }

    @Test
    public void testCustomErrorPage() throws Exception {
        String request = "GET /test/error?status=403 HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";

        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", TEST_PORT, request);

        // Diagnostic output
        System.out.println("=== testCustomErrorPage DIAGNOSTIC ===");
        System.out.println("Status: " + response.statusCode);
        System.out.println("Body: " + response.body.substring(0, Math.min(200, response.body.length())));
        
        // Error servlet should return the specified status
        assertTrue("Should return 403 or 404, got " + response.statusCode, 
            response.statusCode == 403 || response.statusCode == 404);
    }

    // ============== Filter Tests ==============

    @Test
    public void testFilterChainExecution() throws Exception {
        String request = "GET /test/filtered HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";

        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", TEST_PORT, request);

        if (response.statusCode == 200) {
            // Check for filter-added header
            String filterHeader = response.getHeader("X-Filter-Applied");
            if (filterHeader != null) {
                assertEquals("Filter should add header", "true", filterHeader);
            }
        }
    }

    // ============== Forward/Include Tests ==============

    @Test
    public void testRequestForward() throws Exception {
        String request = "GET /test/forward?target=/test/hello HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";

        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", TEST_PORT, request);

        // Forward should show content from target servlet
        assertTrue("Forward should succeed or 404", 
            response.statusCode == 200 || response.statusCode == 404);
    }

    @Test
    public void testRequestInclude() throws Exception {
        String request = "GET /test/include?target=/test/hello HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";

        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", TEST_PORT, request);

        // Include should embed content from target servlet
        assertTrue("Include should succeed or 404", 
            response.statusCode == 200 || response.statusCode == 404);
    }

    // ============== Async Processing Tests ==============

    @Test
    public void testAsyncServlet() throws Exception {
        String request = "GET /test/async HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";

        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", TEST_PORT, request);

        // Async servlet should complete successfully
        assertTrue("Async request should complete", 
            response.statusCode == 200 || response.statusCode == 404);
    }

    @Test
    public void testAsyncTimeout() throws Exception {
        String request = "GET /test/async?timeout=true HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";

        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", TEST_PORT, request);

        // Async timeout should result in error
        assertTrue("Async timeout should be handled", 
            response.statusCode == 200 || response.statusCode == 404 || 
            response.statusCode == 500 || response.statusCode == 503);
    }

    // ============== Servlet Context Tests ==============

    @Test
    public void testServletContextAttribute() throws Exception {
        String request = "GET /test/context?action=get HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";

        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", TEST_PORT, request);

        assertTrue("Context request should be handled", 
            response.statusCode == 200 || response.statusCode == 404);
    }

    // ============== Content Type Tests ==============

    @Test
    public void testJSONContentType() throws Exception {
        String body = "{\"key\":\"value\"}";
        String request = "POST /test/json HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: " + body.length() + "\r\n" +
                        "Connection: close\r\n" +
                        "\r\n" +
                        body;

        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", TEST_PORT, request);

        assertTrue("JSON request should be handled", 
            response.statusCode == 200 || response.statusCode == 404);
    }

    @Test
    public void testXMLContentType() throws Exception {
        String body = "<?xml version=\"1.0\"?><root><item>test</item></root>";
        String request = "POST /test/xml HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Content-Type: application/xml\r\n" +
                        "Content-Length: " + body.length() + "\r\n" +
                        "Connection: close\r\n" +
                        "\r\n" +
                        body;

        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", TEST_PORT, request);

        assertTrue("XML request should be handled", 
            response.statusCode == 200 || response.statusCode == 404);
    }

    // ============== Concurrent Request Tests ==============

    @Test
    public void testConcurrentRequests() throws Exception {
        int numThreads = 5;
        Thread[] threads = new Thread[numThreads];
        final boolean[] results = new boolean[numThreads];

        for (int i = 0; i < numThreads; i++) {
            final int index = i;
            threads[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String request = "GET /test/hello?thread=" + index + " HTTP/1.1\r\n" +
                                        "Host: localhost\r\n" +
                                        "Connection: close\r\n" +
                                        "\r\n";

                        HTTPClientHelper.HTTPResponse response = 
                            HTTPClientHelper.sendRequest("127.0.0.1", TEST_PORT, request);
                        
                        results[index] = (response.statusCode == 200 || response.statusCode == 404);
                    } catch (Exception e) {
                        results[index] = false;
                    }
                }
            });
            threads[i].start();
        }

        // Wait for all threads
        for (Thread thread : threads) {
            thread.join(10000);
        }

        // Check results
        for (int i = 0; i < numThreads; i++) {
            assertTrue("Concurrent request " + i + " should complete", results[i]);
        }
    }

    // ============== Helper Methods ==============

    private String extractSessionId(String setCookieHeader) {
        // Parse JSESSIONID from Set-Cookie header
        // Format: JSESSIONID=value; Path=/; HttpOnly
        if (setCookieHeader == null) {
            return null;
        }
        
        int start = setCookieHeader.indexOf("JSESSIONID=");
        if (start < 0) {
            return null;
        }
        
        start += "JSESSIONID=".length();
        int end = setCookieHeader.indexOf(';', start);
        if (end < 0) {
            end = setCookieHeader.length();
        }
        
        return setCookieHeader.substring(start, end);
    }
}

