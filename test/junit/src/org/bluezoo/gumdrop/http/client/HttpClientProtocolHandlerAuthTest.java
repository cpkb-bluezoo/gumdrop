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
import java.security.MessageDigest;

import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.bluezoo.util.ByteArrays;

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
        handler.get("/protected").send(rh);

        feed("HTTP/1.1 401 Unauthorized\r\n"
                + "WWW-Authenticate: Basic realm=\"test\"\r\n"
                + "Content-Length: 0\r\n\r\n");

        assertFalse("401 must not surface to the handler before retry", rh.error || rh.ok);

        String retry = new String(endpoint.getAllBytes(), StandardCharsets.US_ASCII);
        assertTrue(retry.contains("Authorization: Basic "));
        assertTrue(retry.contains("GET /protected"));

        feed("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok");

        assertTrue(rh.ok);
        assertEquals(HttpStatus.OK, rh.response.getStatus());
        assertEquals("ok", rh.body.toString());
    }

    @Test
    public void digest401RetriesWithDigestAuthorization() throws Exception {
        handler.credentials("alice", "secret");
        RecordingHandler rh = new RecordingHandler();
        handler.get("/resource").send(rh);

        feed("HTTP/1.1 401 Unauthorized\r\n"
                + "WWW-Authenticate: Digest realm=\"example\", nonce=\"deadbeef\", qop=\"auth\"\r\n"
                + "Content-Length: 0\r\n\r\n");

        String wire = new String(endpoint.getAllBytes(), StandardCharsets.US_ASCII);
        assertTrue(wire.contains("Authorization: Digest "));
        assertTrue(wire.contains("username=\"alice\""));
        assertTrue(wire.contains("qop=auth"));

        feed("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
        assertTrue(rh.ok);
    }

    @Test
    public void computeDigestAuthMd5WithoutQopMatchesReference() throws Exception {
        handler.credentials("alice", "secret");
        String challenge = "Digest realm=\"example\", nonce=\"abc\", algorithm=MD5";
        String auth = invokeDigestAuth(challenge, "GET", "/path");
        assertNotNull(auth);
        assertTrue(auth.startsWith("Digest "));

        MessageDigest md = MessageDigest.getInstance("MD5");
        String ha1 = hex(md, "alice:example:secret");
        String ha2 = hex(md, "GET:/path");
        String expectedResponse = hex(md, ha1 + ":abc:" + ha2);

        assertTrue(auth.contains("response=\"" + expectedResponse + "\""));
        assertFalse(auth.contains("algorithm="));
    }

    @Test
    public void computeDigestAuthSha256IncludesAlgorithm() throws Exception {
        handler.credentials("u", "p");
        String challenge = "Digest realm=\"r\", nonce=\"n\", algorithm=SHA-256, qop=\"auth\"";
        String auth = invokeDigestAuth(challenge, "POST", "/x");
        assertNotNull(auth);
        assertTrue(auth.contains("algorithm=SHA-256"));
        assertTrue(auth.contains("qop=auth"));
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

    private static String hex(MessageDigest md, String input) {
        md.reset();
        return ByteArrays.toHexString(md.digest(input.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class RecordingHandler extends DefaultHttpResponseHandler {
        HttpResponse response;
        boolean ok;
        boolean error;
        final StringBuilder body = new StringBuilder();

        @Override
        public void ok(HttpResponse response) {
            ok = true;
            this.response = response;
        }

        @Override
        public void error(HttpResponse response) {
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

        @Override
        public void pushPromise(PushPromise promise) {
            promise.reject();
        }
    }
}
