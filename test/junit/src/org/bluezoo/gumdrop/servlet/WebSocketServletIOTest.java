/*
 * WebSocketServletIOTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.http.HTTPVersion;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketSession;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.ReadListener;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.WebConnection;

import static org.junit.Assert.*;

/**
 * Tests non-blocking WebSocket servlet input delivery via
 * {@link ServletWebConnection}.
 */
public class WebSocketServletIOTest {

    @Test
    public void testMessageDeliveryDoesNotRequireBlockingReadSide() throws Exception {
        TrackingState state = new TrackingState();
        ServletService service = new ServletService();
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
        ServletService service = new ServletService();
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
        ServletService service = new ServletService();
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
        ServletService service = new ServletService();
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
    public void testBlockingReadWithoutListener() throws Exception {
        TrackingState state = new TrackingState();
        ServletService service = new ServletService();
        StubServletHandler handler = new StubServletHandler(service, state);
        ServletWebConnection connection =
                new ServletWebConnection(new NoOpUpgradeHandler(), state, handler);

        final CountDownLatch blocked = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger readByte = new AtomicInteger(-1);
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                blocked.countDown();
                try {
                    readByte.set(connection.getInputStream().read());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            }
        });
        reader.start();
        assertTrue("reader should reach blocking read",
                blocked.await(2, TimeUnit.SECONDS));
        connection.getEventHandler().textMessageReceived(null, "z");
        assertTrue("read should complete after message arrives",
                done.await(2, TimeUnit.SECONDS));
        assertEquals('z', readByte.get());
    }

    @Test
    public void testResumeRequestBodyAfterDrain() throws Exception {
        TrackingState state = new TrackingState();
        ServletService service = new ServletService();
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

    private static final class TrackingState extends StubHTTPResponseState {
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
        private final HTTPResponseState stubState;

        StubServletHandler(ServletService service, HTTPResponseState stubState) {
            super(service, service.getContainer(), 8192);
            this.stubState = stubState;
        }

        @Override
        HTTPResponseState getState() {
            return stubState;
        }
    }

    private static class StubHTTPResponseState implements HTTPResponseState {
        @Override public java.net.SocketAddress getRemoteAddress() {
            return new java.net.InetSocketAddress("127.0.0.1", 54321);
        }
        @Override public java.net.SocketAddress getLocalAddress() {
            return new java.net.InetSocketAddress("127.0.0.1", 8080);
        }
        @Override public boolean isSecure() { return false; }
        @Override public org.bluezoo.gumdrop.SecurityInfo getSecurityInfo() { return null; }
        @Override public HTTPVersion getVersion() { return HTTPVersion.HTTP_2_0; }
        @Override public String getScheme() { return "http"; }
        @Override public org.bluezoo.gumdrop.SelectorLoop getSelectorLoop() { return null; }
        @Override public java.security.Principal getPrincipal() { return null; }
        @Override public void headers(Headers headers) { }
        @Override public void startResponseBody() { }
        @Override public void responseBodyContent(ByteBuffer data) { }
        @Override public void endResponseBody() { }
        @Override public void complete() { }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public void onWritable(Runnable callback) {
            if (callback != null) {
                callback.run();
            }
        }
        @Override public void pauseRequestBody() { }
        @Override public void resumeRequestBody() { }
        @Override public boolean pushPromise(Headers headers) { return false; }
        @Override public void upgradeToWebSocket(String protocol,
                WebSocketEventHandler handler) { }
        @Override public void cancel() { }
        @Override public int pendingResponseBytes() { return 0; }
    }
}
