/*
 * RequestResponseHeaderIndexPerformanceTest.java
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

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.server.HttpResponseState;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;

import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.Enumeration;

import static org.junit.Assert.*;

/**
 * Regression coverage for issue #303: {@code Request.getHeader}/{@code
 * getHeaders} and {@code Response.setHeader}/{@code getHeader}/{@code
 * getHeaders} looped the backing header list by hand instead of using
 * {@link Headers}' own indexed accessors ({@code getValue}/{@code
 * getValues}/{@code removeAll}, already O(1)-per-lookup since issues
 * #141/#142) -- so every single header access paid an O(request header
 * count) scan, and a response setting several headers paid O(n squared)
 * overall.
 *
 * <p>Proves delegation the same way {@code
 * org.bluezoo.gumdrop.http.StreamResponseHeadersIndexTest} proves it for
 * issue #278 -- via {@link Headers#indexBuildCountForTesting}, since a
 * fix that still produces the same header values either way is otherwise
 * unobservable from outside; a hand-rolled scan and an indexed lookup
 * both return identical results, only their cost differs.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
/*
 * NOTE: wall-clock thresholds live here, not in the unit suite: unit tests must
 * be deterministic (CONTRIBUTING.md). Extracted from RequestResponseHeaderIndexTest.
 */
public class RequestResponseHeaderIndexPerformanceTest {

    private static Request newRequest(Headers requestHeaders) throws Exception {
        StubHTTPResponseState state = new StubHTTPResponseState();
        StubServletHandler handler = new StubServletHandler(state);
        RequestBodyStream bodyStream = new RequestBodyStream();
        return new Request(handler, 8192, "GET", "/test", requestHeaders, bodyStream);
    }







    @Test(timeout = 5000)
    public void testGetHeaderLookupCostDoesNotScaleWithHeaderCount() throws Exception {
        Headers requestHeaders = manyHeaders(50000);
        Request request = newRequest(requestHeaders);
        request.getHeader("x-header-0"); // force the index to build once

        long start = System.nanoTime();
        for (int i = 0; i < 100000; i++) {
            assertEquals("v-49999", request.getHeader("x-header-49999"));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue("100,000 lookups against a 50,000-header request took " + elapsedMs
                + "ms -- a per-call linear scan would be far slower than this",
                elapsedMs < 2000);
    }

    // ── Response ──

    private static Response newResponse() throws Exception {
        StubHTTPResponseState state = new StubHTTPResponseState();
        StubServletHandler handler = new StubServletHandler(state);
        Request request = newRequest(new Headers());
        return new Response(handler, request, 8192);
    }







    // ── helpers ──

    private static Headers manyHeaders(int count) {
        Headers headers = new Headers(count);
        for (int i = 0; i < count; i++) {
            headers.add(new Header("x-header-" + i, "v-" + i));
        }
        return headers;
    }

    /** Minimal ServletHandler whose getState() returns a fixed stub. */
    private static final class StubServletHandler extends ServletHandler {
        private final HttpResponseState stubState;

        StubServletHandler(HttpResponseState stubState) {
            super(new Container(), 8192);
            this.stubState = stubState;
        }

        @Override
        HttpResponseState getState() {
            return stubState;
        }
    }

    /** Minimal HttpResponseState: only isSecure()/getSecurityInfo() are ever read here. */
    private static final class StubHTTPResponseState implements HttpResponseState {
        @Override public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 54321);
        }
        @Override public SocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 8080);
        }
        @Override public boolean isSecure() { return false; }
        @Override public SecurityInfo getSecurityInfo() { return null; }
        @Override public HttpVersion getVersion() { return HttpVersion.HTTP_1_1; }
        @Override public String getScheme() { return "http"; }
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public Principal getPrincipal() { return null; }
        @Override public void headers(Headers headers) { }
        @Override public void startResponseBody() { }
        @Override public void responseBodyContent(ByteBuffer data) { }
        @Override public void endResponseBody() { }
        @Override public void complete() { }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public void onWritable(Runnable callback) { }
        @Override public void pauseRequestBody() { }
        @Override public void resumeRequestBody() { }
        @Override public boolean pushPromise(Headers headers) { return false; }
        @Override public void upgradeToWebSocket(String subprotocol, WebSocketEventHandler handler) { }
        @Override public void cancel() { }
    }
}
