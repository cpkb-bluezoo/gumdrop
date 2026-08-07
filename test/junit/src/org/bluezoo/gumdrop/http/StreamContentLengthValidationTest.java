/*
 * StreamContentLengthValidationTest.java
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

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.hpack.Decoder;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

import static org.junit.Assert.*;

/**
 * Regression tests for issue #114: a malformed or conflicting duplicate
 * {@code Content-Length} must be rejected with 400 and the connection
 * closed (RFC 9112 §6.3), not silently stripped and the request processed
 * as if it had no body — the latter can desync a front-end proxy's view
 * of the body boundary from gumdrop's, enabling HTTP request smuggling.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class StreamContentLengthValidationTest {

    private static class StubConnection implements HTTPConnectionLike {
        long maxRequestBodySize = 0; // unlimited, so only CL validation is under test
        HTTPVersion version = HTTPVersion.HTTP_1_1;
        int lastStatusCode = -1;

        @Override public String getScheme() { return "http"; }
        @Override public HTTPVersion getVersion() { return version; }
        @Override public SocketAddress getRemoteSocketAddress() {
            return new InetSocketAddress("127.0.0.1", 12345);
        }
        @Override public SocketAddress getLocalSocketAddress() {
            return new InetSocketAddress("127.0.0.1", 80);
        }
        @Override public SecurityInfo getSecurityInfoForStream() { return null; }
        @Override public HTTPRequestHandlerFactory getHandlerFactory() { return null; }
        @Override public void sendResponseHeaders(int streamId, int statusCode,
                Headers headers, boolean endStream) {
            lastStatusCode = statusCode;
        }
        @Override public void sendResponseBody(int streamId, ByteBuffer buf, boolean endStream) { }
        @Override public void send(ByteBuffer buf) { }
        @Override public void sendRstStream(int streamId, int errorCode) { }
        @Override public void sendGoaway(int errorCode) { }
        @Override public void switchToWebSocketMode(int streamId) { }
        @Override public Decoder getHpackDecoder() { return null; }
        @Override public boolean isSecure() { return false; }
        @Override public TelemetryConfig getTelemetryConfig() { return null; }
        @Override public Trace getTrace() { return null; }
        @Override public void setTrace(Trace trace) { }
        @Override public boolean isTelemetryEnabled() { return false; }
        @Override public HTTPServerMetrics getServerMetrics() { return null; }
        @Override public boolean isEnablePush() { return false; }
        @Override public Stream newStream(HTTPConnectionLike connection, int streamId) {
            return new Stream(connection, streamId);
        }
        @Override public int getNextServerStreamId() { return 2; }
        @Override public byte[] encodeHeaders(Headers headers) { return new byte[0]; }
        @Override public void sendPushPromise(int streamId, int promisedStreamId,
                ByteBuffer headerBlock, boolean endHeaders) { }
        @Override public Stream createPushedStream(int streamId, String method,
                String uri, Headers headers) { return null; }
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public int getMaxHeaderListSize() { return 8192; }
        @Override public long getMaxRequestBodySize() { return maxRequestBodySize; }
        @Override public void onWritable(int streamId, Runnable callback) { }
        @Override public void pauseRead(int streamId) { }
        @Override public void resumeRead(int streamId) { }
    }

    @Test
    public void testNonNumericContentLengthRejected() throws Exception {
        StubConnection conn = new StubConnection();
        Stream stream = new Stream(conn, 1);
        stream.addHeader(new Header(":method", "GET"));
        stream.addHeader(new Header("Content-Length", "5x"));
        stream.streamEndHeaders();
        assertEquals(400, conn.lastStatusCode);
        assertTrue("connection must be closed after a malformed Content-Length",
                stream.isCloseConnection());
    }

    @Test
    public void testNegativeContentLengthRejected() throws Exception {
        StubConnection conn = new StubConnection();
        Stream stream = new Stream(conn, 1);
        stream.addHeader(new Header(":method", "GET"));
        stream.addHeader(new Header("Content-Length", "-1"));
        stream.streamEndHeaders();
        assertEquals(400, conn.lastStatusCode);
        assertTrue(stream.isCloseConnection());
    }

    @Test
    public void testUnequalCommaListContentLengthRejected() throws Exception {
        StubConnection conn = new StubConnection();
        Stream stream = new Stream(conn, 1);
        stream.addHeader(new Header(":method", "GET"));
        stream.addHeader(new Header("Content-Length", "5, 6"));
        stream.streamEndHeaders();
        assertEquals(400, conn.lastStatusCode);
        assertTrue(stream.isCloseConnection());
    }

    @Test
    public void testDuplicateConflictingContentLengthRejectedOnBodyMethod() throws Exception {
        StubConnection conn = new StubConnection();
        Stream stream = new Stream(conn, 1);
        stream.addHeader(new Header(":method", "POST"));
        stream.addHeader(new Header("Content-Length", "5"));
        stream.addHeader(new Header("Content-Length", "44"));
        stream.streamEndHeaders();
        assertEquals(400, conn.lastStatusCode);
        assertTrue("a request smuggled via CL.CL desync requires the "
                        + "connection to be closed, not kept alive",
                stream.isCloseConnection());
    }

    @Test
    public void testDuplicateConflictingContentLengthRejectedOnNoBodyMethod() throws Exception {
        // The original bug: GET/HEAD/DELETE/OPTIONS/TRACE default
        // contentLength to 0 regardless of a malformed/conflicting header,
        // so this case was previously *not* caught by the generic
        // "unresolved contentLength" 411 path that body-bearing methods
        // fall into.
        StubConnection conn = new StubConnection();
        Stream stream = new Stream(conn, 1);
        stream.addHeader(new Header(":method", "GET"));
        stream.addHeader(new Header("Content-Length", "5"));
        stream.addHeader(new Header("Content-Length", "44"));
        stream.streamEndHeaders();
        assertEquals(400, conn.lastStatusCode);
        assertTrue(stream.isCloseConnection());
    }

    @Test
    public void testDuplicateIdenticalContentLengthAllowed() throws Exception {
        // Two identical Content-Length values are unambiguous and must not
        // be rejected — only genuinely conflicting or malformed values are
        // a smuggling risk. (The request still 404s past this point since
        // the stub has no handler factory/routing; what matters here is
        // that it is NOT rejected as a bad Content-Length.)
        StubConnection conn = new StubConnection();
        Stream stream = new Stream(conn, 1);
        stream.addHeader(new Header(":method", "POST"));
        stream.addHeader(new Header("Content-Length", "5"));
        stream.addHeader(new Header("Content-Length", "5"));
        stream.streamEndHeaders();
        assertNotEquals(400, conn.lastStatusCode);
    }

    @Test
    public void testValidContentLengthAllowed() throws Exception {
        StubConnection conn = new StubConnection();
        Stream stream = new Stream(conn, 1);
        stream.addHeader(new Header(":method", "POST"));
        stream.addHeader(new Header("Content-Length", "5"));
        stream.streamEndHeaders();
        assertNotEquals(400, conn.lastStatusCode);
    }
}
