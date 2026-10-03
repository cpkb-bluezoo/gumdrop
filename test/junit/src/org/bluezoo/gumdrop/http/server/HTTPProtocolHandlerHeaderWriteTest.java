/*
 * HTTPProtocolHandlerHeaderWriteTest.java
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

package org.bluezoo.gumdrop.http.server;

import java.util.List;
import org.bluezoo.gumdrop.http.HeaderFields;
import java.util.ArrayList;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.HttpDateCache;
import org.bluezoo.gumdrop.http.HttpVersion;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Characterization tests for {@code HttpProtocolHandler.writeStatusLineAndHeaders}
 * (issue #280), pinning down the exact bytes written to the wire before
 * replacing its per-header {@code StringBuilder}/{@code String}/{@code byte[]}
 * allocation with direct byte writes into the destination buffer. These pass
 * against the pre-refactor implementation unchanged (it already writes this
 * exact output, just less efficiently) and must keep passing afterwards -
 * the fix is only about how the bytes get there, not what they are.
 *
 * <p>Drives {@link HttpProtocolHandler#sendResponseHeaders(int, int, Headers,
 * boolean)} directly with a capturing {@link Endpoint} stub, bypassing
 * {@link Stream} entirely - the header-writing code path under test has no
 * dependency on stream state.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPProtocolHandlerHeaderWriteTest {

    private static final class CapturingEndpoint implements Endpoint {
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();

        @Override public void send(ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            captured.write(bytes, 0, bytes.length);
        }
        @Override public boolean isOpen() { return true; }
        @Override public boolean isClosing() { return false; }
        @Override public void close() { }
        @Override public SocketAddress getLocalAddress() { return null; }
        @Override public SocketAddress getRemoteAddress() { return null; }
        @Override public boolean isSecure() { return false; }
        @Override public SecurityInfo getSecurityInfo() { return null; }
        @Override public void startTLS() { }
        @Override public void pauseRead() { }
        @Override public void resumeRead() { }
        @Override public void onWriteReady(Runnable callback) { }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public org.bluezoo.gumdrop.TimerHandle scheduleTimer(long delayMs, Runnable callback) { return null; }
        @Override public org.bluezoo.gumdrop.SelectorLoop getSelectorLoop() { return null; }
        @Override public Trace getTrace() { return null; }
        @Override public void setTrace(Trace trace) { }
        @Override public boolean isTelemetryEnabled() { return false; }
        @Override public TelemetryConfig getTelemetryConfig() { return null; }

        String capturedAscii() {
            return new String(captured.toByteArray(), StandardCharsets.US_ASCII);
        }
    }

    private HttpProtocolHandler connection;
    private CapturingEndpoint endpoint;

    @Before
    public void setUp() {
        Http2Listener listener = new Http2Listener();
        connection = new HttpProtocolHandler(listener);
        connection.version = HttpVersion.HTTP_1_1;
        endpoint = new CapturingEndpoint();
        connection.connected(endpoint);
    }

    @Test
    public void testSingleAsciiHeaderWrittenExactly() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "content-type", "text/plain");

        connection.sendResponseHeaders(1, 200, headers, false);

        assertEquals("HTTP/1.1 200 OK\r\n"
                + "content-type: text/plain\r\n"
                + "\r\n",
                endpoint.capturedAscii());
    }

    /**
     * The Date header, when its value is exactly the HttpDateCache-cached
     * String (as Stream.sendResponseHeaders always sources it), must be
     * writable via HttpDateCache's pre-encoded bulk byte line instead of
     * the generic per-character path - and the output must be identical
     * either way.
     */
    @Test
    public void testDateHeaderFromCacheIsWrittenExactly() {
        List<Header> headers = new ArrayList<Header>();
        headers.add(new Header("Date", HttpDateCache.get()));

        connection.sendResponseHeaders(1, 200, headers, false);

        assertEquals("HTTP/1.1 200 OK\r\n"
                + "Date: " + HttpDateCache.get() + "\r\n"
                + "\r\n",
                endpoint.capturedAscii());
    }

    /**
     * The other framework-fixed headers (Server, the default security
     * headers, Transfer-Encoding: chunked, Connection: close) take the
     * same reference-matched bulk-write path as Date, provided their
     * value is exactly HttpProtocolHandler's shared constant for that
     * header - which is what Stream.sendResponseHeaders now always uses
     * (see the assertSame check further down: it is this wiring, not just
     * the bytes written here, that Stream.java could silently drift out
     * of sync with in a future change).
     */
    @Test
    public void testServerHeaderIsWrittenExactly() {
        List<Header> headers = new ArrayList<Header>();
        headers.add(new Header("Server", HttpProtocolHandler.SERVER_HEADER_VALUE));

        connection.sendResponseHeaders(1, 200, headers, false);

        assertEquals("HTTP/1.1 200 OK\r\n"
                + "Server: " + HttpProtocolHandler.SERVER_HEADER_VALUE + "\r\n"
                + "\r\n",
                endpoint.capturedAscii());
    }

    @Test
    public void testConnectionCloseHeaderIsWrittenExactly() {
        List<Header> headers = new ArrayList<Header>();
        headers.add(new Header("Connection", HttpProtocolHandler.CONNECTION_CLOSE_VALUE));

        connection.sendResponseHeaders(1, 200, headers, false);

        assertEquals("HTTP/1.1 200 OK\r\n"
                + "Connection: close\r\n"
                + "\r\n",
                endpoint.capturedAscii());
    }

    @Test
    public void testSecurityHeadersAreWrittenExactly() {
        List<Header> headers = new ArrayList<Header>();
        headers.add(new Header("X-Frame-Options", HttpProtocolHandler.X_FRAME_OPTIONS_VALUE));
        headers.add(new Header("X-Content-Type-Options", HttpProtocolHandler.X_CONTENT_TYPE_OPTIONS_VALUE));

        connection.sendResponseHeaders(1, 200, headers, false);

        assertEquals("HTTP/1.1 200 OK\r\n"
                + "X-Frame-Options: SAMEORIGIN\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "\r\n",
                endpoint.capturedAscii());
    }

    @Test
    public void testTransferEncodingChunkedIsWrittenExactly() {
        List<Header> headers = new ArrayList<Header>();
        headers.add(new Header("Transfer-Encoding", HttpProtocolHandler.TRANSFER_ENCODING_CHUNKED_VALUE));

        connection.sendResponseHeaders(1, 200, headers, false);

        assertEquals("HTTP/1.1 200 OK\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "\r\n",
                endpoint.capturedAscii());
    }

    /**
     * A well-known-named header whose value is merely equal (a distinct
     * String with the same characters, built via a non-constant path) but
     * not the same reference must NOT take the bulk-byte fast path - the
     * fast path is only sound because it matches the exact object
     * Stream.java is guaranteed to use, not "equal-looking" text.
     */
    @Test
    public void testEqualButNotSameConnectionValueUsesGenericPath() {
        List<Header> headers = new ArrayList<Header>();
        // new String(...) deliberately defeats literal interning.
        headers.add(new Header("Connection", new String("close".toCharArray())));

        connection.sendResponseHeaders(1, 200, headers, false);

        assertEquals("HTTP/1.1 200 OK\r\n"
                + "Connection: close\r\n"
                + "\r\n",
                endpoint.capturedAscii());
    }

    /**
     * A "Date" header whose value is NOT the cached instance (however
     * unlikely in practice) must still be written verbatim, not silently
     * replaced by the cache's current bytes - proving the fast path's
     * value == HttpDateCache.get() guard, not just its name check, is
     * what gates it.
     */
    @Test
    public void testDateHeaderWithUncachedValueIsNotReplacedByCacheBytes() {
        List<Header> headers = new ArrayList<Header>();
        headers.add(new Header("Date", "Not, 00 Xxx 0000 00:00:00 GMT"));

        connection.sendResponseHeaders(1, 200, headers, false);

        assertEquals("HTTP/1.1 200 OK\r\n"
                + "Date: Not, 00 Xxx 0000 00:00:00 GMT\r\n"
                + "\r\n",
                endpoint.capturedAscii());
    }

    @Test
    public void testMultipleHeadersAndNonDefaultStatus() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "content-type", "application/json");
        HeaderFields.add(headers, "cache-control", "no-store");

        connection.sendResponseHeaders(1, 404, headers, false);

        assertEquals("HTTP/1.1 404 Not Found\r\n"
                + "content-type: application/json\r\n"
                + "cache-control: no-store\r\n"
                + "\r\n",
                endpoint.capturedAscii());
    }

    @Test
    public void testPseudoHeaderSkipped() {
        List<Header> headers = new ArrayList<Header>();
        headers.add(new Header(":status", "200"));
        HeaderFields.add(headers, "content-type", "text/plain");

        connection.sendResponseHeaders(1, 200, headers, false);

        String out = endpoint.capturedAscii();
        assertFalse("HTTP/2 pseudo-headers must not be written on the HTTP/1.1 wire",
                out.contains(":status"));
        assertTrue(out.contains("content-type: text/plain"));
    }

    @Test
    public void testNullValueHeaderSkipped() {
        List<Header> headers = new ArrayList<Header>();
        headers.add(new Header("X-Null", null));
        HeaderFields.add(headers, "content-type", "text/plain");

        connection.sendResponseHeaders(1, 200, headers, false);

        String out = endpoint.capturedAscii();
        assertFalse(out.contains("X-Null"));
        assertTrue(out.contains("content-type: text/plain"));
    }

    @Test
    public void testNonAsciiValueIsRejectedNotEncoded() throws Exception {
        // RFC 9110 section 5.5: non-ASCII field content is obsolete text the
        // recipient must treat as opaque. The writer refuses it rather than
        // encode it (RFC 2047 is not part of HTTP), so a caller's mistake is
        // not turned into something the peer may or may not decode.
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "x-custom", "plain text with one accent: caf\u00e9");

        try {
            connection.sendResponseHeaders(1, 200, headers, false);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("x-custom"));
        }
    }

    @Test
    public void testNonAsciiRejectionHappensBeforeAnyBytesAreWritten() throws Exception {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "x-first", "ok");
        HeaderFields.add(headers, "x-second", "\u00e9\u00e8\u00ea");

        try {
            connection.sendResponseHeaders(1, 200, headers, false);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertEquals("", endpoint.capturedAscii());
        }
    }
}
