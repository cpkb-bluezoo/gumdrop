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
import java.security.MessageDigest;
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

    // ========== MD5 ==========

    @Test
    public void testMd5() {
        byte[] hash = SaslUtils.md5("".getBytes(StandardCharsets.UTF_8));
        assertNotNull(hash);
        assertEquals(16, hash.length);
    }

    @Test
    public void testMd5KnownValue() {
        // MD5("abc") = 900150983cd24fb0d6963f7d28e17f72
        String hex = SaslUtils.md5Hex("abc".getBytes(StandardCharsets.UTF_8));
        assertEquals("900150983cd24fb0d6963f7d28e17f72", hex);
    }

    @Test
    public void testMd5Hex() {
        String hex = SaslUtils.md5Hex("test".getBytes(StandardCharsets.UTF_8));
        assertNotNull(hex);
        assertEquals(32, hex.length());
        assertTrue(hex.matches("[0-9a-f]+"));
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
    public void testHmacMD5() {
        byte[] key = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] data = "message".getBytes(StandardCharsets.UTF_8);
        byte[] hmac = SaslUtils.hmacMD5(key, data);
        assertNotNull(hmac);
        assertEquals(16, hmac.length);
    }

    @Test
    public void testHmacSHA256() {
        byte[] key = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] data = "message".getBytes(StandardCharsets.UTF_8);
        byte[] hmac = SaslUtils.hmacSHA256(key, data);
        assertNotNull(hmac);
        assertEquals(32, hmac.length);
    }

    @Test
    public void testHmacMD5Deterministic() {
        byte[] key = "key".getBytes(StandardCharsets.UTF_8);
        byte[] data = "data".getBytes(StandardCharsets.UTF_8);
        byte[] h1 = SaslUtils.hmacMD5(key, data);
        byte[] h2 = SaslUtils.hmacMD5(key, data);
        assertArrayEquals(h1, h2);
    }

    // ========== CRAM-MD5 ==========

    @Test
    public void testComputeCramMD5Response() {
        String response = SaslUtils.computeCramMD5Response("password", "<challenge@host>");
        assertNotNull(response);
        assertEquals(32, response.length());
        assertTrue(response.matches("[0-9a-f]+"));
    }

    @Test
    public void testComputeCramMD5ResponseDeterministic() {
        String r1 = SaslUtils.computeCramMD5Response("pass", "<1234@host>");
        String r2 = SaslUtils.computeCramMD5Response("pass", "<1234@host>");
        assertEquals(r1, r2);
    }

    @Test
    public void testVerifyCramMD5() {
        String challenge = "<test@example.com>";
        String password = "mypassword";
        String digest = SaslUtils.computeCramMD5Response(password, challenge);
        String response = "user " + digest;
        assertTrue(SaslUtils.verifyCramMD5(response, challenge, password));
    }

    @Test
    public void testVerifyCramMD5WrongPassword() {
        String challenge = "<test@example.com>";
        String digest = SaslUtils.computeCramMD5Response("correctpassword", challenge);
        String response = "user " + digest;
        assertFalse(SaslUtils.verifyCramMD5(response, challenge, "wrongpassword"));
    }

    @Test
    public void testVerifyCramMD5InvalidFormat() {
        assertFalse(SaslUtils.verifyCramMD5("nospacehere", "<challenge>", "pass"));
    }

    // ========== DIGEST-MD5 ==========

    @Test
    public void testParseDigestParams() {
        String response = "realm=\"example.com\",nonce=\"abc123\",qop=\"auth\",charset=utf-8";
        Map<String, String> params = SaslUtils.parseDigestParams(response);

        assertEquals("example.com", params.get("realm"));
        assertEquals("abc123", params.get("nonce"));
        assertEquals("auth", params.get("qop"));
        assertEquals("utf-8", params.get("charset"));
    }

    @Test
    public void testParseDigestParamsUnquoted() {
        String response = "nc=00000001,qop=auth";
        Map<String, String> params = SaslUtils.parseDigestParams(response);
        assertEquals("00000001", params.get("nc"));
        assertEquals("auth", params.get("qop"));
    }

    @Test
    public void testParseDigestParamsEscapedQuote() {
        String response = "realm=\"test\\\"realm\"";
        Map<String, String> params = SaslUtils.parseDigestParams(response);
        assertEquals("test\"realm", params.get("realm"));
    }

    @Test
    public void testParseDigestParamsEmpty() {
        Map<String, String> params = SaslUtils.parseDigestParams("");
        assertTrue(params.isEmpty());
    }

    @Test
    public void testComputeDigestHA1() {
        String ha1 = SaslUtils.computeDigestHA1("user", "realm", "pass");
        assertNotNull(ha1);
        assertEquals(32, ha1.length());
        assertTrue(ha1.matches("[0-9a-f]+"));
    }

    @Test
    public void testComputeDigestHA1Deterministic() {
        String h1 = SaslUtils.computeDigestHA1("alice", "example.com", "secret");
        String h2 = SaslUtils.computeDigestHA1("alice", "example.com", "secret");
        assertEquals(h1, h2);
    }

    // ========== DIGEST-MD5 client rspauth verification (GHSA-9p92-hc4p-35rp) ==========

    @Test
    public void testDigestMD5ClientAcceptsValidRspauth() throws Exception {
        String realm = "example.com";
        String nonce = "server-nonce-1234";
        String username = "alice";
        String password = "secret";
        String host = "ldap.example.com";

        SaslClientMechanism client = SaslUtils.createClient(
                "DIGEST-MD5", username, password, host);
        String challenge1 = SaslUtils.generateDigestMD5Challenge(realm, nonce);
        byte[] response1 = client.evaluateChallenge(
                challenge1.getBytes(StandardCharsets.UTF_8));

        Map<String, String> params = SaslUtils.parseDigestParams(
                new String(response1, StandardCharsets.UTF_8));
        String serverHa1 = SaslUtils.computeDigestHA1(username, realm, password);
        String rspauth = SaslUtils.verifyDigestMD5ClientResponse(serverHa1, nonce, params);
        assertNotNull("server-side verification of the client's own response must succeed", rspauth);

        // Must not throw: this is the server's genuine proof of shared-secret knowledge.
        byte[] response2 = client.evaluateChallenge(
                ("rspauth=" + rspauth).getBytes(StandardCharsets.UTF_8));
        assertNotNull(response2);
        assertTrue(client.isComplete());
    }

    @Test
    public void testDigestMD5ClientRejectsForgedRspauth() throws Exception {
        String realm = "example.com";
        String nonce = "server-nonce-5678";
        String username = "alice";
        String password = "secret";
        String host = "ldap.example.com";

        SaslClientMechanism client = SaslUtils.createClient(
                "DIGEST-MD5", username, password, host);
        String challenge1 = SaslUtils.generateDigestMD5Challenge(realm, nonce);
        client.evaluateChallenge(challenge1.getBytes(StandardCharsets.UTF_8));

        // A spoofed/on-path server (or a MITM replaying an unrelated
        // response digest) that doesn't know the shared secret cannot
        // produce the real rspauth. The client must detect this rather
        // than silently accepting the exchange as authenticated.
        try {
            client.evaluateChallenge(
                    "rspauth=00000000000000000000000000000000".getBytes(StandardCharsets.UTF_8));
            fail("expected an IOException for a forged rspauth");
        } catch (java.io.IOException expected) {
            // expected
        }
    }

    // ========== DIGEST-MD5 server-side response verification (issue #253) ==========

    @Test
    public void testServerVerificationInteroperatesWithRealClient() throws Exception {
        String realm = "example.com";
        String nonce = "server-nonce-9999";
        String username = "alice";
        String password = "secret";
        String host = "ldap.example.com";

        SaslClientMechanism client = SaslUtils.createClient(
                "DIGEST-MD5", username, password, host);
        String challenge1 = SaslUtils.generateDigestMD5Challenge(realm, nonce);
        byte[] response1 = client.evaluateChallenge(
                challenge1.getBytes(StandardCharsets.UTF_8));
        Map<String, String> params = SaslUtils.parseDigestParams(
                new String(response1, StandardCharsets.UTF_8));

        String serverHa1 = SaslUtils.computeDigestHA1(username, realm, password);
        String rspauth = SaslUtils.verifyDigestMD5ClientResponse(serverHa1, nonce, params);

        assertNotNull("the server must accept a genuine client response "
                + "computed with the correct credentials", rspauth);

        // The client, in turn, must accept the server's genuine rspauth.
        byte[] response2 = client.evaluateChallenge(
                ("rspauth=" + rspauth).getBytes(StandardCharsets.UTF_8));
        assertNotNull(response2);
        assertTrue(client.isComplete());
    }

    @Test
    public void testServerVerificationRejectsWrongPassword() throws Exception {
        String realm = "example.com";
        String nonce = "server-nonce-0001";
        String username = "alice";
        String host = "ldap.example.com";

        SaslClientMechanism client = SaslUtils.createClient(
                "DIGEST-MD5", username, "correct-password", host);
        String challenge1 = SaslUtils.generateDigestMD5Challenge(realm, nonce);
        byte[] response1 = client.evaluateChallenge(
                challenge1.getBytes(StandardCharsets.UTF_8));
        Map<String, String> params = SaslUtils.parseDigestParams(
                new String(response1, StandardCharsets.UTF_8));

        String wrongHa1 = SaslUtils.computeDigestHA1(username, realm, "wrong-password");
        assertNull(SaslUtils.verifyDigestMD5ClientResponse(wrongHa1, nonce, params));
    }

    // ========== Challenge generation ==========

    @Test
    public void testGenerateDigestMD5Challenge() {
        String challenge = SaslUtils.generateDigestMD5Challenge("example.com", "abc123");
        assertTrue(challenge.contains("realm=\"example.com\""));
        assertTrue(challenge.contains("nonce=\"abc123\""));
        assertTrue(challenge.contains("qop=\"auth\""));
        assertTrue(challenge.contains("algorithm=md5-sess"));
    }

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
    public void testCreateClientCramMD5() {
        SaslClientMechanism mech = SaslUtils.createClient("CRAM-MD5", "user", "pass", "host");
        assertNotNull(mech);
        assertEquals("CRAM-MD5", mech.getMechanismName());
        assertFalse(mech.hasInitialResponse());
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
        assertNotNull(SaslUtils.createClient("cram-md5", "user", "pass", "host"));
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
}
