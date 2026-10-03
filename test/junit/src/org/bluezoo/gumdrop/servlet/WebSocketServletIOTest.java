/*
 * WebSocketServletIOTest.java
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

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.http.server.HttpResponse;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketSession;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.WebConnection;

import static org.junit.Assert.*;

/**
 * Tests non-blocking WebSocket servlet input delivery via
 * {@link ServletWebConnection}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebSocketServletIOTest {

    @Test
    public void testMessageDeliveryDoesNotRequireBlockingReadSide() throws Exception {
        TrackingState state = new TrackingState();
        Container service = new Container();
        StubServletHandler handler = new StubServletHandler(service, state);
        ServletWebConnection connection =
                new ServletWebConnection(new NoOpUpgradeHandler(), state, handler);

        WebSocketEventHandler events = connection.getEventHandler();
        events.opened(new StubWebSocketSession());

        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        events.textMessageReceived(null, "hello");

        assertEquals(payload.length, connection.getInputStream().available());
        byte[] buf = new byte[payload.length];
        assertEquals(payload.length, connection.getInputStream().read(buf));
        assertEquals("hello", new String(buf, StandardCharsets.UTF_8));
    }

    @Test
    public void testHighWatermarkPausesRequestBody() throws Exception {
        TrackingState state = new TrackingState();
        Container service = new Container();
        StubServletHandler handler = new StubServletHandler(service, state);
        ServletWebConnection connection =
                new ServletWebConnection(new NoOpUpgradeHandler(), state, handler);

        byte[] chunk = new byte[RequestBodyStream.HIGH_WATERMARK];
        connection.getEventHandler().binaryMessageReceived(null, ByteBuffer.wrap(chunk));
        assertEquals(1, state.pauseCount.get());
    }

    @Test
    public void testReadListenerNotifiedOnMessage() throws Exception {
        TrackingState state = new TrackingState();
        Container service = new Container();
        StubServletHandler handler = new StubServletHandler(service, state);
        ServletWebConnection connection =
                new ServletWebConnection(new NoOpUpgradeHandler(), state, handler);

        AtomicInteger dataAvailable = new AtomicInteger();
        connection.getInputStream().setReadListener(new ReadListener() {
            @Override public void onDataAvailable() {
                dataAvailable.incrementAndGet();
            }
            @Override public void onAllDataRead() { }
            @Override public void onError(Throwable t) {
                fail(t.toString());
            }
        });

        connection.getEventHandler().textMessageReceived(null, "ping");
        assertTrue(dataAvailable.get() >= 1);
        assertTrue(connection.getInputStream().isReady());
    }

    @Test
    public void testReadListenerOnAllDataReadAfterClose() throws Exception {
        TrackingState state = new TrackingState();
        Container service = new Container();
        StubServletHandler handler = new StubServletHandler(service, state);
        ServletWebConnection connection =
                new ServletWebConnection(new NoOpUpgradeHandler(), state, handler);

        AtomicBoolean allRead = new AtomicBoolean();
        connection.getInputStream().setReadListener(new ReadListener() {
            @Override public void onDataAvailable() throws IOException {
                byte[] buf = new byte[64];
                while (connection.getInputStream().isReady()) {
                    if (connection.getInputStream().read(buf) <= 0) {
                        break;
                    }
                }
            }
            @Override public void onAllDataRead() {
                allRead.set(true);
            }
            @Override public void onError(Throwable t) {
                fail(t.toString());
            }
        });

        connection.getEventHandler().textMessageReceived(null, "done");
        connection.getEventHandler().closed(1000, "bye");
        assertTrue(allRead.get());
        assertTrue(connection.getInputStream().isFinished());
    }

    @Test
    public void testFlushMarshalsSendThroughIoThread() throws Exception {
        final AtomicBoolean executedOnIo = new AtomicBoolean();
        final AtomicReference<String> sentText = new AtomicReference<String>();
        TrackingState state = new TrackingState() {
            @Override
            public void execute(Runnable task) {
                executedOnIo.set(true);
                task.run();
            }
        };
        Container service = new Container();
        StubServletHandler handler = new StubServletHandler(service, state);
        ServletWebConnection connection =
                new ServletWebConnection(new NoOpUpgradeHandler(), state, handler);
        connection.getEventHandler().opened(new RecordingSession(sentText, null));

        ServletOutputStream out = connection.getOutputStream();
        out.write("hi".getBytes(StandardCharsets.UTF_8));
        out.flush();

        assertTrue(executedOnIo.get());
        assertEquals("hi", sentText.get());
    }

    @Test
    public void testFlushTransfersBufferOwnershipForBinary() throws Exception {
        final AtomicReference<ByteBuffer> sentBinary = new AtomicReference<ByteBuffer>();
        TrackingState state = new TrackingState() {
            @Override
            public void execute(Runnable task) {
                task.run();
            }
        };
        Container service = new Container();
        StubServletHandler handler = new StubServletHandler(service, state);
        ServletWebConnection connection =
                new ServletWebConnection(new NoOpUpgradeHandler(), state, handler);
        connection.getEventHandler().opened(new RecordingSession(null, sentBinary));

        WebSocketServletOutputStream out =
                (WebSocketServletOutputStream) connection.getOutputStream();
        Field bufField = WebSocketServletOutputStream.class.getDeclaredField("buf");
        bufField.setAccessible(true);
        ByteBuffer flushedBuffer = (ByteBuffer) bufField.get(out);
        byte[] invalidUtf8 = new byte[] {(byte) 0xC0, (byte) 0xC0};
        out.write(invalidUtf8);
        out.flush();

        assertNotNull(sentBinary.get());
        assertSame(flushedBuffer.array(), sentBinary.get().array());
        assertEquals(2, sentBinary.get().remaining());
    }

    @Test
    public void testInvalidUtf8SentAsBinary() throws Exception {
        final AtomicReference<ByteBuffer> sentBinary = new AtomicReference<ByteBuffer>();
        TrackingState state = new TrackingState() {
            @Override
            public void execute(Runnable task) {
                task.run();
            }
        };
        Container service = new Container();
        StubServletHandler handler = new StubServletHandler(service, state);
        ServletWebConnection connection =
                new ServletWebConnection(new NoOpUpgradeHandler(), state, handler);
        connection.getEventHandler().opened(new RecordingSession(null, sentBinary));

        byte[] invalidUtf8 = new byte[] {(byte) 0xC0, (byte) 0xC0};
        connection.getOutputStream().write(invalidUtf8);
        connection.getOutputStream().flush();

        assertNotNull(sentBinary.get());
        assertEquals(2, sentBinary.get().remaining());
    }

    @Test
    public void testWriteListenerIsReadyReflectsTransportBackpressure() throws Exception {
        BackpressureState state = new BackpressureState(5 * 1024 * 1024);
        Container service = new Container();
        StubServletHandler handler = new StubServletHandler(service, state);
        ServletWebConnection connection =
                new ServletWebConnection(new NoOpUpgradeHandler(), state, handler);
        connection.getEventHandler().opened(new StubWebSocketSession());

        WebSocketServletOutputStream out =
                (WebSocketServletOutputStream) connection.getOutputStream();
        assertFalse(out.isReady());

        AtomicBoolean notified = new AtomicBoolean();
        out.setWriteListener(new WriteListener() {
            @Override public void onWritePossible() {
                notified.set(true);
            }
            @Override public void onError(Throwable t) {
                fail(t.toString());
            }
        });
        assertFalse(notified.get());

        state.pendingBytes = 0;
        connection.notifyWritePossible();
        assertTrue(out.isReady());
        assertTrue(notified.get());
    }

    @Test
    public void testWriteNonBlockingThrowsWhenNotReady() throws Exception {
        BackpressureState state = new BackpressureState(5 * 1024 * 1024);
        Container service = new Container();
        StubServletHandler handler = new StubServletHandler(service, state);
        ServletWebConnection connection =
                new ServletWebConnection(new NoOpUpgradeHandler(), state, handler);
        connection.getEventHandler().opened(new StubWebSocketSession());

        WebSocketServletOutputStream out =
                (WebSocketServletOutputStream) connection.getOutputStream();
        out.setWriteListener(new WriteListener() {
            @Override public void onWritePossible() { }
            @Override public void onError(Throwable t) { fail(t.toString()); }
        });

        try {
            out.write('x');
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("not ready")
                    || expected.getMessage().contains("Write not ready"));
        }
    }

    @Test
    public void testResumeRequestBodyAfterDrain() throws Exception {
        TrackingState state = new TrackingState();
        Container service = new Container();
        StubServletHandler handler = new StubServletHandler(service, state);
        ServletWebConnection connection =
                new ServletWebConnection(new NoOpUpgradeHandler(), state, handler);

        byte[] chunk = new byte[RequestBodyStream.HIGH_WATERMARK];
        connection.getEventHandler().binaryMessageReceived(null, ByteBuffer.wrap(chunk));
        assertEquals(1, state.pauseCount.get());

        byte[] drain = new byte[chunk.length - RequestBodyStream.LOW_WATERMARK + 1];
        assertEquals(drain.length, connection.getInputStream().read(drain));
        assertEquals(1, state.resumeCount.get());
    }

    private static final class RecordingSession implements WebSocketSession {
        private final AtomicReference<String> sentText;
        private final AtomicReference<ByteBuffer> sentBinary;

        RecordingSession(AtomicReference<String> sentText,
                AtomicReference<ByteBuffer> sentBinary) {
            this.sentText = sentText;
            this.sentBinary = sentBinary;
        }

        @Override public boolean isOpen() { return true; }
        @Override public void sendText(String message) {
            if (sentText != null) {
                sentText.set(message);
            }
        }
        @Override public void sendBinary(ByteBuffer data) {
            if (sentBinary != null) {
                sentBinary.set(data.duplicate());
            }
        }
        @Override public void sendPing(ByteBuffer payload) { }
        @Override public void close() { }
        @Override public void close(int statusCode, String reason) { }
        @Override public java.security.Principal getPrincipal() { return null; }
    }

    private static class BackpressureState extends StubHTTPResponseState {
        volatile int pendingBytes;

        BackpressureState(int pendingBytes) {
            this.pendingBytes = pendingBytes;
        }

        @Override
        public int pendingResponseBytes() {
            return pendingBytes;
        }
    }

    private static final class NoOpUpgradeHandler implements HttpUpgradeHandler {
        @Override public void init(WebConnection wc) { }
        @Override public void destroy() { }
    }

    private static final class StubWebSocketSession implements WebSocketSession {
        @Override public boolean isOpen() { return true; }
        @Override public void sendText(String message) throws IOException { }
        @Override public void sendBinary(ByteBuffer data) throws IOException { }
        @Override public void sendPing(ByteBuffer payload) throws IOException { }
        @Override public void close() throws IOException { }
        @Override public void close(int statusCode, String reason) throws IOException { }
        @Override public java.security.Principal getPrincipal() { return null; }
    }

    private static class TrackingState extends StubHTTPResponseState {
        final AtomicInteger pauseCount = new AtomicInteger();
        final AtomicInteger resumeCount = new AtomicInteger();

        @Override
        public void pauseRequestBody() {
            pauseCount.incrementAndGet();
        }

        @Override
        public void resumeRequestBody() {
            resumeCount.incrementAndGet();
        }
    }

    private static final class StubServletHandler extends ServletHandler {
        private final HttpResponse stubState;

        StubServletHandler(Container service, HttpResponse stubState) {
            super(service, stubState, 8192);
            this.stubState = stubState;
        }

        @Override
        HttpResponse getState() {
            return stubState;
        }
    }

    private static class StubHTTPResponseState implements HttpResponse {
        @Override public java.net.SocketAddress getRemoteAddress() {
            return new java.net.InetSocketAddress("127.0.0.1", 54321);
        }
        @Override public java.net.SocketAddress getLocalAddress() {
            return new java.net.InetSocketAddress("127.0.0.1", 8080);
        }
        @Override public boolean isSecure() { return false; }
        @Override public org.bluezoo.gumdrop.SecurityInfo getSecurityInfo() { return null; }
        @Override public HttpVersion getVersion() { return HttpVersion.HTTP_2_0; }
        @Override public String getScheme() { return "http"; }
        @Override public org.bluezoo.gumdrop.SelectorLoop getSelectorLoop() { return null; }
        @Override public java.security.Principal getPrincipal() { return null; }
        @Override public void status(int code) { }
        @Override public void header(String name, String value) { }
        @Override public void endHeaders() { }
        @Override public void bodyContent(ByteBuffer data) { }
        @Override public void endMessage() { }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public void onWritable(Runnable callback) {
            if (callback != null) {
                callback.run();
            }
        }
        @Override public void pauseRequestBody() { }
        @Override public void resumeRequestBody() { }
        @Override public void startPushPromise(org.bluezoo.gumdrop.http.HttpMethod method, String target) { }
        @Override public boolean endPushPromise() { return false; }
        @Override public void upgradeToWebSocket(String protocol,
                WebSocketEventHandler handler) { }
        @Override public void cancel() { }
        @Override public int pendingResponseBytes() { return 0; }
    }
}
