/*
 * HttpStreamHandlerConcurrentTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.http;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.client.DefaultHttpResponseHandler;
import org.bluezoo.gumdrop.http.client.HttpClientHandler;
import org.bluezoo.gumdrop.http.client.HttpRequest;
import org.bluezoo.gumdrop.http.client.HttpResponse;
import org.bluezoo.gumdrop.http.server.DefaultHttpRequestHandler;
import org.bluezoo.gumdrop.http.server.Http2Listener;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponseState;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies per-stream {@link org.bluezoo.gumdrop.http.server.HttpRequestHandler}
 * binding under concurrent HTTP/2 streams.
 */
public class HttpStreamHandlerConcurrentTest {

    private static final String TEST_HOST = "127.0.0.1";
    private static final AtomicInteger NEXT_PORT = new AtomicInteger(48300);

    private Gumdrop gumdrop;
    private HttpServer server;
    private int testPort;

    @Before
    public void setUp() throws Exception {
        testPort = NEXT_PORT.getAndIncrement();
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(2));

        server = HttpServer.compose()
                .listener(new Http2Listener()
                        .port(testPort)
                        .addresses(InetAddress.ofLiteral(TEST_HOST)))
                .streamHandler(new AccumulatingStreamHandler())
                .server();

        gumdrop.addServer(server);
    }

    @After
    public void tearDown() throws Exception {
        if (gumdrop != null) {
            if (server != null) {
                gumdrop.removeServer(server);
            }
            gumdrop.shutdown();
            gumdrop.join();
        }
    }

    @Test
    public void concurrentStreamsDoNotCrossContaminateState() throws Exception {
        HttpClient client = connectH2(gumdrop, testPort);

        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<String> bodyA = new AtomicReference<String>();
        AtomicReference<String> bodyB = new AtomicReference<String>();
        AtomicReference<Exception> error = new AtomicReference<Exception>();

        post(client, "/a", "aaa", bodyA, error, done);
        post(client, "/b", "bbb", bodyB, error, done);

        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertNull(error.get());
        assertEquals("/a:aaa", bodyA.get());
        assertEquals("/b:bbb", bodyB.get());
    }

    private static HttpClient connectH2(Gumdrop gumdrop, int port) throws Exception {
        HttpClient client = new HttpClient(TEST_HOST, port);
        client.setAltSvcEnabled(false);
        client.setH2WithPriorKnowledge(true);
        client.setH2cUpgradeEnabled(false);

        CountDownLatch connected = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<Exception>();

        client.connect(gumdrop, new HttpClientHandler() {
            @Override
            public void onConnected(Endpoint endpoint) {
                connected.countDown();
            }

            @Override
            public void onSecurityEstablished(SecurityInfo info) {
            }

            @Override
            public void onError(Exception cause) {
                error.set(cause);
                connected.countDown();
            }

            @Override
            public void onDisconnected() {
            }
        });

        assertTrue(connected.await(5, TimeUnit.SECONDS));
        assertNull(error.get());

        // With prior knowledge, HttpClientProtocolHandler.connected() sets
        // negotiatedVersion to HTTP_2_0 synchronously before it calls
        // onConnected() (which counts down the latch above), so the
        // version is already settled by the time await() returns -- no
        // need to poll for it.
        assertEquals(HttpVersion.HTTP_2_0, client.getVersion());
        return client;
    }

    private static void post(HttpClient client, String path, String payload,
                             AtomicReference<String> bodyOut,
                             AtomicReference<Exception> error,
                             CountDownLatch done) {
        HttpRequest request = client.post(path);
        request.header("Content-Length", String.valueOf(payload.length()));
        request.startRequestBody(new DefaultHttpResponseHandler() {
            private final StringBuilder buffer = new StringBuilder();

            @Override
            public void ok(HttpResponse response) {
            }

            @Override
            public void responseBodyContent(ByteBuffer data) {
                byte[] chunk = new byte[data.remaining()];
                data.get(chunk);
                buffer.append(new String(chunk, StandardCharsets.UTF_8));
            }

            @Override
            public void close() {
                bodyOut.set(buffer.toString());
                done.countDown();
            }

            @Override
            public void failed(Exception ex) {
                error.set(ex);
                done.countDown();
            }
        });
        request.requestBodyContent(
                ByteBuffer.wrap(payload.getBytes(StandardCharsets.UTF_8)));
        request.endRequestBody();
    }

    private static final class AccumulatingStreamHandler implements HttpStreamHandler {
        @Override
        public HttpRequestHandler openStream(HttpResponseState stream) {
            return new AccumulatingHandler();
        }
    }

    private static final class AccumulatingHandler extends DefaultHttpRequestHandler {

        private final StringBuilder body = new StringBuilder();
        private String path;

        @Override
        public void headers(HttpResponseState state, Headers headers) {
            path = headers.getPath();
        }

        @Override
        public void requestBodyContent(HttpResponseState state, ByteBuffer data) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            body.append(new String(chunk, StandardCharsets.UTF_8));
        }

        @Override
        public void endRequestBody(HttpResponseState state) {
            Headers response = new Headers();
            response.add(":status", "200");
            response.add("content-type", "text/plain");
            state.headers(response);
            state.startResponseBody();
            String payload = path + ":" + body;
            state.responseBodyContent(
                    ByteBuffer.wrap(payload.getBytes(StandardCharsets.UTF_8)));
            state.endResponseBody();
            state.complete();
        }
    }

}
