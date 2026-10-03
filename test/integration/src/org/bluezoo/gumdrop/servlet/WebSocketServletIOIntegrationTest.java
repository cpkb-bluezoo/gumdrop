/*
 * WebSocketServletIOIntegrationTest.java
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

import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.server.HttpResponseState;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketSession;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.WebConnection;

import static org.junit.Assert.*;

/**
 * Tests non-blocking WebSocket servlet input delivery via
 * {@link ServletWebConnection}.
 *
 * <p>Integration test: uses a real reader thread for the blocking read and the
 * real servlet worker pool to check which thread init and destroy run on.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebSocketServletIOIntegrationTest {

    @Test
    public void testBlockingReadWithoutListener() throws Exception {
        TrackingState state = new TrackingState();
        Container service = new Container();
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
    public void testUpgradeHandlerDestroyMarshalledToWorkerThread() throws Exception {
        TrackingState state = new TrackingState();
        Container service = new Container();
        StubServletHandler handler = new StubServletHandler(service, state);
        final CountDownLatch initStarted = new CountDownLatch(1);
        final CountDownLatch destroyDone = new CountDownLatch(1);
        final AtomicReference<String> destroyThread = new AtomicReference<String>();
        final String callingThread = Thread.currentThread().getName();
        HttpUpgradeHandler upgradeHandler = new HttpUpgradeHandler() {
            @Override
            public void init(WebConnection wc) {
                initStarted.countDown();
            }
            @Override
            public void destroy() {
                destroyThread.set(Thread.currentThread().getName());
                destroyDone.countDown();
            }
        };
        ServletWebConnection connection =
                new ServletWebConnection(upgradeHandler, state, handler);

        connection.getEventHandler().opened(new StubWebSocketSession());
        assertTrue(initStarted.await(2, TimeUnit.SECONDS));
        connection.getEventHandler().closed(1000, "bye");

        assertTrue(destroyDone.await(2, TimeUnit.SECONDS));
        assertNotEquals(callingThread, destroyThread.get());
        assertTrue(destroyThread.get().startsWith("servlet-worker-"));
    }

    @Test
    public void testUpgradeHandlerInitMarshalledToWorkerThread() throws Exception {
        TrackingState state = new TrackingState();
        Container service = new Container();
        StubServletHandler handler = new StubServletHandler(service, state);
        final CountDownLatch initDone = new CountDownLatch(1);
        final AtomicReference<String> initThread = new AtomicReference<String>();
        final String callingThread = Thread.currentThread().getName();
        HttpUpgradeHandler upgradeHandler = new HttpUpgradeHandler() {
            @Override
            public void init(WebConnection wc) {
                initThread.set(Thread.currentThread().getName());
                initDone.countDown();
            }
            @Override
            public void destroy() { }
        };
        ServletWebConnection connection =
                new ServletWebConnection(upgradeHandler, state, handler);

        connection.getEventHandler().opened(new StubWebSocketSession());

        assertTrue(initDone.await(2, TimeUnit.SECONDS));
        assertNotEquals(callingThread, initThread.get());
        assertTrue(initThread.get().startsWith("servlet-worker-"));
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
        private final HttpResponseState stubState;

        StubServletHandler(Container service, HttpResponseState stubState) {
            super(service, stubState, 8192);
            this.stubState = stubState;
        }

        @Override
        HttpResponseState getState() {
            return stubState;
        }
    }

    private static class StubHTTPResponseState implements HttpResponseState {
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
