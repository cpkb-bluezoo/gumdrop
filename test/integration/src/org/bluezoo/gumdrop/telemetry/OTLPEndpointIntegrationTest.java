/*
 * OTLPEndpointIntegrationTest.java
 * Copyright (C) 2025 Chris Burdess
 */

package org.bluezoo.gumdrop.telemetry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.http.DefaultHTTPRequestHandler;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HTTPRequestHandler;
import org.bluezoo.gumdrop.http.HTTPRequestHandlerFactory;
import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.http.HTTPListener;
import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.TCPTransportFactory;
import org.bluezoo.gumdrop.http.client.DefaultHTTPResponseHandler;
import org.bluezoo.gumdrop.http.client.HTTPClientProtocolHandler;
import org.bluezoo.gumdrop.http.client.HTTPClientHandler;
import org.bluezoo.gumdrop.http.client.HTTPRequest;
import org.bluezoo.gumdrop.http.client.HTTPResponseHandler;
import org.bluezoo.gumdrop.http.client.HTTPResponse;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.Assert.*;

/**
 * Focused integration tests for OTLPEndpoint HTTP client functionality.
 */
public class OTLPEndpointIntegrationTest {

    private static final int TEST_PORT = 24400;
    private static final Logger LOGGER = Logger.getLogger(OTLPEndpointIntegrationTest.class.getName());

    private Gumdrop gumdrop;
    private HTTPListener server;
    private TestHandler lastHandler;

    @Before
    public void setUp() throws Exception {
        Logger.getLogger("").setLevel(Level.FINE);
        
        // Create test server
        server = new HTTPListener();
        server.setPort(TEST_PORT);
        server.setAddresses("127.0.0.1");
        server.setHandlerFactory(new HTTPRequestHandlerFactory() {
            @Override
            public HTTPRequestHandler createHandler(HTTPResponseState state, Headers headers) {
                lastHandler = new TestHandler();
                return lastHandler;
            }
        });

        // Start server
        System.setProperty("gumdrop.workers", "2");
        gumdrop = Gumdrop.getInstance();
        gumdrop.addListener(server);
        gumdrop.start();

        // Wait for server
        waitForPort(TEST_PORT);
    }

    @After
    public void tearDown() throws Exception {
        if (gumdrop != null) {
            gumdrop.shutdown();
            gumdrop.join();
        }
        Thread.sleep(500);
    }

