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
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
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
    public void testReadNonBlockingThrowsWhenNotReady() throws Exception {
        RequestBodyStream body = new RequestBodyStream();
        Request request = newRequest(new StubHTTPResponseState(), body);
        request.startAsync();
        request.getInputStream().setReadListener(new ReadListener() {
            @Override public void onDataAvailable() { }
            @Override public void onAllDataRead() { }
            @Override public void onError(Throwable t) { fail(t.toString()); }
        });

        try {
            request.getInputStream().read();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("not ready")
                    || expected.getMessage().contains("Read not ready"));
        }
    }

    @Test
    public void testReadNonBlockingReturnsAvailableBytes() throws Exception {
        RequestBodyStream body = new RequestBodyStream();
        body.offer("xy".getBytes(StandardCharsets.UTF_8));
        Request request = newRequest(new StubHTTPResponseState(), body);
        request.startAsync();
        request.getInputStream().setReadListener(new ReadListener() {
            @Override public void onDataAvailable() { }
            @Override public void onAllDataRead() { }
            @Override public void onError(Throwable t) { fail(t.toString()); }
        });

        byte[] buf = new byte[8];
        assertEquals(2, request.getInputStream().read(buf, 0, buf.length));
        assertEquals('x', (char) buf[0]);
        assertEquals('y', (char) buf[1]);
    }

    @Test
    public void testBlockingReadWithoutListener() throws Exception {
        RequestBodyStream body = new RequestBodyStream();
        Request request = newRequest(new StubHTTPResponseState(), body);

        final CountDownLatch blocked = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger readByte = new AtomicInteger(-1);
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                blocked.countDown();
                try {
                    readByte.set(request.getInputStream().read());
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
        body.offer("z".getBytes(StandardCharsets.UTF_8));
        assertTrue("read should complete after data arrives",
                done.await(2, TimeUnit.SECONDS));
        assertEquals('z', readByte.get());
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
    public void testResponseOutputStreamTransfersBufferOwnership() throws Exception {
        final AtomicReference<ByteBuffer> received = new AtomicReference<ByteBuffer>();
        StubHTTPResponseState state = new StubHTTPResponseState() {
            @Override
            public void responseBodyContent(ByteBuffer data) {
                received.set(data);
            }
        };
        ServletService service = new ServletService();
        StubServletHandler handler = new StubServletHandler(service, state);
        Request request = new Request(handler, 128, "GET", "/t", new Headers(),
                new RequestBodyStream());
        Response response = new Response(handler, request, 128);
        bindHandlerState(handler, state, request, response);

        ResponseOutputStream out = new ResponseOutputStream(response, 128);
        Field bufField = ResponseOutputStream.class.getDeclaredField("buf");
        bufField.setAccessible(true);
        ByteBuffer flushedBuffer = (ByteBuffer) bufField.get(out);
        byte[] payload = "payload-data".getBytes(StandardCharsets.UTF_8);
        out.write(payload);
        out.flush();

        assertSame(flushedBuffer, received.get());
        assertEquals(payload.length, received.get().remaining());
        byte[] actual = new byte[payload.length];
        received.get().duplicate().get(actual);
        assertArrayEquals(payload, actual);
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
