/*
 * SASLUtilsTest.java
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

package org.bluezoo.gumdrop.auth;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SaslUtils}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SASLUtilsTest {

    // ========== Base64 ==========

    @Test
    public void testEncodeBase64Bytes() {
        byte[] data = "Hello, World!".getBytes(StandardCharsets.US_ASCII);
        String encoded = SaslUtils.encodeBase64(data);
        assertEquals("SGVsbG8sIFdvcmxkIQ==", encoded);
    }

    @Test
    public void testEncodeBase64String() {
        assertEquals("SGVsbG8=", SaslUtils.encodeBase64("Hello"));
    }

    @Test
    public void testDecodeBase64() {
        byte[] decoded = SaslUtils.decodeBase64("SGVsbG8=");
        assertEquals("Hello", new String(decoded, StandardCharsets.UTF_8));
    }

    @Test
    public void testDecodeBase64ToString() {
        assertEquals("Hello, World!", SaslUtils.decodeBase64ToString("SGVsbG8sIFdvcmxkIQ=="));
    }

    @Test
    public void testBase64RoundTrip() {
        String original = "The quick brown fox";
        String encoded = SaslUtils.encodeBase64(original);
        assertEquals(original, SaslUtils.decodeBase64ToString(encoded));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDecodeBase64Invalid() {
        SaslUtils.decodeBase64("not valid base64!!!");
    }

    // ========== Nonce ==========

    @Test
    public void testGenerateNonce() {
        String nonce = SaslUtils.generateNonce(16);
        assertNotNull(nonce);
        assertEquals(32, nonce.length()); // 16 bytes = 32 hex chars
    }

    @Test
    public void testGenerateNonceUniqueness() {
        String n1 = SaslUtils.generateNonce(16);
        String n2 = SaslUtils.generateNonce(16);
        assertNotEquals(n1, n2);
    }

    // ========== SHA-256 ==========

    @Test
    public void testSha256() {
        byte[] hash = SaslUtils.sha256("".getBytes(StandardCharsets.UTF_8));
        assertNotNull(hash);
        assertEquals(32, hash.length);
    }

    @Test
    public void testSha256KnownValue() {
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        byte[] hash = SaslUtils.sha256("abc".getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b & 0xff));
        }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sb.toString());
    }

    // ========== HMAC ==========

    @Test
    public void testHmacSHA256() {
        byte[] key = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] data = "message".getBytes(StandardCharsets.UTF_8);
        byte[] hmac = SaslUtils.hmacSHA256(key, data);
        assertNotNull(hmac);
        assertEquals(32, hmac.length);
    }

    // ========== Challenge generation ==========

    @Test
    public void testGenerateScramServerFirst() {
        String msg = SaslUtils.generateScramServerFirst("nonce123", "c2FsdA==", 4096);
        assertEquals("r=nonce123,s=c2FsdA==,i=4096", msg);
    }

    // ========== PLAIN ==========

    @Test
    public void testParsePlainCredentials() {
        byte[] creds = "\0alice\0password".getBytes(StandardCharsets.UTF_8);
        String[] parsed = SaslUtils.parsePlainCredentials(creds);

        assertEquals(3, parsed.length);
        assertEquals("", parsed[0]);        // authzid
        assertEquals("alice", parsed[1]);   // authcid
        assertEquals("password", parsed[2]);
    }

    @Test
    public void testParsePlainCredentialsWithAuthzid() {
        byte[] creds = "admin\0alice\0password".getBytes(StandardCharsets.UTF_8);
        String[] parsed = SaslUtils.parsePlainCredentials(creds);

        assertEquals("admin", parsed[0]);
        assertEquals("alice", parsed[1]);
        assertEquals("password", parsed[2]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParsePlainCredentialsInvalid() {
        byte[] creds = "no-null-bytes".getBytes(StandardCharsets.UTF_8);
        SaslUtils.parsePlainCredentials(creds);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParsePlainCredentialsOnlyOneNull() {
        byte[] creds = "one\0null".getBytes(StandardCharsets.UTF_8);
        SaslUtils.parsePlainCredentials(creds);
    }

    // ========== OAUTHBEARER ==========

    @Test
    public void testParseOAuthBearerCredentials() {
        String creds = "n,a=user@example.com,\u0001auth=Bearer mytoken\u0001\u0001";
        Map<String, String> parsed = SaslUtils.parseOAuthBearerCredentials(creds);

        assertEquals("user@example.com", parsed.get("user"));
        assertEquals("mytoken", parsed.get("token"));
    }

    @Test
    public void testParseOAuthBearerCredentialsNoUser() {
        String creds = "n,,\u0001auth=Bearer tok123\u0001\u0001";
        Map<String, String> parsed = SaslUtils.parseOAuthBearerCredentials(creds);

        assertNull(parsed.get("user"));
        assertEquals("tok123", parsed.get("token"));
    }

    @Test
    public void testParseOAuthBearerCredentialsNoCtrlA() {
        String creds = "n,a=user@example.com,";
        Map<String, String> parsed = SaslUtils.parseOAuthBearerCredentials(creds);
        assertTrue(parsed.isEmpty());
    }

    // ========== Client Mechanisms ==========

    @Test
    public void testCreateClientPlain() {
        SaslClientMechanism mech = SaslUtils.createClient("PLAIN", "user", "pass", "host");
        assertNotNull(mech);
        assertEquals("PLAIN", mech.getMechanismName());
        assertTrue(mech.hasInitialResponse());
    }

    @Test
    public void testCreateClientMd5MechanismsAreNotSupported() {
        // CRAM-MD5 and DIGEST-MD5 were removed deliberately
        assertNull(SaslUtils.createClient("CRAM-MD5", "u", "p", "host"));
        assertNull(SaslUtils.createClient("DIGEST-MD5", "u", "p", "host"));
        assertNull(SaslUtils.createClient("digest-md5", "u", "p", "host"));
    }

    @Test
    public void testCreateClientExternal() {
        SaslClientMechanism mech = SaslUtils.createClient("EXTERNAL", null, null, null);
        assertNotNull(mech);
        assertEquals("EXTERNAL", mech.getMechanismName());
        assertTrue(mech.hasInitialResponse());
    }

    @Test
    public void testCreateClientUnknown() {
        assertNull(SaslUtils.createClient("UNKNOWN_MECH", "user", "pass", "host"));
    }

    @Test
    public void testCreateClientNull() {
        assertNull(SaslUtils.createClient(null, "user", "pass", "host"));
    }

    @Test
    public void testCreateClientCaseInsensitive() {
        assertNotNull(SaslUtils.createClient("plain", "user", "pass", "host"));
        assertNotNull(SaslUtils.createClient("external", "user", "pass", "host"));
    }

    @Test
    public void testPlainClientEvaluateChallenge() throws Exception {
        SaslClientMechanism mech = SaslUtils.createClient("PLAIN", "alice", "secret", "host");
        byte[] response = mech.evaluateChallenge(new byte[0]);

        // Format: \0alice\0secret
        assertNotNull(response);
        assertEquals(0, response[0]); // empty authzid
        assertTrue(mech.isComplete());
    }

    @Test
    public void testExternalClientEvaluateChallenge() throws Exception {
        SaslClientMechanism mech = SaslUtils.createClient("EXTERNAL", null, null, null);
        byte[] response = mech.evaluateChallenge(new byte[0]);
        assertEquals(0, response.length);
        assertTrue(mech.isComplete());
    }

    @Test
    public void testCreateClientGssapiWithoutSubject() {
        assertNull(SaslUtils.createClient("GSSAPI", "user", null, "host"));
    }

    // ========== Client OAuth (RFC 7628, XOAUTH2) ==========

    @Test
    public void testFormatOAuthBearerInitialResponse() {
        assertEquals("n,a=user@example.com,\u0001auth=Bearer mytoken\u0001\u0001",
                SaslUtils.formatOAuthBearerInitialResponse("user@example.com", "mytoken"));
    }

    @Test
    public void testFormatOAuthBearerRoundTripsThroughParser() {
        String ir = SaslUtils.formatOAuthBearerInitialResponse("a@b.c", "tok");
        Map<String, String> parsed = SaslUtils.parseOAuthBearerCredentials(ir);
        assertEquals("a@b.c", parsed.get("user"));
        assertEquals("tok", parsed.get("token"));
    }

    @Test
    public void testFormatOAuthBearerEscapesAuthzid() {
        assertEquals("n,a=a=3Db=2Cc,\u0001auth=Bearer t\u0001\u0001",
                SaslUtils.formatOAuthBearerInitialResponse("a=b,c", "t"));
    }

    @Test
    public void testFormatXOAuth2InitialResponse() {
        assertEquals("user=someuser@example.com\u0001auth=Bearer ya29.vF9dft4qmTc2Nvb3RlckBhdHRhdmlzdGEuY29tCg\u0001\u0001",
                SaslUtils.formatXOAuth2InitialResponse("someuser@example.com",
                        "ya29.vF9dft4qmTc2Nvb3RlckBhdHRhdmlzdGEuY29tCg"));
    }

    @Test
    public void testCreateClientXOAuth2() throws Exception {
        SaslClientMechanism m = SaslUtils.createClient("xoauth2", "u@example.com", "tok", "imap.gmail.com");
        assertNotNull(m);
        assertEquals("XOAUTH2", m.getMechanismName());
        assertTrue(m.hasInitialResponse());
        assertFalse(m.isComplete());
        byte[] ir = m.evaluateChallenge(new byte[0]);
        assertEquals(SaslUtils.formatXOAuth2InitialResponse("u@example.com", "tok"),
                new String(ir, StandardCharsets.UTF_8));
        // error challenge from the server is acknowledged with an empty response
        assertEquals(0, m.evaluateChallenge("{\"status\":\"400\"}".getBytes(StandardCharsets.UTF_8)).length);
        assertTrue(m.isComplete());
    }

    @Test
    public void testCreateClientOAuthBearer() throws Exception {
        SaslClientMechanism m = SaslUtils.createClient("OAUTHBEARER", "u@example.com", "tok", "h");
        assertNotNull(m);
        assertEquals("OAUTHBEARER", m.getMechanismName());
        assertTrue(m.hasInitialResponse());
        byte[] ir = m.evaluateChallenge(new byte[0]);
        assertEquals(SaslUtils.formatOAuthBearerInitialResponse("u@example.com", "tok"),
                new String(ir, StandardCharsets.UTF_8));
        // RFC 7628 section 3.2.3: client acknowledges an error with a single %x01
        assertArrayEquals(new byte[] {1}, m.evaluateChallenge("{\"status\":\"invalid_token\"}".getBytes(StandardCharsets.UTF_8)));
        assertTrue(m.isComplete());
    }

    @Test
    public void testSelectOAuthMechanism() {
        assertEquals("XOAUTH2", SaslUtils.selectOAuthMechanism(
                java.util.Arrays.asList("IMAP4rev1", "AUTH=OAUTHBEARER", "auth=xoauth2")));
        assertEquals("OAUTHBEARER", SaslUtils.selectOAuthMechanism(
                java.util.Arrays.asList("IMAP4rev1", "AUTH=PLAIN", "AUTH=OAUTHBEARER")));
        assertNull(SaslUtils.selectOAuthMechanism(java.util.Arrays.asList("AUTH=PLAIN")));
        assertNull(SaslUtils.selectOAuthMechanism(null));
    }
}
