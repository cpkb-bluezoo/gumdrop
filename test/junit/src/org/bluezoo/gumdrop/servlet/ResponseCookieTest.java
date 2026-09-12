/*
 * ResponseCookieTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.http.HTTPVersion;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;

import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.Principal;

import jakarta.servlet.http.Cookie;

import static org.junit.Assert.*;

/**
 * Tests {@link Response#addCookie(Cookie)} header formatting.
 */
public class ResponseCookieTest {

    @Test
    public void testAddCookieHttpOnlyAndSameSite() throws Exception {
        StubHTTPResponseState state = new StubHTTPResponseState();
        StubServletHandler handler = new StubServletHandler(state);
        Request request = new Request(handler, 8192, "GET", "/test", new Headers(), new RequestBodyStream());
        Response response = new Response(handler, request, 8192);

        Cookie cookie = new Cookie("sid", "abc");
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setAttribute("SameSite", "Strict");
        cookie.setAttribute("Partitioned", "");
        response.addCookie(cookie);

        String header = response.getHeader("Set-Cookie");
        assertNotNull(header);
        assertTrue(header.contains("sid=abc"));
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("Secure"));
        assertTrue(header.contains("SameSite=Strict"));
        assertTrue(header.contains("Partitioned"));
    }

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
