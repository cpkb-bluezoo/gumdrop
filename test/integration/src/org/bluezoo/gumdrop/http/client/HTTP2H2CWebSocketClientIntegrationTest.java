/*
 * HTTP2H2CWebSocketClientIntegrationTest.java
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

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.bluezoo.gumdrop.Gumdrop;
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
 * WebSocket-over-HTTP/2-cleartext (h2c) integration test (RFC 8441 +
 * RFC 9113 §3.3 prior knowledge) for the public {@link WebSocketClient}
 * facade with {@link WebSocketClient#setH2WithPriorKnowledge(boolean)}.
 *
 * <p>Companion to {@link HTTP2WebSocketClientIntegrationTest} (TLS+ALPN);
 * this drives a real {@link HTTPListener} with no TLS at all, proving
 * WebSocket-over-h2 works over the cleartext prior-knowledge path too --
 * the only h2c mechanism this project supports for WebSocket (the older
 * HTTP/1.1-{@code Upgrade}-header h2c bootstrap is deprecated by RFC 9113
 * §3.1 itself and deliberately not implemented here; see {@link
 * WebSocketClient#setH2WithPriorKnowledge(boolean)}'s javadoc for the
 * reasoning). The server side needed no changes at all: {@link
 * HTTPListener} already accepts the h2 connection preface directly on a
 * cleartext connection (RFC 9113 §3.4), independent of how the WebSocket
 * upgrade itself is validated.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTP2H2CWebSocketClientIntegrationTest {

    private static final int PORT = 18451;
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
        System.setProperty("gumdrop.workers", "2");

        listener = new HTTPListener();
        listener.setPort(PORT);
        listener.setAddresses(TEST_HOST);
        // No setSecure/keystore at all -- plain cleartext TCP.
        listener.setHandlerFactory(new H2cEchoWebSocketHandlerFactory());

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
    public void testTextAndBinaryEchoOverH2CPriorKnowledge() throws Exception {
        final CountDownLatch opened = new CountDownLatch(1);
        final CountDownLatch textEchoed = new CountDownLatch(1);
        final CountDownLatch binaryEchoed = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final AtomicReference<String> receivedText = new AtomicReference<>();
        final AtomicReference<byte[]> receivedBinary = new AtomicReference<>();
        final AtomicReference<WebSocketSession> sessionRef = new AtomicReference<>();

        WebSocketClient client = new WebSocketClient(TEST_HOST, PORT);
        // Deliberately not calling setSecure -- defaults to false (cleartext).
        client.setH2WithPriorKnowledge(true);

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

            assertTrue("WebSocket-over-h2c should have opened within " + ASYNC_TIMEOUT_SECONDS + "s",
                    opened.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertNull("Unexpected error before open: " + error.get(), error.get());

            WebSocketSession session = sessionRef.get();
            session.sendText("hello over h2c");
            assertTrue("Text message should have been echoed back",
                    textEchoed.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertNull("Unexpected error awaiting text echo: " + error.get(), error.get());
            assertEquals("hello over h2c", receivedText.get());

            byte[] payload = { 5, 4, 3, 2, (byte) 0xfd, 0, 9 };
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

    // ─────────────────────────────────────────────────────────────────────────
    // Server-side WebSocket-over-h2c echo handler
    // ─────────────────────────────────────────────────────────────────────────

    private static class H2cEchoWebSocketHandlerFactory implements HTTPRequestHandlerFactory {

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
