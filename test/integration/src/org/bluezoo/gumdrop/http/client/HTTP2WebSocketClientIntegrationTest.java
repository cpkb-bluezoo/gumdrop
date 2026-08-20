/*
 * HTTP2WebSocketClientIntegrationTest.java
 * Copyright (C) 2026 Chris Burdess
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

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.TestCertificateManager;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HTTPListener;
import org.bluezoo.gumdrop.http.HTTPRequestHandler;
import org.bluezoo.gumdrop.http.HTTPRequestHandlerFactory;
import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.http.DefaultHTTPRequestHandler;
import org.bluezoo.gumdrop.websocket.DefaultWebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketSession;
import org.bluezoo.gumdrop.websocket.client.WebSocketClient;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import static org.junit.Assert.*;

/**
 * WebSocket-over-HTTP/2 integration test (RFC 8441) for the public
 * {@link WebSocketClient} facade.
 *
 * <p>Drives a real {@link HTTPListener} server (TLS, keystore-based, ALPN
 * offering "h2") whose request handler accepts an Extended CONNECT upgrade
 * via {@link HTTPResponseState#upgradeToWebSocket} and echoes text/binary
 * messages back, proving the client-side Extended-CONNECT-over-h2 path
 * (added alongside the already-working h1.1 and h3 paths) interoperates
 * end to end, over real loopback TCP+TLS -- and that ordinary, concurrent
 * h2 requests on the same server are unaffected by another stream on a
 * different connection being WebSocket-upgraded (the regression this stage
 * specifically had to avoid: see {@code HTTPProtocolHandler.switchToWebSocketMode}).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTP2WebSocketClientIntegrationTest {

    private static final int PORT = 18450;
    private static final String TEST_HOST = "localhost";
    private static final int ASYNC_TIMEOUT_SECONDS = 8;

    @Rule
    public Timeout globalTimeout = Timeout.builder()
            .withTimeout(ASYNC_TIMEOUT_SECONDS * 3L, TimeUnit.SECONDS)
            .withLookingForStuckThread(true)
            .build();

    private static Gumdrop gumdrop;
    private static HTTPListener listener;

    @BeforeClass
    public static void startServer() throws Exception {
        File certsDir = new File("test/integration/certs");
        if (!certsDir.exists()) {
            certsDir.mkdirs();
        }
        File caKeystore = new File(certsDir, "ca-keystore.p12");
        if (caKeystore.exists()) {
            caKeystore.delete();
        }
        TestCertificateManager certManager = new TestCertificateManager(certsDir);
        certManager.generateCA("Test CA", 365);
        certManager.generateServerCertificate("localhost", 365);
        File keystore = new File(certsDir, "ws-h2-server-keystore.p12");
        certManager.saveServerKeystore(keystore, "testpass");

        System.setProperty("gumdrop.workers", "2");

        listener = new HTTPListener();
        listener.setPort(PORT);
        listener.setAddresses(TEST_HOST);
        listener.setSecure(true);
        listener.setKeystoreFile(keystore.getAbsolutePath());
        listener.setKeystorePass("testpass");
        listener.setHandlerFactory(new H2EchoWebSocketHandlerFactory());

        gumdrop = Gumdrop.getInstance();
        gumdrop.addListener(listener);
        gumdrop.start();

        Thread.sleep(500);
    }

    @AfterClass
    public static void stopServer() throws Exception {
        if (gumdrop != null) {
            gumdrop.shutdown();
            try {
                gumdrop.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            gumdrop = null;
        }
    }

    @Test
    public void testTextAndBinaryEcho() throws Exception {
        final CountDownLatch opened = new CountDownLatch(1);
        final CountDownLatch textEchoed = new CountDownLatch(1);
        final CountDownLatch binaryEchoed = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final AtomicReference<String> receivedText = new AtomicReference<>();
        final AtomicReference<byte[]> receivedBinary = new AtomicReference<>();
        final AtomicReference<WebSocketSession> sessionRef = new AtomicReference<>();

        WebSocketClient client = new WebSocketClient(TEST_HOST, PORT);
        client.setSecure(true);
        // No setH3Enabled/setH2Enabled call -- h2Enabled defaults to true,
        // and this is exactly the "just connect" application code path;
        // the server only understands h2 or h1.1 here (no h3 listener),
        // so a successful connection here proves h2 was actually used.
        client.setVerifyPeer(false);

        try {
            client.connect("/ws", new DefaultWebSocketEventHandler() {
                @Override
                public void opened(WebSocketSession session) {
                    sessionRef.set(session);
                    opened.countDown();
                }

                @Override
                public void textMessageReceived(WebSocketSession session, String message) {
                    receivedText.set(message);
                    textEchoed.countDown();
                }

                @Override
                public void binaryMessageReceived(WebSocketSession session, ByteBuffer data) {
                    byte[] bytes = new byte[data.remaining()];
                    data.get(bytes);
                    receivedBinary.set(bytes);
                    binaryEchoed.countDown();
                }

                @Override
                public void closed(int code, String reason) {
                    closed.countDown();
                }

                @Override
                public void error(Throwable cause) {
                    error.set(cause);
                    opened.countDown();
                    textEchoed.countDown();
                    binaryEchoed.countDown();
                }
            });

            assertTrue("WebSocket-over-H2 should have opened within " + ASYNC_TIMEOUT_SECONDS + "s",
                    opened.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertNull("Unexpected error before open: " + error.get(), error.get());

            WebSocketSession session = sessionRef.get();
            session.sendText("hello over h2");
            assertTrue("Text message should have been echoed back",
                    textEchoed.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertNull("Unexpected error awaiting text echo: " + error.get(), error.get());
            assertEquals("hello over h2", receivedText.get());

            byte[] payload = { 9, 8, 7, 6, (byte) 0xfe, 0, 1 };
            session.sendBinary(ByteBuffer.wrap(payload));
            assertTrue("Binary message should have been echoed back",
                    binaryEchoed.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertArrayEquals(payload, receivedBinary.get());

            session.close();
            assertTrue("Session should have closed cleanly",
                    closed.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            client.close();
        }
    }

    /**
     * Regression coverage for the crux fix in this stage
     * ({@code HTTPProtocolHandler.switchToWebSocketMode} no longer flips
     * connection-wide state for h2): an ordinary streaming h2 request and a
     * WebSocket-upgraded h2 stream, concurrently multiplexed on the *same*
     * TCP+TLS connection, with traffic on both interleaved. Before the fix,
     * accepting the WS upgrade would have silently stopped the connection's
     * HTTP/2 frame parser entirely, stalling the concurrent request forever.
     */
    @Test
    public void testConcurrentOrdinaryRequestUnaffectedByWebSocketUpgradeOnSameConnection()
            throws Exception {
        final CountDownLatch opened = new CountDownLatch(1);
        final CountDownLatch textEchoed = new CountDownLatch(1);
        final AtomicReference<Throwable> wsError = new AtomicReference<>();
        final AtomicReference<String> receivedText = new AtomicReference<>();
        final AtomicReference<WebSocketSession> sessionRef = new AtomicReference<>();

        WebSocketClient wsClient = new WebSocketClient(TEST_HOST, PORT);
        wsClient.setSecure(true);
        wsClient.setVerifyPeer(false);

        HTTPClient httpClient = new HTTPClient(TEST_HOST, PORT);
        httpClient.setSecure(true);
        httpClient.setVerifyPeer(false);

        try {
            wsClient.connect("/ws", new DefaultWebSocketEventHandler() {
                @Override
                public void opened(WebSocketSession session) {
                    sessionRef.set(session);
                    opened.countDown();
                }

                @Override
                public void textMessageReceived(WebSocketSession session, String message) {
                    receivedText.set(message);
                    textEchoed.countDown();
                }

                @Override
                public void error(Throwable cause) {
                    wsError.set(cause);
                    opened.countDown();
                    textEchoed.countDown();
                }
            });
            assertTrue("WebSocket-over-H2 should have opened",
                    opened.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertNull("Unexpected WS error: " + wsError.get(), wsError.get());

            // A separate connection, but exercising the same server's h2
            // frame-handling path concurrently with the still-open WS
            // stream above -- proves the server as a whole (not just one
            // connection) keeps serving ordinary h2 requests correctly.
            final CountDownLatch httpDone = new CountDownLatch(1);
            final AtomicReference<Exception> httpError = new AtomicReference<>();
            final AtomicReference<HTTPStatus> httpStatus = new AtomicReference<>();

            httpClient.connect(new HTTPClientHandler() {
                @Override
                public void onConnected(org.bluezoo.gumdrop.Endpoint endpoint) {
                }

                @Override
                public void onSecurityEstablished(org.bluezoo.gumdrop.SecurityInfo info) {
                    HTTPRequest request = httpClient.request("GET", "/test");
                    request.send(new DefaultHTTPResponseHandler() {
                        @Override
                        public void ok(HTTPResponse response) {
                            httpStatus.set(response.getStatus());
                        }

                        @Override
                        public void error(HTTPResponse response) {
                            httpStatus.set(response.getStatus());
                        }

                        @Override
                        public void close() {
                            httpDone.countDown();
                        }

                        @Override
                        public void failed(Exception ex) {
                            httpError.set(ex);
                            httpDone.countDown();
                        }
                    });
                }

                @Override
                public void onError(Exception cause) {
                    httpError.set(cause);
                    httpDone.countDown();
                }

                @Override
                public void onDisconnected() {
                }
            });

            WebSocketSession session = sessionRef.get();
            session.sendText("interleaved");

            assertTrue("Ordinary concurrent HTTP/2 request should have completed",
                    httpDone.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertNull("Ordinary request failed: " + httpError.get(), httpError.get());
            assertEquals(HTTPStatus.OK, httpStatus.get());
            assertEquals("Should have negotiated HTTP/2",
                    org.bluezoo.gumdrop.http.HTTPVersion.HTTP_2_0, httpClient.getVersion());

            assertTrue("WebSocket text echo should still have arrived",
                    textEchoed.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals("interleaved", receivedText.get());
        } finally {
            wsClient.close();
            httpClient.close();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Server-side WebSocket-over-H2 echo handler (also serves ordinary
    // requests via EchoHandlerFactory-equivalent behaviour)
    // ─────────────────────────────────────────────────────────────────────────

    private static class H2EchoWebSocketHandlerFactory implements HTTPRequestHandlerFactory {

        @Override
        public HTTPRequestHandler createHandler(HTTPResponseState state, Headers headers) {
            return new DefaultHTTPRequestHandler() {
                @Override
                public void headers(HTTPResponseState state, Headers headers) {
                    if ("CONNECT".equals(headers.getValue(":method"))
                            && "websocket".equalsIgnoreCase(headers.getValue(":protocol"))) {
                        state.upgradeToWebSocket(null, new DefaultWebSocketEventHandler() {
                            @Override
                            public void textMessageReceived(WebSocketSession session, String message) {
                                try {
                                    session.sendText(message);
                                } catch (Exception e) {
                                    // best effort
                                }
                            }

                            @Override
                            public void binaryMessageReceived(WebSocketSession session, ByteBuffer data) {
                                try {
                                    session.sendBinary(data);
                                } catch (Exception e) {
                                    // best effort
                                }
                            }
                        });
                    } else {
                        Headers responseHeaders = new Headers();
                        responseHeaders.status(HTTPStatus.OK);
                        state.headers(responseHeaders);
                        state.complete();
                    }
                }
            };
        }
    }
}