    @Test
    public void testHTTPClientChunkedUpload() throws Exception {
        TCPTransportFactory factory = new TCPTransportFactory();
        factory.start();
        HTTPClientProtocolHandler endpointHandler = new HTTPClientProtocolHandler(
                new HTTPClientHandler() {
                    @Override
                    public void onConnected(Endpoint endpoint) {
                        LOGGER.info("Client connected");
                    }
                    @Override
                    public void onSecurityEstablished(SecurityInfo info) {}
                    @Override
                    public void onError(Exception e) {
                        LOGGER.log(Level.WARNING, "Connection error", e);
                    }
                    @Override
                    public void onDisconnected() {
                        LOGGER.info("Client disconnected");
                    }
                },
                "127.0.0.1", TEST_PORT, false);
        endpointHandler.setH2Enabled(false);

        ClientEndpoint client = new ClientEndpoint(factory, "127.0.0.1", TEST_PORT);
        client.connect(endpointHandler);

        // Wait for connection to be ready
        long deadline = System.currentTimeMillis() + 5000;
        while (!endpointHandler.isOpen() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue("Should connect", endpointHandler.isOpen());

        final CountDownLatch responseLatch = new CountDownLatch(1);
        final AtomicReference<HTTPResponse> responseRef = new AtomicReference<>();
        final AtomicReference<Exception> errorRef = new AtomicReference<>();

        // Create POST request with Transfer-Encoding: chunked
        HTTPRequest request = endpointHandler.post("/v1/traces");
        request.header("Content-Type", "application/x-protobuf");
        request.header("Transfer-Encoding", "chunked");

        LOGGER.info("Starting request body");
        
        // Start body with response handler
        request.startRequestBody(new DefaultHTTPResponseHandler() {
            @Override
            public void ok(HTTPResponse response) {
                LOGGER.info("Response 2xx received: " + response.getStatus());
                responseRef.set(response);
            }
            @Override
            public void close() {
                LOGGER.info("Response complete");
                responseLatch.countDown();
            }
            @Override
            public void failed(Exception e) {
                LOGGER.log(Level.WARNING, "Request failed", e);
                errorRef.set(e);
                responseLatch.countDown();
            }
        });

        // Send body data
        String testData = "Hello, World!";
        ByteBuffer data = ByteBuffer.wrap(testData.getBytes(StandardCharsets.UTF_8));
        LOGGER.info("Sending body data: " + data.remaining() + " bytes");
        int written = request.requestBodyContent(data);
        LOGGER.info("Written: " + written + " bytes");

        // End body
        LOGGER.info("Ending request body");
        request.endRequestBody();

        // Wait for response
        assertTrue("Should get response", responseLatch.await(5, TimeUnit.SECONDS));
        assertNull("Should not have error", errorRef.get());

        // Verify handler received body
        assertNotNull("Should have handler", lastHandler);
        Thread.sleep(200); // Allow time for body to be processed
        String receivedBody = lastHandler.getReceivedBody();
        LOGGER.info("Server received body: '" + receivedBody + "'");
        assertEquals("Body should match", testData, receivedBody);

        endpointHandler.close();
    }

    @Test
    public void testHTTPClientSimpleGET() throws Exception {
        TCPTransportFactory factory = new TCPTransportFactory();
        factory.start();
        HTTPClientProtocolHandler endpointHandler = new HTTPClientProtocolHandler(
                new HTTPClientHandler() {
                    @Override
                    public void onConnected(Endpoint endpoint) {}
                    @Override
                    public void onSecurityEstablished(SecurityInfo info) {}
                    @Override
                    public void onError(Exception e) {}
                    @Override
                    public void onDisconnected() {}
                },
                "127.0.0.1", TEST_PORT, false);
        endpointHandler.setH2Enabled(false);

        ClientEndpoint client = new ClientEndpoint(factory, "127.0.0.1", TEST_PORT);
        client.connect(endpointHandler);

        long deadline = System.currentTimeMillis() + 5000;
        while (!endpointHandler.isOpen() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue("Should connect", endpointHandler.isOpen());

        final CountDownLatch responseLatch = new CountDownLatch(1);
        final AtomicReference<HTTPResponse> responseRef = new AtomicReference<>();

        HTTPRequest request = endpointHandler.get("/test");
        request.send(new DefaultHTTPResponseHandler() {
            @Override
            public void ok(HTTPResponse response) {
                responseRef.set(response);
            }
            @Override
            public void close() {
                responseLatch.countDown();
            }
            @Override
            public void failed(Exception e) {
                responseLatch.countDown();
            }
        });

        assertTrue("Should get response", responseLatch.await(5, TimeUnit.SECONDS));
        assertNotNull("Should have response", responseRef.get());
        assertEquals("Should be 200 OK", HTTPStatus.OK, responseRef.get().getStatus());

        endpointHandler.close();
    }

    private void waitForPort(int port) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                Thread.sleep(200);
                return;
            } catch (Exception e) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("Port " + port + " not listening");
    }

    /**
     * Test handler that captures request details.
     * 
     * <p>Uses the correct event-driven pattern: waits for requestComplete()
     * to know when the request is done, not by checking headers.
     */
    static class TestHandler extends DefaultHTTPRequestHandler {
        private ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();
        private Headers requestHeaders;
        private HTTPResponseState state;
        private String path;
        private boolean hasBody;

        @Override
        public void headers(HTTPResponseState state, Headers headers) {
            this.state = state;
            this.requestHeaders = headers;
            this.path = headers.getPath();
            
            LOGGER.info("Server received: " + headers.getMethod() + " " + path);
            // Don't respond here - wait for requestComplete() or endRequestBody()
        }

        @Override
        public void startRequestBody(HTTPResponseState state) {
            hasBody = true;
            LOGGER.info("  startRequestBody called");
        }

        @Override
        public void requestBodyContent(HTTPResponseState state, ByteBuffer data) {
            int remaining = data.remaining();
            byte[] buf = new byte[remaining];
            data.get(buf);
            try {
                bodyBuffer.write(buf);
            } catch (Exception e) {}
            LOGGER.info("  Received body chunk: " + remaining + " bytes (total: " + bodyBuffer.size() + ")");
        }

        @Override
        public void endRequestBody(HTTPResponseState state) {
            LOGGER.info("  endRequestBody called, total: " + bodyBuffer.size() + " bytes");
        }

        @Override
        public void requestComplete(HTTPResponseState state) {
            LOGGER.info("  requestComplete called, body=" + hasBody + ", bodySize=" + bodyBuffer.size());
            sendOk();
        }

        private void sendOk() {
            Headers response = new Headers();
            response.status(HTTPStatus.OK);
            response.add(new Header("Content-Length", "0"));
            state.headers(response);
            state.complete();
        }

        String getReceivedBody() {
            return new String(bodyBuffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

