/*
 * HTTPClientConnectionTest.java
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

import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.http.HTTPVersion;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JUnit 4 test for HTTP client connection implementation.
 *
 * <p>This test verifies HTTP client functionality including:
 * <ul>
 *   <li>h2c (HTTP/2 cleartext) upgrade request generation and handling</li>
 *   <li>HTTP/1.1 chunked transfer encoding for request bodies</li>
 *   <li>HTTP/1.1 chunked transfer encoding for response bodies</li>
 *   <li>HTTP response parsing including headers and body content</li>
 *   <li>Trailer header parsing for chunked responses</li>
 * </ul>
 *
 * <p>Tests use a mock HTTP client connection that captures sent data and
 * allows injection of response data for verification.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPClientConnectionTest {

    private TestHTTPClient client;
    private TestHTTPClientConnection connection;
    private List<String> sentData;
    private List<TestResponseHandler> handlers;
    private Logger rootLogger;
    private Level originalLogLevel;

    @Before
    public void setUp() throws Exception {
        sentData = new ArrayList<>();
        handlers = new ArrayList<>();

        // Set up logging (less verbose for tests)
        rootLogger = Logger.getLogger("");
        originalLogLevel = rootLogger.getLevel();
        rootLogger.setLevel(Level.WARNING);

        // Create test client and connection
        client = new TestHTTPClient(InetAddress.getByName("127.0.0.1"), 8080);
        connection = new TestHTTPClientConnection(client, sentData);

        // Mark the connection as open and set initial version
        connection.setOpenState(true);
        connection.setNegotiatedVersion(HTTPVersion.HTTP_1_1);
    }

    @After
    public void tearDown() throws Exception {
        if (rootLogger != null && originalLogLevel != null) {
            rootLogger.setLevel(originalLogLevel);
        }

        if (client != null) {
            client.close();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // H2C Upgrade Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testH2cUpgradeEnabled() {
        HTTPRequest request = connection.get("/test");
        TestResponseHandler handler = new TestResponseHandler();
        handlers.add(handler);
        request.send(handler);

        assertFalse("Should have sent request", sentData.isEmpty());
        String requestData = sentData.get(0);

        // Should contain h2c upgrade headers
        assertTrue("Should contain Upgrade header",
                requestData.contains("Upgrade: h2c"));
        assertTrue("Should contain HTTP2-Settings header",
                requestData.contains("HTTP2-Settings:"));
        assertTrue("Should contain Connection header with Upgrade",
                requestData.contains("Connection: Upgrade, HTTP2-Settings"));
    }

    @Test
    public void testH2cUpgradeSettingsBase64() {
        HTTPRequest request = connection.get("/");
        TestResponseHandler handler = new TestResponseHandler();
        handlers.add(handler);
        request.send(handler);

        assertFalse("Should have sent request", sentData.isEmpty());
        String requestData = sentData.get(0);

        // Find the HTTP2-Settings header value
        String settingsPrefix = "HTTP2-Settings: ";
        int settingsStart = requestData.indexOf(settingsPrefix);
        assertTrue("Should have HTTP2-Settings header", settingsStart >= 0);

        int valueStart = settingsStart + settingsPrefix.length();
        int valueEnd = requestData.indexOf("\r\n", valueStart);
        String settingsValue = requestData.substring(valueStart, valueEnd);

        // Should be valid base64url encoding (no padding)
        assertFalse("HTTP2-Settings should not have padding",
                settingsValue.contains("="));
        assertTrue("HTTP2-Settings should be base64url",
                settingsValue.matches("[A-Za-z0-9_-]+"));
    }

    @Test
    public void testH2cUpgradeDeclined() {
        // Send request with upgrade
        HTTPRequest request = connection.get("/test");
        TestResponseHandler handler = new TestResponseHandler();
        handlers.add(handler);
        request.send(handler);

        // Simulate regular HTTP/1.1 response (server declined upgrade)
        String response = "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 13\r\n" +
                "\r\n" +
                "Hello, World!";

        connection.receive(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));

        // Connection should remain HTTP/1.1
        assertEquals("Should remain HTTP/1.1 when upgrade declined",
                HTTPVersion.HTTP_1_1, connection.getVersion());

        // Handler should have received the response
        assertTrue("Handler should receive ok()", handler.gotOk);
        assertEquals("Should receive 200 status", HTTPStatus.OK, handler.status);
        assertTrue("Handler should receive close()", handler.gotClose);
    }

    @Test
    public void testH2cUpgradeOnlyFirstRequest() {
        // First request should have upgrade headers
        HTTPRequest request1 = connection.get("/first");
        TestResponseHandler handler1 = new TestResponseHandler();
        handlers.add(handler1);
        request1.send(handler1);

        String firstRequest = sentData.get(0);
        assertTrue("First request should have Upgrade header",
                firstRequest.contains("Upgrade: h2c"));

        // Simulate upgrade declined response
        String response1 = "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 2\r\n" +
                "\r\n" +
                "OK";
        connection.receive(ByteBuffer.wrap(response1.getBytes(StandardCharsets.UTF_8)));

        // Clear and send second request
        sentData.clear();

        // Second request should NOT have upgrade headers (already declined)
        HTTPRequest request2 = connection.get("/second");
        TestResponseHandler handler2 = new TestResponseHandler();
        handlers.add(handler2);
        request2.send(handler2);

        String secondRequest = sentData.get(0);
        assertFalse("Second request should not have Upgrade header",
                secondRequest.contains("Upgrade: h2c"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP/1.1 Chunked Request Body Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testChunkedRequestBody() {
        HTTPRequest request = connection.post("/upload");
        request.header("Transfer-Encoding", "chunked");

        TestResponseHandler handler = new TestResponseHandler();
        handlers.add(handler);

        // Start the request body
        request.startRequestBody(handler);

        // The headers should be sent
        assertFalse("Should have sent headers", sentData.isEmpty());
        String headerData = sentData.get(0);
        assertTrue("Should have Transfer-Encoding header",
                headerData.contains("Transfer-Encoding: chunked"));

        // Send first chunk
        sentData.clear();
        String chunk1 = "Hello";
        int sent1 = request.requestBodyContent(ByteBuffer.wrap(chunk1.getBytes(StandardCharsets.UTF_8)));
        assertEquals("Should send all bytes", chunk1.length(), sent1);

        // Verify chunk format
        assertFalse("Should have sent chunk", sentData.isEmpty());
        String chunkData1 = combineStrings(sentData);
        assertTrue("Chunk should have size prefix",
                chunkData1.contains("5\r\n"));
        assertTrue("Chunk should have content",
                chunkData1.contains("Hello"));

        // Send second chunk
        sentData.clear();
        String chunk2 = " World";
        int sent2 = request.requestBodyContent(ByteBuffer.wrap(chunk2.getBytes(StandardCharsets.UTF_8)));
        assertEquals("Should send all bytes", chunk2.length(), sent2);

        String chunkData2 = combineStrings(sentData);
        assertTrue("Chunk should have size prefix",
                chunkData2.contains("6\r\n"));
        assertTrue("Chunk should have content",
                chunkData2.contains(" World"));

        // End the request body
        sentData.clear();
        request.endRequestBody();

        String finalChunk = combineStrings(sentData);
        assertTrue("Should send final zero-length chunk",
                finalChunk.contains("0\r\n\r\n"));
    }

    @Test
    public void testChunkedRequestHexSizes() {
        HTTPRequest request = connection.post("/upload");
        request.header("Transfer-Encoding", "chunked");

        TestResponseHandler handler = new TestResponseHandler();
        handlers.add(handler);
        request.startRequestBody(handler);

        // Send a chunk larger than 9 bytes to test hex encoding
        sentData.clear();
        byte[] largeChunk = new byte[255];
        for (int i = 0; i < largeChunk.length; i++) {
            largeChunk[i] = (byte) 'X';
        }

        int sent = request.requestBodyContent(ByteBuffer.wrap(largeChunk));
        assertEquals("Should send all bytes", 255, sent);

        String chunkData = combineStrings(sentData);
        // 255 in hex is "ff"
        assertTrue("Chunk size should be in hex",
                chunkData.toLowerCase().contains("ff\r\n"));

        request.endRequestBody();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP/1.1 Chunked Response Body Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testChunkedResponseBasic() {
        HTTPRequest request = connection.get("/chunked");
        TestResponseHandler handler = new TestResponseHandler();
        handlers.add(handler);
        request.send(handler);

        // Simulate chunked response
        String response = "HTTP/1.1 200 OK\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "5\r\n" +
                "Hello\r\n" +
                "6\r\n" +
                " World\r\n" +
                "0\r\n" +
                "\r\n";

        connection.receive(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));

        // Verify handler received everything
        assertTrue("Handler should receive ok()", handler.gotOk);
        assertEquals("Should receive 200 status", HTTPStatus.OK, handler.status);
        assertTrue("Handler should receive startResponseBody()", handler.gotStartBody);
        assertTrue("Handler should receive endResponseBody()", handler.gotEndBody);
        assertTrue("Handler should receive close()", handler.gotClose);

        // Verify body content
        String body = handler.getBodyAsString();
        assertEquals("Body should contain both chunks", "Hello World", body);
    }

    @Test
    public void testChunkedResponseWithExtensions() {
        HTTPRequest request = connection.get("/chunked-ext");
        TestResponseHandler handler = new TestResponseHandler();
        handlers.add(handler);
        request.send(handler);

        // Simulate chunked response with chunk extensions (should be ignored)
        String response = "HTTP/1.1 200 OK\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "5;name=value\r\n" +
                "Hello\r\n" +
                "0;trailer=info\r\n" +
                "\r\n";

        connection.receive(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));

        assertTrue("Handler should receive ok()", handler.gotOk);
        assertTrue("Handler should receive close()", handler.gotClose);

        String body = handler.getBodyAsString();
        assertEquals("Body should parse correctly ignoring extensions", "Hello", body);
    }

    @Test
    public void testChunkedResponseWithTrailers() {
        HTTPRequest request = connection.get("/chunked-trailers");
        TestResponseHandler handler = new TestResponseHandler();
        handlers.add(handler);
        request.send(handler);

        // Simulate chunked response with trailer headers
        String response = "HTTP/1.1 200 OK\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Trailer: X-Checksum\r\n" +
                "\r\n" +
                "5\r\n" +
                "Hello\r\n" +
                "0\r\n" +
                "X-Checksum: abc123\r\n" +
                "\r\n";

        connection.receive(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));

        assertTrue("Handler should receive ok()", handler.gotOk);
        assertTrue("Handler should receive close()", handler.gotClose);

        // Verify trailer header was received
        assertTrue("Should receive trailer header",
                handler.headers.contains("X-Checksum: abc123"));
    }

    @Test
    public void testChunkedResponseFragmented() {
        HTTPRequest request = connection.get("/chunked-fragmented");
        TestResponseHandler handler = new TestResponseHandler();
        handlers.add(handler);
        request.send(handler);

        // Send response in fragments (simulating network conditions)
        String part1 = "HTTP/1.1 200 OK\r\n";
        String part2 = "Transfer-Encoding: chunked\r\n\r\n";
        String part3 = "5\r\nHel";
        String part4 = "lo\r\n6\r\n World\r\n0\r\n\r\n";

        connection.receive(ByteBuffer.wrap(part1.getBytes(StandardCharsets.UTF_8)));
        connection.receive(ByteBuffer.wrap(part2.getBytes(StandardCharsets.UTF_8)));
        connection.receive(ByteBuffer.wrap(part3.getBytes(StandardCharsets.UTF_8)));
        connection.receive(ByteBuffer.wrap(part4.getBytes(StandardCharsets.UTF_8)));

        assertTrue("Handler should receive ok()", handler.gotOk);
        assertTrue("Handler should receive close()", handler.gotClose);

        String body = handler.getBodyAsString();
        assertEquals("Body should be correctly assembled from fragments",
                "Hello World", body);
    }

    @Test
    public void testChunkedResponseLargeChunk() {
        HTTPRequest request = connection.get("/chunked-large");
        TestResponseHandler handler = new TestResponseHandler();
        handlers.add(handler);
        request.send(handler);

        // Create a large chunk (1024 bytes)
        byte[] largeData = new byte[1024];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) ((i % 26) + 'A');
        }
        String largeDataStr = new String(largeData, StandardCharsets.US_ASCII);

        // 1024 in hex is "400"
        String response = "HTTP/1.1 200 OK\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "400\r\n" +
                largeDataStr + "\r\n" +
                "0\r\n" +
                "\r\n";

        connection.receive(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));

        assertTrue("Handler should receive ok()", handler.gotOk);
        assertTrue("Handler should receive close()", handler.gotClose);

        String body = handler.getBodyAsString();
        assertEquals("Body should contain large chunk data", largeDataStr, body);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP/1.1 Fixed Content-Length Response Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testContentLengthResponse() {
        HTTPRequest request = connection.get("/fixed-length");
        TestResponseHandler handler = new TestResponseHandler();
        handlers.add(handler);
        request.send(handler);

        String body = "Hello, World!";
        String response = "HTTP/1.1 200 OK\r\n" +
                "Content-Length: " + body.length() + "\r\n" +
                "\r\n" +
                body;

        connection.receive(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));

        assertTrue("Handler should receive ok()", handler.gotOk);
        assertTrue("Handler should receive close()", handler.gotClose);

        assertEquals("Body should match", body, handler.getBodyAsString());
    }

    @Test
    public void testNoContentResponse() {
        HTTPRequest request = connection.get("/no-content");
        TestResponseHandler handler = new TestResponseHandler();
        handlers.add(handler);
        request.send(handler);

        String response = "HTTP/1.1 204 No Content\r\n" +
                "\r\n";

        connection.receive(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));

        assertTrue("Handler should receive ok()", handler.gotOk);
        assertEquals("Should receive 204 status", HTTPStatus.NO_CONTENT, handler.status);
        assertTrue("Handler should receive close()", handler.gotClose);
        assertFalse("Should not receive startResponseBody()", handler.gotStartBody);
    }

    @Test
    public void testErrorResponse() {
        HTTPRequest request = connection.get("/not-found");
        TestResponseHandler handler = new TestResponseHandler();
        handlers.add(handler);
        request.send(handler);

        String response = "HTTP/1.1 404 Not Found\r\n" +
                "Content-Length: 9\r\n" +
                "\r\n" +
                "Not Found";

        connection.receive(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));

        assertTrue("Handler should receive error()", handler.gotError);
        assertEquals("Should receive 404 status", HTTPStatus.NOT_FOUND, handler.status);
        assertTrue("Handler should receive close()", handler.gotClose);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Request Header Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testRequestHostHeader() {
        HTTPRequest request = connection.get("/test");
        TestResponseHandler handler = new TestResponseHandler();
        handlers.add(handler);
        request.send(handler);

        assertFalse("Should have sent request", sentData.isEmpty());
        String requestData = sentData.get(0);

        // Should have Host header with correct value
        assertTrue("Should have Host header",
                requestData.contains("Host: 127.0.0.1:8080"));
    }

    @Test
    public void testRequestCustomHeaders() {
        HTTPRequest request = connection.get("/api");
        request.header("Accept", "application/json");
        request.header("Authorization", "Bearer token123");
        request.header("X-Custom", "custom-value");

        TestResponseHandler handler = new TestResponseHandler();
        handlers.add(handler);
        request.send(handler);

        assertFalse("Should have sent request", sentData.isEmpty());
        String requestData = sentData.get(0);

        assertTrue("Should have Accept header",
                requestData.contains("Accept: application/json"));
        assertTrue("Should have Authorization header",
                requestData.contains("Authorization: Bearer token123"));
        assertTrue("Should have custom header",
                requestData.contains("X-Custom: custom-value"));
    }

    @Test
    public void testRequestMethods() {
        String[] methods = {"GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH"};

        for (String method : methods) {
            sentData.clear();

            HTTPRequest request = connection.request(method, "/test");
            TestResponseHandler handler = new TestResponseHandler();
            handlers.add(handler);
            request.send(handler);

            assertFalse("Should have sent " + method + " request", sentData.isEmpty());
            String requestData = sentData.get(0);

            assertTrue("Request should start with " + method,
                    requestData.startsWith(method + " /test HTTP/1.1"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper Methods
    // ─────────────────────────────────────────────────────────────────────────

    private String combineStrings(List<String> strings) {
        StringBuilder sb = new StringBuilder();
        for (String s : strings) {
            sb.append(s);
        }
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test Helper Classes
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Test HTTP client that provides access to internal connection.
     */
    private static class TestHTTPClient extends HTTPClient {

        public TestHTTPClient(InetAddress host, int port) {
            super(host, port);
        }
    }

    /**
     * Test HTTP client connection that captures sent data and allows
     * injection of response data.
     */
    private static class TestHTTPClientConnection extends HTTPClientConnection {

        private final List<String> sentData;
        private boolean isOpen;

        public TestHTTPClientConnection(TestHTTPClient client, List<String> sentData) {
            super(client, new MockSocketChannel(), null, false, null);
            this.sentData = sentData;
            this.isOpen = true;
        }

        @Override
        public void send(ByteBuffer buf) {
            if (buf == null) {
                return;
            }
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            sentData.add(new String(data, StandardCharsets.UTF_8));
        }

        @Override
        public boolean isOpen() {
            return isOpen;
        }

        public void setOpenState(boolean open) {
            this.isOpen = open;
        }

        public void setNegotiatedVersion(HTTPVersion version) {
            // Use reflection to set the private field
            try {
                java.lang.reflect.Field field = HTTPClientConnection.class.getDeclaredField("negotiatedVersion");
                field.setAccessible(true);
                field.set(this, version);

                // Also mark as "connected" so requests don't fail
                java.lang.reflect.Field openField = HTTPClientConnection.class.getDeclaredField("open");
                openField.setAccessible(true);
                openField.set(this, true);
            } catch (Exception e) {
                throw new RuntimeException("Failed to set negotiatedVersion", e);
            }
        }
    }

    /**
     * Test response handler that captures all callback invocations.
     */
    private static class TestResponseHandler implements HTTPResponseHandler {

        boolean gotOk = false;
        boolean gotError = false;
        boolean gotStartBody = false;
        boolean gotEndBody = false;
        boolean gotClose = false;
        boolean gotFailed = false;

        HTTPStatus status;
        Exception failureException;
        List<String> headers = new ArrayList<>();
        List<ByteBuffer> bodyChunks = new ArrayList<>();

        @Override
        public void ok(HTTPResponse response) {
            gotOk = true;
            status = response.getStatus();
        }

        @Override
        public void error(HTTPResponse response) {
            gotError = true;
            status = response.getStatus();
        }

        @Override
        public void header(String name, String value) {
            headers.add(name + ": " + value);
        }

        @Override
        public void startResponseBody() {
            gotStartBody = true;
        }

        @Override
        public void responseBodyContent(ByteBuffer data) {
            // Copy the data since buffer is reused
            ByteBuffer copy = ByteBuffer.allocate(data.remaining());
            copy.put(data);
            copy.flip();
            bodyChunks.add(copy);
        }

        @Override
        public void endResponseBody() {
            gotEndBody = true;
        }

        @Override
        public void pushPromise(PushPromise promise) {
            promise.reject();
        }

        @Override
        public void close() {
            gotClose = true;
        }

        @Override
        public void failed(Exception ex) {
            gotFailed = true;
            failureException = ex;
        }

        public String getBodyAsString() {
            int totalSize = 0;
            for (ByteBuffer chunk : bodyChunks) {
                totalSize += chunk.remaining();
            }

            ByteBuffer combined = ByteBuffer.allocate(totalSize);
            for (ByteBuffer chunk : bodyChunks) {
                combined.put(chunk);
            }
            combined.flip();

            byte[] bytes = new byte[combined.remaining()];
            combined.get(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
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
        public SocketChannel bind(SocketAddress local) {
            return this;
        }

        @Override
        public <T> SocketChannel setOption(SocketOption<T> name, T value) {
            return this;
        }

        @Override
        public <T> T getOption(SocketOption<T> name) {
            return null;
        }

        @Override
        public Set<SocketOption<?>> supportedOptions() {
            return Collections.emptySet();
        }

        @Override
        public SocketChannel shutdownInput() {
            return this;
        }

        @Override
        public SocketChannel shutdownOutput() {
            return this;
        }

        @Override
        public java.net.Socket socket() {
            return new java.net.Socket() {
                @Override
                public void setTcpNoDelay(boolean on) {
                }

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
                public int getPort() {
                    return 8080;
                }

                @Override
                public int getLocalPort() {
                    return 54321;
                }
            };
        }

        @Override
        public boolean isConnected() {
            return open;
        }

        @Override
        public boolean isConnectionPending() {
            return false;
        }

        @Override
        public boolean connect(SocketAddress remote) {
            return true;
        }

        @Override
        public boolean finishConnect() {
            return true;
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 8080);
        }

        @Override
        public int read(ByteBuffer dst) {
            return -1;
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) {
            return -1;
        }

        @Override
        public int write(ByteBuffer src) {
            return src.remaining();
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) {
            return 0;
        }

        @Override
        public SocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 54321);
        }

        @Override
        protected void implCloseSelectableChannel() {
            open = false;
        }

        @Override
        protected void implConfigureBlocking(boolean block) {
        }
    }
}

