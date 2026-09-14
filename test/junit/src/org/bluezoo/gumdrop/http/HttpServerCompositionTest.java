/*
 * HttpServerCompositionTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.http;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Workstream C.3 — {@link HttpServer#compose()} composition API.
 */
public class HttpServerCompositionTest {

    private static final String TEST_HOST = "127.0.0.1";
    private static final AtomicInteger NEXT_PORT = new AtomicInteger(48100);

    private Gumdrop gumdrop;
    private HttpServer server;
    private int testPort;

    @Before
    public void setUp() throws Exception {
        testPort = NEXT_PORT.getAndIncrement();
        System.setProperty("gumdrop.workers", "2");
        gumdrop = Gumdrop.getInstance();
        if (gumdrop.isStarted()) {
            gumdrop.shutdown();
            gumdrop.join();
            gumdrop = Gumdrop.getInstance();
        }

        server = HttpServer.compose()
                .listener(new Http2Listener()
                        .port(testPort)
                        .addresses(InetAddress.ofLiteral(TEST_HOST)))
                .streamHandler(new HelloStreamHandler())
                .server();

        gumdrop.addServer(server);
        gumdrop.start();
    }

    @After
    public void tearDown() throws Exception {
        if (gumdrop != null) {
            if (server != null) {
                gumdrop.removeServer(server);
                server = null;
            }
            gumdrop.shutdown();
            gumdrop.join();
        }
    }

    @Test
    public void testBuilderWiresHandlerToListener() throws Exception {
        HttpClient client = connect(testPort);
        HttpRequest request = client.get("/hello");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HttpResponse> responseRef = new AtomicReference<HttpResponse>();
        ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();
        AtomicReference<Exception> errorRef = new AtomicReference<Exception>();

        request.send(new DefaultHttpResponseHandler() {
            @Override
            public void ok(HttpResponse response) {
                responseRef.set(response);
            }

            @Override
            public void responseBodyContent(ByteBuffer data) {
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                try {
                    bodyBuffer.write(bytes);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void close() {
                latch.countDown();
            }

            @Override
            public void failed(Exception cause) {
                errorRef.set(cause);
                latch.countDown();
            }
        });

        assertTrue("response not received", latch.await(5, TimeUnit.SECONDS));
        assertNull(errorRef.get());
        HttpResponse response = responseRef.get();
        assertNotNull(response);
        assertEquals(200, response.getStatus().code);
        assertEquals("Hello, World!",
                new String(bodyBuffer.toByteArray(), StandardCharsets.UTF_8));
    }

    private static HttpClient connect(int port) throws Exception {
        HttpClient client = new HttpClient(TEST_HOST, port);
        client.setAltSvcEnabled(false);
        client.setH2Enabled(false);
        client.setH2cUpgradeEnabled(false);

        CountDownLatch connected = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<Exception>();

        client.connect(new HttpClientHandler() {
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
        return client;
    }

    private static final class HelloStreamHandler implements HttpStreamHandler {
        @Override
        public HttpRequestHandler openStream(HttpResponseState stream) {
            return new HelloHandler();
        }
    }

    private static final class HelloHandler extends DefaultHttpRequestHandler {
        @Override
        public void headers(HttpResponseState state, Headers headers) {
            Headers response = new Headers();
            response.add(":status", "200");
            response.add("content-type", "text/plain");
            state.headers(response);
            state.startResponseBody();
            state.responseBodyContent(
                    ByteBuffer.wrap("Hello, World!".getBytes(StandardCharsets.UTF_8)));
            state.endResponseBody();
            state.complete();
        }
    }

}
