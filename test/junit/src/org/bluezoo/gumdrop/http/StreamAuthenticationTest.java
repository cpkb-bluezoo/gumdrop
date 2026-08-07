/*
 * StreamAuthenticationTest.java
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

package org.bluezoo.gumdrop.http;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.http.hpack.Decoder;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Base64;

import javax.servlet.http.HttpServletRequest;

import static org.junit.Assert.*;

/**
 * Regression tests for issue #115: an {@link HTTPAuthenticationProvider}
 * configured on a service (e.g. via {@code HTTPService.setRealm}, which
 * {@code WebDAVService} inherits) was stored on {@code
 * HTTPProtocolHandler}/{@code Stream} but never actually consulted, so no
 * HTTP/1.1 or HTTP/2 request was ever rejected for missing or invalid
 * credentials regardless of configuration.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class StreamAuthenticationTest {

    private static final String REALM = "test-realm";
    private static final String USERNAME = "alice";
    private static final String PASSWORD = "secret";

    /** Simple HTTP Basic provider, accepting only USERNAME/PASSWORD. */
    private static final class TestBasicProvider extends HTTPAuthenticationProvider {
        @Override protected String getAuthMethod() {
            return HttpServletRequest.BASIC_AUTH;
        }
        @Override protected String getRealmName() {
            return REALM;
        }
        @Override protected boolean passwordMatch(String realm, String user, String pass) {
            return REALM.equals(realm) && USERNAME.equals(user) && PASSWORD.equals(pass);
        }
        @Override protected String getDigestHA1(String realm, String username) {
            return null;
        }
        @Override protected Realm.TokenValidationResult validateBearerToken(String token) {
            return null;
        }
        @Override protected Realm.TokenValidationResult validateOAuthToken(String token) {
            return null;
        }
    }

    private static String basicHeader(String username, String password) {
        String creds = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(
                creds.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static class StubConnection implements HTTPConnectionLike {
        long maxRequestBodySize = 0; // unlimited
        HTTPVersion version = HTTPVersion.HTTP_1_1;
        HTTPAuthenticationProvider authProvider;
        int lastStatusCode = -1;
        Headers lastResponseHeaders;

        @Override public String getScheme() { return "http"; }
        @Override public HTTPVersion getVersion() { return version; }
        @Override public SocketAddress getRemoteSocketAddress() {
            return new InetSocketAddress("127.0.0.1", 12345);
        }
        @Override public SocketAddress getLocalSocketAddress() {
            return new InetSocketAddress("127.0.0.1", 80);
        }
        @Override public SecurityInfo getSecurityInfoForStream() { return null; }
        @Override public HTTPRequestHandlerFactory getHandlerFactory() { return null; }
        @Override public void sendResponseHeaders(int streamId, int statusCode,
                Headers headers, boolean endStream) {
            lastStatusCode = statusCode;
            lastResponseHeaders = headers;
        }
        @Override public void sendResponseBody(int streamId, ByteBuffer buf, boolean endStream) { }
        @Override public void send(ByteBuffer buf) { }
        @Override public void sendRstStream(int streamId, int errorCode) { }
        @Override public void sendGoaway(int errorCode) { }
        @Override public void switchToWebSocketMode(int streamId) { }
        @Override public Decoder getHpackDecoder() { return null; }
        @Override public boolean isSecure() { return false; }
        @Override public TelemetryConfig getTelemetryConfig() { return null; }
        @Override public Trace getTrace() { return null; }
        @Override public void setTrace(Trace trace) { }
        @Override public boolean isTelemetryEnabled() { return false; }
        @Override public HTTPServerMetrics getServerMetrics() { return null; }
        @Override public boolean isEnablePush() { return false; }
        @Override public Stream newStream(HTTPConnectionLike connection, int streamId) {
            return new Stream(connection, streamId);
        }
        @Override public int getNextServerStreamId() { return 2; }
        @Override public byte[] encodeHeaders(Headers headers) { return new byte[0]; }
        @Override public void sendPushPromise(int streamId, int promisedStreamId,
                ByteBuffer headerBlock, boolean endHeaders) { }
        @Override public Stream createPushedStream(int streamId, String method,
                String uri, Headers headers) { return null; }
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public int getMaxHeaderListSize() { return 8192; }
        @Override public long getMaxRequestBodySize() { return maxRequestBodySize; }
        @Override public HTTPAuthenticationProvider getAuthenticationProvider() { return authProvider; }
        @Override public void onWritable(int streamId, Runnable callback) { }
        @Override public void pauseRead(int streamId) { }
        @Override public void resumeRead(int streamId) { }
    }

    @Test
    public void testNoProviderConfiguredAllowsRequest() throws Exception {
        StubConnection conn = new StubConnection();
        conn.authProvider = null;
        Stream stream = new Stream(conn, 1);
        stream.addHeader(new Header(":method", "PUT"));
        stream.streamEndHeaders();
        assertNotEquals(401, conn.lastStatusCode);
    }

    @Test
    public void testMissingAuthorizationHeaderRejected() throws Exception {
        StubConnection conn = new StubConnection();
        conn.authProvider = new TestBasicProvider();
        Stream stream = new Stream(conn, 1);
        stream.addHeader(new Header(":method", "PUT"));
        stream.streamEndHeaders();
        assertEquals(401, conn.lastStatusCode);
        assertNotNull("a 401 must carry a WWW-Authenticate challenge",
                conn.lastResponseHeaders.getValue("WWW-Authenticate"));
        assertNull("no principal should be attached to a rejected request",
                stream.getPrincipal());
    }

    @Test
    public void testInvalidCredentialsRejected() throws Exception {
        StubConnection conn = new StubConnection();
        conn.authProvider = new TestBasicProvider();
        Stream stream = new Stream(conn, 1);
        stream.addHeader(new Header(":method", "PUT"));
        stream.addHeader(new Header("Authorization", basicHeader(USERNAME, "wrong-password")));
        stream.streamEndHeaders();
        assertEquals(401, conn.lastStatusCode);
    }

    @Test
    public void testValidCredentialsAllowedAndPrincipalSet() throws Exception {
        StubConnection conn = new StubConnection();
        conn.authProvider = new TestBasicProvider();
        Stream stream = new Stream(conn, 1);
        stream.addHeader(new Header(":method", "PUT"));
        stream.addHeader(new Header("Authorization", basicHeader(USERNAME, PASSWORD)));
        stream.streamEndHeaders();
        assertNotEquals(401, conn.lastStatusCode);
        assertNotNull("a successfully authenticated request must expose a principal",
                stream.getPrincipal());
        assertEquals(USERNAME, stream.getPrincipal().getName());
    }
}
