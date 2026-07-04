/*
 * HTTP3ClientIntegrationTest.java
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

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropNative;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.TestCertificateManager;
import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.http.HTTPVersion;
import org.bluezoo.gumdrop.http.h3.HTTP3Listener;
import org.junit.AfterClass;
import org.junit.Assume;
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

import static org.junit.Assert.*;

/**
 * HTTP/3 integration tests for the public {@link HTTPClient} facade.
 *
 * <p>Drives {@code HTTPClient} with {@link HTTPClient#setH3Enabled(boolean)}
 * against a real {@link HTTP3Listener} echo server over QUIC, asserting that
 * the negotiated version is {@link HTTPVersion#HTTP_3} and that GET/POST
 * request/response bodies round-trip.
 *
 * <h2>Native library requirement</h2>
 *
 * <p>HTTP/3 rides on QUIC, which is implemented via JNI to the native
 * {@code libgumdrop}/{@code libquiche} library. That library is built only by
 * the {@code native} Ant target (which needs {@code QUICHE_DIR}) and is not
 * produced by the integration-test targets. When it is absent, both the
 * server ({@link HTTP3Listener#start()}) and the client
 * ({@code QuicTransportFactory.start()}) fail with a {@link LinkageError}.
 *
 * <p>These tests therefore probe for native availability in
 * {@code @BeforeClass} and {@link Assume#assumeTrue} it, so the whole class is
 * cleanly <em>skipped</em> (not failed) on machines without the native build.
 * Run {@code ant native} with {@code QUICHE_DIR} set, and ensure
 * {@code -Djava.library.path=dist} (the integration JUnit task already sets
 * this), to exercise these tests for real.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTP3ClientIntegrationTest {

    private static final int H3_PORT = 18446;
    private static final String TEST_HOST = "127.0.0.1";
    private static final int ASYNC_TIMEOUT_SECONDS = 8;

    private static final String TEST_PAYLOAD =
            "HTTP/3 over QUIC: the quick brown fox. 0123456789";

    @Rule
    public Timeout globalTimeout = Timeout.builder()
            .withTimeout(ASYNC_TIMEOUT_SECONDS * 3L, TimeUnit.SECONDS)
            .withLookingForStuckThread(true)
            .build();

    private static Gumdrop gumdrop;
    private static HTTP3Listener listener;

    /**
     * Returns whether the native QUIC library can be loaded. Touches a cheap
     * native method inside a {@code try/catch (LinkageError)} because there is
     * no {@code isAvailable()} flag: the first reference to {@link GumdropNative}
     * runs {@code System.loadLibrary("gumdrop")} and, if the library is missing,
     * throws {@code ExceptionInInitializerError}; subsequent references throw
     * {@code NoClassDefFoundError}. Both are {@link LinkageError}s.
     */
    static boolean quicNativeAvailable() {
        try {
            GumdropNative.quiche_version_is_supported(1);
            return true;
        } catch (LinkageError e) {
            return false;
        }
    }

    @BeforeClass
    public static void startServer() throws Exception {
        Assume.assumeTrue(
                "native QUIC library (libgumdrop) not available; skipping HTTP/3 tests",
                quicNativeAvailable());

        File certsDir = new File("test/integration/certs");
        if (!certsDir.exists()) {
            certsDir.mkdirs();
        }
        // QUIC/BoringSSL loads PEM cert+key files, not a JSSE keystore, so
        // generate a fresh CA-signed server certificate and export it as PEM.
        File caKeystore = new File(certsDir, "ca-keystore.p12");
        if (caKeystore.exists()) {
            caKeystore.delete();
        }
        TestCertificateManager certManager = new TestCertificateManager(certsDir);
        certManager.generateCA("Test CA", 365);
        certManager.generateServerCertificate("localhost", 365);
        File pemCert = new File(certsDir, "h3-server-chain.pem");
        File pemKey = new File(certsDir, "h3-server-key.pem");
        certManager.saveServerPem(pemCert, pemKey);

        System.setProperty("gumdrop.workers", "2");

        listener = new HTTP3Listener();
        listener.setPort(H3_PORT);
        listener.setAddresses(TEST_HOST);
        listener.setCertFile(pemCert.getAbsolutePath());
        listener.setKeyFile(pemKey.getAbsolutePath());
        listener.setHandlerFactory(new EchoHandlerFactory());

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
    public void testHttp3Get() throws Exception {
        Result r = exchange("GET", "/test", null);
        assertEquals("Should negotiate HTTP/3", HTTPVersion.HTTP_3, r.version);
        assertEquals("Should return 200 OK", HTTPStatus.OK, r.status);
        assertTrue("Echo body should report the GET method", r.body.contains("Method: GET"));
    }

    @Test
    public void testHttp3Post() throws Exception {
        Result r = exchange("POST", "/echo", TEST_PAYLOAD);
        assertEquals("Should negotiate HTTP/3", HTTPVersion.HTTP_3, r.version);
        assertEquals("Should return 200 OK", HTTPStatus.OK, r.status);
        assertTrue("Echo body should contain the uploaded payload",
                r.body.contains(TEST_PAYLOAD));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static final class Result {
        HTTPVersion version;
        HTTPStatus status;
        String body;
    }

    private Result exchange(String method, String path, String payload) throws Exception {
        HTTPClient client = connect();
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

    private HTTPClient connect() throws Exception {
        HTTPClient client = new HTTPClient(TEST_HOST, H3_PORT);
        client.setH3Enabled(true);
        // The test server presents a certificate signed by our throwaway test
        // CA; BoringSSL has no way to trust it, so disable peer verification.
        client.setVerifyPeer(false);
        client.setAltSvcEnabled(false);

        final CountDownLatch connected = new CountDownLatch(1);
        final AtomicReference<Exception> error = new AtomicReference<>();
        client.connect(new HTTPClientHandler() {
            @Override
            public void onConnected(Endpoint endpoint) {
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

        assertTrue("HTTP/3 connection to " + TEST_HOST + ":" + H3_PORT + " timed out",
                connected.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNull("HTTP/3 connection failed: " + error.get(), error.get());
        return client;
    }
}
