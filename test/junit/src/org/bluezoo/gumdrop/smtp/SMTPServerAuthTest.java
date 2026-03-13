/*
 * SMTPServerAuthTest.java
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

package org.bluezoo.gumdrop.smtp;

import org.bluezoo.gumdrop.auth.SASLUtils;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.util.ByteArrays;
import org.junit.Test;

import java.util.Base64;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for SMTP server-side SASL authentication mechanisms
 * (items 81-83) and supporting SASLUtils methods.
 */
public class SMTPServerAuthTest {

    // -- CRAM-MD5 (RFC 2195) --

    @Test
    public void testCramMD5ChallengeFormat() {
        String challenge = SASLUtils.generateCramMD5Challenge("mail.example.com");
        assertNotNull(challenge);
        assertTrue(challenge.startsWith("<"));
        assertTrue(challenge.endsWith(">"));
        assertTrue(challenge.contains("@mail.example.com"));
    }

    @Test
    public void testCramMD5ResponseVerification() {
        String challenge = "<12345.678@mail.example.com>";
        String password = "secret";
        String expected = SASLUtils.computeCramMD5Response(password, challenge);
        assertNotNull(expected);
        assertFalse(expected.isEmpty());
        String response = "testuser " + expected;
        assertTrue(SASLUtils.verifyCramMD5(response, challenge, password));
    }

    @Test
    public void testCramMD5WrongPassword() {
        String challenge = "<12345.678@mail.example.com>";
        String correctDigest = SASLUtils.computeCramMD5Response("secret", challenge);
        String response = "testuser " + correctDigest;
        assertFalse(SASLUtils.verifyCramMD5(response, challenge, "wrongpassword"));
    }

    @Test
    public void testCramMD5MalformedResponse() {
        assertFalse(SASLUtils.verifyCramMD5("nospaceinresponse", "<c@h>", "pass"));
    }

    // -- DIGEST-MD5 (RFC 2831) --

    @Test
    public void testDigestMD5ChallengeFormat() {
        String challenge = SASLUtils.generateDigestMD5Challenge("example.com", "abc123");
        assertNotNull(challenge);
        assertTrue(challenge.contains("realm=\"example.com\""));
        assertTrue(challenge.contains("nonce=\"abc123\""));
        assertTrue(challenge.contains("qop=\"auth\""));
        assertTrue(challenge.contains("algorithm=md5-sess"));
    }

    @Test
    public void testDigestParamsParsingSimple() {
        String input = "username=\"alice\",realm=\"example.com\",nonce=\"abc123\"";
        Map<String, String> params = SASLUtils.parseDigestParams(input);
        assertEquals("alice", params.get("username"));
        assertEquals("example.com", params.get("realm"));
        assertEquals("abc123", params.get("nonce"));
    }

    @Test
    public void testDigestParamsParsingWithEscapes() {
        String input = "username=\"ali\\\"ce\"";
        Map<String, String> params = SASLUtils.parseDigestParams(input);
        assertEquals("ali\"ce", params.get("username"));
    }

    @Test
    public void testDigestHA1Computation() {
        String ha1 = SASLUtils.computeDigestHA1("alice", "example.com", "secret");
        assertNotNull(ha1);
        assertEquals(32, ha1.length()); // MD5 hex = 32 chars
    }

    // -- SCRAM-SHA-256 (RFC 5802, RFC 7677) --

    @Test
    public void testScramServerFirstMessage() {
        String serverFirst = SASLUtils.generateScramServerFirst(
                "clientnonce+servernonce", "c2FsdA==", 4096);
        assertEquals("r=clientnonce+servernonce,s=c2FsdA==,i=4096", serverFirst);
    }

    @Test
    public void testScramCredentialsDerivation() {
        byte[] salt = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        Realm.ScramCredentials creds =
                Realm.ScramCredentials.derive("password", salt, 4096, "SHA-256");
        assertNotNull(creds);
        assertNotNull(creds.salt);
        assertEquals(4096, creds.iterations);
        assertNotNull(creds.storedKey);
        assertNotNull(creds.serverKey);
        assertTrue(creds.storedKey.length > 0);
        assertTrue(creds.serverKey.length > 0);
    }

