package org.bluezoo.gumdrop.auth;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SASLUtils}.
 */
public class SASLUtilsTest {

    // ========== Base64 ==========

    @Test
    public void testEncodeBase64Bytes() {
        byte[] data = "Hello, World!".getBytes(StandardCharsets.US_ASCII);
        String encoded = SASLUtils.encodeBase64(data);
        assertEquals("SGVsbG8sIFdvcmxkIQ==", encoded);
    }

    @Test
    public void testEncodeBase64String() {
        assertEquals("SGVsbG8=", SASLUtils.encodeBase64("Hello"));
    }

    @Test
    public void testDecodeBase64() {
        byte[] decoded = SASLUtils.decodeBase64("SGVsbG8=");
        assertEquals("Hello", new String(decoded, StandardCharsets.UTF_8));
    }

    @Test
    public void testDecodeBase64ToString() {
        assertEquals("Hello, World!", SASLUtils.decodeBase64ToString("SGVsbG8sIFdvcmxkIQ=="));
    }

    @Test
    public void testBase64RoundTrip() {
        String original = "The quick brown fox";
        String encoded = SASLUtils.encodeBase64(original);
        assertEquals(original, SASLUtils.decodeBase64ToString(encoded));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDecodeBase64Invalid() {
        SASLUtils.decodeBase64("not valid base64!!!");
    }

    // ========== Nonce ==========

    @Test
    public void testGenerateNonce() {
        String nonce = SASLUtils.generateNonce(16);
        assertNotNull(nonce);
        assertEquals(32, nonce.length()); // 16 bytes = 32 hex chars
    }

    @Test
    public void testGenerateNonceUniqueness() {
        String n1 = SASLUtils.generateNonce(16);
        String n2 = SASLUtils.generateNonce(16);
        assertNotEquals(n1, n2);
    }

    // ========== MD5 ==========

    @Test
    public void testMd5() {
        byte[] hash = SASLUtils.md5("".getBytes(StandardCharsets.UTF_8));
        assertNotNull(hash);
        assertEquals(16, hash.length);
    }

    @Test
    public void testMd5KnownValue() {
        // MD5("abc") = 900150983cd24fb0d6963f7d28e17f72
        String hex = SASLUtils.md5Hex("abc".getBytes(StandardCharsets.UTF_8));
        assertEquals("900150983cd24fb0d6963f7d28e17f72", hex);
    }

    @Test
    public void testMd5Hex() {
        String hex = SASLUtils.md5Hex("test".getBytes(StandardCharsets.UTF_8));
        assertNotNull(hex);
        assertEquals(32, hex.length());
        assertTrue(hex.matches("[0-9a-f]+"));
    }

    // ========== SHA-256 ==========

    @Test
    public void testSha256() {
        byte[] hash = SASLUtils.sha256("".getBytes(StandardCharsets.UTF_8));
        assertNotNull(hash);
        assertEquals(32, hash.length);
    }

    @Test
    public void testSha256KnownValue() {
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        byte[] hash = SASLUtils.sha256("abc".getBytes(StandardCharsets.UTF_8));
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
        byte[] hmac = SASLUtils.hmacMD5(key, data);
        assertNotNull(hmac);
        assertEquals(16, hmac.length);
    }

    @Test
    public void testHmacSHA256() {
        byte[] key = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] data = "message".getBytes(StandardCharsets.UTF_8);
        byte[] hmac = SASLUtils.hmacSHA256(key, data);
        assertNotNull(hmac);
        assertEquals(32, hmac.length);
    }

    @Test
    public void testHmacMD5Deterministic() {
        byte[] key = "key".getBytes(StandardCharsets.UTF_8);
        byte[] data = "data".getBytes(StandardCharsets.UTF_8);
        byte[] h1 = SASLUtils.hmacMD5(key, data);
        byte[] h2 = SASLUtils.hmacMD5(key, data);
        assertArrayEquals(h1, h2);
    }

    // ========== CRAM-MD5 ==========

    @Test
    public void testComputeCramMD5Response() {
        String response = SASLUtils.computeCramMD5Response("password", "<challenge@host>");
        assertNotNull(response);
        assertEquals(32, response.length());
        assertTrue(response.matches("[0-9a-f]+"));
    }

