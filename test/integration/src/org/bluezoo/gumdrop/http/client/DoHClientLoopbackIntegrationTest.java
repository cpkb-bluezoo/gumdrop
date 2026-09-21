/*
 * DoHClientLoopbackIntegrationTest.java
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
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.TestTlsFiles;
import org.bluezoo.gumdrop.dns.DnsFormatException;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.dns.client.DnsClientTransportHandler;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpServer;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.doh.DoHClientTransport;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Loopback DoH (RFC 8484) over gumdrop's HTTPS server and {@link DoHClientTransport}.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DoHClientLoopbackIntegrationTest extends AbstractServerIntegrationTest {

    private static final int HTTPS_PORT = 18447;
    private static final String TEST_HOST = "::1";
    private static final String QUERY_NAME = "doh.loopback.test.";
    private static final String EXPECTED_ANSWER = "127.0.0.1";
    private static final long TIMEOUT_SECONDS = 5;

    @Rule
    public Timeout globalTimeout = Timeout.builder()
            .withTimeout(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)
            .withLookingForStuckThread(true)
            .build();

    @Override
    protected Collection<? extends Server> buildServers() throws Exception {
        HttpServer secure = HttpServer.compose()
                .listener(new Http2Listener()
                        .port(HTTPS_PORT)
                        .addresses(InetAddress.getByName(TEST_HOST))
                        .secure(true)
                        .tls(TestTlsFiles.serverTlsConfig()))
                .streamHandler(new DoHHandlerFactory())
                .server();
        return Collections.singletonList(secure);
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
    public void testDnsResolverOverDoh() throws Exception {
        DoHClientTransport transport = new DoHClientTransport();
        transport.setTrustManager(TestTlsFiles.trustManager());

        DnsResolver resolver = new DnsResolver();
        resolver.setSelectorLoop(gumdrop.nextWorkerLoop());
        resolver.setTransport(transport);
        resolver.addServer(InetAddress.getByName(TEST_HOST), HTTPS_PORT);
        resolver.open();
        waitForDohConnected(transport);

        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<String> resolved = new AtomicReference<String>();
        AtomicReference<String> error = new AtomicReference<String>();

        resolver.queryA(QUERY_NAME, new DnsQueryCallback() {
            @Override
            public void onResponse(DnsMessage response) {
                for (DnsResourceRecord record : response.getAnswers()) {
                    InetAddress addr = record.getAddress();
                    if (addr != null) {
                        resolved.set(addr.getHostAddress());
                        doneLatch.countDown();
                        return;
                    }
                }
                error.set("no A record in " + response);
                doneLatch.countDown();
            }

            @Override
            public void onError(String errorMessage) {
                error.set(errorMessage);
                doneLatch.countDown();
            }
        });

        assertTrue("query timed out", doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        assertEquals(EXPECTED_ANSWER, resolved.get());
        resolver.close();
    }

    @Test
    public void testDohTransportPostRoundTrip() throws Exception {
        DoHClientTransport transport = new DoHClientTransport();
        transport.setTrustManager(TestTlsFiles.trustManager());

        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<DnsMessage> responseMessage = new AtomicReference<DnsMessage>();
        AtomicReference<Exception> error = new AtomicReference<Exception>();

        DnsClientTransportHandler handler = new DnsClientTransportHandler() {
            @Override
            public void onReceive(ByteBuffer data) {
                try {
                    responseMessage.set(DnsMessage.parse(data));
                } catch (DnsFormatException e) {
                    error.set(e);
                }
                doneLatch.countDown();
            }

            @Override
            public void onError(Exception cause) {
                error.set(cause);
                doneLatch.countDown();
            }
        };

        transport.open(InetAddress.getByName(TEST_HOST), HTTPS_PORT,
                gumdrop.nextWorkerLoop(), handler);

        waitForDohConnected(transport);

        DnsMessage query = DnsMessage.createQuery(42, QUERY_NAME, DnsType.A);
        transport.send(query.serialize());

        assertTrue("DoH response timed out", doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        DnsMessage response = responseMessage.get();
        assertNotNull(response);
        assertTrue(response.isResponse());
        assertEquals(42, response.getId());
        assertEquals(1, response.getAnswers().size());
        assertEquals(EXPECTED_ANSWER,
                response.getAnswers().get(0).getAddress().getHostAddress());
        transport.close();
    }

    private static void waitForDohConnected(DoHClientTransport transport) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (isDohConnected(transport)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("DoH HTTP connection not established");
    }

    private static boolean isDohConnected(DoHClientTransport transport) throws Exception {
        java.lang.reflect.Field connectedField =
                DoHClientTransport.class.getDeclaredField("connected");
        connectedField.setAccessible(true);
        return connectedField.getBoolean(transport);
    }

    private static final class DoHHandlerFactory implements HttpStreamHandler {
        @Override
        public HttpRequestHandler openStream(HttpResponseState state) {
            return new DoHHandler();
        }
    }

    private static final class DoHHandler extends DefaultHttpRequestHandler {
        private String method;
        private String path;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        @Override
        public void headers(HttpResponseState state, Headers headers) {
            method = headers.getMethod();
            path = headers.getPath();
        }

        @Override
        public void requestBodyContent(HttpResponseState state, ByteBuffer data) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            try {
                body.write(chunk);
            } catch (java.io.IOException ignored) {
            }
        }

        @Override
        public void requestComplete(HttpResponseState state) {
            if (!"POST".equals(method) || !"/dns-query".equals(path)) {
                reject(state, HttpStatus.NOT_FOUND);
                return;
            }
            try {
                DnsMessage query = DnsMessage.parse(ByteBuffer.wrap(body.toByteArray()));
                InetAddress answer = InetAddress.getByName(EXPECTED_ANSWER);
                DnsResourceRecord a = DnsResourceRecord.a(
                        query.getQuestions().get(0).getName(), 60, answer);
                DnsMessage response = query.createResponse(Arrays.asList(a));
                byte[] wire = new byte[response.serialize().remaining()];
                response.serialize().get(wire);

                Headers responseHeaders = new Headers();
                responseHeaders.status(HttpStatus.OK);
                responseHeaders.add("content-type", "application/dns-message");
                responseHeaders.add("content-length", String.valueOf(wire.length));
                state.headers(responseHeaders);
                state.startResponseBody();
                state.responseBodyContent(ByteBuffer.wrap(wire));
                state.endResponseBody();
                state.complete();
            } catch (Exception e) {
                reject(state, HttpStatus.BAD_REQUEST);
            }
        }

        private static void reject(HttpResponseState state, HttpStatus status) {
            Headers responseHeaders = new Headers();
            responseHeaders.status(status);
            state.headers(responseHeaders);
            state.complete();
        }
    }
}
