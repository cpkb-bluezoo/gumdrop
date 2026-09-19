/*
 * HttpServerRouterIntegrationTest.java
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
import org.bluezoo.gumdrop.http.server.NotFoundHttpRequestHandler;
import org.bluezoo.gumdrop.http.Headers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Path dispatch belongs in {@link HttpRequestHandler}, not the HTTP protocol SPI.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpServerRouterIntegrationTest {

    private static final String TEST_HOST = "127.0.0.1";
    private static final AtomicInteger NEXT_PORT = new AtomicInteger(48200);

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
                .streamHandler(new PathDispatchStreamHandler())
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
    public void testPathDispatchInRequestHandler() throws Exception {
        HttpClient client = connect(gumdrop, testPort);
        assertStatus(client.get("/api"), 200);
        assertStatus(client.get("/missing"), 404);
    }

    private static void assertStatus(HttpRequest request, int expectedStatus)
            throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Integer> statusRef = new AtomicReference<Integer>();
        AtomicReference<Exception> errorRef = new AtomicReference<Exception>();

        request.send(new DefaultHttpResponseHandler() {
            @Override
            public void ok(HttpResponse response) {
                statusRef.set(Integer.valueOf(response.getStatus().code));
                latch.countDown();
            }

            @Override
            public void error(HttpResponse response) {
                statusRef.set(Integer.valueOf(response.getStatus().code));
                latch.countDown();
            }

            @Override
            public void failed(Exception cause) {
                errorRef.set(cause);
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNull(errorRef.get());
        assertEquals(Integer.valueOf(expectedStatus), statusRef.get());
    }

    private static HttpClient connect(Gumdrop gumdrop, int port) throws Exception {
        HttpClient client = new HttpClient(TEST_HOST, port);
        client.setAltSvcEnabled(false);
        client.setH2Enabled(false);
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
        return client;
    }

    private static final class PathDispatchStreamHandler implements HttpStreamHandler {
        @Override
        public HttpRequestHandler openStream(HttpResponseState stream) {
            return new PathDispatchHandler();
        }
    }

    private static final class PathDispatchHandler extends DefaultHttpRequestHandler {
        @Override
        public void headers(HttpResponseState state, Headers headers) {
            if ("/api".equals(headers.getPath())) {
                Headers response = new Headers();
                response.add(":status", "200");
                state.headers(response);
                state.complete();
                return;
            }
            NotFoundHttpRequestHandler.INSTANCE.headers(state, headers);
        }
    }

}