    @Test
    public void testComputeCramMD5ResponseDeterministic() {
        String r1 = SASLUtils.computeCramMD5Response("pass", "<1234@host>");
        String r2 = SASLUtils.computeCramMD5Response("pass", "<1234@host>");
        assertEquals(r1, r2);
    }

    @Test
    public void testVerifyCramMD5() {
        String challenge = "<test@example.com>";
        String password = "mypassword";
        String digest = SASLUtils.computeCramMD5Response(password, challenge);
        String response = "user " + digest;
        assertTrue(SASLUtils.verifyCramMD5(response, challenge, password));
    }

    @Test
    public void testVerifyCramMD5WrongPassword() {
        String challenge = "<test@example.com>";
        String digest = SASLUtils.computeCramMD5Response("correctpassword", challenge);
        String response = "user " + digest;
        assertFalse(SASLUtils.verifyCramMD5(response, challenge, "wrongpassword"));
    }

    @Test
    public void testVerifyCramMD5InvalidFormat() {
        assertFalse(SASLUtils.verifyCramMD5("nospacehere", "<challenge>", "pass"));
    }

    // ========== DIGEST-MD5 ==========

    @Test
    public void testParseDigestParams() {
        String response = "realm=\"example.com\",nonce=\"abc123\",qop=\"auth\",charset=utf-8";
        Map<String, String> params = SASLUtils.parseDigestParams(response);

        assertEquals("example.com", params.get("realm"));
        assertEquals("abc123", params.get("nonce"));
        assertEquals("auth", params.get("qop"));
        assertEquals("utf-8", params.get("charset"));
    }

    @Test
    public void testParseDigestParamsUnquoted() {
        String response = "nc=00000001,qop=auth";
        Map<String, String> params = SASLUtils.parseDigestParams(response);
        assertEquals("00000001", params.get("nc"));
        assertEquals("auth", params.get("qop"));
    }

    @Test
    public void testParseDigestParamsEscapedQuote() {
        String response = "realm=\"test\\\"realm\"";
        Map<String, String> params = SASLUtils.parseDigestParams(response);
        assertEquals("test\"realm", params.get("realm"));
    }

    @Test
    public void testParseDigestParamsEmpty() {
        Map<String, String> params = SASLUtils.parseDigestParams("");
        assertTrue(params.isEmpty());
    }

    @Test
    public void testComputeDigestHA1() {
        String ha1 = SASLUtils.computeDigestHA1("user", "realm", "pass");
        assertNotNull(ha1);
        assertEquals(32, ha1.length());
        assertTrue(ha1.matches("[0-9a-f]+"));
    }

    @Test
    public void testComputeDigestHA1Deterministic() {
        String h1 = SASLUtils.computeDigestHA1("alice", "example.com", "secret");
        String h2 = SASLUtils.computeDigestHA1("alice", "example.com", "secret");
        assertEquals(h1, h2);
    }

    // ========== Challenge generation ==========

    @Test
    public void testGenerateDigestMD5Challenge() {
        String challenge = SASLUtils.generateDigestMD5Challenge("example.com", "abc123");
        assertTrue(challenge.contains("realm=\"example.com\""));
        assertTrue(challenge.contains("nonce=\"abc123\""));
        assertTrue(challenge.contains("qop=\"auth\""));
        assertTrue(challenge.contains("algorithm=md5-sess"));
    }

    @Test
    public void testGenerateScramServerFirst() {
        String msg = SASLUtils.generateScramServerFirst("nonce123", "c2FsdA==", 4096);
        assertEquals("r=nonce123,s=c2FsdA==,i=4096", msg);
    }

    // ========== PLAIN ==========

    @Test
    public void testParsePlainCredentials() {
        byte[] creds = "\0alice\0password".getBytes(StandardCharsets.UTF_8);
        String[] parsed = SASLUtils.parsePlainCredentials(creds);

        assertEquals(3, parsed.length);
        assertEquals("", parsed[0]);        // authzid
        assertEquals("alice", parsed[1]);   // authcid
        assertEquals("password", parsed[2]);
    }

