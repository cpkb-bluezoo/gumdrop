/*
 * HTTP3WebSocketClientIntegrationTest.java
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
import org.bluezoo.gumdrop.http.HTTPRequestHandler;
import org.bluezoo.gumdrop.http.HTTPRequestHandlerFactory;
import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.http.DefaultHTTPRequestHandler;
import org.bluezoo.gumdrop.http.h3.HTTP3Listener;
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
 * WebSocket-over-HTTP/3 integration test (RFC 9220) for the public
 * {@link WebSocketClient} facade with {@link WebSocketClient#setH3Enabled(boolean)}.
 *
 * <p>Drives a real {@link HTTP3Listener} server that accepts an Extended
 * CONNECT upgrade (via {@link HTTPResponseState#upgradeToWebSocket}) and
 * echoes text/binary messages back, over real loopback QUIC -- proving
 * the client-side Extended CONNECT path (added alongside the existing,
 * already-working server-side path) actually interoperates end to end.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTP3WebSocketClientIntegrationTest {

    private static final int H3_PORT = 18447;
    // Must match the certificate's dNSName SAN -- see
    // HTTP3ClientIntegrationTest's own note on why an IP literal doesn't work.
    private static final String TEST_HOST = "localhost";
    private static final int ASYNC_TIMEOUT_SECONDS = 8;

    @Rule
    public Timeout globalTimeout = Timeout.builder()
            .withTimeout(ASYNC_TIMEOUT_SECONDS * 3L, TimeUnit.SECONDS)
            .withLookingForStuckThread(true)
            .build();

    private static Gumdrop gumdrop;
    private static HTTP3Listener listener;

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
        File pemCert = new File(certsDir, "ws-h3-server-chain.pem");
        File pemKey = new File(certsDir, "ws-h3-server-key.pem");
        certManager.saveServerPem(pemCert, pemKey);

        System.setProperty("gumdrop.workers", "2");

        listener = new HTTP3Listener();
        listener.setPort(H3_PORT);
        listener.setAddresses(TEST_HOST);
        listener.setCertFile(pemCert.getAbsolutePath());
        listener.setKeyFile(pemKey.getAbsolutePath());
        listener.setHandlerFactory(new EchoWebSocketHandlerFactory());

        gumdrop = Gumdrop.getInstance();
        gumdrop.addListener(listener);
        gumdrop.start();

        // QUIC binds a UDP socket, so there is no TCP port to poll for
        // readiness; allow a brief moment for the engine to bind.
        Thread.sleep(1000);
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

        WebSocketClient client = new WebSocketClient(TEST_HOST, H3_PORT);
        client.setH3Enabled(true);
        // The test server presents a certificate signed by our throwaway
        // test CA, which nothing here trusts by default.
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

            assertTrue("WebSocket-over-H3 should have opened within " + ASYNC_TIMEOUT_SECONDS + "s",
                    opened.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertNull("Unexpected error before open: " + error.get(), error.get());

            WebSocketSession session = sessionRef.get();
            session.sendText("hello over h3");
            assertTrue("Text message should have been echoed back",
                    textEchoed.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertNull("Unexpected error awaiting text echo: " + error.get(), error.get());
            assertEquals("hello over h3", receivedText.get());

            byte[] payload = { 1, 2, 3, 4, (byte) 0xff, 0, 42 };
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
    // Server-side WebSocket-over-H3 echo handler
    // ─────────────────────────────────────────────────────────────────────────

    private static class EchoWebSocketHandlerFactory implements HTTPRequestHandlerFactory {

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
                        sendNotFound(state);
                    }
                }

                private void sendNotFound(HTTPResponseState state) {
                    Headers responseHeaders = new Headers();
                    responseHeaders.status(org.bluezoo.gumdrop.http.HTTPStatus.NOT_FOUND);
                    state.headers(responseHeaders);
                    state.complete();
                }
            };
        }
    }
}
