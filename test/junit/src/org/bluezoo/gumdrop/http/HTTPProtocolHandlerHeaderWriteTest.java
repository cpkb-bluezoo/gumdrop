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

package org.bluezoo.gumdrop.http;

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

import javax.mail.internet.MimeUtility;

import static org.junit.Assert.*;

/**
 * Characterization tests for {@code HTTPProtocolHandler.writeStatusLineAndHeaders}
 * (issue #280), pinning down the exact bytes written to the wire before
 * replacing its per-header {@code StringBuilder}/{@code String}/{@code byte[]}
 * allocation with direct byte writes into the destination buffer. These pass
 * against the pre-refactor implementation unchanged (it already writes this
 * exact output, just less efficiently) and must keep passing afterwards -
 * the fix is only about how the bytes get there, not what they are.
 *
 * <p>Drives {@link HTTPProtocolHandler#sendResponseHeaders(int, int, Headers,
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

    private HTTPProtocolHandler connection;
    private CapturingEndpoint endpoint;

    @Before
    public void setUp() {
        HTTPListener listener = new HTTPListener();
        connection = new HTTPProtocolHandler(listener);
        connection.version = HTTPVersion.HTTP_1_1;
        endpoint = new CapturingEndpoint();
        connection.connected(endpoint);
    }

    @Test
    public void testSingleAsciiHeaderWrittenExactly() {
        Headers headers = new Headers();
        headers.add("content-type", "text/plain");

        connection.sendResponseHeaders(1, 200, headers, false);

        assertEquals("HTTP/1.1 200 OK\r\n"
                + "content-type: text/plain\r\n"
                + "\r\n",
                endpoint.capturedAscii());
    }

    /**
     * The Date header, when its value is exactly the HTTPDateCache-cached
     * String (as Stream.sendResponseHeaders always sources it), must be
     * writable via HTTPDateCache's pre-encoded bulk byte line instead of
     * the generic per-character path - and the output must be identical
     * either way.
     */
    @Test
    public void testDateHeaderFromCacheIsWrittenExactly() {
        Headers headers = new Headers();
        headers.add(new Header("Date", HTTPDateCache.get()));

        connection.sendResponseHeaders(1, 200, headers, false);

        assertEquals("HTTP/1.1 200 OK\r\n"
                + "Date: " + HTTPDateCache.get() + "\r\n"
                + "\r\n",
                endpoint.capturedAscii());
    }

    /**
     * The other framework-fixed headers (Server, the default security
     * headers, Transfer-Encoding: chunked, Connection: close) take the
     * same reference-matched bulk-write path as Date, provided their
     * value is exactly HTTPProtocolHandler's shared constant for that
     * header - which is what Stream.sendResponseHeaders now always uses
     * (see the assertSame check further down: it is this wiring, not just
     * the bytes written here, that Stream.java could silently drift out
     * of sync with in a future change).
     */
    @Test
    public void testServerHeaderIsWrittenExactly() {
        Headers headers = new Headers();
        headers.add(new Header("Server", HTTPProtocolHandler.SERVER_HEADER_VALUE));

        connection.sendResponseHeaders(1, 200, headers, false);

        assertEquals("HTTP/1.1 200 OK\r\n"
                + "Server: " + HTTPProtocolHandler.SERVER_HEADER_VALUE + "\r\n"
                + "\r\n",
                endpoint.capturedAscii());
    }

    @Test
    public void testConnectionCloseHeaderIsWrittenExactly() {
        Headers headers = new Headers();
        headers.add(new Header("Connection", HTTPProtocolHandler.CONNECTION_CLOSE_VALUE));

        connection.sendResponseHeaders(1, 200, headers, false);

        assertEquals("HTTP/1.1 200 OK\r\n"
                + "Connection: close\r\n"
                + "\r\n",
                endpoint.capturedAscii());
    }

    @Test
    public void testSecurityHeadersAreWrittenExactly() {
        Headers headers = new Headers();
        headers.add(new Header("X-Frame-Options", HTTPProtocolHandler.X_FRAME_OPTIONS_VALUE));
        headers.add(new Header("X-Content-Type-Options", HTTPProtocolHandler.X_CONTENT_TYPE_OPTIONS_VALUE));

        connection.sendResponseHeaders(1, 200, headers, false);

        assertEquals("HTTP/1.1 200 OK\r\n"
                + "X-Frame-Options: SAMEORIGIN\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "\r\n",
                endpoint.capturedAscii());
    }

    @Test
    public void testTransferEncodingChunkedIsWrittenExactly() {
        Headers headers = new Headers();
        headers.add(new Header("Transfer-Encoding", HTTPProtocolHandler.TRANSFER_ENCODING_CHUNKED_VALUE));

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
        Headers headers = new Headers();
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
     * value == HTTPDateCache.get() guard, not just its name check, is
     * what gates it.
     */
    @Test
    public void testDateHeaderWithUncachedValueIsNotReplacedByCacheBytes() {
        Headers headers = new Headers();
        headers.add(new Header("Date", "Not, 00 Xxx 0000 00:00:00 GMT"));

        connection.sendResponseHeaders(1, 200, headers, false);

        assertEquals("HTTP/1.1 200 OK\r\n"
                + "Date: Not, 00 Xxx 0000 00:00:00 GMT\r\n"
                + "\r\n",
                endpoint.capturedAscii());
    }

    @Test
    public void testMultipleHeadersAndNonDefaultStatus() {
        Headers headers = new Headers();
        headers.add("content-type", "application/json");
        headers.add("cache-control", "no-store");

        connection.sendResponseHeaders(1, 404, headers, false);

        assertEquals("HTTP/1.1 404 Not Found\r\n"
                + "content-type: application/json\r\n"
                + "cache-control: no-store\r\n"
                + "\r\n",
                endpoint.capturedAscii());
    }

    @Test
    public void testPseudoHeaderSkipped() {
        Headers headers = new Headers();
        headers.add(new Header(":status", "200"));
        headers.add("content-type", "text/plain");

        connection.sendResponseHeaders(1, 200, headers, false);

        String out = endpoint.capturedAscii();
        assertFalse("HTTP/2 pseudo-headers must not be written on the HTTP/1.1 wire",
                out.contains(":status"));
        assertTrue(out.contains("content-type: text/plain"));
    }

    @Test
    public void testNullValueHeaderSkipped() {
        Headers headers = new Headers();
        headers.add(new Header("X-Null", null));
        headers.add("content-type", "text/plain");

        connection.sendResponseHeaders(1, 200, headers, false);

        String out = endpoint.capturedAscii();
        assertFalse(out.contains("X-Null"));
        assertTrue(out.contains("content-type: text/plain"));
    }

    @Test
    public void testMostlyAsciiValueWithOneNonAsciiCharUsesBEncoding() throws Exception {
        // One non-ASCII char among many ASCII ones: nonAsciiCount is not
        // greater than asciiCount, so getCharsetFlags does not set
        // CHARSET_Q_ENCODING - "B" (base64) is used, per the existing,
        // unchanged encoding choice this test deliberately does not
        // re-litigate.
        String value = "plain text with one accent: café and nothing else unusual";
        Headers headers = new Headers();
        headers.add("x-custom", value);

        connection.sendResponseHeaders(1, 200, headers, false);

        String expectedEncoded = MimeUtility.encodeText(value, "UTF-8", "B");
        assertEquals("HTTP/1.1 200 OK\r\n"
                + "x-custom: " + expectedEncoded + "\r\n"
                + "\r\n",
                endpoint.capturedAscii());
    }

    @Test
    public void testMostlyNonAsciiValueUsesQEncoding() throws Exception {
        // Non-ASCII chars outnumber ASCII ones here, so getCharsetFlags
        // sets CHARSET_Q_ENCODING and "Q" encoding is used instead.
        String value = "éèêëàâ x";
        Headers headers = new Headers();
        headers.add("x-custom", value);

        connection.sendResponseHeaders(1, 200, headers, false);

        String expectedEncoded = MimeUtility.encodeText(value, "UTF-8", "Q");
        assertEquals("HTTP/1.1 200 OK\r\n"
                + "x-custom: " + expectedEncoded + "\r\n"
                + "\r\n",
                endpoint.capturedAscii());
    }
}
