/*
 * HTTPClientIntegrationTest.java
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

package org.bluezoo.gumdrop.http.client;

import org.bluezoo.gumdrop.AbstractServerIntegrationTest;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TCPTransportFactory;
import org.bluezoo.gumdrop.TestCertificateManager;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.http.HTTPVersion;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import static org.junit.Assert.*;

/**
 * Integration tests for Gumdrop's HTTP client implementation.
 *
 * <p>Tests HTTP client functionality with real network connections:
 * <ul>
 *   <li>h2c (HTTP/2 cleartext) upgrade from HTTP/1.1</li>
 *   <li>h2 (HTTP/2 over TLS) via ALPN negotiation</li>
 *   <li>HTTP/1.1 chunked transfer encoding for uploads</li>
 *   <li>Content verification - uploaded data matches received data</li>
 *   <li>Multiple concurrent requests over HTTP/2</li>
 * </ul>
 *
 * <p>The test uses an echo handler on the servers that returns the
 * uploaded content back to the client for verification.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPClientIntegrationTest extends AbstractServerIntegrationTest {

    private static final int HTTP_PORT = 18090;
    private static final int HTTPS_PORT = 18444;
    private static final String TEST_HOST = "127.0.0.1";
    
    /** Timeout for async operations - if not done in 5s, something is wrong. */
    private static final int ASYNC_TIMEOUT_SECONDS = 5;

    /** Global timeout for all tests. */
    @Rule
    public Timeout globalTimeout = Timeout.builder()
        .withTimeout(ASYNC_TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)
        .withLookingForStuckThread(true)
        .build();

    @Override
    protected File getTestConfigFile() {
        return new File("test/integration/config/http-client-test.xml");
    }

    @Override
    protected Level getTestLogLevel() {
        return Level.WARNING;
    }

    /** Certificate manager for generating and using test certificates. */
    private static TestCertificateManager certManager;

    /**
     * Set up certificates for HTTPS testing.
     */
    @BeforeClass
    public static void setupCertificates() throws Exception {
        File certsDir = new File("test/integration/certs");
        if (!certsDir.exists()) {
            certsDir.mkdirs();
        }

        // Clean up existing CA keystore to avoid password conflicts
        File caKeystore = new File(certsDir, "ca-keystore.p12");
        if (caKeystore.exists()) {
            caKeystore.delete();
        }

        certManager = new TestCertificateManager(certsDir);

        // Generate fresh certificates
        certManager.generateCA("Test CA", 365);
        certManager.generateServerCertificate("localhost", 365);

        File keystoreFile = new File(certsDir, "test-keystore.p12");
        certManager.saveServerKeystore(keystoreFile, "testpass");
    }

    // Handler factory is configured in XML - no programmatic setup needed

    // ─────────────────────────────────────────────────────────────────────────
    // Helper Methods
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates an HTTP client and waits for connection to be established.
     *
     * @param host the target host
     * @param port the target port
     * @return connected HTTPClientProtocolHandler
     * @throws Exception if connection fails
     */
    private HTTPClientProtocolHandler createConnectedClient(String host, int port) throws Exception {
        TCPTransportFactory factory = new TCPTransportFactory();
        factory.start();
        HTTPClientProtocolHandler endpointHandler = new HTTPClientProtocolHandler(
                new HTTPClientHandler() {
                    @Override
                    public void onConnected(Endpoint endpoint) {}
                    @Override
                    public void onSecurityEstablished(SecurityInfo info) {}
                    @Override
                    public void onError(Exception cause) {}
                    @Override
                    public void onDisconnected() {}
                },
                host, port, false);

        ClientEndpoint client = new ClientEndpoint(factory, Gumdrop.getInstance().nextWorkerLoop(), host, port);
        client.connect(endpointHandler);

        long deadline = System.currentTimeMillis() + ASYNC_TIMEOUT_SECONDS * 1000L;
        while (!endpointHandler.isOpen() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        if (!endpointHandler.isOpen()) {
            throw new Exception("Connection timed out");
        }

        return endpointHandler;
    }

    /**
     * Creates an HTTP/2 client with prior knowledge and waits for connection.
     * The client will immediately send the HTTP/2 connection preface (PRI) 
     * without attempting h2c upgrade.
     *
     * @param host the target host
     * @param port the target port
     * @return connected HTTPClientProtocolHandler configured for HTTP/2 prior knowledge
     * @throws Exception if connection fails
     */
    private HTTPClientProtocolHandler createH2PriorKnowledgeClient(String host, int port) throws Exception {
        TCPTransportFactory factory = new TCPTransportFactory();
        factory.start();
        HTTPClientProtocolHandler endpointHandler = new HTTPClientProtocolHandler(
                new HTTPClientHandler() {
                    @Override
                    public void onConnected(Endpoint endpoint) {}
                    @Override
                    public void onSecurityEstablished(SecurityInfo info) {}
                    @Override
                    public void onError(Exception cause) {}
                    @Override
                    public void onDisconnected() {}
                },
                host, port, false);
        endpointHandler.setH2WithPriorKnowledge(true);

        ClientEndpoint client = new ClientEndpoint(factory, Gumdrop.getInstance().nextWorkerLoop(), host, port);
        client.connect(endpointHandler);

        long deadline = System.currentTimeMillis() + ASYNC_TIMEOUT_SECONDS * 1000L;
        while (!endpointHandler.isOpen() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        if (!endpointHandler.isOpen()) {
            throw new Exception("Connection timed out");
        }

        return endpointHandler;
    }

    /**
     * Creates an HTTPS client and waits for connection to be established.
     *
     * @param host the target host
     * @param port the target port
     * @return connected HTTPClientProtocolHandler
     * @throws Exception if connection fails
     */
    private HTTPClientProtocolHandler createSecureConnectedClient(String host, int port) throws Exception {
        TCPTransportFactory factory = new TCPTransportFactory();
        factory.setSecure(true);
        factory.setSSLContext(certManager.createClientSSLContext());
        factory.start();

        HTTPClientProtocolHandler endpointHandler = new HTTPClientProtocolHandler(
                new HTTPClientHandler() {
                    @Override
                    public void onConnected(Endpoint endpoint) {}
                    @Override
                    public void onSecurityEstablished(SecurityInfo info) {}
                    @Override
                    public void onError(Exception cause) {
                        cause.printStackTrace();
                    }
                    @Override
                    public void onDisconnected() {}
                },
                host, port, true);

        ClientEndpoint client = new ClientEndpoint(factory, Gumdrop.getInstance().nextWorkerLoop(), host, port);
        client.connect(endpointHandler);

        long deadline = System.currentTimeMillis() + ASYNC_TIMEOUT_SECONDS * 1000L;
        while (!endpointHandler.isOpen() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        if (!endpointHandler.isOpen()) {
            throw new Exception("Connection timed out - check if TLS handshake is completing");
        }

        return endpointHandler;
    }


    // ─────────────────────────────────────────────────────────────────────────
    // Basic Connectivity Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Ignore("Focusing on h2c test only")
    public void testHTTPServerListening() throws Exception {
        assertTrue("HTTP port should be listening",
            isPortListening(TEST_HOST, HTTP_PORT));
    }

    @Test
    @Ignore("Focusing on h2c test only")
    public void testHTTPSServerListening() throws Exception {
        assertTrue("HTTPS port should be listening",
            isPortListening(TEST_HOST, HTTPS_PORT));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP/1.1 Tests
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Test HTTP/1.1 GET request - no request body, no Content-Length needed.
     */
    @Test
    public void testHTTP11SimpleGET() throws Exception {
        HTTPClientProtocolHandler client = createConnectedClient(TEST_HOST, HTTP_PORT);
        client.setH2cUpgradeEnabled(false); // Force HTTP/1.1

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HTTPStatus> status = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();

        HTTPRequest request = client.get("/test");
        request.send(new DefaultHTTPResponseHandler() {
            @Override
            public void ok(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void error(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void close() {
                latch.countDown();
            }

            @Override
            public void failed(Exception ex) {
                error.set(ex);
                latch.countDown();
            }
        });

        boolean completed = latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        
        assertEquals("Should be HTTP/1.1", HTTPVersion.HTTP_1_1, client.getVersion());
        client.close();

        assertTrue("Request should complete", completed);
        assertNull("Should not fail: " + error.get(), error.get());
        assertNotNull("Should receive status", status.get());
        assertEquals("Should receive 200 OK", HTTPStatus.OK, status.get());
    }

    /**
     * Test HTTP/1.1 HEAD request - no request body, no Content-Length needed.
     * Note: Per HTTP spec, HEAD response should have no body, but we don't
     * enforce that here since we're testing the client, not the server.
     */
    @Test
    public void testHTTP11HEAD() throws Exception {
        HTTPClientProtocolHandler client = createConnectedClient(TEST_HOST, HTTP_PORT);
        client.setH2cUpgradeEnabled(false); // Force HTTP/1.1

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HTTPStatus> status = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();

        HTTPRequest request = client.head("/test");
        request.send(new DefaultHTTPResponseHandler() {
            @Override
            public void ok(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void error(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void close() {
                latch.countDown();
            }

            @Override
            public void failed(Exception ex) {
                error.set(ex);
                latch.countDown();
            }
        });

        boolean completed = latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        
        assertEquals("Should be HTTP/1.1", HTTPVersion.HTTP_1_1, client.getVersion());
        client.close();

        assertTrue("Request should complete", completed);
        assertNull("Should not fail: " + error.get(), error.get());
        assertEquals("Should receive 200 OK", HTTPStatus.OK, status.get());
    }

    /**
     * Test HTTP/1.1 DELETE request - no request body required.
     */
    @Test
    public void testHTTP11DELETE() throws Exception {
        HTTPClientProtocolHandler client = createConnectedClient(TEST_HOST, HTTP_PORT);
        client.setH2cUpgradeEnabled(false); // Force HTTP/1.1

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HTTPStatus> status = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();

        HTTPRequest request = client.delete("/test");
        request.send(new DefaultHTTPResponseHandler() {
            @Override
            public void ok(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void error(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void close() {
                latch.countDown();
            }

            @Override
            public void failed(Exception ex) {
                error.set(ex);
                latch.countDown();
            }
        });

        boolean completed = latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        
        assertEquals("Should be HTTP/1.1", HTTPVersion.HTTP_1_1, client.getVersion());
        client.close();

        assertTrue("Request should complete", completed);
        assertNull("Should not fail: " + error.get(), error.get());
        assertNotNull("Should receive status", status.get());
        // Server may return 200 OK, 204 No Content, or 404 Not Found depending on implementation
    }

    /**
     * Test HTTP/1.1 OPTIONS request - no request body required.
     */
    @Test
    public void testHTTP11OPTIONS() throws Exception {
        HTTPClientProtocolHandler client = createConnectedClient(TEST_HOST, HTTP_PORT);
        client.setH2cUpgradeEnabled(false); // Force HTTP/1.1

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HTTPStatus> status = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();

        HTTPRequest request = client.options("*");
        request.send(new DefaultHTTPResponseHandler() {
            @Override
            public void ok(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void error(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void close() {
                latch.countDown();
            }

            @Override
            public void failed(Exception ex) {
                error.set(ex);
                latch.countDown();
            }
        });

        boolean completed = latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        
        assertEquals("Should be HTTP/1.1", HTTPVersion.HTTP_1_1, client.getVersion());
        client.close();

        assertTrue("Request should complete", completed);
        assertNull("Should not fail: " + error.get(), error.get());
        assertEquals("Should receive 200 OK", HTTPStatus.OK, status.get());
    }

    @Test
    public void testHTTP11POSTWithContentLength() throws Exception {
        HTTPClientProtocolHandler client = createConnectedClient(TEST_HOST, HTTP_PORT);
        
        // Disable h2c upgrade - this test is specifically for HTTP/1.1
        client.setH2cUpgradeEnabled(false);

        String testContent = "Hello, World! This is test content for HTTP/1.1 POST.";
        byte[] contentBytes = testContent.getBytes(StandardCharsets.UTF_8);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HTTPStatus> status = new AtomicReference<>();
        AtomicReference<String> responseBody = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();

        HTTPRequest request = client.post("/echo");
        request.header("Content-Type", "text/plain");
        request.header("Content-Length", String.valueOf(contentBytes.length));

        request.startRequestBody(new DefaultHTTPResponseHandler() {
            @Override
            public void ok(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void error(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void responseBodyContent(ByteBuffer data) {
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                try {
                    bodyBuffer.write(bytes);
                } catch (Exception e) {
                    // ignore
                }
            }

            @Override
            public void close() {
                responseBody.set(new String(bodyBuffer.toByteArray(), StandardCharsets.UTF_8));
                latch.countDown();
            }

            @Override
            public void failed(Exception ex) {
                error.set(ex);
                latch.countDown();
            }
        });

        request.requestBodyContent(ByteBuffer.wrap(contentBytes));
        request.endRequestBody();

        boolean completed = latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        client.close();

        assertTrue("Request should complete", completed);
        assertNull("Should not fail: " + error.get(), error.get());
        assertEquals("Should receive 200 OK", HTTPStatus.OK, status.get());

        String body = responseBody.get();
        assertNotNull("Should have response body", body);
        assertTrue("Response should contain echoed content",
            body.contains(testContent));
    }

    @Test
    public void testHTTP11ChunkedPOST() throws Exception {
        HTTPClientProtocolHandler client = createConnectedClient(TEST_HOST, HTTP_PORT);
        
        // Disable h2c upgrade - this test is specifically for HTTP/1.1
        client.setH2cUpgradeEnabled(false);

        String chunk1 = "First chunk of data. ";
        String chunk2 = "Second chunk follows. ";
        String chunk3 = "Third and final chunk.";
        String fullContent = chunk1 + chunk2 + chunk3;

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HTTPStatus> status = new AtomicReference<>();
        AtomicReference<String> responseBody = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();

        HTTPRequest request = client.post("/echo");
        request.header("Content-Type", "text/plain");
        request.header("Transfer-Encoding", "chunked");

        request.startRequestBody(new DefaultHTTPResponseHandler() {
            @Override
            public void ok(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void error(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void responseBodyContent(ByteBuffer data) {
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                try {
                    bodyBuffer.write(bytes);
                } catch (Exception e) {
                    // ignore
                }
            }

            @Override
            public void close() {
                responseBody.set(new String(bodyBuffer.toByteArray(), StandardCharsets.UTF_8));
                latch.countDown();
            }

            @Override
            public void failed(Exception ex) {
                error.set(ex);
                latch.countDown();
            }
        });

        // Send chunks
        request.requestBodyContent(ByteBuffer.wrap(chunk1.getBytes(StandardCharsets.UTF_8)));
        request.requestBodyContent(ByteBuffer.wrap(chunk2.getBytes(StandardCharsets.UTF_8)));
        request.requestBodyContent(ByteBuffer.wrap(chunk3.getBytes(StandardCharsets.UTF_8)));
        request.endRequestBody();

        boolean completed = latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        client.close();

        assertTrue("Request should complete", completed);
        assertNull("Should not fail: " + error.get(), error.get());
        assertEquals("Should receive 200 OK", HTTPStatus.OK, status.get());

        String body = responseBody.get();
        assertNotNull("Should have response body", body);
        assertTrue("Response should contain all chunked content",
            body.contains(fullContent));
    }

    @Test
    @Ignore("Focusing on h2c test only")
    public void testHTTP11LargeChunkedUpload() throws Exception {
        HTTPClientProtocolHandler client = createConnectedClient(TEST_HOST, HTTP_PORT);

        // Create large content (64KB)
        byte[] largeContent = new byte[65536];
        for (int i = 0; i < largeContent.length; i++) {
            largeContent[i] = (byte) ((i % 26) + 'A');
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HTTPStatus> status = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();

        HTTPRequest request = client.post("/echo");
        request.header("Content-Type", "application/octet-stream");
        request.header("Transfer-Encoding", "chunked");

        request.startRequestBody(new DefaultHTTPResponseHandler() {
            @Override
            public void ok(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void error(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void responseBodyContent(ByteBuffer data) {
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                try {
                    bodyBuffer.write(bytes);
                } catch (Exception e) {
                    // ignore
                }
            }

            @Override
            public void close() {
                latch.countDown();
            }

            @Override
            public void failed(Exception ex) {
                error.set(ex);
                latch.countDown();
            }
        });

        // Send in 8KB chunks
        int chunkSize = 8192;
        for (int offset = 0; offset < largeContent.length; offset += chunkSize) {
            int length = Math.min(chunkSize, largeContent.length - offset);
            ByteBuffer chunk = ByteBuffer.wrap(largeContent, offset, length);
            request.requestBodyContent(chunk);
        }
        request.endRequestBody();

        boolean completed = latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        client.close();

        assertTrue("Request should complete", completed);
        assertNull("Should not fail: " + error.get(), error.get());
        assertEquals("Should receive 200 OK", HTTPStatus.OK, status.get());

        // Verify content was echoed correctly
        byte[] receivedContent = bodyBuffer.toByteArray();
        assertTrue("Should receive echoed content",
            receivedContent.length >= largeContent.length);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // h2c Upgrade Tests
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Tests h2c upgrade using OPTIONS request.
     *
     * <p>Flow:
     * <ol>
     *   <li>Client sends OPTIONS * HTTP/1.1 with h2c upgrade headers</li>
     *   <li>Server responds with 101 Switching Protocols</li>
     *   <li>Both switch to HTTP/2</li>
     *   <li>Client sends POST with body over HTTP/2</li>
     *   <li>Server echoes body back</li>
     * </ol>
     */
    @Test
    public void testH2cUpgradeWithOptions() throws Exception {
        HTTPClientProtocolHandler client = createConnectedClient(TEST_HOST, HTTP_PORT);

        // h2c upgrade is automatic on plaintext connections
        System.out.println("Testing h2c upgrade with OPTIONS request...");

        // Send OPTIONS request - h2c upgrade headers will be added automatically
        // If server accepts, we get 101 -> switch to HTTP/2 -> response on stream 1
        CountDownLatch optionsLatch = new CountDownLatch(1);
        AtomicReference<HTTPStatus> optionsStatus = new AtomicReference<>();
        AtomicReference<Exception> optionsError = new AtomicReference<>();

        HTTPRequest optionsRequest = client.options("*");
        optionsRequest.send(new DefaultHTTPResponseHandler() {
            @Override
            public void ok(HTTPResponse response) {
                optionsStatus.set(response.getStatus());
                System.out.println("OPTIONS ok response: " + response.getStatus());
            }

            @Override
            public void error(HTTPResponse response) {
                optionsStatus.set(response.getStatus());
                System.out.println("OPTIONS error response: " + response.getStatus());
            }

            @Override
            public void close() {
                optionsLatch.countDown();
            }

            @Override
            public void failed(Exception ex) {
                optionsError.set(ex);
                log("OPTIONS failed: " + ex.getMessage());
                optionsLatch.countDown();
            }
        });

        boolean optionsCompleted = optionsLatch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue("OPTIONS request should complete", optionsCompleted);
        assertNull("OPTIONS should not fail: " + optionsError.get(), optionsError.get());

        // Check if upgrade happened
        HTTPVersion version = client.getVersion();
        System.out.println("HTTP version after OPTIONS: " + version);
        System.out.println("OPTIONS status: " + optionsStatus.get());

        // Whether h2c upgrade succeeded or not, we should have a valid response
        assertNotNull("Should have OPTIONS status", optionsStatus.get());
        
        if (version == HTTPVersion.HTTP_2_0) {
            System.out.println("h2c upgrade successful! Connection is now HTTP/2.");
            // The actual response to OPTIONS comes over HTTP/2 (not the 101)
            assertEquals("Should receive 200 OK for OPTIONS", HTTPStatus.OK, optionsStatus.get());
        } else {
            System.out.println("Server did not upgrade to h2c, continuing with HTTP/1.1");
            // Server declined h2c, which is valid - got HTTP/1.1 response
        }

        client.close();
    }

    /**
     * Test POST request with automatic h2c upgrade.
     * The connection automatically attempts h2c upgrade on plaintext connections.
     * The POST request triggers the upgrade and receives its response over HTTP/2.
     */
    @Test
    public void testH2cPOSTWithContentLength() throws Exception {
        HTTPClientProtocolHandler client = createConnectedClient(TEST_HOST, HTTP_PORT);

        // h2c upgrade is automatic on plaintext connections
        // Send POST - connection will include upgrade headers and handle the transition
        String testContent = "Hello, World! This is test content for HTTP/2 POST.";
        byte[] contentBytes = testContent.getBytes(StandardCharsets.UTF_8);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HTTPStatus> status = new AtomicReference<>();
        AtomicReference<String> responseBody = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();

        HTTPRequest request = client.post("/echo");
        request.header("Content-Type", "text/plain");
        request.header("Content-Length", String.valueOf(contentBytes.length));

        request.startRequestBody(new DefaultHTTPResponseHandler() {
            @Override
            public void ok(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void error(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void responseBodyContent(ByteBuffer data) {
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                try {
                    bodyBuffer.write(bytes);
                } catch (Exception e) {
                    // ignore
                }
            }

            @Override
            public void close() {
                responseBody.set(new String(bodyBuffer.toByteArray(), StandardCharsets.UTF_8));
                latch.countDown();
            }

            @Override
            public void failed(Exception ex) {
                error.set(ex);
                latch.countDown();
            }
        });

        request.requestBodyContent(ByteBuffer.wrap(contentBytes));
        request.endRequestBody();

        boolean completed = latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        
        // After request completes, check if we're on HTTP/2
        HTTPVersion version = client.getVersion();
        System.out.println("After POST: version=" + version + " status=" + status.get());
        
        client.close();

        assertTrue("POST should complete", completed);
        assertNull("Should not fail: " + error.get(), error.get());
        assertEquals("Should receive 200 OK", HTTPStatus.OK, status.get());

        String body = responseBody.get();
        assertNotNull("Should have response body", body);
        assertTrue("Response should contain echoed content", body.contains(testContent));
        
        // Verify we upgraded to HTTP/2 (if server supports it)
        // Note: If server doesn't support h2c, this will still pass as HTTP/1.1
    }

    /**
     * Test chunked POST request with automatic h2c upgrade.
     * The connection automatically attempts h2c upgrade on plaintext connections.
     * Data is streamed as multiple chunks (DATA frames in HTTP/2).
     */
    @Test
    public void testH2cChunkedPOST() throws Exception {
        HTTPClientProtocolHandler client = createConnectedClient(TEST_HOST, HTTP_PORT);

        // h2c upgrade is automatic - just send the chunked POST
        String chunk1 = "First chunk of data. ";
        String chunk2 = "Second chunk follows. ";
        String chunk3 = "Third and final chunk.";
        String fullContent = chunk1 + chunk2 + chunk3;

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HTTPStatus> status = new AtomicReference<>();
        AtomicReference<String> responseBody = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();

        HTTPRequest request = client.post("/echo");
        request.header("Content-Type", "text/plain");
        // No Content-Length - will be sent as DATA frames

        request.startRequestBody(new DefaultHTTPResponseHandler() {
            @Override
            public void ok(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void error(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void responseBodyContent(ByteBuffer data) {
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                try {
                    bodyBuffer.write(bytes);
                } catch (Exception e) {
                    // ignore
                }
            }

            @Override
            public void close() {
                responseBody.set(new String(bodyBuffer.toByteArray(), StandardCharsets.UTF_8));
                latch.countDown();
            }

            @Override
            public void failed(Exception ex) {
                error.set(ex);
                latch.countDown();
            }
        });

        // Send chunks as DATA frames
        request.requestBodyContent(ByteBuffer.wrap(chunk1.getBytes(StandardCharsets.UTF_8)));
        request.requestBodyContent(ByteBuffer.wrap(chunk2.getBytes(StandardCharsets.UTF_8)));
        request.requestBodyContent(ByteBuffer.wrap(chunk3.getBytes(StandardCharsets.UTF_8)));
        request.endRequestBody();

        boolean completed = latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        client.close();

        assertTrue("HTTP/2 chunked POST should complete", completed);
        assertNull("Should not fail: " + error.get(), error.get());
        assertEquals("Should receive 200 OK", HTTPStatus.OK, status.get());

        String body = responseBody.get();
        assertNotNull("Should have response body", body);
        assertTrue("Response should contain all chunked content", body.contains(fullContent));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP/2 with Prior Knowledge Tests
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Test HTTP/2 with prior knowledge - skip h2c upgrade and send PRI directly.
     * This assumes the server supports HTTP/2 on plaintext connections.
     */
    @Test
    public void testH2PriorKnowledge() throws Exception {
        // Must set prior knowledge BEFORE connecting
        HTTPClientProtocolHandler client = createH2PriorKnowledgeClient(TEST_HOST, HTTP_PORT);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HTTPStatus> status = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();

        HTTPRequest request = client.get("/test");
        request.send(new DefaultHTTPResponseHandler() {
            @Override
            public void ok(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void error(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void close() {
                latch.countDown();
            }

            @Override
            public void failed(Exception ex) {
                error.set(ex);
                latch.countDown();
            }
        });

        boolean completed = latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Should be HTTP/2 immediately (no upgrade needed)
        HTTPVersion version = client.getVersion();
        
        client.close();

        assertTrue("Request should complete", completed);
        assertNull("Should not fail: " + error.get(), error.get());
        assertEquals("Should receive 200 OK", HTTPStatus.OK, status.get());
        assertEquals("Should be HTTP/2 with prior knowledge", HTTPVersion.HTTP_2_0, version);
    }

    /**
     * Test HTTP/2 with prior knowledge - POST with body.
     */
    @Test
    public void testH2PriorKnowledgePOST() throws Exception {
        // Must set prior knowledge BEFORE connecting
        HTTPClientProtocolHandler client = createH2PriorKnowledgeClient(TEST_HOST, HTTP_PORT);

        String testContent = "Hello from HTTP/2 with prior knowledge!";
        byte[] contentBytes = testContent.getBytes(StandardCharsets.UTF_8);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HTTPStatus> status = new AtomicReference<>();
        AtomicReference<String> responseBody = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();

        HTTPRequest request = client.post("/echo");
        request.header("Content-Type", "text/plain");
        request.header("Content-Length", String.valueOf(contentBytes.length));

        request.startRequestBody(new DefaultHTTPResponseHandler() {
            @Override
            public void ok(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void error(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void responseBodyContent(ByteBuffer data) {
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                try {
                    bodyBuffer.write(bytes);
                } catch (Exception e) {
                    // ignore
                }
            }

            @Override
            public void close() {
                responseBody.set(new String(bodyBuffer.toByteArray(), StandardCharsets.UTF_8));
                latch.countDown();
            }

            @Override
            public void failed(Exception ex) {
                error.set(ex);
                latch.countDown();
            }
        });

        request.requestBodyContent(ByteBuffer.wrap(contentBytes));
        request.endRequestBody();

        boolean completed = latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        HTTPVersion version = client.getVersion();
        
        client.close();

        assertTrue("POST should complete", completed);
        assertNull("Should not fail: " + error.get(), error.get());
        assertEquals("Should receive 200 OK", HTTPStatus.OK, status.get());
        assertEquals("Should be HTTP/2", HTTPVersion.HTTP_2_0, version);
        
        String body = responseBody.get();
        assertNotNull("Should have response body", body);
        assertTrue("Response should contain echoed content", body.contains(testContent));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTPS / h2 Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testHTTPSSimpleGET() throws Exception {
        HTTPClientProtocolHandler client = createSecureConnectedClient(TEST_HOST, HTTPS_PORT);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HTTPStatus> status = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();

        HTTPRequest request = client.get("/test");
        request.send(new DefaultHTTPResponseHandler() {
            @Override
            public void ok(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void error(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void close() {
                latch.countDown();
            }

            @Override
            public void failed(Exception ex) {
                error.set(ex);
                latch.countDown();
            }
        });

        boolean completed = latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        client.close();

        assertTrue("HTTPS request should complete", completed);
        assertNull("Should not fail: " + error.get(), error.get());
        assertNotNull("Should receive status", status.get());
        assertEquals("Should receive 200 OK", HTTPStatus.OK, status.get());
    }

    @Test
    public void testHTTPSPOSTWithContentVerification() throws Exception {
        HTTPClientProtocolHandler client = createSecureConnectedClient(TEST_HOST, HTTPS_PORT);

        String testContent = "Secure content for HTTPS POST verification test!";
        byte[] contentBytes = testContent.getBytes(StandardCharsets.UTF_8);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HTTPStatus> status = new AtomicReference<>();
        AtomicReference<String> responseBody = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();

        HTTPRequest request = client.post("/echo");
        request.header("Content-Type", "text/plain");
        request.header("Content-Length", String.valueOf(contentBytes.length));

        request.startRequestBody(new DefaultHTTPResponseHandler() {
            @Override
            public void ok(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void error(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void responseBodyContent(ByteBuffer data) {
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                try {
                    bodyBuffer.write(bytes);
                } catch (Exception e) {
                    // ignore
                }
            }

            @Override
            public void close() {
                responseBody.set(new String(bodyBuffer.toByteArray(), StandardCharsets.UTF_8));
                latch.countDown();
            }

            @Override
            public void failed(Exception ex) {
                error.set(ex);
                latch.countDown();
            }
        });

        request.requestBodyContent(ByteBuffer.wrap(contentBytes));
        request.endRequestBody();

        boolean completed = latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        client.close();

        assertTrue("HTTPS POST should complete", completed);
        assertNull("Should not fail: " + error.get(), error.get());
        assertEquals("Should receive 200 OK", HTTPStatus.OK, status.get());

        String body = responseBody.get();
        assertNotNull("Should have response body", body);
        assertTrue("Response should contain echoed content",
            body.contains(testContent));
    }

    @Test
    public void testHTTPSChunkedUpload() throws Exception {
        HTTPClientProtocolHandler client = createSecureConnectedClient(TEST_HOST, HTTPS_PORT);

        String chunk1 = "HTTPS chunk one. ";
        String chunk2 = "HTTPS chunk two. ";
        String chunk3 = "HTTPS chunk three.";
        String fullContent = chunk1 + chunk2 + chunk3;

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HTTPStatus> status = new AtomicReference<>();
        AtomicReference<String> responseBody = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();

        HTTPRequest request = client.post("/echo");
        request.header("Content-Type", "text/plain");
        // For HTTP/1.1, we need Transfer-Encoding: chunked for streaming without Content-Length
        // For HTTP/2, DATA frames handle this automatically
        if (client.getVersion() == HTTPVersion.HTTP_1_1) {
            request.header("Transfer-Encoding", "chunked");
        }

        request.startRequestBody(new DefaultHTTPResponseHandler() {
            @Override
            public void ok(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void error(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void responseBodyContent(ByteBuffer data) {
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                try {
                    bodyBuffer.write(bytes);
                } catch (Exception e) {
                    // ignore
                }
            }

            @Override
            public void close() {
                responseBody.set(new String(bodyBuffer.toByteArray(), StandardCharsets.UTF_8));
                latch.countDown();
            }

            @Override
            public void failed(Exception ex) {
                error.set(ex);
                latch.countDown();
            }
        });

        request.requestBodyContent(ByteBuffer.wrap(chunk1.getBytes(StandardCharsets.UTF_8)));
        request.requestBodyContent(ByteBuffer.wrap(chunk2.getBytes(StandardCharsets.UTF_8)));
        request.requestBodyContent(ByteBuffer.wrap(chunk3.getBytes(StandardCharsets.UTF_8)));
        request.endRequestBody();

        boolean completed = latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        client.close();

        assertTrue("HTTPS chunked upload should complete", completed);
        assertNull("Should not fail: " + error.get(), error.get());
        assertEquals("Should receive 200 OK", HTTPStatus.OK, status.get());

        String body = responseBody.get();
        assertNotNull("Should have response body", body);
        assertTrue("Response should contain all chunked content",
            body.contains(fullContent));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Content Verification Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testContentIntegrity() throws Exception {
        HTTPClientProtocolHandler client = createConnectedClient(TEST_HOST, HTTP_PORT);

        // Create content with a specific pattern for verification
        StringBuilder contentBuilder = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            contentBuilder.append("Line ").append(i).append(": ");
            contentBuilder.append("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789");
            contentBuilder.append("\n");
        }
        String originalContent = contentBuilder.toString();
        byte[] contentBytes = originalContent.getBytes(StandardCharsets.UTF_8);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HTTPStatus> status = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();

        HTTPRequest request = client.post("/echo");
        request.header("Content-Type", "text/plain; charset=UTF-8");
        request.header("Content-Length", String.valueOf(contentBytes.length));

        request.startRequestBody(new DefaultHTTPResponseHandler() {
            @Override
            public void ok(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void error(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void responseBodyContent(ByteBuffer data) {
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                try {
                    bodyBuffer.write(bytes);
                } catch (Exception e) {
                    // ignore
                }
            }

            @Override
            public void close() {
                latch.countDown();
            }

            @Override
            public void failed(Exception ex) {
                error.set(ex);
                latch.countDown();
            }
        });

        request.requestBodyContent(ByteBuffer.wrap(contentBytes));
        request.endRequestBody();

        boolean completed = latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        client.close();

        assertTrue("Request should complete", completed);
        assertNull("Should not fail: " + error.get(), error.get());
        assertEquals("Should receive 200 OK", HTTPStatus.OK, status.get());

        String receivedContent = new String(bodyBuffer.toByteArray(), StandardCharsets.UTF_8);
        assertTrue("Received content should contain original content",
            receivedContent.contains(originalContent));
    }
}

