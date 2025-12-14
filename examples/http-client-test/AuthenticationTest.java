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

import org.bluezoo.gumdrop.http.client.DefaultHTTPResponseHandler;
import org.bluezoo.gumdrop.http.client.HTTPClient;
import org.bluezoo.gumdrop.http.client.HTTPRequest;
import org.bluezoo.gumdrop.http.client.HTTPResponse;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test demonstrating HTTP client authentication capabilities.
 *
 * <p>This example shows how to use different authentication schemes
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
            // Test 1: Basic Authentication with automatic challenge handling
            testBasicAuthentication();

            // Test 2: Bearer Token Authentication (manual header)
            testBearerAuthentication();

            // Test 3: Digest Authentication (requires challenge)
            testDigestAuthentication();

            System.out.println("\nAll authentication tests completed successfully!");

        } catch (Exception e) {
            System.err.println("Authentication test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Tests Basic Authentication using httpbin.org/basic-auth endpoint.
     *
     * <p>The client handles 401 challenges automatically when credentials
     * are configured via {@link HTTPClient#credentials(String, String)}.
     */
    private static void testBasicAuthentication() throws Exception {
        System.out.println("\n=== Testing Basic Authentication ===");

        final CountDownLatch latch = new CountDownLatch(1);

        final HTTPClient client = new HTTPClient(TEST_HOST, TEST_PORT);

        // Set credentials for automatic Basic/Digest authentication
        client.credentials("user", "passwd");

        // Request protected resource - connection established automatically
        HTTPRequest request = client.get("/basic-auth/user/passwd");
        request.header("User-Agent", "Gumdrop-HTTP-Client/1.0");

        request.send(new DefaultHTTPResponseHandler() {
            @Override
            public void ok(HTTPResponse response) {
                System.out.println("Response: " + response.getStatus());
                System.out.println("  Basic authentication successful!");
            }

            @Override
            public void error(HTTPResponse response) {
                System.out.println("Error: " + response.getStatus());
                System.out.println("  Basic authentication failed");
            }

            @Override
            public void close() {
                System.out.println("Response complete");
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

        System.out.println("Sent: GET /basic-auth/user/passwd");

        boolean completed = latch.await(30, TimeUnit.SECONDS);
        if (!completed) {
            client.close();
            throw new Exception("Basic auth test timed out after 30 seconds");
        }

        System.out.println("Basic auth test completed");
    }

    /**
     * Tests Bearer Token Authentication.
     *
     * <p>Bearer tokens are added manually via the Authorization header.
     * This is commonly used for OAuth 2.0 and API key authentication.
     */
    private static void testBearerAuthentication() throws Exception {
        System.out.println("\n=== Testing Bearer Authentication ===");

        final CountDownLatch latch = new CountDownLatch(1);
        final StringBuilder responseBody = new StringBuilder();

        final HTTPClient client = new HTTPClient(TEST_HOST, TEST_PORT);

        // Request endpoint that shows headers (to verify Bearer token was sent)
        HTTPRequest request = client.get("/headers");
        request.header("User-Agent", "Gumdrop-HTTP-Client/1.0");
        request.header("Accept", "application/json");

        // Add Bearer token manually
        request.header("Authorization", "Bearer fake-token-12345");

        request.send(new DefaultHTTPResponseHandler() {
            @Override
            public void ok(HTTPResponse response) {
                System.out.println("Response: " + response.getStatus());
            }

            @Override
            public void responseBodyContent(ByteBuffer data) {
                String chunk = StandardCharsets.UTF_8.decode(data).toString();
                responseBody.append(chunk);

                // Check if the Bearer token appears in the response
                if (chunk.contains("Bearer fake-token-12345")) {
                    System.out.println("  Bearer token found in request headers!");
                }
            }

            @Override
            public void close() {
                System.out.println("Response complete");
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

        System.out.println("Sent: GET /headers with Bearer token");

        boolean completed = latch.await(30, TimeUnit.SECONDS);
        if (!completed) {
            client.close();
            throw new Exception("Bearer auth test timed out after 30 seconds");
        }

        System.out.println("Bearer auth test completed");
    }

    /**
     * Tests Digest Authentication with challenge handling.
     *
     * <p>When the server responds with 401 and a WWW-Authenticate: Digest header,
     * the client automatically computes the digest response and retries the request.
     */
    private static void testDigestAuthentication() throws Exception {
        System.out.println("\n=== Testing Digest Authentication ===");

        final CountDownLatch latch = new CountDownLatch(1);

        final HTTPClient client = new HTTPClient(TEST_HOST, TEST_PORT);

        // Set credentials for automatic Digest authentication
        client.credentials("user", "passwd");

        // Request protected resource that requires digest auth
        // This will trigger a 401 challenge that should be handled automatically
        HTTPRequest request = client.get("/digest-auth/auth/user/passwd");
        request.header("User-Agent", "Gumdrop-HTTP-Client/1.0");

        request.send(new DefaultHTTPResponseHandler() {
            @Override
            public void ok(HTTPResponse response) {
                System.out.println("Response: " + response.getStatus());
                System.out.println("  Digest authentication successful after challenge!");
            }

            @Override
            public void error(HTTPResponse response) {
                System.out.println("Error: " + response.getStatus());
                System.out.println("  Digest authentication failed");
            }

            @Override
            public void close() {
                System.out.println("Response complete");
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

        System.out.println("Sent: GET /digest-auth/auth/user/passwd (challenge expected)");

        boolean completed = latch.await(30, TimeUnit.SECONDS);
        if (!completed) {
            client.close();
            throw new Exception("Digest auth test timed out after 30 seconds");
        }

        System.out.println("Digest auth test completed");
    }
}
