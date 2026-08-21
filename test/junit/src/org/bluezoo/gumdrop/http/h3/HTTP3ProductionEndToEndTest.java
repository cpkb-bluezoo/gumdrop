/*
 * HTTP3ProductionEndToEndTest.java
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

package org.bluezoo.gumdrop.http.h3;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.http.DefaultHTTPRequestHandler;
import org.bluezoo.gumdrop.http.HTTPRequestHandler;
import org.bluezoo.gumdrop.http.HTTPRequestHandlerFactory;
import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.client.HTTPResponse;
import org.bluezoo.gumdrop.http.client.HTTPResponseHandler;
import org.bluezoo.gumdrop.http.client.PushPromise;
import org.bluezoo.gumdrop.http.qpack.Decoder;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.QuicConnectionCloseException;
import org.bluezoo.gumdrop.quic.QuicEngine;
import org.bluezoo.gumdrop.quic.QuicTransportFactory;
import org.bluezoo.gumdrop.quic.SessionTicketCache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Drives a real HTTP/3 GET request/response over a real client+server QUIC
 * connection, purely through the production classes ({@link
 * QuicTransportFactory}, {@link QuicEngine}, {@link HTTP3ServerHandler},
 * {@link H3Stream}, {@link HTTP3ClientHandler}, {@link H3ClientStream}) --
 * no quiche, no hand-called test harness. Proves the Stage 3 H3 rewire end
 * to end: HTTP/3 framing ({@link H3Parser}/{@link H3Writer}), QPACK
 * (dynamic-table {@link org.bluezoo.gumdrop.http.qpack.Encoder}/
 * {@link org.bluezoo.gumdrop.http.qpack.Decoder}, including the encoder/
 * decoder unidirectional streams, RFC 9204 section 4.2), the control
 * stream + SETTINGS exchange ({@link H3ControlStream}), and response body
 * delivery all working together over a real loopback UDP socket on a real
 * {@link SelectorLoop} thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTP3ProductionEndToEndTest {

    private static final String SERVER_NAME = "test.gumdrop.local";

    private static Path certsDirectory;
    private static Path certFile;
    private static Path keyFile;

    @BeforeClass
    public static void generatePemFiles() throws Exception {
        certsDirectory = Files.createTempDirectory("http3-production-e2e-test");
        Path keystorePath = certsDirectory.resolve("server.p12");
        certFile = certsDirectory.resolve("cert.pem");
        keyFile = certsDirectory.resolve("key.pem");

        run(new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "server",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "1",
                "-dname", "CN=" + SERVER_NAME,
                "-keystore", keystorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", "changeit",
                "-keypass", "changeit"), "keytool");

        run(new ProcessBuilder(
                "openssl", "pkcs12", "-in", keystorePath.toString(),
                "-nodes", "-nocerts", "-out", keyFile.toString(),
                "-passin", "pass:changeit"), "openssl (key export)");

        run(new ProcessBuilder(
                "openssl", "pkcs12", "-in", keystorePath.toString(),
                "-nokeys", "-out", certFile.toString(),
                "-passin", "pass:changeit"), "openssl (cert export)");
    }

    private static void run(ProcessBuilder pb, String toolName) throws Exception {
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished || process.exitValue() != 0) {
            fail(toolName + " failed to produce test PEM files");
        }
    }

    @AfterClass
    public static void deletePemFiles() throws IOException {
        if (certsDirectory == null) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(certsDirectory)) {
            for (Path entry : entries) {
                Files.deleteIfExists(entry);
            }
        }
        Files.deleteIfExists(certsDirectory);
    }

    @Test
    public void testGetRequestResponse() throws Exception {
        SelectorLoop loop = new SelectorLoop(0);
        loop.start();
        QuicEngine serverEngine = null;
        QuicEngine clientEngine = null;
        try {
            QuicTransportFactory serverFactory = new QuicTransportFactory();
            serverFactory.setApplicationProtocols("h3");
            serverFactory.setCertFile(certFile);
            serverFactory.setKeyFile(keyFile);
            serverFactory.start();

            final HTTPRequestHandlerFactory handlerFactory = new HTTPRequestHandlerFactory() {
                @Override
                public HTTPRequestHandler createHandler(HTTPResponseState state, Headers requestHeaders) {
                    return new HTTPRequestHandler() {
                        @Override
                        public void headers(HTTPResponseState state, Headers headers) {
                            Headers response = new Headers();
                            response.add(":status", "200");
                            response.add("content-type", "text/plain");
                            state.headers(response);
                            state.startResponseBody();
                            state.responseBodyContent(
                                    ByteBuffer.wrap("hello h3".getBytes(StandardCharsets.US_ASCII)));
                            state.endResponseBody();
                            state.complete();
                        }

                        @Override
                        public void startRequestBody(HTTPResponseState state) {
                        }

                        @Override
                        public void requestBodyContent(HTTPResponseState state, ByteBuffer data) {
                        }

                        @Override
                        public void endRequestBody(HTTPResponseState state) {
                        }

                        @Override
                        public void requestComplete(HTTPResponseState state) {
                        }
                    };
                }
            };

            serverEngine = serverFactory.createServerEngine(
                    InetAddress.getLoopbackAddress(), 0,
                    new QuicEngine.ConnectionAcceptedHandler() {
                        @Override
                        public void connectionAccepted(QuicConnection connection) {
                            new HTTP3ServerHandler(connection, handlerFactory, null, null, null, false);
                        }
                    }, loop);

            int port = ((InetSocketAddress) serverEngine.getLocalAddress()).getPort();

            QuicTransportFactory clientFactory = new QuicTransportFactory();
            clientFactory.setApplicationProtocols("h3");
            clientFactory.setVerifyPeer(false);
            clientFactory.start();

            final CountDownLatch responseLatch = new CountDownLatch(1);
            final AtomicReference<HTTPResponse> okResponse = new AtomicReference<HTTPResponse>();
            final AtomicReference<Exception> failure = new AtomicReference<Exception>();
            final AtomicReference<String> body = new AtomicReference<String>();

            clientEngine = clientFactory.connect(
                    InetAddress.getLoopbackAddress(), port,
                    new QuicEngine.ConnectionAcceptedHandler() {
                        @Override
                        public void connectionAccepted(QuicConnection connection) {
                            HTTP3ClientHandler h3 = new HTTP3ClientHandler(connection);

                            Headers requestHeaders = new Headers();
                            requestHeaders.add(":method", "GET");
                            requestHeaders.add(":scheme", "https");
                            requestHeaders.add(":authority", SERVER_NAME);
                            requestHeaders.add(":path", "/");

                            h3.sendRequest(requestHeaders, new HTTPResponseHandler() {
                                private final StringBuilder buf = new StringBuilder();

                                @Override
                                public void ok(HTTPResponse response) {
                                    okResponse.set(response);
                                }

                                @Override
                                public void error(HTTPResponse response) {
                                    failure.set(new IOException("Unexpected error status: " + response.getStatus()));
                                    responseLatch.countDown();
                                }

                                @Override
                                public void header(String name, String value) {
                                }

                                @Override
                                public void startResponseBody() {
                                }

                                @Override
                                public void responseBodyContent(ByteBuffer data) {
                                    byte[] bytes = new byte[data.remaining()];
                                    data.get(bytes);
                                    buf.append(new String(bytes, StandardCharsets.US_ASCII));
                                }

                                @Override
                                public void endResponseBody() {
                                }

                                @Override
                                public void pushPromise(PushPromise promise) {
                                }

                                @Override
                                public void close() {
                                    body.set(buf.toString());
                                    responseLatch.countDown();
                                }

                                @Override
                                public void failed(Exception ex) {
                                    failure.set(ex);
                                    responseLatch.countDown();
                                }
                            }, true);
                        }
                    }, loop, SERVER_NAME);

            assertTrue("Response should complete within 5s", responseLatch.await(5, TimeUnit.SECONDS));
            if (failure.get() != null) {
                throw failure.get();
            }

            assertEquals(HTTPStatus.OK, okResponse.get().getStatus());
            assertEquals("hello h3", body.get());
        } finally {
            // Stop the loop thread first so closing the engines from this
            // (test) thread doesn't race its own concurrent I/O processing.
            loop.shutdown();
            loop.awaitQuiesce(2000);
            if (clientEngine != null) {
                clientEngine.close();
            }
            if (serverEngine != null) {
                serverEngine.close();
            }
        }
    }

    /**
     * RFC 9204's dynamic table (previously not wired in at all -- the H3
     * layer used the static-table-only {@code SimpleEncoder}/{@code
     * SimpleDecoder} pair) is now actually used end to end: a header the
     * client's real {@link org.bluezoo.gumdrop.http.qpack.Encoder} can't
     * find in the static table gets inserted and mirrored into the
     * server's real {@link Decoder} via the QPACK encoder stream (RFC
     * 9204 section 4.2) -- observed here as the server-side dynamic
     * table's insert count -- and a second request repeating the exact
     * same header reuses that entry (an indexed reference) rather than
     * inserting it again, proving the wiring isn't just "doesn't crash"
     * but actually compresses repeated headers.
     *
     * <p>The first request is a throwaway used only to force a full
     * round trip (guaranteeing both sides' SETTINGS, including {@code
     * SETTINGS_QPACK_MAX_TABLE_CAPACITY}, have been exchanged and
     * processed) before the insert-count baseline is taken -- otherwise
     * whether the very first request's encode call already saw a
     * non-zero capacity would be a race against when the server's
     * SETTINGS happens to arrive.
     */
    @Test
    public void testDynamicTableEntryReusedAcrossRequests() throws Exception {
        SelectorLoop loop = new SelectorLoop(0);
        loop.start();
        QuicEngine serverEngine = null;
        QuicEngine clientEngine = null;
        try {
            QuicTransportFactory serverFactory = new QuicTransportFactory();
            serverFactory.setApplicationProtocols("h3");
            serverFactory.setCertFile(certFile);
            serverFactory.setKeyFile(keyFile);
            serverFactory.start();

            final HTTPRequestHandlerFactory handlerFactory = new HTTPRequestHandlerFactory() {
                @Override
                public HTTPRequestHandler createHandler(HTTPResponseState state, Headers requestHeaders) {
                    return new DefaultHTTPRequestHandler() {
                        @Override
                        public void headers(HTTPResponseState state, Headers headers) {
                            Headers response = new Headers();
                            response.add(":status", "200");
                            state.headers(response);
                            state.complete();
                        }
                    };
                }
            };

            final AtomicReference<HTTP3ServerHandler> serverHandlerRef = new AtomicReference<HTTP3ServerHandler>();
            serverEngine = serverFactory.createServerEngine(
                    InetAddress.getLoopbackAddress(), 0,
                    new QuicEngine.ConnectionAcceptedHandler() {
                        @Override
                        public void connectionAccepted(QuicConnection connection) {
                            serverHandlerRef.set(new HTTP3ServerHandler(
                                    connection, handlerFactory, null, null, null, false));
                        }
                    }, loop);

            int port = ((InetSocketAddress) serverEngine.getLocalAddress()).getPort();

            QuicTransportFactory clientFactory = new QuicTransportFactory();
            clientFactory.setApplicationProtocols("h3");
            clientFactory.setVerifyPeer(false);
            clientFactory.start();

            final AtomicReference<HTTP3ClientHandler> h3Ref = new AtomicReference<HTTP3ClientHandler>();
            final CountDownLatch clientReady = new CountDownLatch(1);

            clientEngine = clientFactory.connect(
                    InetAddress.getLoopbackAddress(), port,
                    new QuicEngine.ConnectionAcceptedHandler() {
                        @Override
                        public void connectionAccepted(QuicConnection connection) {
                            h3Ref.set(new HTTP3ClientHandler(connection));
                            clientReady.countDown();
                        }
                    }, loop, SERVER_NAME);

            assertTrue("Client should have connected within 5s", clientReady.await(5, TimeUnit.SECONDS));
            HTTP3ClientHandler h3 = h3Ref.get();

            // Request 1: throwaway, no custom header -- just to force a
            // full round trip before taking the insert-count baseline.
            sendGetAndAwait(h3, "/warmup", null, null);

            Decoder serverDecoder = getQpackDecoder(serverHandlerRef.get());
            long baseline = getInsertCount(serverDecoder);

            // Request 2: a header not in the QPACK static table -- must
            // be inserted (and mirrored server-side). Uses the exact same
            // :path as request 3 below -- :path itself isn't in the QPACK
            // static table either, so a *different* path per request would
            // itself be a novel dynamic-table insertion each time and
            // contaminate the insert-count delta this test is measuring.
            sendGetAndAwait(h3, "/trace", "x-custom-trace-id", "1234567890abcdef0123456789abcdef");
            long afterFirstUse = getInsertCount(serverDecoder);
            assertTrue("A novel header should have been inserted into the dynamic table",
                    afterFirstUse > baseline);

            // Request 3: the exact same request again -- must be
            // referenced (indexed), not inserted a second time.
            sendGetAndAwait(h3, "/trace", "x-custom-trace-id", "1234567890abcdef0123456789abcdef");
            long afterSecondUse = getInsertCount(serverDecoder);
            assertEquals("Repeating the same header must reuse the existing dynamic table entry, "
                    + "not insert a duplicate", afterFirstUse, afterSecondUse);
        } finally {
            loop.shutdown();
            loop.awaitQuiesce(2000);
            if (clientEngine != null) {
                clientEngine.close();
            }
            if (serverEngine != null) {
                serverEngine.close();
            }
        }
    }

    private static void sendGetAndAwait(HTTP3ClientHandler h3, String path,
            String extraHeaderName, String extraHeaderValue) throws Exception {
        Headers requestHeaders = new Headers();
        requestHeaders.add(":method", "GET");
        requestHeaders.add(":scheme", "https");
        requestHeaders.add(":authority", SERVER_NAME);
        requestHeaders.add(":path", path);
        if (extraHeaderName != null) {
            requestHeaders.add(extraHeaderName, extraHeaderValue);
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Exception> failure = new AtomicReference<Exception>();
        h3.sendRequest(requestHeaders, new LatchResponseHandler(latch, failure), true);
        assertTrue("Response to " + path + " should complete within 5s", latch.await(5, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw failure.get();
        }
    }

    private static Decoder getQpackDecoder(HTTP3ServerHandler serverHandler) throws Exception {
        Field f = HTTP3ServerHandler.class.getDeclaredField("qpackDecoder");
        f.setAccessible(true);
        return (Decoder) f.get(serverHandler);
    }

    private static long getInsertCount(Decoder decoder) throws Exception {
        Field tableField = Decoder.class.getDeclaredField("table");
        tableField.setAccessible(true);
        Object table = tableField.get(decoder);
        Method m = table.getClass().getDeclaredMethod("getInsertCount");
        m.setAccessible(true);
        return (Long) m.invoke(table);
    }

    /**
     * RFC 9114 section 8.1: a fatal framing violation on the peer's
     * control stream must close the whole QUIC connection with an
     * application-level error, not just log a warning and otherwise do
     * nothing (the previous behaviour of {@link H3ControlStream#frameError}).
     *
     * <p>Deliberately bypasses {@link HTTP3ClientHandler} on the client
     * side -- it opens a raw unidirectional stream, writes the RFC 9114
     * section 6.2.1 control-stream type byte (0x00) followed immediately
     * by a HEADERS frame (section 7.2.2), which section 7.2.4 forbids on
     * the control stream. The server's real {@link H3ControlStream}
     * (registered by the real {@link HTTP3ServerHandler}) is the one
     * that detects this and must react.
     */
    @Test
    public void testMalformedControlStreamFrameClosesConnection() throws Exception {
        SelectorLoop loop = new SelectorLoop(0);
        loop.start();
        QuicEngine serverEngine = null;
        QuicEngine clientEngine = null;
        try {
            QuicTransportFactory serverFactory = new QuicTransportFactory();
            serverFactory.setApplicationProtocols("h3");
            serverFactory.setCertFile(certFile);
            serverFactory.setKeyFile(keyFile);
            serverFactory.start();

            final HTTPRequestHandlerFactory handlerFactory = new HTTPRequestHandlerFactory() {
                @Override
                public HTTPRequestHandler createHandler(HTTPResponseState state, Headers requestHeaders) {
                    return new DefaultHTTPRequestHandler();
                }
            };

            serverEngine = serverFactory.createServerEngine(
                    InetAddress.getLoopbackAddress(), 0,
                    new QuicEngine.ConnectionAcceptedHandler() {
                        @Override
                        public void connectionAccepted(QuicConnection connection) {
                            new HTTP3ServerHandler(connection, handlerFactory, null, null, null, false);
                        }
                    }, loop);

            int port = ((InetSocketAddress) serverEngine.getLocalAddress()).getPort();

            QuicTransportFactory clientFactory = new QuicTransportFactory();
            clientFactory.setApplicationProtocols("h3");
            clientFactory.setVerifyPeer(false);
            clientFactory.start();

            final CountDownLatch errorLatch = new CountDownLatch(1);
            final AtomicReference<Exception> clientStreamError = new AtomicReference<Exception>();

            clientEngine = clientFactory.connect(
                    InetAddress.getLoopbackAddress(), port,
                    new QuicEngine.ConnectionAcceptedHandler() {
                        @Override
                        public void connectionAccepted(QuicConnection connection) {
                            Endpoint stream = connection.openUnidirectionalStream(new ProtocolHandler() {
                                @Override
                                public void connected(Endpoint endpoint) {
                                }

                                @Override
                                public void receive(ByteBuffer data) {
                                }

                                @Override
                                public void securityEstablished(SecurityInfo info) {
                                }

                                @Override
                                public void disconnected() {
                                }

                                @Override
                                public void error(Exception cause) {
                                    clientStreamError.set(cause);
                                    errorLatch.countDown();
                                }
                            });

                            byte[] emptyFieldSection = new byte[0];
                            int length = H3Writer.streamTypeLength(0x00) + H3Writer.headersLength(emptyFieldSection.length);
                            ByteBuffer out = ByteBuffer.allocate(length);
                            H3Writer.writeStreamType(out, 0x00);
                            H3Writer.writeHeaders(out, emptyFieldSection);
                            out.flip();
                            stream.send(out);
                        }
                    }, loop, SERVER_NAME);

            assertTrue("Client should observe the connection close within 5s",
                    errorLatch.await(5, TimeUnit.SECONDS));

            Exception cause = clientStreamError.get();
            assertTrue("Cause should be a QuicConnectionCloseException, was: " + cause,
                    cause instanceof QuicConnectionCloseException);
            QuicConnectionCloseException qcce = (QuicConnectionCloseException) cause;
            assertTrue("Should be an application-level close", qcce.isApplicationError());
            assertEquals("RFC 9114 section 8.1 H3_FRAME_UNEXPECTED",
                    H3ErrorCode.H3_FRAME_UNEXPECTED, qcce.getErrorCode());
        } finally {
            loop.shutdown();
            loop.awaitQuiesce(2000);
            if (clientEngine != null) {
                clientEngine.close();
            }
            if (serverEngine != null) {
                serverEngine.close();
            }
        }
    }

    /**
     * Stage 10 Part 5 (docs/QUIC-AGENT15-MIGRATION-PLAN.md): proves the
     * actual {@link org.bluezoo.gumdrop.http.client.HTTPMethodSafety}
     * gating built into {@link H3Request}/{@link HTTP3ClientHandler}, not
     * just the lower-level {@link QuicConnection} 0-RTT mechanism already
     * proven by {@code QuicProductionEndToEndTest#testEarlyDataHandlerSendsBeforeHandshakeCompletes}.
     *
     * <p>A first connection captures a session ticket. A second connection
     * issues a GET and a POST immediately from {@link
     * QuicEngine.EarlyDataHandler#earlyDataReady}, both via real
     * {@link H3Request} objects -- the same class application code gets
     * back from {@link org.bluezoo.gumdrop.http.client.HTTPClient#request},
     * wired up exactly the way {@code HTTPClient.connectH3} wires it
     * (idempotent {@code connectionAccepted}, {@code runDeferredRequests}
     * once established). Both requests complete successfully, but only
     * the GET's stream should appear in the connection's own {@code
     * sentZeroRttStream} bookkeeping -- the POST must have been deferred
     * until the handshake was established (RFC 9001 section 4.6.1: 0-RTT
     * data has no anti-replay guarantee, so only safe/idempotent methods
     * may use it).
     */
    @Test
    public void testGetRidesZeroRttPostDefersUntilEstablished() throws Exception {
        SessionTicketCache.clear();
        SelectorLoop loop = new SelectorLoop(0);
        loop.start();
        QuicEngine serverEngine = null;
        QuicEngine firstClientEngine = null;
        QuicEngine secondClientEngine = null;
        try {
            QuicTransportFactory serverFactory = new QuicTransportFactory();
            serverFactory.setApplicationProtocols("h3");
            serverFactory.setCertFile(certFile);
            serverFactory.setKeyFile(keyFile);
            serverFactory.setEarlyDataEnabled(true);
            serverFactory.start();

            final HTTPRequestHandlerFactory handlerFactory = new HTTPRequestHandlerFactory() {
                @Override
                public HTTPRequestHandler createHandler(HTTPResponseState state, Headers requestHeaders) {
                    return new HTTPRequestHandler() {
                        @Override
                        public void headers(HTTPResponseState state, Headers headers) {
                            Headers response = new Headers();
                            response.add(":status", "200");
                            response.add("content-type", "text/plain");
                            state.headers(response);
                            state.startResponseBody();
                            state.responseBodyContent(
                                    ByteBuffer.wrap("ok".getBytes(StandardCharsets.US_ASCII)));
                            state.endResponseBody();
                            state.complete();
                        }

                        @Override
                        public void startRequestBody(HTTPResponseState state) {
                        }

                        @Override
                        public void requestBodyContent(HTTPResponseState state, ByteBuffer data) {
                        }

                        @Override
                        public void endRequestBody(HTTPResponseState state) {
                        }

                        @Override
                        public void requestComplete(HTTPResponseState state) {
                        }
                    };
                }
            };

            serverEngine = serverFactory.createServerEngine(
                    InetAddress.getLoopbackAddress(), 0,
                    new QuicEngine.ConnectionAcceptedHandler() {
                        @Override
                        public void connectionAccepted(QuicConnection connection) {
                            new HTTP3ServerHandler(connection, handlerFactory, null, null, null, false);
                        }
                    }, loop);
            int port = ((InetSocketAddress) serverEngine.getLocalAddress()).getPort();

            // First connection: an ordinary handshake, just to make the
            // server issue a session ticket (see
            // QuicProductionEndToEndTest#testSessionTicketCapturedAfterHandshake).
            QuicTransportFactory firstClientFactory = new QuicTransportFactory();
            firstClientFactory.setApplicationProtocols("h3");
            firstClientFactory.setVerifyPeer(false);
            firstClientFactory.start();

            final CountDownLatch warmupLatch = new CountDownLatch(1);
            final AtomicReference<Exception> warmupFailure = new AtomicReference<Exception>();
            firstClientEngine = firstClientFactory.connect(
                    InetAddress.getLoopbackAddress(), port,
                    new QuicEngine.ConnectionAcceptedHandler() {
                        @Override
                        public void connectionAccepted(QuicConnection connection) {
                            HTTP3ClientHandler h3 = new HTTP3ClientHandler(connection);
                            Headers requestHeaders = new Headers();
                            requestHeaders.add(":method", "GET");
                            requestHeaders.add(":scheme", "https");
                            requestHeaders.add(":authority", SERVER_NAME);
                            requestHeaders.add(":path", "/warmup");
                            h3.sendRequest(requestHeaders,
                                    new LatchResponseHandler(warmupLatch, warmupFailure), true);
                        }
                    }, loop, SERVER_NAME);
            assertTrue("Warm-up request should complete within 5s", warmupLatch.await(5, TimeUnit.SECONDS));
            if (warmupFailure.get() != null) {
                throw warmupFailure.get();
            }

            SessionTicketCache.Entry entry = null;
            long deadline = System.currentTimeMillis() + 3000;
            while (entry == null && System.currentTimeMillis() < deadline) {
                entry = SessionTicketCache.get(SERVER_NAME, port);
                if (entry == null) {
                    Thread.sleep(50);
                }
            }
            assertNotNull("A session ticket should have been cached after the warm-up handshake", entry);

            // Second connection: 0-RTT-enabled, issuing a GET and a POST
            // immediately from earlyDataReady -- exactly mirroring how
            // HTTPClient.connectH3 wires this up.
            QuicTransportFactory secondClientFactory = new QuicTransportFactory();
            secondClientFactory.setApplicationProtocols("h3");
            secondClientFactory.setVerifyPeer(false);
            secondClientFactory.setEarlyDataEnabled(true);
            secondClientFactory.start();

            final AtomicReference<HTTP3ClientHandler> h3HandlerRef = new AtomicReference<HTTP3ClientHandler>();
            final AtomicReference<QuicConnection> secondConnectionRef = new AtomicReference<QuicConnection>();
            final AtomicReference<H3Request> getRequestRef = new AtomicReference<H3Request>();
            final AtomicReference<H3Request> postRequestRef = new AtomicReference<H3Request>();
            final CountDownLatch getLatch = new CountDownLatch(1);
            final CountDownLatch postLatch = new CountDownLatch(1);
            final AtomicReference<Exception> getFailure = new AtomicReference<Exception>();
            final AtomicReference<Exception> postFailure = new AtomicReference<Exception>();

            secondClientEngine = secondClientFactory.connect(
                    InetAddress.getLoopbackAddress(), port,
                    new QuicEngine.ConnectionAcceptedHandler() {
                        @Override
                        public void connectionAccepted(QuicConnection connection) {
                            secondConnectionRef.set(connection);
                            if (h3HandlerRef.get() == null) {
                                h3HandlerRef.set(new HTTP3ClientHandler(connection));
                            } else {
                                h3HandlerRef.get().runDeferredRequests();
                            }
                        }
                    },
                    new QuicEngine.EarlyDataHandler() {
                        @Override
                        public void earlyDataReady(final QuicConnection connection) {
                            HTTP3ClientHandler h3 = new HTTP3ClientHandler(connection);
                            h3HandlerRef.set(h3);

                            H3Request getRequest = new H3Request(h3, "GET", "/get", SERVER_NAME, "https", null);
                            getRequestRef.set(getRequest);
                            getRequest.send(new LatchResponseHandler(getLatch, getFailure));

                            H3Request postRequest = new H3Request(h3, "POST", "/post", SERVER_NAME, "https", null);
                            postRequestRef.set(postRequest);
                            postRequest.send(new LatchResponseHandler(postLatch, postFailure));
                        }
                    },
                    loop, SERVER_NAME);

            assertTrue("GET should complete within 5s", getLatch.await(5, TimeUnit.SECONDS));
            assertTrue("POST should complete within 5s", postLatch.await(5, TimeUnit.SECONDS));
            if (getFailure.get() != null) {
                throw getFailure.get();
            }
            if (postFailure.get() != null) {
                throw postFailure.get();
            }

            QuicConnection secondConnection = secondConnectionRef.get();
            assertNotNull(secondConnection);
            Object zeroRttState = getPrivateField(secondConnection, "zeroRttState", Object.class);
            assertEquals("ACCEPTED", zeroRttState.toString());

            long getStreamId = getPrivateField(getRequestRef.get(), "streamId", Long.class).longValue();
            long postStreamId = getPrivateField(postRequestRef.get(), "streamId", Long.class).longValue();

            @SuppressWarnings("unchecked")
            Map<Long, Map<Long, ?>> sentZeroRttStream =
                    getPrivateField(secondConnection, "sentZeroRttStream", Map.class);
            boolean getWasZeroRtt = false;
            boolean postWasZeroRtt = false;
            for (Map<Long, ?> perPacketStreams : sentZeroRttStream.values()) {
                if (perPacketStreams.containsKey(Long.valueOf(getStreamId))) {
                    getWasZeroRtt = true;
                }
                if (perPacketStreams.containsKey(Long.valueOf(postStreamId))) {
                    postWasZeroRtt = true;
                }
            }
            assertTrue("GET (0-RTT-eligible) should have been sent as 0-RTT data", getWasZeroRtt);
            assertFalse("POST (not 0-RTT-eligible) must not have been sent as 0-RTT data", postWasZeroRtt);
        } finally {
            loop.shutdown();
            loop.awaitQuiesce(2000);
            if (secondClientEngine != null) {
                secondClientEngine.close();
            }
            if (firstClientEngine != null) {
                firstClientEngine.close();
            }
            if (serverEngine != null) {
                serverEngine.close();
            }
            SessionTicketCache.clear();
        }
    }

    /**
     * RFC 9220 section 3 / RFC 8441 section 4: a client must not attempt
     * Extended CONNECT before it knows the peer advertised
     * {@code SETTINGS_ENABLE_CONNECT_PROTOCOL = 1} -- {@link
     * HTTP3ClientHandler#connectWebSocket} used to have no such gate at
     * all, sending the request unconditionally the moment it was called.
     *
     * <p>Calls {@code connectWebSocket} synchronously from inside the
     * client's own {@code connectionAccepted} callback -- the earliest
     * possible moment, guaranteed to be before the peer's own control
     * stream/SETTINGS frame can have arrived (that requires the peer to
     * receive this side's handshake completion and then send its own
     * data, at least one further round trip away) -- and asserts both
     * that this race was genuine (the private {@code
     * initialSettingsReceived} flag really was still false at the moment
     * of the call, not accidentally already resolved) and that the
     * WebSocket still opens successfully once the peer's real SETTINGS
     * arrive, proving the call was queued and replayed rather than sent
     * blind or silently dropped.
     */
    @Test
    public void testConnectWebSocketDefersUntilPeerSettingsKnown() throws Exception {
        SelectorLoop loop = new SelectorLoop(0);
        loop.start();
        QuicEngine serverEngine = null;
        QuicEngine clientEngine = null;
        try {
            QuicTransportFactory serverFactory = new QuicTransportFactory();
            serverFactory.setApplicationProtocols("h3");
            serverFactory.setCertFile(certFile);
            serverFactory.setKeyFile(keyFile);
            serverFactory.start();

            final CountDownLatch serverOpened = new CountDownLatch(1);

            HTTPRequestHandlerFactory handlerFactory = new HTTPRequestHandlerFactory() {
                @Override
                public HTTPRequestHandler createHandler(HTTPResponseState state, Headers requestHeaders) {
                    return new DefaultHTTPRequestHandler() {
                        @Override
                        public void headers(HTTPResponseState state, Headers headers) {
                            if ("CONNECT".equals(headers.getValue(":method"))
                                    && "websocket".equalsIgnoreCase(headers.getValue(":protocol"))) {
                                state.upgradeToWebSocket(null, new org.bluezoo.gumdrop.websocket.DefaultWebSocketEventHandler() {
                                    @Override
                                    public void opened(org.bluezoo.gumdrop.websocket.WebSocketSession session) {
                                        serverOpened.countDown();
                                    }
                                });
                            }
                        }
                    };
                }
            };

            serverEngine = serverFactory.createServerEngine(
                    InetAddress.getLoopbackAddress(), 0,
                    new QuicEngine.ConnectionAcceptedHandler() {
                        @Override
                        public void connectionAccepted(QuicConnection connection) {
                            new HTTP3ServerHandler(connection, handlerFactory, null, null, null, false);
                        }
                    }, loop);

            int port = ((InetSocketAddress) serverEngine.getLocalAddress()).getPort();

            QuicTransportFactory clientFactory = new QuicTransportFactory();
            clientFactory.setApplicationProtocols("h3");
            clientFactory.setVerifyPeer(false);
            clientFactory.start();

            final CountDownLatch clientOpened = new CountDownLatch(1);
            final AtomicReference<Boolean> settingsAlreadyKnownAtCallTime = new AtomicReference<Boolean>();
            final AtomicReference<Long> connectWebSocketReturnValue = new AtomicReference<Long>();
            final AtomicReference<Throwable> wsError = new AtomicReference<Throwable>();

            clientEngine = clientFactory.connect(
                    InetAddress.getLoopbackAddress(), port,
                    new QuicEngine.ConnectionAcceptedHandler() {
                        @Override
                        public void connectionAccepted(QuicConnection connection) {
                            HTTP3ClientHandler h3 = new HTTP3ClientHandler(connection);
                            try {
                                settingsAlreadyKnownAtCallTime.set(
                                        getPrivateField(h3, "initialSettingsReceived", Boolean.class));
                            } catch (Exception e) {
                                fail("reflection failed: " + e);
                            }
                            long streamId = h3.connectWebSocket(SERVER_NAME, "/ws", null, null,
                                    new org.bluezoo.gumdrop.websocket.DefaultWebSocketEventHandler() {
                                        @Override
                                        public void opened(org.bluezoo.gumdrop.websocket.WebSocketSession session) {
                                            clientOpened.countDown();
                                        }

                                        @Override
                                        public void error(Throwable cause) {
                                            wsError.set(cause);
                                            clientOpened.countDown();
                                        }
                                    });
                            connectWebSocketReturnValue.set(streamId);
                        }
                    }, loop, SERVER_NAME);

            assertTrue("Server should have completed the WebSocket upgrade within 5s",
                    serverOpened.await(5, TimeUnit.SECONDS));
            assertTrue("Client should have received its own opened() callback within 5s",
                    clientOpened.await(5, TimeUnit.SECONDS));
            assertNull("connectWebSocket must not have errored", wsError.get());
            assertFalse("connectWebSocket was called before the peer's SETTINGS frame could "
                    + "possibly have arrived -- this test is only meaningful if that race is "
                    + "genuine, not accidentally already resolved by the time of the call",
                    Boolean.TRUE.equals(settingsAlreadyKnownAtCallTime.get()));
            // The distinguishing assertion: since the peer's support wasn't
            // yet known, connectWebSocket must have deferred rather than
            // opened a stream synchronously -- its synchronous return value
            // is -1 either way (see its own javadoc), but only the deferred
            // path returns -1 *without* ever having called
            // quicConnection.openStream(...) at all, which is what actually
            // matters for RFC 8441 section 4's "must not send before
            // knowing" requirement. Confirmed by reverting the gate locally
            // and re-running this test: without it, connectWebSocket runs to
            // completion synchronously and returns a real (non-negative)
            // stream ID here instead.
            assertEquals("connectWebSocket must defer (return -1) when called before the "
                    + "peer's SETTINGS frame has arrived, not open a stream immediately",
                    -1L, connectWebSocketReturnValue.get().longValue());
        } finally {
            loop.shutdown();
            loop.awaitQuiesce(2000);
            if (clientEngine != null) {
                clientEngine.close();
            }
            if (serverEngine != null) {
                serverEngine.close();
            }
        }
    }

    private static <T> T getPrivateField(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    /** Counts down a latch on completion (success or failure), recording any failure. */
    private static final class LatchResponseHandler implements HTTPResponseHandler {
        private final CountDownLatch latch;
        private final AtomicReference<Exception> failure;

        LatchResponseHandler(CountDownLatch latch, AtomicReference<Exception> failure) {
            this.latch = latch;
            this.failure = failure;
        }

        @Override
        public void ok(HTTPResponse response) {
        }

        @Override
        public void error(HTTPResponse response) {
            failure.set(new IOException("Unexpected error status: " + response.getStatus()));
            latch.countDown();
        }

        @Override
        public void header(String name, String value) {
        }

        @Override
        public void startResponseBody() {
        }

        @Override
        public void responseBodyContent(ByteBuffer data) {
        }

        @Override
        public void endResponseBody() {
        }

        @Override
        public void pushPromise(PushPromise promise) {
        }

        @Override
        public void close() {
            latch.countDown();
        }

        @Override
        public void failed(Exception ex) {
            failure.set(ex);
            latch.countDown();
        }
    }
}
