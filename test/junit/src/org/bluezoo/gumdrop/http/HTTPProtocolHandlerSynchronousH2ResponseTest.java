/*
 * HTTPProtocolHandlerSynchronousH2ResponseTest.java
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

import static org.junit.Assert.*;

/**
 * Regression test: an HTTP/2 request with no body (client's HEADERS frame
 * carries END_STREAM) whose handler answers entirely from within its
 * {@code headers()} callback - the common, fast case, and exactly the
 * pattern shown in {@link DefaultHTTPRequestHandler}'s own class Javadoc -
 * must free its {@code activeStreams} concurrency slot once the exchange is
 * done.
 *
 * <p>{@code Stream.streamEndHeaders()} dispatches to the handler
 * synchronously while the stream is still in state {@code OPEN} (the
 * request's own end-of-stream is not applied until {@code
 * streamEndRequest()}, called by the caller only after {@code
 * streamEndHeaders()} returns). When the handler completes the response
 * inline, {@code sendResponseBodyInternal} only recognises full closure from
 * state {@code HALF_CLOSED_REMOTE}, so from {@code OPEN} it instead lands on
 * {@code HALF_CLOSED_LOCAL} - and {@code streamResponseCompleted()} (which
 * frees the {@code activeStreams} slot) never runs. {@code
 * streamEndRequest()} then unconditionally stamps {@code state =
 * HALF_CLOSED_REMOTE}, discarding the fact that the response was already
 * fully sent. The stream never reaches {@code CLOSED} and its slot leaks
 * for the lifetime of the connection.
 *
 * <p>Under sustained HTTP/2 GET traffic this exhausts {@code
 * SETTINGS_MAX_CONCURRENT_STREAMS} (default 100) after ~100 requests, after
 * which the server refuses every further stream with {@code
 * RST_STREAM(REFUSED_STREAM)} - observed as a load-generating HTTP/2 client
 * reporting its connection closed by the peer and every subsequent request
 * failing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPProtocolHandlerSynchronousH2ResponseTest {

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

    private static final class NoopEndpoint implements Endpoint {
        @Override public void send(ByteBuffer data) { }
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

    @Before
    public void setUp() {
        HTTPListener listener = new HTTPListener();
        listener.setHandlerFactory((state, headers) -> new SynchronousGetHandler());

        connection = new HTTPProtocolHandler(listener);
        connection.connected(new NoopEndpoint());
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
    public void testActiveStreamSlotFreedWhenHandlerRespondsSynchronouslyToBodylessGet() throws Exception {
        // Stream ID 1 is pre-claimed by the PRI_SETTINGS->HTTP2 transition
        // itself (h2c-upgrade bookkeeping that runs unconditionally); use
        // the next real client stream ID to avoid that unrelated quirk.
        connection.headersFrameReceived(3, true, true, 0, false, 16,
                encodeGetHeaders("/"));

        assertEquals("a stream whose response was fully sent before its own "
                + "END_STREAM was processed must not leak its "
                + "activeStreams concurrency slot",
                0, connection.activeStreamCountForTesting());
    }

    @Test
    public void testManySequentialBodylessGetsDoNotExhaustConcurrencyLimit() throws Exception {
        // Default SETTINGS_MAX_CONCURRENT_STREAMS is 100 (see the
        // HTTPProtocolHandler(HTTPListener) constructor); well more than
        // 100 sequential requests must all succeed rather than the server
        // starting to RST_STREAM(REFUSED_STREAM) once leaked slots pile up
        // to that limit.
        for (int i = 0; i < 250; i++) {
            int streamId = 3 + (i * 2);
            connection.headersFrameReceived(streamId, true, true, 0, false, 16,
                    encodeGetHeaders("/"));
        }

        assertEquals(0, connection.activeStreamCountForTesting());
    }
}
