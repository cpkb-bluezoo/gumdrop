/*
 * H3ClientWebSocketResponseHandlerTest.java
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

package org.bluezoo.gumdrop.http.h3;

import java.lang.reflect.Field;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.qpack.Decoder;
import org.bluezoo.gumdrop.http.qpack.SimpleEncoder;
import org.bluezoo.gumdrop.quic.QuicConnectionCloseException;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketFrame;
import org.bluezoo.gumdrop.websocket.WebSocketSession;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Regression tests for issue #397's {@link H3ClientStream} rearchitecture:
 * {@link H3ClientStream} itself now has no notion of WebSocket at all, so
 * these tests drive the real {@code H3ClientStream} dispatch path (not
 * {@link H3ClientWebSocketResponseHandler} directly) to confirm the two
 * classes are still correctly wired together -- opened/message/close
 * (RFC 9220), a rejected upgrade, and the QUIC-connection-loss-maps-to-
 * close-1006 path (RFC 6455 section 7.4) all still work exactly as they
 * did when {@code H3ClientStream} carried a dedicated {@code wsHandler}
 * field.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class H3ClientWebSocketResponseHandlerTest {

    @Test
    public void testSuccessfulUpgradeOpensReceivesAndClosesNormally() throws Exception {
        RecordingWsHandler wsHandler = new RecordingWsHandler();
        H3ClientStream stream = createWebSocketStream(wsHandler);

        stream.headersFrameReceived(encode(":status", "200"));
        assertNotNull("opened() should have been called", wsHandler.session);
        assertNull("error() should not have been called", wsHandler.error);

        ByteBuffer frame = WebSocketFrame.createTextFrame("hello", false).encode();
        stream.dataFrameReceived(frame, true);
        assertEquals("hello", wsHandler.lastTextMessage);

        stream.readFinished();
        assertEquals("RFC 6455 close code for a clean transport close",
                1001, wsHandler.closeCode);
    }

    @Test
    public void testRejectedUpgradeReportsErrorWithoutOpening() throws Exception {
        RecordingWsHandler wsHandler = new RecordingWsHandler();
        H3ClientStream stream = createWebSocketStream(wsHandler);

        stream.headersFrameReceived(encode(":status", "403"));

        assertNotNull("error() should have been called", wsHandler.error);
        assertNull("opened() should not have been called", wsHandler.session);
    }

    @Test
    public void testQuicConnectionCloseAfterUpgradeMapsToCode1006() throws Exception {
        RecordingWsHandler wsHandler = new RecordingWsHandler();
        H3ClientStream stream = createWebSocketStream(wsHandler);

        stream.headersFrameReceived(encode(":status", "200"));
        assertNotNull("opened() should have been called", wsHandler.session);

        stream.error(new QuicConnectionCloseException(true, 0x10c, "server going away"));

        assertEquals("RFC 6455 section 7.4: 1006 for an abnormal close with no closing handshake",
                1006, wsHandler.closeCode);
        assertFalse("a QUIC close after a successful upgrade must not surface as "
                + "wsHandler.error() -- it's a close, not a generic failure",
                wsHandler.errorCalledAfterOpen);
    }

    private H3ClientStream createWebSocketStream(WebSocketEventHandler wsHandler) throws Exception {
        H3ClientWebSocketResponseHandler responseHandler =
                new H3ClientWebSocketResponseHandler(null, wsHandler);
        // connection is null: exercises the stream/handler wiring in
        // isolation, without a real HTTP3ClientHandler/QuicConnection
        // stack -- H3ClientStream tolerates this (see H3ClientStreamTest).
        H3ClientStream stream = new H3ClientStream(null, new Decoder(4096), responseHandler);
        responseHandler.bindStream(stream);
        setField(stream, "streamId", 1L);
        // A closed WebSocket connection writes its close frame/tears down
        // the transport (H3ClientWebSocketTransport.close()), which needs
        // a non-null endpoint -- connected() isn't otherwise exercised in
        // this isolated unit test (see H3ClientStreamTest's own comment).
        setField(stream, "endpoint", new StubEndpoint());

        Headers requestHeaders = new Headers();
        requestHeaders.add(new Header(":method", "CONNECT"));
        requestHeaders.add(new Header(":protocol", "websocket"));
        stream.prepareRequest(requestHeaders, false);
        return stream;
    }

    private static void setField(H3ClientStream stream, String name, Object value) throws Exception {
        Field f = H3ClientStream.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(stream, value);
    }

    private static ByteBuffer encode(String... pairs) {
        SimpleEncoder encoder = new SimpleEncoder();
        java.util.List<Header> headers = new java.util.ArrayList<Header>();
        for (int i = 0; i < pairs.length; i += 2) {
            headers.add(new Header(pairs[i], pairs[i + 1]));
        }
        ByteBuffer buf = ByteBuffer.allocate(4096);
        encoder.encode(buf, headers);
        buf.flip();
        return buf;
    }

    private static class StubEndpoint implements Endpoint {
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
        @Override public org.bluezoo.gumdrop.TimerHandle scheduleTimer(long delayMs, Runnable callback) { return null; }
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public Trace getTrace() { return null; }
        @Override public void setTrace(Trace trace) { }
        @Override public boolean isTelemetryEnabled() { return false; }
        @Override public TelemetryConfig getTelemetryConfig() { return null; }
    }

    private static class RecordingWsHandler implements WebSocketEventHandler {
        WebSocketSession session;
        String lastTextMessage;
        int closeCode = -1;
        String closeReason;
        Throwable error;
        boolean errorCalledAfterOpen;

        @Override
        public void opened(WebSocketSession session) {
            this.session = session;
        }

        @Override
        public void textMessageReceived(WebSocketSession session, String message) {
            this.lastTextMessage = message;
        }

        @Override
        public void binaryMessageReceived(WebSocketSession session, ByteBuffer data) {
        }

        @Override
        public void closed(int code, String reason) {
            this.closeCode = code;
            this.closeReason = reason;
        }

        @Override
        public void error(Throwable cause) {
            this.error = cause;
            if (session != null) {
                errorCalledAfterOpen = true;
            }
        }
    }
}
