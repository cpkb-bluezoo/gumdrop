/*
 * ServletNonBlockingIOTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.http.HTTPVersion;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;

import static org.junit.Assert.*;

/**
 * Tests ReadListener/WriteListener wiring to the HTTP I/O layer.
 */
public class ServletNonBlockingIOTest {

    @Test
    public void testReadListenerRequiresAsync() throws Exception {
        Request request = newRequest(new StubHTTPResponseState());
        try {
            request.getInputStream().setReadListener(new ReadListener() {
                @Override public void onDataAvailable() { }
                @Override public void onAllDataRead() { }
                @Override public void onError(Throwable t) { }
            });
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("async"));
        }
    }

    @Test
    public void testReadListenerNotifiedWhenDataArrives() throws Exception {
        StubHTTPResponseState state = new StubHTTPResponseState();
        RequestBodyStream body = new RequestBodyStream();
        Request request = newRequest(state, body);
        request.startAsync();

        AtomicInteger dataAvailable = new AtomicInteger();
        request.getInputStream().setReadListener(new ReadListener() {
            @Override public void onDataAvailable() {
                dataAvailable.incrementAndGet();
            }
            @Override public void onAllDataRead() { }
            @Override public void onError(Throwable t) {
                fail(t.toString());
            }
        });

        body.offer("chunk".getBytes(StandardCharsets.UTF_8));
        request.in.dispatchDataAvailable();
        assertTrue(dataAvailable.get() >= 1);
        assertTrue(request.getInputStream().isReady());
        assertFalse(request.getInputStream().isFinished());
    }

    @Test
    public void testReadListenerOnAllDataReadAfterDrain() throws Exception {
        RequestBodyStream body = new RequestBodyStream();
        Request request = newRequest(new StubHTTPResponseState(), body);
        request.startAsync();

        AtomicBoolean allRead = new AtomicBoolean();
        request.getInputStream().setReadListener(new ReadListener() {
            @Override public void onDataAvailable() throws IOException {
                byte[] buf = new byte[64];
                while (request.getInputStream().isReady()) {
                    if (request.getInputStream().read(buf) <= 0) {
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

        body.offer("done".getBytes(StandardCharsets.UTF_8));
        body.finish();
        request.in.dispatchDataAvailable();
        assertTrue(allRead.get());
        assertTrue(request.getInputStream().isFinished());
    }

    @Test
    public void testHandlerRequestBodyContentNotifiesReadListener() throws Exception {
        StubHTTPResponseState state = new StubHTTPResponseState();
        ServletService service = new ServletService();
        ServletHandler handler = new ServletHandler(service, service.getContainer(), 8192);
        Headers h = new Headers();
        h.add(":method", "POST");
        h.add(":path", "/upload");
        handler.headers(state, h);

        Request request = handler.getRequest();
        assertNotNull(request);
        request.startAsync();
        AtomicInteger dataAvailable = new AtomicInteger();
        request.getInputStream().setReadListener(new ReadListener() {
            @Override public void onDataAvailable() {
                dataAvailable.incrementAndGet();
            }
            @Override public void onAllDataRead() { }
            @Override public void onError(Throwable t) {
                fail(t.toString());
            }
        });

        handler.requestBodyContent(state, ByteBuffer.wrap("x".getBytes(StandardCharsets.UTF_8)));
        assertTrue(dataAvailable.get() >= 1);
    }

    @Test
    public void testWriteListenerRequiresAsync() throws Exception {
        StubHTTPResponseState state = new StubHTTPResponseState();
        ServletService service = new ServletService();
        StubServletHandler handler = new StubServletHandler(service, state);
        Request request = new Request(handler, 8192, "GET", "/t", new Headers(),
                new RequestBodyStream());
        Response response = new Response(handler, request, 8192);
        ServletOutputStreamWrapper wrapper =
                new ServletOutputStreamWrapper(response, new java.io.ByteArrayOutputStream());
        try {
            wrapper.setWriteListener(new WriteListener() {
                @Override public void onWritePossible() { }
                @Override public void onError(Throwable t) { }
            });
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("async"));
        }
    }

    @Test
    public void testWriteListenerIsReadyReflectsTransportBackpressure() throws Exception {
        BackpressureState state = new BackpressureState(5 * 1024 * 1024);
        ServletService service = new ServletService();
        StubServletHandler handler = new StubServletHandler(service, state);
        Request request = new Request(handler, 8192, "GET", "/t", new Headers(),
                new RequestBodyStream());
        Response response = new Response(handler, request, 8192);
        bindHandlerState(handler, state, request, response);
        request.startAsync();

        ServletOutputStreamWrapper out =
                (ServletOutputStreamWrapper) response.getOutputStream();
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
        response.notifyWritePossible();
        assertTrue(out.isReady());
        assertTrue(notified.get());
    }

    private static Request newRequest(StubHTTPResponseState state) throws Exception {
        return newRequest(state, new RequestBodyStream());
    }

    private static Request newRequest(StubHTTPResponseState state, RequestBodyStream body)
            throws Exception {
        ServletService service = new ServletService();
        StubServletHandler handler = new StubServletHandler(service, state);
        return new Request(handler, 8192, "GET", "/test", new Headers(), body);
    }

    private static void bindHandlerState(ServletHandler handler, HTTPResponseState state,
            Request request, Response response) throws Exception {
        java.lang.reflect.Field stateField = ServletHandler.class.getDeclaredField("state");
        stateField.setAccessible(true);
        stateField.set(handler, state);
        java.lang.reflect.Field requestField = ServletHandler.class.getDeclaredField("request");
        requestField.setAccessible(true);
        requestField.set(handler, request);
        java.lang.reflect.Field responseField = ServletHandler.class.getDeclaredField("response");
        responseField.setAccessible(true);
        responseField.set(handler, response);
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
                org.bluezoo.gumdrop.websocket.WebSocketEventHandler handler) { }
        @Override public void cancel() { }
        @Override public int pendingResponseBytes() { return 0; }
    }
}
