/*
 * HTTPClientVersionIntegrationTest.java
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
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.TestCertificateManager;
import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.http.HTTPVersion;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import static org.junit.Assert.*;

/**
 * Protocol-version integration tests for the public {@link HTTPClient} facade.
 *
 * <p>Where {@link HTTPClientIntegrationTest} drives the low-level
 * {@link HTTPClientProtocolHandler} directly, this suite exercises the
 * caller-facing {@code HTTPClient} class end to end against a real echo
 * server and asserts the negotiated wire protocol for each transport:
 *
 * <ul>
 *   <li><b>HTTP/1.1 cleartext</b> — h2/h2c disabled, plaintext socket.</li>
 *   <li><b>HTTP/1.1 over TLS</b> — TLS with ALPN offering only http/1.1.</li>
 *   <li><b>HTTP/2 over TLS (h2)</b> — TLS with ALPN offering "h2"; this is
 *       the canonical HTTP/2 deployment (RFC 9113 §3.2 / RFC 7301) and
 *       verifies that the client advertises "h2" and adopts the negotiated
 *       protocol.</li>
 * </ul>
 *
 * <p>Each exchange runs on a fresh connection (connect → one request →
 * close) so the assertions describe a single, unambiguous request/response
 * rather than depending on connection reuse. The server is the shared
 * {@link EchoHandlerFactory}, which returns 200 and reflects the request
 * method, path and body, allowing both status and content to be verified.
 *
 * <p>HTTP/3 is covered separately by {@link HTTP3ClientIntegrationTest},
 * which requires the native QUIC library and so must be conditionally
 * skipped. HTTP/2 over cleartext (h2c upgrade / prior knowledge) is
 * intentionally not asserted here; see the {@code @Ignore}d cases in
 * {@link HTTPClientIntegrationTest} for the current cleartext-h2 limitation.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPClientVersionIntegrationTest extends AbstractServerIntegrationTest {

    private static final int HTTP_PORT = 18090;
    private static final int HTTPS_PORT = 18444;
    private static final String TEST_HOST = "127.0.0.1";

    /** Timeout for a single async request; anything slower indicates a hang. */
    private static final int ASYNC_TIMEOUT_SECONDS = 5;

    private static final String TEST_PAYLOAD =
            "The quick brown fox jumps over the lazy dog. 0123456789";

    /** Global timeout guards against a wedged connection hanging the suite. */
    @Rule
    public Timeout globalTimeout = Timeout.builder()
            .withTimeout(ASYNC_TIMEOUT_SECONDS * 4L, TimeUnit.SECONDS)
            .withLookingForStuckThread(true)
            .build();

    private static TestCertificateManager certManager;

    @Override
    protected File getTestConfigFile() {
        return new File("test/integration/config/http-client-test.xml");
    }

    @Override
    protected Level getTestLogLevel() {
        return Level.WARNING;
    }

    /**
     * Generates the TLS material used by both the server (keystore referenced
     * from the XML config) and the client (CA trust). Runs before the base
     * class starts the server in {@code @Before}.
     */
    @BeforeClass
    public static void setupCertificates() throws Exception {
        File certsDir = new File("test/integration/certs");
        if (!certsDir.exists()) {
            certsDir.mkdirs();
        }
        // Remove any stale CA keystore so keytool does not append to a
        // keystore written with a different (random) password.
        File caKeystore = new File(certsDir, "ca-keystore.p12");
        if (caKeystore.exists()) {
            caKeystore.delete();
        }

        certManager = new TestCertificateManager(certsDir);
        certManager.generateCA("Test CA", 365);
        certManager.generateServerCertificate("localhost", 365);
        certManager.saveServerKeystore(new File(certsDir, "test-keystore.p12"), "testpass");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testHttp11CleartextGet() throws Exception {
        Result r = exchange(HTTP_PORT, false, true, "GET", "/test", null);
        assertEquals("Should negotiate HTTP/1.1", HTTPVersion.HTTP_1_1, r.version);
        assertEquals("Should return 200 OK", HTTPStatus.OK, r.status);
        assertTrue("Echo body should report the GET method", r.body.contains("Method: GET"));
    }

    @Test
    public void testHttp11CleartextPost() throws Exception {
        Result r = exchange(HTTP_PORT, false, true, "POST", "/echo", TEST_PAYLOAD);
        assertEquals("Should negotiate HTTP/1.1", HTTPVersion.HTTP_1_1, r.version);
        assertEquals("Should return 200 OK", HTTPStatus.OK, r.status);
        assertTrue("Echo body should contain the uploaded payload",
                r.body.contains(TEST_PAYLOAD));
    }

    @Test
    public void testHttp11TlsGet() throws Exception {
        Result r = exchange(HTTPS_PORT, true, true, "GET", "/test", null);
        assertEquals("Forcing http/1.1 over TLS", HTTPVersion.HTTP_1_1, r.version);
        assertEquals("Should return 200 OK", HTTPStatus.OK, r.status);
        assertTrue("Echo body should report the GET method", r.body.contains("Method: GET"));
    }

    @Test
    public void testHttp11TlsPost() throws Exception {
        Result r = exchange(HTTPS_PORT, true, true, "POST", "/echo", TEST_PAYLOAD);
        assertEquals("Forcing http/1.1 over TLS", HTTPVersion.HTTP_1_1, r.version);
        assertEquals("Should return 200 OK", HTTPStatus.OK, r.status);
        assertTrue("Echo body should contain the uploaded payload",
                r.body.contains(TEST_PAYLOAD));
    }

    @Test
    public void testHttp2TlsAlpnGet() throws Exception {
        Result r = exchange(HTTPS_PORT, true, false, "GET", "/test", null);
        assertEquals("ALPN should negotiate h2 over TLS", HTTPVersion.HTTP_2_0, r.version);
        assertEquals("Should return 200 OK", HTTPStatus.OK, r.status);
        assertTrue("Echo body should report the GET method", r.body.contains("Method: GET"));
    }

    @Test
    public void testHttp2TlsAlpnPost() throws Exception {
        Result r = exchange(HTTPS_PORT, true, false, "POST", "/echo", TEST_PAYLOAD);
        assertEquals("ALPN should negotiate h2 over TLS", HTTPVersion.HTTP_2_0, r.version);
        assertEquals("Should return 200 OK", HTTPStatus.OK, r.status);
        assertTrue("Echo body should contain the uploaded payload",
                r.body.contains(TEST_PAYLOAD));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Result of a single request: negotiated version, status and body. */
    private static final class Result {
        HTTPVersion version;
        HTTPStatus status;
        String body;
    }

    /**
     * Connects a fresh {@link HTTPClient}, performs a single request, records
     * the negotiated version, and closes the connection.
     *
     * @param port the target port
     * @param secure whether to use TLS
     * @param forceHttp11 if true, disable h2/h2c so the exchange stays HTTP/1.1;
     *                    if false, allow HTTP/2 (via ALPN on TLS)
     * @param method the HTTP method
     * @param path the request path
     * @param payload the request body, or null for a bodyless request
     */
    private Result exchange(int port, boolean secure, boolean forceHttp11,
                            String method, String path, String payload) throws Exception {
        HTTPClient client = connect(port, secure, forceHttp11);
        try {
            Result result = new Result();

            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<Exception> error = new AtomicReference<>();
            final ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();

            DefaultHTTPResponseHandler handler = new DefaultHTTPResponseHandler() {
                @Override
                public void ok(HTTPResponse response) {
                    result.status = response.getStatus();
                }

                @Override
                public void error(HTTPResponse response) {
                    result.status = response.getStatus();
                }

                @Override
                public void responseBodyContent(ByteBuffer data) {
                    byte[] bytes = new byte[data.remaining()];
                    data.get(bytes);
                    bodyBuffer.write(bytes, 0, bytes.length);
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

            HTTPRequest request = client.request(method, path);
            if (payload != null) {
                byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
                request.header("Content-Type", "text/plain; charset=UTF-8");
                request.header("Content-Length", String.valueOf(payloadBytes.length));
                request.startRequestBody(handler);
                request.requestBodyContent(ByteBuffer.wrap(payloadBytes));
                request.endRequestBody();
            } else {
                request.send(handler);
            }

            assertTrue(method + " " + path + " did not complete in time",
                    latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertNull(method + " " + path + " failed: " + error.get(), error.get());

            result.version = client.getVersion();
            result.body = new String(bodyBuffer.toByteArray(), StandardCharsets.UTF_8);
            return result;
        } finally {
            client.close();
        }
    }

    /**
     * Creates and connects an {@link HTTPClient}, waiting until the connection
     * (and TLS handshake, if secure) is established.
     */
    private HTTPClient connect(int port, boolean secure, boolean forceHttp11) throws Exception {
        HTTPClient client = new HTTPClient(TEST_HOST, port);
        // Keep the negotiated version deterministic: never let Alt-Svc silently
        // migrate the connection to h3 mid-test.
        client.setAltSvcEnabled(false);
        if (secure) {
            client.setSecure(true);
            client.setSSLContext(certManager.createClientSSLContext());
        }
        if (forceHttp11) {
            client.setH2Enabled(false);
            client.setH2cUpgradeEnabled(false);
        }

        final boolean isSecure = secure;
        final CountDownLatch connected = new CountDownLatch(1);
        final AtomicReference<Exception> error = new AtomicReference<>();
        client.connect(new HTTPClientHandler() {
            @Override
            public void onConnected(Endpoint endpoint) {
                // For cleartext there is no security handshake to await.
                if (!isSecure) {
                    connected.countDown();
                }
            }

            @Override
            public void onSecurityEstablished(SecurityInfo info) {
                connected.countDown();
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

        assertTrue("Connection to " + TEST_HOST + ":" + port + " timed out",
                connected.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNull("Connection failed: " + error.get(), error.get());
        return client;
    }
}
