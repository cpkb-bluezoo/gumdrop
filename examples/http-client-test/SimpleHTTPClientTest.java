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

import org.bluezoo.gumdrop.http.client.DefaultHTTPResponseHandler;
import org.bluezoo.gumdrop.http.client.HTTPClient;
import org.bluezoo.gumdrop.http.client.HTTPRequest;
import org.bluezoo.gumdrop.http.client.HTTPResponse;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Simple test to verify HTTP client functionality.
 *
 * <p>This example demonstrates how to use the Gumdrop HTTP client to make
 * basic HTTP requests and handle responses in an event-driven manner.
 *
 * <p>The simplest usage pattern does not require calling connect() - the
 * connection is established automatically when the first request is made.
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

            System.out.println("\nAll tests completed successfully!");

        } catch (Exception e) {
            System.err.println("Test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Tests a simple GET request to httpbin.org/get.
     *
     * <p>This demonstrates the simplest usage pattern - just create the client
     * and make requests. No need to call connect() first.
     */
    private static void testSimpleGET() throws Exception {
        System.out.println("\n=== Testing Simple GET Request ===");

        final CountDownLatch latch = new CountDownLatch(1);
        final StringBuilder responseBody = new StringBuilder();

        final HTTPClient client = new HTTPClient(TEST_HOST, TEST_PORT);

        // No need to call connect() - just make the request directly
        HTTPRequest request = client.get("/get");
        request.header("User-Agent", "Gumdrop-HTTP-Client/1.0");
        request.header("Accept", "application/json");

        request.send(new DefaultHTTPResponseHandler() {
            @Override
            public void ok(HTTPResponse response) {
                System.out.println("Response: " + response.getStatus());
            }

            @Override
            public void error(HTTPResponse response) {
                System.out.println("Error response: " + response.getStatus());
            }

            @Override
            public void responseBodyContent(ByteBuffer data) {
                String chunk = StandardCharsets.UTF_8.decode(data).toString();
                responseBody.append(chunk);
            }

            @Override
            public void close() {
                System.out.println("Response complete");
                System.out.println("  Body length: " + responseBody.length() + " characters");

                // Print first 200 characters of response
                String preview = responseBody.toString();
                if (preview.length() > 200) {
                    preview = preview.substring(0, 200) + "...";
                }
                System.out.println("  Preview: " + preview);

                client.close();
                latch.countDown();
            }

            @Override
            public void failed(Exception ex) {
                System.err.println("Request failed: " + ex.getMessage());
                client.close();
                latch.countDown();
            }
        });

        System.out.println("Sent: GET /get");

        // Wait for response (timeout after 30 seconds)
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        if (!completed) {
            client.close();
            throw new Exception("Test timed out after 30 seconds");
        }

        System.out.println("GET test completed successfully");
    }

    /**
     * Tests a POST request with JSON body to httpbin.org/post.
     */
    private static void testPOST() throws Exception {
        System.out.println("\n=== Testing POST Request ===");

        final CountDownLatch latch = new CountDownLatch(1);
        final StringBuilder responseBody = new StringBuilder();

        final HTTPClient client = new HTTPClient(TEST_HOST, TEST_PORT);

        // Prepare JSON body
        String jsonBody = "{\"name\":\"Gumdrop\",\"type\":\"HTTP Client\",\"version\":\"1.0\"}";
        byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);

        // Create and send POST request
        HTTPRequest request = client.post("/post");
        request.header("User-Agent", "Gumdrop-HTTP-Client/1.0");
        request.header("Content-Type", "application/json");
        request.header("Content-Length", String.valueOf(bodyBytes.length));

        // Start request body, send data, then end
        request.startRequestBody(new DefaultHTTPResponseHandler() {
            @Override
            public void ok(HTTPResponse response) {
                System.out.println("Response: " + response.getStatus());
            }

            @Override
            public void error(HTTPResponse response) {
                System.out.println("Error response: " + response.getStatus());
            }

            @Override
            public void responseBodyContent(ByteBuffer data) {
                String chunk = StandardCharsets.UTF_8.decode(data).toString();
                responseBody.append(chunk);
            }

            @Override
            public void close() {
                System.out.println("Response complete");
                System.out.println("  Body length: " + responseBody.length() + " characters");
                client.close();
                latch.countDown();
            }

            @Override
            public void failed(Exception ex) {
                System.err.println("Request failed: " + ex.getMessage());
                client.close();
                latch.countDown();
            }
        });

        request.requestBodyContent(ByteBuffer.wrap(bodyBytes));
        request.endRequestBody();

        System.out.println("Sent: POST /post (" + bodyBytes.length + " bytes)");

        // Wait for response (timeout after 30 seconds)
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        if (!completed) {
            client.close();
            throw new Exception("Test timed out after 30 seconds");
        }

        System.out.println("POST test completed successfully");
    }
}
