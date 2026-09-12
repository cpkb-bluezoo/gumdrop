/*
 * Servlet61ApiTest.java
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

import org.bluezoo.gumdrop.NullSecurityInfo;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.http.HTTPVersion;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ServletConnection;

import static org.junit.Assert.*;

/**
 * Regression tests for Servlet 5.0/6.0/6.1 API gap-fill (Phase 2).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Servlet61ApiTest {

    @Test
    public void testRequestIdsAndServletConnection() throws Exception {
        StubHTTPResponseState state = new StubHTTPResponseState();
        state.connectionId = "conn-abc";
        state.protocolConnectionId = "42";

        Request request = newRequest(state, "POST", "/app/item?q=1");

        assertNotNull(request.getRequestId());
        assertFalse(request.getRequestId().isEmpty());
        assertEquals("42", request.getProtocolRequestId());

        ServletConnection conn = request.getServletConnection();
        assertEquals("conn-abc", conn.getConnectionId());
        assertEquals("42", conn.getProtocolConnectionId());
        assertEquals(request.getProtocol(), conn.getProtocol());
        assertFalse(conn.isSecure());
    }

    @Test
    public void testSecureProtocolRequestAttribute() throws Exception {
        StubHTTPResponseState state = new StubHTTPResponseState();
        state.secure = true;
        state.securityInfo = new StubSecurityInfo("TLSv1.3");

        Request request = newRequest(state, "GET", "/secure");

        assertEquals("TLSv1.3",
                request.getAttribute("jakarta.servlet.request.secure_protocol"));
    }

    @Test
    public void testErrorRequestMethodAndQueryStringAttributes() throws Exception {
        StubHTTPResponseState state = new StubHTTPResponseState();
        Request request = newRequest(state, "DELETE", "/api/item?id=7");

        ErrorRequest errorRequest = new ErrorRequest(
                request, "/error", null, 404, null, "TestServlet");

        assertEquals("DELETE",
                errorRequest.getAttribute("jakarta.servlet.error.method"));
        assertEquals("id=7",
                errorRequest.getAttribute("jakarta.servlet.error.query_string"));
    }

    @Test
    public void testRequestInputStreamReadByteBuffer() throws Exception {
        RequestBodyStream body = new RequestBodyStream();
        body.offer("hello".getBytes(StandardCharsets.UTF_8));
        body.finish();

        RequestInputStream in = new RequestInputStream(null, body);
        ByteBuffer buf = ByteBuffer.allocate(5);
        assertEquals(5, in.read(buf));
        buf.flip();
        assertEquals("hello", StandardCharsets.UTF_8.decode(buf).toString());
    }

    @Test
    public void testServletOutputStreamWriteByteBuffer() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ServletOutputStreamWrapper out = new ServletOutputStreamWrapper(null, bytes);
        out.write(ByteBuffer.wrap("data".getBytes(StandardCharsets.UTF_8)));
        out.flush();
        assertEquals("data", bytes.toString(StandardCharsets.UTF_8.name()));
    }

    private static Request newRequest(StubHTTPResponseState state, String method,
            String target) throws Exception {
        StubServletHandler handler = new StubServletHandler(state);
        RequestBodyStream bodyStream = new RequestBodyStream();
        return new Request(handler, 8192, method, target, new Headers(), bodyStream);
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
        String connectionId = "stub-conn";
        String protocolConnectionId = "";
        boolean secure;
        SecurityInfo securityInfo = NullSecurityInfo.INSTANCE;

        @Override public java.net.SocketAddress getRemoteAddress() {
            return new java.net.InetSocketAddress("127.0.0.1", 54321);
        }
        @Override public java.net.SocketAddress getLocalAddress() {
            return new java.net.InetSocketAddress("127.0.0.1", 8080);
        }
        @Override public boolean isSecure() { return secure; }
        @Override public SecurityInfo getSecurityInfo() { return securityInfo; }
        @Override public HTTPVersion getVersion() { return HTTPVersion.HTTP_2_0; }
        @Override public String getScheme() { return secure ? "https" : "http"; }
        @Override public String getConnectionId() { return connectionId; }
        @Override public String getProtocolConnectionId() { return protocolConnectionId; }
        @Override public org.bluezoo.gumdrop.SelectorLoop getSelectorLoop() { return null; }
        @Override public java.security.Principal getPrincipal() { return null; }
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
        @Override public void upgradeToWebSocket(String protocol,
                org.bluezoo.gumdrop.websocket.WebSocketEventHandler handler) { }
        @Override public void cancel() { }
    }

    private static final class StubSecurityInfo implements SecurityInfo {
        private final String protocol;

        StubSecurityInfo(String protocol) {
            this.protocol = protocol;
        }

        @Override public String getProtocol() { return protocol; }
        @Override public String getCipherSuite() { return "TLS_AES_128_GCM_SHA256"; }
        @Override public int getKeySize() { return 128; }
        @Override public java.security.cert.Certificate[] getPeerCertificates() { return null; }
        @Override public java.security.cert.Certificate[] getLocalCertificates() { return null; }
        @Override public String getApplicationProtocol() { return "h2"; }
        @Override public long getHandshakeDurationMs() { return -1; }
        @Override public boolean isSessionResumed() { return false; }
    }
}
