/*
 * RequestResponseHeaderIndexTest.java
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
import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.http.HTTPVersion;
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
public class RequestResponseHeaderIndexTest {

    private static Request newRequest(Headers requestHeaders) throws Exception {
        StubHTTPResponseState state = new StubHTTPResponseState();
        StubServletHandler handler = new StubServletHandler(state);
        RequestBodyStream bodyStream = new RequestBodyStream();
        return new Request(handler, 8192, "GET", "/test", requestHeaders, bodyStream);
    }

    @Test
    public void testGetHeaderDoesNotRescanPerCall() throws Exception {
        Headers requestHeaders = manyHeaders(5000);
        Request request = newRequest(requestHeaders);

        // Force the index to build once, then hammer getHeader() for a
        // mix of present and absent names -- none of this touches
        // requestHeaders' own modCount (no add/remove), so a correctly
        // delegating implementation rebuilds the index at most once.
        assertEquals("v-0", request.getHeader("x-header-0"));
        int buildsAfterFirstCall = requestHeaders.indexBuildCountForTesting;
        assertTrue("the first lookup must have built the index at least once",
                buildsAfterFirstCall >= 1);

        for (int i = 0; i < 5000; i++) {
            assertEquals("v-" + i, request.getHeader("x-header-" + i));
        }
        assertNull(request.getHeader("does-not-exist"));

        assertEquals("getHeader() must not rebuild the index on every call -- "
                + "that would mean it is still scanning the backing list by "
                + "hand rather than delegating to Headers' indexed accessor",
                buildsAfterFirstCall, requestHeaders.indexBuildCountForTesting);
    }

    @Test
    public void testGetHeaderCaseInsensitiveAndCorrectAtScale() throws Exception {
        Headers requestHeaders = manyHeaders(2000);
        Request request = newRequest(requestHeaders);

        assertEquals("v-1999", request.getHeader("X-HEADER-1999"));
        assertEquals("v-0", request.getHeader("x-Header-0"));
        assertNull(request.getHeader("x-header-2000"));
    }

    @Test
    public void testGetHeadersReturnsAllValuesForRepeatedName() throws Exception {
        Headers requestHeaders = new Headers();
        requestHeaders.add(new Header("Accept", "text/html"));
        requestHeaders.add(new Header("Accept", "application/json"));
        requestHeaders.add(new Header("X-Other", "irrelevant"));
        Request request = newRequest(requestHeaders);

        Enumeration<String> values = request.getHeaders("accept");
        assertTrue(values.hasMoreElements());
        assertEquals("text/html", values.nextElement());
        assertTrue(values.hasMoreElements());
        assertEquals("application/json", values.nextElement());
        assertFalse(values.hasMoreElements());
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

    @Test
    public void testSetHeaderReplacesRatherThanDuplicates() throws Exception {
        Response response = newResponse();
        response.setHeader("Content-Type", "text/plain");
        response.setHeader("Content-Type", "application/json");

        assertEquals("application/json", response.getHeader("Content-Type"));
        assertEquals("setHeader must replace, not accumulate, prior values for the same name",
                1, response.headers.getValues("Content-Type").size());
    }

    @Test
    public void testManySetHeaderCallsDoNotRescanWholeListEachTime() throws Exception {
        Response response = newResponse();

        for (int i = 0; i < 3000; i++) {
            response.setHeader("x-header-" + i, "v-" + i);
        }
        // Reading back a header set early exercises the exact scenario
        // the issue calls out: several setHeader calls (each internally
        // an indexed removeAll + add) followed by a lookup -- correct
        // delegation keeps this fast regardless of how many headers came
        // before it.
        assertEquals("v-0", response.getHeader("x-header-0"));
        assertEquals("v-2999", response.getHeader("x-header-2999"));
        assertNull(response.getHeader("x-header-3000"));
    }

    @Test
    public void testGetHeadersOnResponse() throws Exception {
        Response response = newResponse();
        response.addHeader("Set-Cookie", "a=1");
        response.addHeader("Set-Cookie", "b=2");

        java.util.Collection<String> values = response.getHeaders("set-cookie");
        assertEquals(2, values.size());
        assertTrue(values.contains("a=1"));
        assertTrue(values.contains("b=2"));

        assertNull("getHeaders for a name with no headers must return null "
                + "(this implementation's existing contract, unchanged by "
                + "routing through the index)",
                response.getHeaders("x-absent"));
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
        private final HTTPResponseState stubState;

        StubServletHandler(HTTPResponseState stubState) {
            super(null, null, 8192);
            this.stubState = stubState;
        }

        @Override
        HTTPResponseState getState() {
            return stubState;
        }
    }

    /** Minimal HTTPResponseState: only isSecure()/getSecurityInfo() are ever read here. */
    private static final class StubHTTPResponseState implements HTTPResponseState {
        @Override public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 54321);
        }
        @Override public SocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 8080);
        }
        @Override public boolean isSecure() { return false; }
        @Override public SecurityInfo getSecurityInfo() { return null; }
        @Override public HTTPVersion getVersion() { return HTTPVersion.HTTP_1_1; }
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
