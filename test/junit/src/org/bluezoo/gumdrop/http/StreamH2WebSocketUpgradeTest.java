/*
 * StreamH2WebSocketUpgradeTest.java
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
import org.bluezoo.gumdrop.http.h2.H2FrameHandler;
import org.bluezoo.gumdrop.http.hpack.Decoder;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.websocket.DefaultWebSocketEventHandler;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

import static org.junit.Assert.*;

/**
 * RFC 8441 — unit tests for {@code Stream}'s Extended CONNECT (WebSocket
 * over HTTP/2) header validation and upgrade acceptance.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class StreamH2WebSocketUpgradeTest {

    private static class StubConnection implements HTTPConnectionLike {
        HTTPVersion version = HTTPVersion.HTTP_2_0;
        int lastStatusCode = -1;
        boolean rstStreamSent = false;
        int lastRstStreamErrorCode = -1;
        boolean switchedToWebSocketMode = false;
        HTTPRequestHandlerFactory handlerFactory;

        @Override public String getScheme() { return "https"; }
        @Override public HTTPVersion getVersion() { return version; }
        @Override public SocketAddress getRemoteSocketAddress() {
            return new InetSocketAddress("127.0.0.1", 12345);
        }
        @Override public SocketAddress getLocalSocketAddress() {
            return new InetSocketAddress("127.0.0.1", 443);
        }
        @Override public SecurityInfo getSecurityInfoForStream() { return null; }
        @Override public HTTPRequestHandlerFactory getHandlerFactory() { return handlerFactory; }
        @Override public void sendResponseHeaders(int streamId, int statusCode,
                Headers headers, boolean endStream) {
            lastStatusCode = statusCode;
        }
        @Override public void sendResponseBody(int streamId, ByteBuffer buf, boolean endStream) { }
        @Override public void send(ByteBuffer buf) { }
        @Override public void sendRstStream(int streamId, int errorCode) {
            rstStreamSent = true;
            lastRstStreamErrorCode = errorCode;
        }
        @Override public void sendGoaway(int errorCode) { }
        @Override public void switchToWebSocketMode(int streamId) {
            switchedToWebSocketMode = true;
        }
        @Override public void switchToStreamTunnelMode(int streamId) { }
        @Override public org.bluezoo.gumdrop.TimerHandle scheduleTimer(long delayMs, Runnable callback) { return null; }
        @Override public Decoder getHpackDecoder() { return null; }
        @Override public boolean isSecure() { return true; }
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
        @Override public long getMaxRequestBodySize() { return 0; }
        @Override public HTTPAuthenticationProvider getAuthenticationProvider() { return null; }
        @Override public void onWritable(int streamId, Runnable callback) { }
        @Override public void pauseRead(int streamId) { }
        @Override public void resumeRead(int streamId) { }
        @Override public int pendingResponseBytes(int streamId) { return 0; }
    }

    /** A factory whose handler immediately accepts the upgrade. */
    private static HTTPRequestHandlerFactory upgradingFactory() {
        return new HTTPRequestHandlerFactory() {
            @Override
            public HTTPRequestHandler createHandler(HTTPResponseState state, Headers headers) {
                return new DefaultHTTPRequestHandler() {
                    @Override
                    public void headers(HTTPResponseState state, Headers headers) {
                        state.upgradeToWebSocket(null, new DefaultWebSocketEventHandler() { });
                    }
                };
            }
        };
    }

    // -- validateH2Headers() via Extended CONNECT (RFC 8441 section 4) --

    @Test
    public void testExtendedConnectMissingSchemeRejected() {
        StubConnection conn = new StubConnection();
        Stream stream = new Stream(conn, 1);
        stream.addHeader(new Header(":method", "CONNECT"));
        stream.addHeader(new Header(":protocol", "websocket"));
        stream.addHeader(new Header(":path", "/ws"));
        stream.streamEndHeaders();

        assertTrue("extended CONNECT missing :scheme must be rejected", conn.rstStreamSent);
        assertEquals(H2FrameHandler.ERROR_PROTOCOL_ERROR, conn.lastRstStreamErrorCode);
    }

    @Test
    public void testExtendedConnectMissingPathRejected() {
        StubConnection conn = new StubConnection();
        Stream stream = new Stream(conn, 1);
        stream.addHeader(new Header(":method", "CONNECT"));
        stream.addHeader(new Header(":protocol", "websocket"));
        stream.addHeader(new Header(":scheme", "https"));
        stream.streamEndHeaders();

        assertTrue("extended CONNECT missing :path must be rejected", conn.rstStreamSent);
        assertEquals(H2FrameHandler.ERROR_PROTOCOL_ERROR, conn.lastRstStreamErrorCode);
    }

    @Test
    public void testExtendedConnectWithSchemeAndPathAccepted() {
        StubConnection conn = new StubConnection();
        conn.handlerFactory = upgradingFactory();
        Stream stream = new Stream(conn, 1);
        stream.addHeader(new Header(":method", "CONNECT"));
        stream.addHeader(new Header(":protocol", "websocket"));
        stream.addHeader(new Header(":scheme", "https"));
        stream.addHeader(new Header(":path", "/ws"));
        stream.streamEndHeaders();

        assertFalse("a well-formed extended CONNECT must not be reset", conn.rstStreamSent);
    }

    @Test
    public void testClassicConnectStillOnlyNeedsMethod() {
        // RFC 9113 section 8.3.1: classic (non-extended) CONNECT, no
        // :protocol, needs only :method -- unaffected by the RFC 8441 change.
        StubConnection conn = new StubConnection();
        Stream stream = new Stream(conn, 1);
        stream.addHeader(new Header(":method", "CONNECT"));
        stream.streamEndHeaders();

        assertFalse("classic CONNECT (no :protocol) must not require :scheme/:path",
                conn.rstStreamSent);
    }

    @Test
    public void testOrdinaryGetRequestUnaffected() {
        StubConnection conn = new StubConnection();
        Stream stream = new Stream(conn, 1);
        stream.addHeader(new Header(":method", "GET"));
        stream.addHeader(new Header(":scheme", "https"));
        stream.addHeader(new Header(":path", "/"));
        stream.streamEndHeaders();

        assertFalse(conn.rstStreamSent);
    }

    // -- upgradeToWebSocket() h2 acceptance (RFC 8441 section 4) --

    @Test
    public void testUpgradeAcceptedWithHttp200AndSwitchesConnectionMode() {
        StubConnection conn = new StubConnection();
        conn.handlerFactory = upgradingFactory();
        Stream stream = new Stream(conn, 1);
        stream.addHeader(new Header(":method", "CONNECT"));
        stream.addHeader(new Header(":protocol", "websocket"));
        stream.addHeader(new Header(":scheme", "https"));
        stream.addHeader(new Header(":path", "/ws"));
        stream.streamEndHeaders();

        // RFC 8441 section 4: 200, not 101 -- there is no Sec-WebSocket-Key
        // exchange over HTTP/2.
        assertEquals(200, conn.lastStatusCode);
        assertTrue(conn.switchedToWebSocketMode);
    }

    @Test(expected = IllegalStateException.class)
    public void testUpgradeToWebSocketOnNonExtendedConnectThrows() {
        StubConnection conn = new StubConnection();
        Stream stream = new Stream(conn, 1);
        stream.addHeader(new Header(":method", "GET"));
        stream.addHeader(new Header(":scheme", "https"));
        stream.addHeader(new Header(":path", "/"));
        stream.streamEndHeaders();

        stream.upgradeToWebSocket(null, new DefaultWebSocketEventHandler() { });
    }
}