    @Test
    public void testScramCredentialsDeterministic() {
        byte[] salt = new byte[]{1, 2, 3, 4};
        Realm.ScramCredentials a = Realm.ScramCredentials.derive("pw", salt, 4096, "SHA-256");
        Realm.ScramCredentials b = Realm.ScramCredentials.derive("pw", salt, 4096, "SHA-256");
        assertArrayEquals(a.storedKey, b.storedKey);
        assertArrayEquals(a.serverKey, b.serverKey);
    }

    @Test
    public void testScramCredentialsDifferentPasswords() {
        byte[] salt = new byte[]{1, 2, 3, 4};
        Realm.ScramCredentials a = Realm.ScramCredentials.derive("pw1", salt, 4096, "SHA-256");
        Realm.ScramCredentials b = Realm.ScramCredentials.derive("pw2", salt, 4096, "SHA-256");
        assertFalse(java.util.Arrays.equals(a.storedKey, b.storedKey));
    }

    // -- OAUTHBEARER (RFC 7628) --

    @Test
    public void testOAuthBearerCredentialsParsing() {
        String credentials = "n,a=user@example.com,\u0001auth=Bearer my-token\u0001\u0001";
        Map<String, String> result = SASLUtils.parseOAuthBearerCredentials(credentials);
        assertEquals("user@example.com", result.get("user"));
        assertEquals("my-token", result.get("token"));
    }

    @Test
    public void testOAuthBearerNoUser() {
        String credentials = "n,,\u0001auth=Bearer token123\u0001\u0001";
        Map<String, String> result = SASLUtils.parseOAuthBearerCredentials(credentials);
        assertNull(result.get("user"));
        assertEquals("token123", result.get("token"));
    }

    @Test
    public void testOAuthBearerEmptyCredentials() {
        Map<String, String> result = SASLUtils.parseOAuthBearerCredentials("");
        assertNull(result.get("token"));
    }

    // -- Nonce generation --

    @Test
    public void testNonceGeneration() {
        String nonce1 = SASLUtils.generateNonce(16);
        String nonce2 = SASLUtils.generateNonce(16);
        assertNotNull(nonce1);
        assertNotNull(nonce2);
        assertEquals(32, nonce1.length()); // 16 bytes = 32 hex chars
        assertNotEquals(nonce1, nonce2);
    }

    // -- HMAC functions --

    @Test
    public void testHmacMD5() {
        byte[] key = "key".getBytes();
        byte[] data = "The quick brown fox".getBytes();
        byte[] hmac = SASLUtils.hmacMD5(key, data);
        assertNotNull(hmac);
        assertEquals(16, hmac.length); // MD5 = 16 bytes
    }

    @Test
    public void testHmacSHA256() {
        byte[] key = "key".getBytes();
        byte[] data = "data".getBytes();
        byte[] hmac = SASLUtils.hmacSHA256(key, data);
        assertNotNull(hmac);
        assertEquals(32, hmac.length); // SHA-256 = 32 bytes
    }

    // -- Base64 helpers --

    @Test
    public void testBase64RoundTrip() {
        String original = "Hello, World!";
        String encoded = SASLUtils.encodeBase64(original);
        String decoded = SASLUtils.decodeBase64ToString(encoded);
        assertEquals(original, decoded);
    }

    // -- PLAIN credentials (RFC 4616) --

    @Test
    public void testPlainCredentialsParsing() {
        byte[] creds = "\0alice\0secret".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String[] parsed = SASLUtils.parsePlainCredentials(creds);
        assertEquals(3, parsed.length);
        assertEquals("", parsed[0]); // authzid
        assertEquals("alice", parsed[1]); // authcid
        assertEquals("secret", parsed[2]); // password
    }

    @Test
    public void testPlainCredentialsWithAuthzid() {
        byte[] creds = "admin\0alice\0secret".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String[] parsed = SASLUtils.parsePlainCredentials(creds);
        assertEquals("admin", parsed[0]);
        assertEquals("alice", parsed[1]);
        assertEquals("secret", parsed[2]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPlainCredentialsMalformed() {
        SASLUtils.parsePlainCredentials("notnulls".getBytes());
    }

    // -- SHA-256 hash --

    @Test
    public void testSha256() {
        byte[] hash = SASLUtils.sha256("test".getBytes());
        assertNotNull(hash);
        assertEquals(32, hash.length);
    }

    // -- MD5 hash --

    @Test
    public void testMd5Hex() {
        String hex = SASLUtils.md5Hex("test".getBytes());
        assertNotNull(hex);
        assertEquals(32, hex.length());
    }

}