    @Test
    public void testParsePlainCredentialsWithAuthzid() {
        byte[] creds = "admin\0alice\0password".getBytes(StandardCharsets.UTF_8);
        String[] parsed = SASLUtils.parsePlainCredentials(creds);

        assertEquals("admin", parsed[0]);
        assertEquals("alice", parsed[1]);
        assertEquals("password", parsed[2]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParsePlainCredentialsInvalid() {
        byte[] creds = "no-null-bytes".getBytes(StandardCharsets.UTF_8);
        SASLUtils.parsePlainCredentials(creds);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParsePlainCredentialsOnlyOneNull() {
        byte[] creds = "one\0null".getBytes(StandardCharsets.UTF_8);
        SASLUtils.parsePlainCredentials(creds);
    }

    // ========== OAUTHBEARER ==========

    @Test
    public void testParseOAuthBearerCredentials() {
        String creds = "n,a=user@example.com,\u0001auth=Bearer mytoken\u0001\u0001";
        Map<String, String> parsed = SASLUtils.parseOAuthBearerCredentials(creds);

        assertEquals("user@example.com", parsed.get("user"));
        assertEquals("mytoken", parsed.get("token"));
    }

    @Test
    public void testParseOAuthBearerCredentialsNoUser() {
        String creds = "n,,\u0001auth=Bearer tok123\u0001\u0001";
        Map<String, String> parsed = SASLUtils.parseOAuthBearerCredentials(creds);

        assertNull(parsed.get("user"));
        assertEquals("tok123", parsed.get("token"));
    }

    @Test
    public void testParseOAuthBearerCredentialsNoCtrlA() {
        String creds = "n,a=user@example.com,";
        Map<String, String> parsed = SASLUtils.parseOAuthBearerCredentials(creds);
        assertTrue(parsed.isEmpty());
    }

    // ========== Client Mechanisms ==========

    @Test
    public void testCreateClientPlain() {
        SASLClientMechanism mech = SASLUtils.createClient("PLAIN", "user", "pass", "host");
        assertNotNull(mech);
        assertEquals("PLAIN", mech.getMechanismName());
        assertTrue(mech.hasInitialResponse());
    }

    @Test
    public void testCreateClientCramMD5() {
        SASLClientMechanism mech = SASLUtils.createClient("CRAM-MD5", "user", "pass", "host");
        assertNotNull(mech);
        assertEquals("CRAM-MD5", mech.getMechanismName());
        assertFalse(mech.hasInitialResponse());
    }

    @Test
    public void testCreateClientExternal() {
        SASLClientMechanism mech = SASLUtils.createClient("EXTERNAL", null, null, null);
        assertNotNull(mech);
        assertEquals("EXTERNAL", mech.getMechanismName());
        assertTrue(mech.hasInitialResponse());
    }

    @Test
    public void testCreateClientUnknown() {
        assertNull(SASLUtils.createClient("UNKNOWN_MECH", "user", "pass", "host"));
    }

    @Test
    public void testCreateClientNull() {
        assertNull(SASLUtils.createClient(null, "user", "pass", "host"));
    }

    @Test
    public void testCreateClientCaseInsensitive() {
        assertNotNull(SASLUtils.createClient("plain", "user", "pass", "host"));
        assertNotNull(SASLUtils.createClient("cram-md5", "user", "pass", "host"));
    }

    @Test
    public void testPlainClientEvaluateChallenge() throws Exception {
        SASLClientMechanism mech = SASLUtils.createClient("PLAIN", "alice", "secret", "host");
        byte[] response = mech.evaluateChallenge(new byte[0]);

        // Format: \0alice\0secret
        assertNotNull(response);
        assertEquals(0, response[0]); // empty authzid
        assertTrue(mech.isComplete());
    }

    @Test
    public void testExternalClientEvaluateChallenge() throws Exception {
        SASLClientMechanism mech = SASLUtils.createClient("EXTERNAL", null, null, null);
        byte[] response = mech.evaluateChallenge(new byte[0]);
        assertEquals(0, response.length);
        assertTrue(mech.isComplete());
    }

    @Test
    public void testCreateClientGssapiWithoutSubject() {
        assertNull(SASLUtils.createClient("GSSAPI", "user", null, "host"));
    }
}
