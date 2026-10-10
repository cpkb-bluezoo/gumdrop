/*
 * HttpClientProtocolHandlerAuthTest.java
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

package org.bluezoo.gumdrop.http.client;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.bluezoo.gumdrop.testsupport.DigestTestSupport;

import org.bluezoo.gumdrop.testsupport.CollectingResponseHandler;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Authentication helpers and HTTP/1.1 401/407 retry on
 * {@link HttpClientProtocolHandler}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpClientProtocolHandlerAuthTest {

    private HttpClientProtocolHandler handler;
    private BinaryRecordingEndpoint endpoint;

    @Before
    public void setUp() {
        handler = new HttpClientProtocolHandler(null, "example.com", 80, false);
        endpoint = new BinaryRecordingEndpoint();
        handler.connected(endpoint);
    }

    private void feed(String raw) {
        handler.receive(ByteBuffer.wrap(raw.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void basic401RetriesWithAuthorizationHeader() {
        handler.credentials("user", "pass");
        RecordingHandler rh = new RecordingHandler();
        handler.get("/protected", rh).endMessage();

        feed("HTTP/1.1 401 Unauthorized\r\n"
                + "WWW-Authenticate: Basic realm=\"test\"\r\n"
                + "Content-Length: 0\r\n\r\n");

        assertFalse("401 must not surface to the handler before retry", rh.error || rh.ok);

        String retry = new String(endpoint.getAllBytes(), StandardCharsets.US_ASCII);
        assertTrue(retry.contains("Authorization: Basic "));
        assertTrue(retry.contains("GET /protected"));

        feed("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok");

        assertTrue(rh.ok);
        assertEquals(HttpStatus.OK, rh.response);
        assertEquals("ok", rh.body.toString());
    }

    @Test
    public void digest401RetriesWithDigestAuthorization() throws Exception {
        handler.credentials("alice", "secret");
        RecordingHandler rh = new RecordingHandler();
        handler.get("/resource", rh).endMessage();

        feed("HTTP/1.1 401 Unauthorized\r\n"
                + "WWW-Authenticate: Digest realm=\"example\", nonce=\"deadbeef\", qop=\"auth\", algorithm=SHA-256\r\n"
                + "Content-Length: 0\r\n\r\n");

        String wire = new String(endpoint.getAllBytes(), StandardCharsets.US_ASCII);
        assertTrue(wire.contains("Authorization: Digest "));
        assertTrue(wire.contains("username=\"alice\""));
        assertTrue(wire.contains("qop=auth"));

        feed("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
        assertTrue(rh.ok);
    }

    @Test
    public void computeDigestAuthMd5IsNeverAnswered() throws Exception {
        handler.credentials("alice", "secret");
        assertNull(invokeDigestAuth(
                "Digest realm=\"example\", nonce=\"abc\", algorithm=MD5", "GET", "/path"));
        assertNull(invokeDigestAuth(
                "Digest realm=\"example\", nonce=\"abc\", qop=\"auth\", algorithm=MD5-sess",
                "GET", "/path"));
    }

    @Test
    public void computeDigestAuthWithoutAlgorithmIsNotAnswered() throws Exception {
        handler.credentials("alice", "secret");
        assertNull(invokeDigestAuth(
                "Digest realm=\"example\", nonce=\"abc\", qop=\"auth\"", "GET", "/path"));
        assertNull(invokeDigestAuth(
                "Digest realm=\"example\", nonce=\"abc\", algorithm=BOGUS", "GET", "/path"));
    }

    @Test
    public void computeDigestAuthSha256MatchesIndependentComputation() throws Exception {
        handler.credentials("alice", "secret");
        String challenge = "Digest realm=\"example\", nonce=\"abc\", qop=\"auth\", algorithm=SHA-256";
        String auth = invokeDigestAuth(challenge, "GET", "/path");
        assertNotNull(auth);
        assertTrue(auth, auth.contains("algorithm=SHA-256"));
        assertTrue(auth, auth.contains("qop=auth"));
        assertFalse(auth, auth.contains("MD5"));
        assertVerifies(auth, "alice", "secret", "example", "abc", "GET", "/path");
    }

    @Test
    public void computeDigestAuthSha256WithoutQopMatchesReference() throws Exception {
        handler.credentials("alice", "secret");
        String auth = invokeDigestAuth(
                "Digest realm=\"example\", nonce=\"abc\", algorithm=sha-256", "GET", "/path");
        assertNotNull(auth);
        assertTrue(auth, auth.contains("algorithm=SHA-256"));
        String ha1 = DigestTestSupport.ha1("alice", "example", "secret");
        String ha2 = DigestTestSupport.hex("SHA-256", "GET:/path");
        String expected = DigestTestSupport.hex("SHA-256", ha1 + ":abc:" + ha2);
        assertTrue(auth, auth.contains("response=\"" + expected + "\""));
    }

    @Test
    public void computeDigestAuthSha256SessUsesSessionKey() throws Exception {
        handler.credentials("alice", "secret");
        String auth = invokeDigestAuth(
                "Digest realm=\"example\", nonce=\"abc\", qop=\"auth\", algorithm=SHA-256-sess",
                "GET", "/path");
        assertNotNull(auth);
        assertTrue(auth, auth.contains("algorithm=SHA-256-sess"));
        String cnonce = param(auth, "cnonce");
        String ha1 = DigestTestSupport.hex("SHA-256",
                DigestTestSupport.ha1("alice", "example", "secret") + ":abc:" + cnonce);
        String expected = DigestTestSupport.response("SHA-256", ha1, "abc",
                "00000001", cnonce, "auth", "GET", "/path");
        assertEquals(expected, param(auth, "response"));
    }

    @Test
    public void digestChallengeWithMd5IsNotRetried() {
        handler.credentials("alice", "secret");
        RecordingHandler rh = new RecordingHandler();
        handler.get("/resource", rh).endMessage();
        feed("HTTP/1.1 401 Unauthorized\r\n"
                + "WWW-Authenticate: Digest realm=\"example\", nonce=\"n\", qop=\"auth\", algorithm=MD5\r\n"
                + "Content-Length: 0\r\n\r\n");
        String wire = new String(endpoint.getAllBytes(), StandardCharsets.US_ASCII);
        assertFalse(wire, wire.contains("Authorization:"));
    }

    @Test
    public void digestChallengeWithoutAlgorithmIsNotRetried() {
        handler.credentials("alice", "secret");
        RecordingHandler rh = new RecordingHandler();
        handler.get("/resource", rh).endMessage();
        feed("HTTP/1.1 401 Unauthorized\r\n"
                + "WWW-Authenticate: Digest realm=\"example\", nonce=\"n\", qop=\"auth\"\r\n"
                + "Content-Length: 0\r\n\r\n");
        String wire = new String(endpoint.getAllBytes(), StandardCharsets.US_ASCII);
        assertFalse(wire, wire.contains("Authorization:"));
    }

    @Test
    public void sha256ChallengeIsPreferredOverMd5ChallengeInSeparateHeaders() {
        handler.credentials("alice", "secret");
        RecordingHandler rh = new RecordingHandler();
        handler.get("/resource", rh).endMessage();
        feed("HTTP/1.1 401 Unauthorized\r\n"
                + "WWW-Authenticate: Digest realm=\"example\", nonce=\"nmd5\", qop=\"auth\", algorithm=MD5\r\n"
                + "WWW-Authenticate: Digest realm=\"example\", nonce=\"nsha\", qop=\"auth\", algorithm=SHA-256\r\n"
                + "Content-Length: 0\r\n\r\n");
        String wire = new String(endpoint.getAllBytes(), StandardCharsets.US_ASCII);
        int at = wire.indexOf("Authorization: Digest ");
        assertTrue(wire, at > 0);
        String auth = wire.substring(at, wire.indexOf("\r\n", at));
        assertEquals("nsha", param(auth, "nonce"));
        assertTrue(auth, auth.contains("algorithm=SHA-256"));
        assertVerifies(auth, "alice", "secret", "example", "nsha", "GET", "/resource");
    }

    /** Recomputes the SHA-256 qop=auth response from the header's own nc/cnonce. */
    private static void assertVerifies(String auth, String user, String pw, String realm,
            String nonce, String method, String uri) {
        String expected = DigestTestSupport.response("SHA-256",
                DigestTestSupport.ha1(user, realm, pw), nonce, param(auth, "nc"),
                param(auth, "cnonce"), "auth", method, uri);
        assertEquals(expected, param(auth, "response"));
    }

    /** Extracts name=value or name="value" from an Authorization header. */
    private static String param(String auth, String name) {
        int i = auth.indexOf(", " + name + "=");
        if (i < 0) {
            i = auth.indexOf(" " + name + "=");
        }
        assertTrue(name + " in " + auth, i >= 0);
        int start = auth.indexOf('=', i) + 1;
        if (auth.charAt(start) == '"') {
            return auth.substring(start + 1, auth.indexOf('"', start + 1));
        }
        int end = auth.indexOf(',', start);
        return end < 0 ? auth.substring(start) : auth.substring(start, end);
    }

    @Test
    public void computeDigestAuthReturnsNullWhenChallengeIncomplete() throws Exception {
        handler.credentials("u", "p");
        assertNull(invokeDigestAuth("Digest realm=\"only\"", "GET", "/"));
        assertNull(invokeDigestAuth("Digest nonce=\"only\"", "GET", "/"));
    }

    @Test
    public void parseDirectiveReadsQuotedAndUnquotedValues() throws Exception {
        String header = "Digest realm=\"My Realm\", nonce=abc123, qop=\"auth, auth-int\"";
        assertEquals("My Realm", invokeParseDirective(header, "realm"));
        assertEquals("abc123", invokeParseDirective(header, "nonce"));
        assertEquals("auth, auth-int", invokeParseDirective(header, "qop"));
    }

    private String invokeDigestAuth(String challenge, String method, String uri) throws Exception {
        Method m = HttpClientProtocolHandler.class.getDeclaredMethod(
                "computeDigestAuth", String.class, String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(handler, challenge, method, uri);
    }

    private String invokeParseDirective(String header, String name) throws Exception {
        Method m = HttpClientProtocolHandler.class.getDeclaredMethod(
                "parseDirective", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(handler, header, name);
    }

    private static final class RecordingHandler extends CollectingResponseHandler {
        HttpStatus response;
        boolean ok;
        boolean error;
        final StringBuilder body = new StringBuilder();

        @Override
        public void ok(HttpStatus response) {
            ok = true;
            this.response = response;
        }

        @Override
        public void error(HttpStatus response) {
            error = true;
            this.response = response;
        }

        @Override
        public void responseBodyContent(ByteBuffer data) {
            if (data.hasRemaining()) {
                byte[] chunk = new byte[data.remaining()];
                data.get(chunk);
                body.append(new String(chunk, StandardCharsets.UTF_8));
            }
        }

    }
}
