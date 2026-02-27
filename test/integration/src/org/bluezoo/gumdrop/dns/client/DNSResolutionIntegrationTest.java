/*
 * DNSResolutionIntegrationTest.java
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

package org.bluezoo.gumdrop.dns.client;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.http.client.DefaultHTTPResponseHandler;
import org.bluezoo.gumdrop.http.client.HTTPClient;
import org.bluezoo.gumdrop.http.client.HTTPClientHandler;
import org.bluezoo.gumdrop.http.client.HTTPRequest;
import org.bluezoo.gumdrop.http.client.HTTPResponse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Integration test that performs an HTTPS GET against a public test
 * server, exercising the full asynchronous DNS resolution path.
 *
 * <p>The hostname is resolved using Gumdrop's {@link DNSResolver}
 * (not {@code InetAddress.getByName}), validating end-to-end that
 * the async resolver, transport, cache, and client wiring all work.
 *
 * <p>Requires network access. Connects to {@code httpbin.org:443}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSResolutionIntegrationTest {

    private static final String TEST_HOST = "httpbin.org";
    private static final int TEST_PORT = 443;
    private static final int TIMEOUT_SECONDS = 15;

    @Before
    public void setUp() {
        System.setProperty("gumdrop.workers", "2");
    }

    @After
    public void tearDown() {
        Gumdrop gumdrop = Gumdrop.getInstance();
        if (gumdrop.isStarted()) {
            gumdrop.shutdown();
        }
    }

    /**
     * Performs an HTTPS GET to httpbin.org/get and verifies the
     * response is 200 OK with a JSON body containing the request URL.
     */
    @Test
    public void testHTTPSGetWithDNSResolution() throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[] {
            new X509TrustManager() {
                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }

                @Override
                public void checkClientTrusted(
                        X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(
                        X509Certificate[] chain, String authType) {
                }
            }
        }, null);

        HTTPClient client = new HTTPClient(TEST_HOST, TEST_PORT);
        client.setSecure(true);
        client.setSSLContext(sslContext);

        CountDownLatch readyLatch = new CountDownLatch(1);
        CountDownLatch responseLatch = new CountDownLatch(1);
        AtomicReference<HTTPStatus> status = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();

        client.connect(new HTTPClientHandler() {
            @Override
            public void onConnected(Endpoint endpoint) {
                readyLatch.countDown();
            }

            @Override
            public void onSecurityEstablished(SecurityInfo info) {
                readyLatch.countDown();
            }

            @Override
            public void onError(Exception cause) {
                error.set(cause);
                readyLatch.countDown();
                responseLatch.countDown();
            }

            @Override
            public void onDisconnected() {
            }
        });

        boolean ready = readyLatch.await(TIMEOUT_SECONDS,
                TimeUnit.SECONDS);
        assertTrue("Connection should complete (DNS + TLS)",
                ready);
        assertNull("Connection should not error: " + error.get(),
                error.get());

        HTTPRequest request = client.get("/get");
        request.send(new DefaultHTTPResponseHandler() {
            @Override
            public void ok(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void error(HTTPResponse response) {
                status.set(response.getStatus());
            }

            @Override
            public void responseBodyContent(ByteBuffer data) {
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                bodyBuffer.write(bytes, 0, bytes.length);
            }

            @Override
            public void close() {
                responseLatch.countDown();
            }

            @Override
            public void failed(Exception ex) {
                error.set(ex);
                responseLatch.countDown();
            }
        });

        boolean completed = responseLatch.await(TIMEOUT_SECONDS,
                TimeUnit.SECONDS);

        client.close();

        assertTrue("Request should complete", completed);
        assertNull("Request should not fail: " + error.get(),
                error.get());
        assertEquals("Should receive 200 OK",
                HTTPStatus.OK, status.get());

        String body = new String(bodyBuffer.toByteArray(),
                StandardCharsets.UTF_8);
        assertTrue("Response should be JSON from httpbin",
                body.contains("\"url\""));
        assertTrue("Response should contain the request URL",
                body.contains("httpbin.org/get"));
    }
}
