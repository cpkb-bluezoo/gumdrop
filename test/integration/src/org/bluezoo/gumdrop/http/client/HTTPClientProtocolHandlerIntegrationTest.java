/*
 * HTTPClientProtocolHandlerIntegrationTest.java
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

import org.bluezoo.gumdrop.AbstractServerIntegrationTest;
import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.TestTlsFiles;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpServer;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.http.server.DefaultHttpRequestHandler;
import org.bluezoo.gumdrop.http.server.Http2Listener;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponseState;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests for {@link HttpClientProtocolHandler} behaviours that need a
 * real server but are not covered by {@link HTTPClientIntegrationTest}'s echo suite:
 * HTTP Basic authentication retry, additional methods, and Alt-Svc discovery.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPClientProtocolHandlerIntegrationTest extends AbstractServerIntegrationTest {

    private static final int HTTP_PORT = 18109;
    private static final int HTTPS_PORT = 18110;
    private static final String TEST_HOST = "::1";
    private static final int TIMEOUT_SECONDS = 8;

    private static final String AUTH_USER = "alice";
    private static final String AUTH_PASS = "s3cret";

    @Rule
    public Timeout globalTimeout = Timeout.builder()
            .withTimeout(TIMEOUT_SECONDS * 3L, TimeUnit.SECONDS)
            .withLookingForStuckThread(true)
            .build();

    @Override
    protected Collection<? extends Server> buildServers() throws Exception {
        HttpServer plaintext = HttpServer.compose()
                .listener(new Http2Listener()
                        .port(HTTP_PORT)
                        .addresses(InetAddress.getByName(TEST_HOST)))
                .streamHandler(new FeaturesHandlerFactory())
                .server();
        HttpServer secure = HttpServer.compose()
                .listener(new Http2Listener()
                        .port(HTTPS_PORT)
                        .addresses(InetAddress.getByName(TEST_HOST))
                        .secure(true)
                        .tls(TestTlsFiles.serverTlsConfig()))
                .streamHandler(new FeaturesHandlerFactory())
                .server();
        return Arrays.asList(plaintext, secure);
    }

    @Override
    protected Level getTestLogLevel() {
        return Level.WARNING;
    }

    @BeforeClass
    public static void requireTlsFixtures() {
        TestTlsFiles.assumeAvailable();
    }

    @Test
    public void testBasicAuthRetryOverHttp11() throws Exception {
        ResponseCapture cap = exchange(HTTP_PORT, false, true, "GET", "/protected", null,
                AUTH_USER, AUTH_PASS, null);
        assertEquals(HttpVersion.HTTP_1_1, cap.version);
        assertEquals(HttpStatus.OK, cap.status);
        assertTrue(cap.body.contains("authorized"));
    }

    @Test
    public void testBasicAuthRetryOverHttp2Tls() throws Exception {
        ResponseCapture cap = exchange(HTTPS_PORT, true, false, "GET", "/protected", null,
                AUTH_USER, AUTH_PASS, null);
        assertEquals(HttpVersion.HTTP_2_0, cap.version);
        assertEquals(HttpStatus.OK, cap.status);
    }

    @Test
    public void testOptionsAndDeleteMethods() throws Exception {
        ResponseCapture options = exchange(HTTP_PORT, false, true, "OPTIONS", "/any", null,
                null, null, null);
        assertEquals(HttpStatus.OK, options.status);
        assertTrue(options.body.contains("OPTIONS"));

        ResponseCapture del = exchange(HTTP_PORT, false, true, "DELETE", "/resource/1", null,
                null, null, null);
        assertEquals(HttpStatus.OK, del.status);
        assertTrue(del.body.contains("DELETE"));
    }

    @Test
    public void testPatchWithBody() throws Exception {
        String patchBody = "{\"op\":\"replace\"}";
        ResponseCapture cap = exchange(HTTP_PORT, false, true, "PATCH", "/doc", patchBody,
                null, null, null);
        assertEquals(HttpStatus.OK, cap.status);
        assertTrue(cap.body.contains(patchBody));
    }

    @Test
    public void testAltSvcHeaderInvokesListener() throws Exception {
        final AtomicReference<String> altSvc = new AtomicReference<String>();
        HttpClientProtocolHandler client = createClient(HTTP_PORT, false, true);
        client.setAltSvcListener(new AltSvcListener() {
            @Override
            public void altSvcReceived(String value) {
                altSvc.set(value);
            }
        });

        ResponseCapture cap = send(client, "GET", "/alt-svc", null, null, null);
        client.close();

        assertEquals(HttpStatus.OK, cap.status);
        assertNotNull(altSvc.get());
        assertTrue(altSvc.get().contains("h3="));
    }

    private static final class ResponseCapture {
        HttpVersion version;
        HttpStatus status;
        String body;
    }

    private ResponseCapture exchange(int port, boolean secure, boolean forceHttp11,
            String method, String path, String body,
            String user, String pass, AltSvcListener altSvcListener) throws Exception {
        HttpClientProtocolHandler client = createClient(port, secure, forceHttp11);
        if (user != null) {
            client.credentials(user, pass);
        }
        if (altSvcListener != null) {
            client.setAltSvcListener(altSvcListener);
        }
        try {
            return send(client, method, path, body, user, pass);
        } finally {
            client.close();
        }
    }

    private ResponseCapture send(HttpClientProtocolHandler client, String method, String path,
            String body, String user, String pass) throws Exception {
        ResponseCapture result = new ResponseCapture();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<Exception>();
        ByteArrayOutputStream bodyOut = new ByteArrayOutputStream();

        DefaultHttpResponseHandler handler = new DefaultHttpResponseHandler() {
            @Override
            public void ok(HttpResponse response) {
                result.status = response.getStatus();
            }

            @Override
            public void error(HttpResponse response) {
                result.status = response.getStatus();
            }

            @Override
            public void responseBodyContent(ByteBuffer data) {
                byte[] chunk = new byte[data.remaining()];
                data.get(chunk);
                bodyOut.write(chunk, 0, chunk.length);
            }

            @Override
            public void close() {
                latch.countDown();
            }

            @Override
            public void failed(Exception ex) {
                error.set(ex);
                latch.countDown();
            }
        };

        HttpRequest request = client.request(method, path);
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            request.header("Content-Type", "application/json");
            request.header("Content-Length", String.valueOf(bytes.length));
            request.startRequestBody(handler);
            request.requestBodyContent(ByteBuffer.wrap(bytes));
            request.endRequestBody();
        } else {
            request.send(handler);
        }

        assertTrue(method + " " + path + " timed out", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNull(method + " failed: " + error.get(), error.get());
        result.version = client.getVersion();
        result.body = new String(bodyOut.toByteArray(), StandardCharsets.UTF_8);
        return result;
    }

    private HttpClientProtocolHandler createClient(int port, boolean secure, boolean forceHttp11)
            throws Exception {
        TcpTransportFactory factory = new TcpTransportFactory();
        if (secure) {
            factory.setSecure(true);
            factory.setApplicationProtocols("h2", "http/1.1");
            factory.setTrustManager(TestTlsFiles.trustManager());
        }
        factory.start();

        HttpClientProtocolHandler handler = new HttpClientProtocolHandler(
                new HttpClientHandler() {
                    @Override
                    public void onConnected(Endpoint endpoint) {
                    }

                    @Override
                    public void onSecurityEstablished(SecurityInfo info) {
                    }

                    @Override
                    public void onError(Exception cause) {
                    }

                    @Override
                    public void onDisconnected() {
                    }
                },
                TEST_HOST, port, secure);
        if (forceHttp11) {
            handler.setH2cUpgradeEnabled(false);
            handler.setH2Enabled(false);
        }

        ClientEndpoint client = new ClientEndpoint(factory, gumdrop.nextWorkerLoop(), TEST_HOST, port);
        client.connect(gumdrop, handler);

        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000L;
        while (!handler.isOpen() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        if (!handler.isOpen()) {
            throw new IllegalStateException("client did not connect");
        }
        return handler;
    }

    private static final class FeaturesHandlerFactory implements HttpStreamHandler {
        @Override
        public HttpRequestHandler openStream(HttpResponseState state) {
            return new FeaturesHandler();
        }
    }

    /**
     * Routes by path: /protected (Basic auth), /alt-svc (Alt-Svc header),
     * everything else echoes the method and body.
     */
    private static final class FeaturesHandler extends DefaultHttpRequestHandler {
        private String method;
        private String path;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private boolean sawAuth;

        @Override
        public void headers(HttpResponseState state, Headers headers) {
            method = headers.getMethod();
            path = headers.getPath();
            String auth = headers.getValue("authorization");
            sawAuth = auth != null && auth.regionMatches(true, 0, "Basic ", 0, 6);
        }

        @Override
        public void requestBodyContent(HttpResponseState state, ByteBuffer data) {
            if (data.hasRemaining()) {
                byte[] chunk = new byte[data.remaining()];
                data.get(chunk);
                body.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void requestComplete(HttpResponseState state) {
            if ("/protected".equals(path)) {
                if (!sawAuth) {
                    Headers h = new Headers();
                    h.status(HttpStatus.UNAUTHORIZED);
                    h.add(new Header("WWW-Authenticate", "Basic realm=\"integration\""));
                    state.headers(h);
                    state.complete();
                    return;
                }
                writeTextResponse(state, HttpStatus.OK, "authorized:" + AUTH_USER);
                return;
            }
            if ("/alt-svc".equals(path)) {
                Headers h = new Headers();
                h.status(HttpStatus.OK);
                h.add(new Header("Alt-Svc", "h3=\":443\"; ma=3600"));
                h.add(new Header("Content-Length", "0"));
                state.headers(h);
                state.complete();
                return;
            }
            String payload = "Method: " + method;
            if (body.size() > 0) {
                payload = payload + "\nBody: "
                        + new String(body.toByteArray(), StandardCharsets.UTF_8);
            }
            writeTextResponse(state, HttpStatus.OK, payload);
        }

        private static void writeTextResponse(HttpResponseState state, HttpStatus status, String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            Headers h = new Headers();
            h.status(status);
            h.add(new Header("Content-Type", "text/plain; charset=UTF-8"));
            h.add(new Header("Content-Length", String.valueOf(bytes.length)));
            state.headers(h);
            state.startResponseBody();
            state.responseBodyContent(ByteBuffer.wrap(bytes));
            state.endResponseBody();
            state.complete();
        }
    }
}
