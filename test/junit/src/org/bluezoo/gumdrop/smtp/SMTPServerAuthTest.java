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

import org.bluezoo.gumdrop.auth.SaslUtils;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.util.ByteArrays;
import org.junit.Test;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for SMTP server-side SASL authentication mechanisms
 * (items 81-83) and supporting SaslUtils methods.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SMTPServerAuthTest {

    // -- SCRAM-SHA-256 (RFC 5802, RFC 7677) --

    @Test
    public void testScramServerFirstMessage() {
        String serverFirst = SaslUtils.generateScramServerFirst(
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

    @Test
    public void testVerifyScramClientFinalRejectsInvalidProof() {
        byte[] salt = new byte[]{1, 2, 3, 4};
        Realm.ScramCredentials creds =
                Realm.ScramCredentials.derive("secret", salt, 4096, "SHA-256");
        String clientNonce = "clientnonce";
        String serverNonce = clientNonce + "servernonce";
        String serverFirst = SaslUtils.generateScramServerFirst(
                serverNonce, creds.salt, creds.iterations);
        String authChallenge = "n=alice,r=" + clientNonce + "," + serverFirst;
        String clientFinal = "c=biws,r=" + serverNonce + ",p="
                + java.util.Base64.getEncoder().encodeToString(new byte[32]);
        assertNull(SaslUtils.verifyScramClientFinal(
                creds, authChallenge, clientFinal, serverNonce));
    }

    @Test
    public void testVerifyScramClientFinalRejectsWrongNonce() {
        byte[] salt = new byte[]{1, 2, 3, 4};
        Realm.ScramCredentials creds =
                Realm.ScramCredentials.derive("secret", salt, 4096, "SHA-256");
        String serverFirst = SaslUtils.generateScramServerFirst(
                "expected", creds.salt, creds.iterations);
        String authChallenge = "n=alice,r=client," + serverFirst;
        String clientFinal = "c=biws,r=wrong,p=AAAA";
        assertNull(SaslUtils.verifyScramClientFinal(
                creds, authChallenge, clientFinal, "expected"));
    }

    @Test
    public void testVerifyScramClientFinalAcceptsValidProof() throws Exception {
        byte[] salt = new byte[]{9, 8, 7, 6};
        String password = "secret";
        Realm.ScramCredentials creds =
                Realm.ScramCredentials.derive(password, salt, 4096, "SHA-256");
        String clientNonce = "client";
        String serverNonce = clientNonce + SaslUtils.generateNonce(16);
        String serverFirst = SaslUtils.generateScramServerFirst(
                serverNonce, creds.salt, creds.iterations);
        String clientFirstBare = "n=alice,r=" + clientNonce;
        String authChallenge = clientFirstBare + "," + serverFirst;
        String clientFinalWithoutProof = "c=biws,r=" + serverNonce;
        String authMessage = authChallenge + "," + clientFinalWithoutProof;

        javax.crypto.SecretKeyFactory factory =
                javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        javax.crypto.spec.PBEKeySpec spec =
                new javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, 4096, 256);
        byte[] saltedPassword = factory.generateSecret(spec).getEncoded();
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(saltedPassword, "HmacSHA256"));
        byte[] clientKey = mac.doFinal("Client Key".getBytes(
                java.nio.charset.StandardCharsets.UTF_8));

        byte[] clientSignature = SaslUtils.hmacSHA256(creds.storedKey,
                authMessage.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] clientProof = new byte[clientSignature.length];
        for (int i = 0; i < clientProof.length; i++) {
            clientProof[i] = (byte) (clientKey[i] ^ clientSignature[i]);
        }
        String clientFinal = clientFinalWithoutProof + ",p="
                + java.util.Base64.getEncoder().encodeToString(clientProof);

        byte[] serverSignature = SaslUtils.verifyScramClientFinal(
                creds, authChallenge, clientFinal, serverNonce);
        assertNotNull(serverSignature);
        byte[] expected = SaslUtils.hmacSHA256(creds.serverKey,
                authMessage.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertArrayEquals(expected, serverSignature);
    }

    // -- OAUTHBEARER (RFC 7628) --

    @Test
    public void testOAuthBearerCredentialsParsing() {
        String credentials = "n,a=user@example.com,\u0001auth=Bearer my-token\u0001\u0001";
        Map<String, String> result = SaslUtils.parseOAuthBearerCredentials(credentials);
        assertEquals("user@example.com", result.get("user"));
        assertEquals("my-token", result.get("token"));
    }

    @Test
    public void testOAuthBearerNoUser() {
        String credentials = "n,,\u0001auth=Bearer token123\u0001\u0001";
        Map<String, String> result = SaslUtils.parseOAuthBearerCredentials(credentials);
        assertNull(result.get("user"));
        assertEquals("token123", result.get("token"));
    }

    @Test
    public void testOAuthBearerEmptyCredentials() {
        Map<String, String> result = SaslUtils.parseOAuthBearerCredentials("");
        assertNull(result.get("token"));
    }

    // -- Nonce generation --

    @Test
    public void testNonceGeneration() {
        String nonce1 = SaslUtils.generateNonce(16);
        String nonce2 = SaslUtils.generateNonce(16);
        assertNotNull(nonce1);
        assertNotNull(nonce2);
        assertEquals(32, nonce1.length()); // 16 bytes = 32 hex chars
        assertNotEquals(nonce1, nonce2);
    }

    // -- HMAC functions --

    @Test
    public void testHmacSHA256() {
        byte[] key = "key".getBytes();
        byte[] data = "data".getBytes();
        byte[] hmac = SaslUtils.hmacSHA256(key, data);
        assertNotNull(hmac);
        assertEquals(32, hmac.length); // SHA-256 = 32 bytes
    }

    // -- Base64 helpers --

    @Test
    public void testBase64RoundTrip() {
        String original = "Hello, World!";
        String encoded = SaslUtils.encodeBase64(original);
        String decoded = SaslUtils.decodeBase64ToString(encoded);
        assertEquals(original, decoded);
    }

    // -- PLAIN credentials (RFC 4616) --

    @Test
    public void testPlainCredentialsParsing() {
        byte[] creds = "\0alice\0secret".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String[] parsed = SaslUtils.parsePlainCredentials(creds);
        assertEquals(3, parsed.length);
        assertEquals("", parsed[0]); // authzid
        assertEquals("alice", parsed[1]); // authcid
        assertEquals("secret", parsed[2]); // password
    }

    @Test
    public void testPlainCredentialsWithAuthzid() {
        byte[] creds = "admin\0alice\0secret".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String[] parsed = SaslUtils.parsePlainCredentials(creds);
        assertEquals("admin", parsed[0]);
        assertEquals("alice", parsed[1]);
        assertEquals("secret", parsed[2]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPlainCredentialsMalformed() {
        SaslUtils.parsePlainCredentials("notnulls".getBytes());
    }

    // -- SHA-256 hash --

    @Test
    public void testSha256() {
        byte[] hash = SaslUtils.sha256("test".getBytes());
        assertNotNull(hash);
        assertEquals(32, hash.length);
    }

}
