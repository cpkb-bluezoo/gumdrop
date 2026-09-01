/*
 * StreamH2MethodPathTest.java
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
import org.bluezoo.gumdrop.auth.SASLUtils;
import org.bluezoo.gumdrop.http.hpack.Decoder;
import org.bluezoo.gumdrop.http.hpack.Encoder;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.util.ByteArrays;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import static org.junit.Assert.*;

/**
 * Regression tests proving {@code Stream}'s HTTP/2 HPACK-decoded request
 * path correctly populates {@code method}/{@code requestTarget} the same
 * way the HTTP/1.1 path already does via {@code addHeader()}.
 *
 * <p>Before the fix, {@code Stream}'s reusable {@code hpackHandler} (used
 * to decode HTTP/2 header blocks, {@code Stream.java}'s
 * {@code streamEndHeaders()}) only added decoded headers to the
 * {@code Headers} collection, unlike {@code addHeader()} (used by the
 * HTTP/1.1 path and for pushed streams), which also mirrors {@code :method}/
 * {@code :path} into the {@code method}/{@code requestTarget} fields. Two
 * confirmed, concrete symptoms for ordinary (non-pushed) HTTP/2 requests:
 * <ul>
 *   <li>{@code HTTPAuthenticationProvider.authenticateDigest} requires a
 *       non-null {@code requestMethod}/{@code digestUri} (RFC 7616 H(A2)
 *       binding) and fails immediately with {@code invalid_digest_format}
 *       when either is null -- so Digest authentication over HTTP/2 always
 *       failed, regardless of credentials.</li>
 *   <li>{@code Stream.sendResponseBody()}'s {@code "HEAD".equals(method)}
 *       check (RFC 9110 section 9.3.2 body suppression) never matched for
 *       HTTP/2, so a HEAD response body was not suppressed.</li>
 * </ul>
 *
 * <p>Unlike {@code StreamContentLengthValidationTest}/{@code
 * StreamAuthenticationTest} (which call {@code stream.addHeader(...)}
 * directly, bypassing HPACK entirely and so unable to reproduce this), these
 * tests drive a real {@link Encoder}/{@link Decoder} round trip to exercise
 * the actual HPACK-decode path.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class StreamH2MethodPathTest {

    private static final String REALM = "test-realm";
    private static final String USERNAME = "alice";
    private static final String PASSWORD = "secret";
    private static final String HA1 = SASLUtils.computeDigestHA1(
            USERNAME, REALM, PASSWORD);

    private static final class TestDigestProvider extends HTTPAuthenticationProvider {
        @Override protected String getAuthMethod() {
            return HttpServletRequest.DIGEST_AUTH;
        }
        @Override protected String getRealmName() {
            return REALM;
        }
        @Override protected boolean passwordMatch(String realm, String user, String pass) {
            return false;
        }
        @Override protected String getDigestHA1(String realm, String username) {
            return (REALM.equals(realm) && USERNAME.equals(username)) ? HA1 : null;
        }
        @Override protected Realm.TokenValidationResult validateBearerToken(String token) {
            return null;
        }
        @Override protected Realm.TokenValidationResult validateOAuthToken(String token) {
            return null;
        }
    }

    private static String extractNonce(String challenge) {
        int i = challenge.indexOf("nonce=\"");
        assertTrue(i >= 0);
        int start = i + "nonce=\"".length();
        int end = challenge.indexOf('"', start);
        return challenge.substring(start, end);
    }

    private static String computeDigestResponse(String nonce, String qop, String nc,
            String cnonce, String method, String uri) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(method.getBytes());
        md.update((byte) ':');
        md.update(uri.getBytes());
        String ha2Hex = ByteArrays.toHexString(md.digest());

        md.reset();
        md.update(HA1.getBytes());
        md.update((byte) ':');
        md.update(nonce.getBytes());
        md.update((byte) ':');
        md.update(nc.getBytes());
        md.update((byte) ':');
        md.update(cnonce.getBytes());
        md.update((byte) ':');
        md.update(qop.getBytes());
        md.update((byte) ':');
        md.update(ha2Hex.getBytes());
        return ByteArrays.toHexString(md.digest());
    }

    /** RFC 7616 Digest Authorization header, bound to {@code method}/{@code uri}. */
    private static String buildDigestAuthorizationHeader(String nonce,
            String method, String uri) throws Exception {
        String qop = "auth";
        String cnonce = "clientnonce1";
        String nc = "00000001";
        String response = computeDigestResponse(nonce, qop, nc, cnonce, method, uri);
        return "Digest username=\"" + USERNAME + "\", realm=\"" + REALM
                + "\", nonce=\"" + nonce + "\", uri=\"" + uri + "\", response="
                + response + ", qop=" + qop + ", nc=" + nc + ", cnonce=\""
                + cnonce + "\"";
    }

    private static class StubH2Connection implements HTTPConnectionLike {
        final Decoder hpackDecoder = new Decoder(4096, 8192);
        HTTPAuthenticationProvider authProvider;
        int lastStatusCode = -1;
        boolean bodySuppressed = true;
        boolean bodySendAttempted = false;

        @Override public String getScheme() { return "https"; }
        @Override public HTTPVersion getVersion() { return HTTPVersion.HTTP_2_0; }
        @Override public SocketAddress getRemoteSocketAddress() {
            return new InetSocketAddress("127.0.0.1", 12345);
        }
        @Override public SocketAddress getLocalSocketAddress() {
            return new InetSocketAddress("127.0.0.1", 443);
        }
        @Override public SecurityInfo getSecurityInfoForStream() { return null; }
        // A no-op handler (rather than null) so streamEndHeaders() doesn't
        // auto-send a 404 -- these tests drive sendResponseHeaders/Body
        // manually afterward, like a real application handler would.
        @Override public HTTPRequestHandlerFactory getHandlerFactory() {
            return new HTTPRequestHandlerFactory() {
                @Override
                public HTTPRequestHandler createHandler(HTTPResponseState state, Headers headers) {
                    return new DefaultHTTPRequestHandler();
                }
            };
        }
        @Override public void sendResponseHeaders(int streamId, int statusCode,
                Headers headers, boolean endStream) {
            lastStatusCode = statusCode;
        }
        @Override public void sendResponseBody(int streamId, ByteBuffer buf, boolean endStream) {
            bodySendAttempted = true;
            if (buf != null && buf.hasRemaining()) {
                bodySuppressed = false;
            }
        }
        @Override public void send(ByteBuffer buf) { }
        @Override public void sendRstStream(int streamId, int errorCode) { }
        @Override public void sendGoaway(int errorCode) { }
        @Override public void switchToWebSocketMode(int streamId) { }
        @Override public void switchToStreamTunnelMode(int streamId) { }
        @Override public org.bluezoo.gumdrop.TimerHandle scheduleTimer(long delayMs, Runnable callback) { return null; }
        @Override public Decoder getHpackDecoder() { return hpackDecoder; }
        @Override public boolean isSecure() { return true; }
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
        @Override public long getMaxRequestBodySize() { return 0; }
        @Override public HTTPAuthenticationProvider getAuthenticationProvider() { return authProvider; }
        @Override public void onWritable(int streamId, Runnable callback) { }
        @Override public void pauseRead(int streamId) { }
        @Override public void resumeRead(int streamId) { }
        @Override public int pendingResponseBytes(int streamId) { return 0; }
    }

    /**
     * Builds a real HPACK-encoded header block and feeds it through
     * {@code Stream}'s actual HTTP/2 decode path (unlike
     * {@code stream.addHeader(...)}, which bypasses HPACK -- and the bug
     * -- entirely).
     */
    private static void sendH2Headers(Stream stream, Header... headers) throws Exception {
        List<Header> list = new ArrayList<>();
        for (Header h : headers) {
            list.add(h);
        }
        Encoder encoder = new Encoder(4096, 8192);
        ByteBuffer buf = ByteBuffer.allocate(4096);
        encoder.encode(buf, list);
        buf.flip();
        stream.appendHeaderBlockFragment(buf);
        stream.streamEndHeaders();
    }

    @Test
    public void testDigestAuthOverH2Succeeds() throws Exception {
        StubH2Connection conn = new StubH2Connection();
        conn.authProvider = new TestDigestProvider();
        String nonce = extractNonce(conn.authProvider.generateChallenge());
        String authHeader = buildDigestAuthorizationHeader(nonce, "GET", "/protected");

        Stream stream = new Stream(conn, 1);
        sendH2Headers(stream,
                new Header(":method", "GET"),
                new Header(":scheme", "https"),
                new Header(":authority", "example.com"),
                new Header(":path", "/protected"),
                new Header("authorization", authHeader));

        assertNotEquals("Digest auth over HTTP/2 must not fail with a malformed-"
                        + "request error just because :method/:path weren't populated",
                401, conn.lastStatusCode);
        assertNotNull("a successfully Digest-authenticated HTTP/2 request "
                        + "must expose a principal",
                stream.getPrincipal());
        assertEquals(USERNAME, stream.getPrincipal().getName());
    }

    @Test
    public void testDigestAuthOverH2RejectsWrongMethodBinding() throws Exception {
        // Sanity check on the fix itself: the digest response is bound to
        // GET /protected, so presenting it against a POST must still fail
        // -- proving the real, now-populated method is what's being
        // checked, not a hardcoded/ignored value.
        StubH2Connection conn = new StubH2Connection();
        conn.authProvider = new TestDigestProvider();
        String nonce = extractNonce(conn.authProvider.generateChallenge());
        String authHeader = buildDigestAuthorizationHeader(nonce, "GET", "/protected");

        Stream stream = new Stream(conn, 1);
        sendH2Headers(stream,
                new Header(":method", "POST"),
                new Header(":scheme", "https"),
                new Header(":authority", "example.com"),
                new Header(":path", "/protected"),
                new Header("authorization", authHeader));

        assertNull("a digest response bound to GET must not authenticate a POST",
                stream.getPrincipal());
    }

    @Test
    public void testHeadResponseBodySuppressedOverH2() throws Exception {
        StubH2Connection conn = new StubH2Connection();
        Stream stream = new Stream(conn, 1);
        sendH2Headers(stream,
                new Header(":method", "HEAD"),
                new Header(":scheme", "https"),
                new Header(":authority", "example.com"),
                new Header(":path", "/"));

        stream.sendResponseHeaders(200, new Headers(), false);
        stream.sendResponseBody(ByteBuffer.wrap("should not be sent".getBytes()), true);

        assertTrue("RFC 9110 section 9.3.2: a HEAD response over HTTP/2 must "
                        + "not carry a body",
                conn.bodySuppressed);
    }

    @Test
    public void testGetResponseBodyNotSuppressedOverH2() throws Exception {
        StubH2Connection conn = new StubH2Connection();
        Stream stream = new Stream(conn, 1);
        sendH2Headers(stream,
                new Header(":method", "GET"),
                new Header(":scheme", "https"),
                new Header(":authority", "example.com"),
                new Header(":path", "/"));

        stream.sendResponseHeaders(200, new Headers(), false);
        stream.sendResponseBody(ByteBuffer.wrap("hello".getBytes()), true);

        assertTrue("a GET response body must still be sent",
                conn.bodySendAttempted);
        assertFalse("a GET response body must not be suppressed",
                conn.bodySuppressed);
    }
}
