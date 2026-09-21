/*
 * HTTPClientFacadeIntegrationTest.java
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
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.http.HttpClient;
import org.bluezoo.gumdrop.http.HttpServer;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.server.Http2Listener;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.net.InetAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Additional {@link HttpClient} facade coverage: fluent credentials, TRACE, and
 * {@link HttpClient#altSvcReceived(String)} on a live response path.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPClientFacadeIntegrationTest extends AbstractServerIntegrationTest {

    private static final int HTTP_PORT = 18111;
    private static final String TEST_HOST = "::1";
    private static final int TIMEOUT_SECONDS = 8;

    @Rule
    public Timeout globalTimeout = Timeout.builder()
            .withTimeout(TIMEOUT_SECONDS * 3L, TimeUnit.SECONDS)
            .withLookingForStuckThread(true)
            .build();

    @Override
    protected Collection<? extends Server> buildServers() throws Exception {
        Http2Listener listener = new Http2Listener()
                .port(HTTP_PORT)
                .addresses(InetAddress.getByName(TEST_HOST));
        // RFC 9110 section 9.3.8: TRACE is off by default; this test exercises TRACE.
        listener.setTraceMethodEnabled(true);
        HttpServer server = HttpServer.compose()
                .listener(listener)
                .streamHandler(new EchoHandlerFactory())
                .server();
        return Collections.singletonList(server);
    }

    @Override
    protected Level getTestLogLevel() {
        return Level.WARNING;
    }

    @Test
    public void testTraceRequestViaFacade() throws Exception {
        HttpClient client = connectPlainHttp11();
        try {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<HttpStatus> status = new AtomicReference<HttpStatus>();
            AtomicReference<Exception> error = new AtomicReference<Exception>();

            client.request("TRACE", "/trace-me").send(new DefaultHttpResponseHandler() {
                @Override
                public void ok(HttpResponse response) {
                    status.set(response.getStatus());
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
            });

            assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertNull(error.get());
            assertEquals(HttpStatus.OK, status.get());
        } finally {
            client.close();
        }
    }

    @Test
    public void testCredentialsFluentBuilderDoesNotBreakConnect() throws Exception {
        HttpClient client = new HttpClient(TEST_HOST, HTTP_PORT)
                .credentials("user", "pass")
                .h2Enabled(false)
                .h2cUpgradeEnabled(false);
        CountDownLatch connected = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<Exception>();
        client.connect(gumdrop, new HttpClientHandler() {
            @Override
            public void onConnected(Endpoint endpoint) {
                connected.countDown();
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
        assertTrue(connected.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNull(error.get());
        assertTrue(client.isOpen());
        client.close();
    }

    @Test
    public void testAltSvcReceivedPopulatesCacheOnConnectedClient() throws Exception {
        AltSvcCache.clear();
        HttpClient client = connectPlainHttp11();
        try {
            client.altSvcReceived("h3=\":443\"; ma=120");
            assertTrue(AltSvcCache.get(TEST_HOST, HTTP_PORT) != null
                    || AltSvcCache.get("::1", HTTP_PORT) != null);
        } finally {
            client.close();
            AltSvcCache.clear();
        }
    }

    private HttpClient connectPlainHttp11() throws Exception {
        HttpClient client = new HttpClient(TEST_HOST, HTTP_PORT);
        client.setH2Enabled(false);
        client.setH2cUpgradeEnabled(false);
        client.setAltSvcEnabled(false);

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
        assertTrue(connected.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNull(error.get());
        return client;
    }
}
