/*
 * ServletNonBlockingIOIntegrationTest.java
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
import org.bluezoo.gumdrop.http.server.HttpResponse;
import org.bluezoo.gumdrop.http.HttpVersion;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.ReadListener;
import jakarta.servlet.WriteListener;

import static org.junit.Assert.*;

/**
 * Tests ReadListener/WriteListener wiring to the HTTP I/O layer.
 *
 * <p>Integration test: blocks a real reader thread in a servlet input stream
 * until another thread delivers data.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServletNonBlockingIOIntegrationTest {

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

    private static Request newRequest(StubHTTPResponseState state) throws Exception {
        return newRequest(state, new RequestBodyStream());
    }

    private static Request newRequest(StubHTTPResponseState state, RequestBodyStream body)
            throws Exception {
        Container service = new Container();
        StubServletHandler handler = new StubServletHandler(service, state);
        return new Request(handler, 8192, "GET", "/test", new Headers(), body);
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
