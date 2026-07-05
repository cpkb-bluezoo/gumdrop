/*
 * StreamRequestBodyLimitTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
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
 * Tests for RFC 9110 section 15.5.14 request-body size limits (SEC-010).
 */
public class StreamRequestBodyLimitTest {

    private static class StubConnection implements HTTPConnectionLike {
        long maxRequestBodySize = 10;
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
    public void testContentLengthExceedsMaxRejectedAtHeaders() throws Exception {
        StubConnection conn = new StubConnection();
        Stream stream = new Stream(conn, 1);
        stream.addHeader(new Header(":method", "POST"));
        stream.addHeader(new Header("Content-Length", "100"));
        stream.streamEndHeaders();
        assertEquals(413, conn.lastStatusCode);
    }

    @Test
    public void testCumulativeBodyExceedsMaxRejected() throws Exception {
        StubConnection conn = new StubConnection();
        Stream stream = new Stream(conn, 1);
        stream.receiveRequestBody(ByteBuffer.wrap(new byte[6]));
        stream.receiveRequestBody(ByteBuffer.wrap(new byte[6]));
        assertEquals(413, conn.lastStatusCode);
        assertEquals(6, stream.getRequestBodyBytesReceived());
    }

    @Test
    public void testUnlimitedAllowsLargeBody() throws Exception {
        StubConnection conn = new StubConnection();
        conn.maxRequestBodySize = 0;
        Stream stream = new Stream(conn, 1);
        stream.receiveRequestBody(ByteBuffer.wrap(new byte[100]));
        assertEquals(-1, conn.lastStatusCode);
        assertEquals(100, stream.getRequestBodyBytesReceived());
    }

    @Test
    public void testHttp2ContentLengthExceedsMaxRejectedAtHeaders() throws Exception {
        StubConnection conn = new StubConnection();
        conn.version = HTTPVersion.HTTP_2_0;
        Stream stream = new Stream(conn, 1);
        stream.addHeader(new Header(":method", "POST"));
        stream.addHeader(new Header(":scheme", "https"));
        stream.addHeader(new Header(":path", "/"));
        stream.addHeader(new Header("content-length", "100"));
        stream.streamEndHeaders();
        assertEquals(413, conn.lastStatusCode);
    }
}
