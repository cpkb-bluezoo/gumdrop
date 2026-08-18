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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.QuicConnectionCloseException;
import org.bluezoo.gumdrop.quic.QuicEngine;
import org.bluezoo.gumdrop.quic.QuicTransportFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Drives a real HTTP/3 GET request/response over a real client+server QUIC
 * connection, purely through the production classes ({@link
 * QuicTransportFactory}, {@link QuicEngine}, {@link HTTP3ServerHandler},
 * {@link H3Stream}, {@link HTTP3ClientHandler}, {@link H3ClientStream}) --
 * no quiche, no hand-called test harness. Proves the Stage 3 H3 rewire end
 * to end: HTTP/3 framing ({@link H3Parser}/{@link H3Writer}), QPACK
 * (static-table {@link org.bluezoo.gumdrop.http.qpack.SimpleEncoder}/
 * {@link org.bluezoo.gumdrop.http.qpack.SimpleDecoder}), the control
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
            assertEquals("RFC 9114 section 8.1 H3_FRAME_UNEXPECTED", 0x105L, qcce.getErrorCode());
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
}
