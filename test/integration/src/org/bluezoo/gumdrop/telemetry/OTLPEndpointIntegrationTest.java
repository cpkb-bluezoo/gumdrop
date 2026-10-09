/*
 * OTLPEndpointIntegrationTest.java
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

package org.bluezoo.gumdrop.telemetry;

import java.util.List;
import org.bluezoo.gumdrop.http.HeaderFields;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.testsupport.CollectingRequestHandler;
import org.bluezoo.gumdrop.testsupport.CollectingResponseHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.ListenerBindCheck;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.bluezoo.gumdrop.http.server.HttpResponse;
import org.bluezoo.gumdrop.http.server.Http2Listener;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.http.client.DefaultHttpResponseHandler;
import org.bluezoo.gumdrop.http.client.HttpClientProtocolHandler;
import org.bluezoo.gumdrop.http.client.HttpClientHandler;
import org.bluezoo.gumdrop.http.client.HttpRequest;
import org.bluezoo.gumdrop.http.client.HttpResponseHandler;

import java.io.ByteArrayOutputStream;
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
 * Focused integration tests for OtlpEndpoint HTTP client functionality.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class OTLPEndpointIntegrationTest {

    private static final int TEST_PORT = 24400;
    private static final Logger LOGGER = Logger.getLogger(OTLPEndpointIntegrationTest.class.getName());

    private Gumdrop gumdrop;
    private Http2Listener server;
    private volatile TestHandler lastHandler;

    @Before
    public void setUp() throws Exception {
        Logger.getLogger("").setLevel(Level.FINE);
        
        // Create test server
        server = new Http2Listener();
        server.port(TEST_PORT);
        server.addresses(java.net.InetAddress.getByName("::1"));
        server.streamHandler(new HttpStreamHandler() {
            @Override
            public HttpRequestHandler openStream(HttpResponse state) {
                lastHandler = CollectingRequestHandler.bind(new TestHandler(), state);
                return lastHandler;
            }
        });

        // Start server
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(2));
        gumdrop.addListener(server);

        // Wait for the listener to be bound
        ListenerBindCheck.assertBound(gumdrop);
    }

    @After
    public void tearDown() throws Exception {
        if (gumdrop != null) {
            gumdrop.shutdown();
            gumdrop.join();
        }
    }

    @Test
    public void testHTTPClientChunkedUpload() throws Exception {
        final CountDownLatch connectedLatch = new CountDownLatch(1);
        TcpTransportFactory factory = new TcpTransportFactory();
        factory.start();
        HttpClientProtocolHandler endpointHandler = new HttpClientProtocolHandler(
                new HttpClientHandler() {
                    @Override
                    public void onConnected(Endpoint endpoint) {
                        LOGGER.info("Client connected");
                        connectedLatch.countDown();
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
                "::1", TEST_PORT, false);
        endpointHandler.setH2Enabled(false);

        ClientEndpoint client = new ClientEndpoint(factory, "::1", TEST_PORT);
        client.connect(gumdrop, endpointHandler);

        // Wait for connection to be ready
        assertTrue("Should connect", connectedLatch.await(30, TimeUnit.SECONDS));
        assertTrue("Should be open", endpointHandler.isOpen());

        final CountDownLatch responseLatch = new CountDownLatch(1);
        final AtomicReference<HttpStatus> responseRef = new AtomicReference<>();
        final AtomicReference<Exception> errorRef = new AtomicReference<>();

        // Create POST request with Transfer-Encoding: chunked
        HttpRequest request = endpointHandler.post("/v1/traces", new CollectingResponseHandler() {
            @Override
            public void ok(HttpStatus response) {
                LOGGER.info("Response 2xx received: " + response);
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
        request.header("Content-Type", "application/x-protobuf");
        request.header("Transfer-Encoding", "chunked");

        LOGGER.info("Starting request body");
        
        // Start body with response handler

        // Send body data
        String testData = "Hello, World!";
        ByteBuffer data = ByteBuffer.wrap(testData.getBytes(StandardCharsets.UTF_8));
        LOGGER.info("Sending body data: " + data.remaining() + " bytes");
        int written = request.bodyContent(data);
        LOGGER.info("Written: " + written + " bytes");

        // End body
        LOGGER.info("Ending request body");
        request.endMessage();

        // Wait for response
        assertTrue("Should get response", responseLatch.await(5, TimeUnit.SECONDS));
        assertNull("Should not have error", errorRef.get());

        // Verify handler received body
        assertNotNull("Should have handler", lastHandler);
        String receivedBody = lastHandler.getReceivedBody();
        LOGGER.info("Server received body: '" + receivedBody + "'");
        assertEquals("Body should match", testData, receivedBody);

        endpointHandler.close();
    }

    @Test
    public void testHTTPClientSimpleGET() throws Exception {
        final CountDownLatch connectedLatch = new CountDownLatch(1);
        TcpTransportFactory factory = new TcpTransportFactory();
        factory.start();
        HttpClientProtocolHandler endpointHandler = new HttpClientProtocolHandler(
                new HttpClientHandler() {
                    @Override
                    public void onConnected(Endpoint endpoint) {
                        connectedLatch.countDown();
                    }
                    @Override
                    public void onSecurityEstablished(SecurityInfo info) {}
                    @Override
                    public void onError(Exception e) {}
                    @Override
                    public void onDisconnected() {}
                },
                "::1", TEST_PORT, false);
        endpointHandler.setH2Enabled(false);

        ClientEndpoint client = new ClientEndpoint(factory, "::1", TEST_PORT);
        client.connect(gumdrop, endpointHandler);

        assertTrue("Should connect", connectedLatch.await(30, TimeUnit.SECONDS));
        assertTrue("Should be open", endpointHandler.isOpen());

        final CountDownLatch responseLatch = new CountDownLatch(1);
        final AtomicReference<HttpStatus> responseRef = new AtomicReference<>();

        HttpRequest request = endpointHandler.get("/test", new CollectingResponseHandler() {
            @Override
            public void ok(HttpStatus response) {
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
        request.endMessage();

        assertTrue("Should get response", responseLatch.await(5, TimeUnit.SECONDS));
        assertNotNull("Should have response", responseRef.get());
        assertEquals("Should be 200 OK", HttpStatus.OK, responseRef.get());

        endpointHandler.close();
    }

    /**
     * Test handler that captures request details.
     * 
     * <p>Uses the correct event-driven pattern: waits for requestComplete()
     * to know when the request is done, not by checking headers.
     */
    static class TestHandler extends CollectingRequestHandler {
        private ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();
        private List<Header> requestHeaders;
        private HttpResponse state;
        private String path;
        private boolean hasBody;

        @Override
        public void headers(HttpResponse state, List<Header> headers) {
            this.state = state;
            this.requestHeaders = headers;
            this.path = HeaderFields.getValue(headers, ":path");
            
            LOGGER.info("Server received: " + HeaderFields.getValue(headers, ":method") + " " + path);
            // Don't respond here - wait for requestComplete() or endRequestBody()
        }

        @Override
        public void startRequestBody(HttpResponse state) {
            hasBody = true;
            LOGGER.info("  startRequestBody called");
        }

        @Override
        public void requestBodyContent(HttpResponse state, ByteBuffer data) {
            int remaining = data.remaining();
            byte[] buf = new byte[remaining];
            data.get(buf);
            try {
                bodyBuffer.write(buf);
            } catch (Exception e) {}
            LOGGER.info("  Received body chunk: " + remaining + " bytes (total: " + bodyBuffer.size() + ")");
        }

        @Override
        public void endRequestBody(HttpResponse state) {
            LOGGER.info("  endRequestBody called, total: " + bodyBuffer.size() + " bytes");
        }

        @Override
        public void requestComplete(HttpResponse state) {
            LOGGER.info("  requestComplete called, body=" + hasBody + ", bodySize=" + bodyBuffer.size());
            sendOk();
        }

        private void sendOk() {
            state.status(HttpStatus.OK.code);
            state.longHeader("Content-Length", 0L);
            state.endMessage();
        }

        String getReceivedBody() {
            return new String(bodyBuffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

