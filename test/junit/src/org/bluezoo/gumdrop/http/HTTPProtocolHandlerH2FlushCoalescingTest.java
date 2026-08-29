/*
 * HTTPProtocolHandlerH2FlushCoalescingTest.java
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
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.http.hpack.Encoder;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.junit.Before;
import org.junit.Test;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Regression test for issue #322: {@code sendResponseHeaders} and {@code
 * sendResponseBody} each called {@code h2Writer.flush()} independently, so
 * a small HTTP/2 response answered synchronously (headers, one body
 * chunk, then the empty END_STREAM DATA frame {@link HTTPResponseState#complete()}
 * sends once no headers remain buffered) produced three separate {@code
 * EndpointChannel.write()} calls -- three separate TLS records for a
 * secure connection -- instead of coalescing what the writer had already
 * buffered into fewer channel writes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPProtocolHandlerH2FlushCoalescingTest {

    /** Answers entirely within headers(), like the DefaultHTTPRequestHandler javadoc example. */
    private static final class SynchronousGetHandler extends DefaultHTTPRequestHandler {
        @Override
        public void headers(HTTPResponseState state, Headers headers) {
            if ("GET".equals(headers.getMethod())) {
                Headers response = new Headers();
                response.status(HTTPStatus.OK);
                response.add("content-type", "text/plain");
                state.headers(response);
                state.startResponseBody();
                state.responseBodyContent(ByteBuffer.wrap("Hello, World!".getBytes()));
                state.endResponseBody();
                state.complete();
            }
        }
    }

    /** Counts how many times the connection hands a buffer to the transport. */
    private static final class CountingEndpoint implements Endpoint {
        final AtomicInteger sendCount = new AtomicInteger();
        @Override public void send(ByteBuffer data) { sendCount.incrementAndGet(); }
        @Override public boolean isOpen() { return true; }
        @Override public boolean isClosing() { return false; }
        @Override public void close() { }
        @Override public SocketAddress getLocalAddress() { return null; }
        @Override public SocketAddress getRemoteAddress() { return null; }
        @Override public boolean isSecure() { return true; }
        @Override public SecurityInfo getSecurityInfo() { return null; }
        @Override public void startTLS() { }
        @Override public void pauseRead() { }
        @Override public void resumeRead() { }
        @Override public void onWriteReady(Runnable callback) { }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public TimerHandle scheduleTimer(long delayMs, Runnable callback) { return null; }
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public Trace getTrace() { return null; }
        @Override public void setTrace(Trace trace) { }
        @Override public boolean isTelemetryEnabled() { return false; }
        @Override public TelemetryConfig getTelemetryConfig() { return null; }
    }

    private static final class StubSecurityInfo implements SecurityInfo {
        @Override public String getProtocol() { return "TLSv1.3"; }
        @Override public String getCipherSuite() { return "TLS_AES_256_GCM_SHA384"; }
        @Override public int getKeySize() { return 256; }
        @Override public Certificate[] getPeerCertificates() { return null; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public String getApplicationProtocol() { return "h2"; }
        @Override public long getHandshakeDurationMs() { return 0; }
        @Override public boolean isSessionResumed() { return false; }
    }

    private HTTPProtocolHandler connection;
    private CountingEndpoint endpoint;

    @Before
    public void setUp() {
        HTTPListener listener = new HTTPListener();
        listener.setHandlerFactory((state, headers) -> new SynchronousGetHandler());

        connection = new HTTPProtocolHandler(listener);
        endpoint = new CountingEndpoint();
        connection.connected(endpoint);
        // Negotiate HTTP/2 via ALPN, then receive the client's initial
        // SETTINGS frame - the two steps that bring up hpackDecoder/Encoder
        // and move the connection out of PRI_SETTINGS, exactly as the real
        // TLS handshake + first client frame would.
        connection.securityEstablished(new StubSecurityInfo());
        connection.settingsFrameReceived(false, Collections.emptyMap());
    }

    private ByteBuffer encodeGetHeaders(String path) throws Exception {
        Encoder encoder = new Encoder(4096, HTTPListener.DEFAULT_MAX_HEADER_LIST_SIZE);
        Headers request = new Headers();
        request.add(new Header(":method", "GET"));
        request.add(new Header(":scheme", "https"));
        request.add(new Header(":authority", "example.test"));
        request.add(new Header(":path", path));
        ByteBuffer buf = ByteBuffer.allocate(256);
        encoder.encode(buf, request);
        buf.flip();
        return buf;
    }

    @Test
    public void testSmallSynchronousResponseCoalescesHeadersAndBodyIntoFewerSends() throws Exception {
        // Connection setup (server preface SETTINGS, SETTINGS ACK) already
        // happened in setUp() -- snapshot the count so this only measures
        // sends caused by handling the request/response below.
        int before = endpoint.sendCount.get();

        // Stream ID 1 is pre-claimed by the PRI_SETTINGS->HTTP2 transition
        // itself (h2c-upgrade bookkeeping that runs unconditionally); use
        // the next real client stream ID to avoid that unrelated quirk.
        connection.headersFrameReceived(3, true, true, 0, false, 16,
                encodeGetHeaders("/"));

        int responseSends = endpoint.sendCount.get() - before;
        assertTrue("a small synchronous HEADERS+DATA(+empty END_STREAM DATA) response "
                + "must not need one Endpoint.send() per frame -- expected the deferred "
                + "HEADERS to be pushed out together with the first DATA frame instead "
                + "of as a separate channel write; got " + responseSends + " sends",
                responseSends <= 2);
    }
}
