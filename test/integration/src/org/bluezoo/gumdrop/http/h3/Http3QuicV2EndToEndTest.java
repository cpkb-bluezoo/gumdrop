/*
 * Http3QuicV2EndToEndTest.java
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.BeforeClass;
import org.junit.Test;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TestTlsFiles;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.client.HttpResponse;
import org.bluezoo.gumdrop.http.client.HttpResponseHandler;
import org.bluezoo.gumdrop.http.client.PushPromise;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponseState;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.QuicEngine;
import org.bluezoo.gumdrop.quic.QuicTransportFactory;
import org.bluezoo.gumdrop.quic.packet.QuicVersion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * HTTP/3 (RFC 9114) over QUIC version 2 (RFC 9369): a GET with a body
 * of tens of kilobytes, with both endpoints on version 2, with the server
 * switching a version 1 first flight to version 2 (RFC 9368 section 2.3),
 * and with the client following a Version Negotiation packet to version 2
 * (RFC 9368 section 2.1). ALPN {@code h3} is unchanged on version 2 (RFC
 * 9369 section 7).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Http3QuicV2EndToEndTest {

    // Larger than one QUIC packet's worth, but small enough that everything
    // queued fits one loopback UDP datagram: the send path does not yet
    // split queued stream data to the datagram size.
    private static final int BODY_LENGTH = 40000;
    private static final int CHUNK = 8192;

    @BeforeClass
    public static void requireTlsFiles() {
        TestTlsFiles.assumeAvailable();
    }

    private static String bodyOf(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('a' + (i % 26)));
        }
        return sb.toString();
    }

    /** Runs a GET and returns the QUIC version the client connection used. */
    private static QuicVersion get(String serverVersions, String clientVersions) throws Exception {
        SelectorLoop loop = new SelectorLoop(0);
        loop.start();
        QuicEngine serverEngine = null;
        QuicEngine clientEngine = null;
        try {
            QuicTransportFactory serverFactory = new QuicTransportFactory();
            serverFactory.setApplicationProtocols("h3");
            serverFactory.setCertFile(TestTlsFiles.certFile());
            serverFactory.setKeyFile(TestTlsFiles.keyFile());
            serverFactory.setVersions(serverVersions);
            serverFactory.start();

            final byte[] responseBody = bodyOf(BODY_LENGTH).getBytes(StandardCharsets.US_ASCII);
            final HttpStreamHandler streamHandler = new HttpStreamHandler() {
                @Override
                public HttpRequestHandler openStream(HttpResponseState state) {
                    return new HttpRequestHandler() {
                        @Override
                        public void headers(HttpResponseState state, Headers headers) {
                            Headers response = new Headers();
                            response.add(":status", "200");
                            response.add("content-type", "text/plain");
                            state.headers(response);
                            state.startResponseBody();
                            for (int off = 0; off < responseBody.length; off += CHUNK) {
                                int n = Math.min(CHUNK, responseBody.length - off);
                                state.responseBodyContent(ByteBuffer.wrap(responseBody, off, n));
                            }
                            state.endResponseBody();
                            state.complete();
                        }

                        @Override
                        public void startRequestBody(HttpResponseState state) {
                        }

                        @Override
                        public void requestBodyContent(HttpResponseState state, ByteBuffer data) {
                        }

                        @Override
                        public void endRequestBody(HttpResponseState state) {
                        }

                        @Override
                        public void requestComplete(HttpResponseState state) {
                        }
                    };
                }
            };
            serverEngine = serverFactory.createServerEngine(InetAddress.getLoopbackAddress(), 0,
                    new QuicEngine.ConnectionAcceptedHandler() {
                        @Override
                        public void connectionAccepted(QuicConnection connection) {
                            new Http3ServerHandler(connection, streamHandler, null, null, null, false, false);
                        }
                    }, loop);
            int port = ((InetSocketAddress) serverEngine.getLocalAddress()).getPort();

            QuicTransportFactory clientFactory = new QuicTransportFactory();
            clientFactory.setApplicationProtocols("h3");
            clientFactory.setVerifyPeer(false);
            clientFactory.setVerifyHostname(false);
            clientFactory.setVersions(clientVersions);
            clientFactory.start();

            final CountDownLatch done = new CountDownLatch(1);
            final AtomicReference<HttpResponse> okResponse = new AtomicReference<HttpResponse>();
            final AtomicReference<Exception> failure = new AtomicReference<Exception>();
            final AtomicReference<String> body = new AtomicReference<String>();
            final AtomicReference<QuicVersion> version = new AtomicReference<QuicVersion>();

            clientEngine = clientFactory.connect(InetAddress.getLoopbackAddress(), port,
                    new QuicEngine.ConnectionAcceptedHandler() {
                        @Override
                        public void connectionAccepted(QuicConnection connection) {
                            version.set(connection.getVersion());
                            Http3ClientHandler h3 = new Http3ClientHandler(connection);
                            Headers requestHeaders = new Headers();
                            requestHeaders.add(":method", "GET");
                            requestHeaders.add(":scheme", "https");
                            requestHeaders.add(":authority", TestTlsFiles.SERVER_NAME);
                            requestHeaders.add(":path", "/");
                            h3.sendRequest(requestHeaders, new HttpResponseHandler() {
                                private final StringBuilder buf = new StringBuilder();

                                @Override
                                public void ok(HttpResponse response) {
                                    okResponse.set(response);
                                }

                                @Override
                                public void error(HttpResponse response) {
                                    failure.set(new IOException("Unexpected status: " + response.getStatus()));
                                    done.countDown();
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
                                    done.countDown();
                                }

                                @Override
                                public void failed(Exception ex) {
                                    failure.set(ex);
                                    done.countDown();
                                }
                            }, true);
                        }
                    }, loop, TestTlsFiles.SERVER_NAME);

            assertTrue("response must complete within 20s", done.await(20, TimeUnit.SECONDS));
            if (failure.get() != null) {
                throw failure.get();
            }
            assertEquals(HttpStatus.OK, okResponse.get().getStatus());
            assertEquals(bodyOf(BODY_LENGTH), body.get());
            return version.get();
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

    @Test
    public void testGetOverVersionTwoOnly() throws Exception {
        assertEquals(QuicVersion.V2, get("2", "2"));
    }

    @Test
    public void testGetWhenServerSwitchesVersionOneFirstFlightToVersionTwo() throws Exception {
        assertEquals(QuicVersion.V2, get("1,2", "2,1"));
    }

    @Test
    public void testGetAfterVersionNegotiationToVersionTwo() throws Exception {
        assertEquals(QuicVersion.V2, get("2", "1,2"));
    }

    @Test
    public void testGetOverVersionOneStillWorks() throws Exception {
        assertEquals(QuicVersion.V1, get("1,2", "1,2"));
    }
}
