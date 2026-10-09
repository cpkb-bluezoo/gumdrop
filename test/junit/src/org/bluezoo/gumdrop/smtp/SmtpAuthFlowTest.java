/*
 * SmtpAuthFlowTest.java
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

import org.bluezoo.gumdrop.auth.SynchronousRealm;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.auth.SaslUtils;

import static org.junit.Assert.*;

/**
 * Drives the SASL authentication exchanges of {@link SmtpProtocolHandler}
 * (PLAIN, LOGIN, CRAM-MD5, DIGEST-MD5, SCRAM-SHA-256, OAUTHBEARER) against
 * a stub realm over a secure stub endpoint.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SmtpAuthFlowTest {

    private static final String USER = "alice@example.com";
    private static final String PASSWORD = "wonderland";
    private static final int ITERATIONS = 4096;

    private SmtpListener listener;
    private SmtpProtocolHandler handler;
    private SMTPProtocolHandlerTest.StubEndpoint endpoint;

    @Before
    public void setUp() {
        listener = new SmtpListener();
        listener.realm(new StubRealm());
        handler = new SmtpProtocolHandler(listener, null);
        endpoint = new SMTPProtocolHandlerTest.StubEndpoint();
        endpoint.secure = true;
        handler.connected(endpoint);
        endpoint.sentData.clear();
    }

    private void send(String command) {
        byte[] data = (command + "\r\n").getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(data));
    }

    private String last() {
        List<String> responses = endpoint.getResponses();
        assertFalse("No responses", responses.isEmpty());
        return responses.get(responses.size() - 1);
    }

    private void expect(String command, String code) {
        endpoint.sentData.clear();
        send(command);
        String response = last();
        assertTrue(command + " -> " + response, response.startsWith(code));
    }

    private void ehlo() {
        expect("EHLO client.example.com", "250");
    }

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(
                text.getBytes(StandardCharsets.UTF_8));
    }

    private static String plain(String user, String password) {
        return b64("\0" + user + "\0" + password);
    }

    @Test
    public void testRepeatedFailuresLockTheClientOut() {
        listener.maxAuthFailures(2);
        ehlo();
        expect("AUTH PLAIN " + plain(USER, "wrong"), "535");
        expect("AUTH PLAIN " + plain(USER, "wrong"), "535");
        expect("AUTH PLAIN " + plain(USER, PASSWORD), "454");
    }

    @Test
    public void testLockoutNeedsConfiguring() {
        ehlo();
        for (int i = 0; i < 8; i++) {
            expect("AUTH PLAIN " + plain(USER, "wrong"), "535");
        }
        expect("AUTH PLAIN " + plain(USER, PASSWORD), "235");
    }

    @Test
    public void testSuccessClearsTheFailureCount() {
        listener.maxAuthFailures(2);
        ehlo();
        expect("AUTH PLAIN " + plain(USER, "wrong"), "535");
        expect("AUTH PLAIN " + plain(USER, PASSWORD), "235");
        assertFalse(listener.isAuthLockedOut(endpoint.getRemoteAddress()));
    }

    @Test
    public void testEhloAdvertisesAuth() {
        endpoint.sentData.clear();
        send("EHLO client.example.com");
        boolean sawAuth = false;
        List<String> responses = endpoint.getResponses();
        for (int i = 0; i < responses.size(); i++) {
            String line = responses.get(i);
            if (line.contains("AUTH ") && line.contains("PLAIN")) {
                sawAuth = true;
            }
        }
        assertTrue(responses.toString(), sawAuth);
    }

    @Test
    public void testAuthRequiresEhlo() {
        expect("AUTH PLAIN", "503");
    }

    @Test
    public void testAuthSyntaxAndUnsupported() {
        ehlo();
        expect("AUTH", "501");
        expect("AUTH BOGUS", "504");
        expect("AUTH GSSAPI", "504");
    }

    @Test
    public void testPlainInitialResponseSuccessThenAlreadyAuthenticated() {
        ehlo();
        expect("AUTH PLAIN " + plain(USER, PASSWORD), "235");
        expect("AUTH PLAIN " + plain(USER, PASSWORD), "503");
        expect("MAIL FROM:<alice@example.com>", "250");
    }

    @Test
    public void testAuthenticatedSenderMustMatch() {
        ehlo();
        expect("AUTH PLAIN " + plain(USER, PASSWORD), "235");
        expect("MAIL FROM:<mallory@example.com>", "550");
        expect("MAIL FROM:<alice@other.example>", "550");
    }

    @Test
    public void testPlainTwoStep() {
        ehlo();
        expect("AUTH PLAIN", "334");
        expect(plain(USER, PASSWORD), "235");
    }

    @Test
    public void testPlainBadPassword() {
        ehlo();
        expect("AUTH PLAIN " + plain(USER, "wrong"), "535");
    }

    @Test
    public void testPlainMalformed() {
        ehlo();
        expect("AUTH PLAIN " + b64("only-one-field"), "535");
        expect("AUTH PLAIN " + b64("\0user"), "535");
        expect("AUTH PLAIN " + b64("\0\0pass"), "535");
        expect("AUTH PLAIN " + b64("\0user\0"), "535");
        expect("AUTH PLAIN " + b64("a\0b\0c\0d"), "535");
        expect("AUTH PLAIN !!!notbase64", "535");
    }

    @Test
    public void testAbortExchange() {
        ehlo();
        expect("AUTH PLAIN", "334");
        expect("*", "501");
    }

    @Test
    public void testLoginTwoStep() {
        ehlo();
        expect("AUTH LOGIN", "334");
        expect(b64(USER), "334");
        expect(b64(PASSWORD), "235");
    }

    @Test
    public void testLoginInitialResponse() {
        ehlo();
        expect("AUTH LOGIN " + b64(USER), "334");
        expect(b64("wrong"), "535");
    }

    @Test
    public void testLoginEmptyFields() {
        ehlo();
        expect("AUTH LOGIN", "334");
        expect("=", "535");
        expect("AUTH LOGIN", "334");
        expect(b64(USER), "334");
        expect("=", "535");
    }

    @Test
    public void testCramMd5Success() {
        ehlo();
        endpoint.sentData.clear();
        send("AUTH CRAM-MD5");
        String line = last();
        assertTrue(line, line.startsWith("334 "));
        String challenge = new String(Base64.getDecoder().decode(line.substring(4)),
                StandardCharsets.US_ASCII);
        String digest = SaslUtils.computeCramMD5Response(PASSWORD, challenge);
        expect(b64(USER + " " + digest), "235");
    }

    @Test
    public void testCramMd5Failures() {
        ehlo();
        expect("AUTH CRAM-MD5", "334");
        expect(b64(USER + " 0123456789abcdef0123456789abcdef"), "535");
        expect("AUTH CRAM-MD5", "334");
        expect(b64("nospace"), "535");
    }

    @Test
    public void testDigestMd5Failures() {
        ehlo();
        expect("AUTH DIGEST-MD5", "334");
        expect(b64("realm=\"x\""), "535");
        expect("AUTH DIGEST-MD5", "334");
        expect(b64("username=\"" + USER + "\",realm=\"x\",nonce=\"zz\",nc=00000001,"
                + "cnonce=\"c\",digest-uri=\"smtp/x\",response=00000000000000000000000000000000,"
                + "qop=auth"), "535");
    }

    @Test
    public void testOAuthBearerSuccess() {
        ehlo();
        String ir = "n,a=" + USER + ",\u0001auth=Bearer goodtoken\u0001\u0001";
        expect("AUTH OAUTHBEARER " + b64(ir), "235");
    }

    @Test
    public void testOAuthBearerUserMismatch() {
        ehlo();
        String ir = "n,a=bob@example.com,\u0001auth=Bearer goodtoken\u0001\u0001";
        expect("AUTH OAUTHBEARER " + b64(ir), "535");
    }

    @Test
    public void testOAuthBearerInvalidTokenThenAck() {
        ehlo();
        String ir = "n,,\u0001auth=Bearer badtoken\u0001\u0001";
        expect("AUTH OAUTHBEARER " + b64(ir), "334");
        expect("AQ==", "535");
    }

    @Test
    public void testOAuthBearerTwoStep() {
        ehlo();
        expect("AUTH OAUTHBEARER", "334");
        String ir = "n,,\u0001auth=Bearer goodtoken\u0001\u0001";
        expect(b64(ir), "235");
    }

    @Test
    public void testOAuthBearerNoToken() {
        ehlo();
        expect("AUTH OAUTHBEARER " + b64("n,,\u0001\u0001"), "535");
    }

    @Test
    public void testScramSuccess() throws Exception {
        ehlo();
        String clientNonce = "clientnonce123";
        String bare = "n=" + USER + ",r=" + clientNonce;
        endpoint.sentData.clear();
        send("AUTH SCRAM-SHA-256 " + b64("n,," + bare));
        String line = last();
        assertTrue(line, line.startsWith("334 "));
        String serverFirst = new String(Base64.getDecoder().decode(line.substring(4)),
                StandardCharsets.UTF_8);
        String serverNonce = null;
        String salt = null;
        int iterations = 0;
        String[] parts = serverFirst.split(",");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].startsWith("r=")) {
                serverNonce = parts[i].substring(2);
            } else if (parts[i].startsWith("s=")) {
                salt = parts[i].substring(2);
            } else if (parts[i].startsWith("i=")) {
                iterations = Integer.parseInt(parts[i].substring(2));
            }
        }
        assertNotNull(serverNonce);
        String withoutProof = "c=biws,r=" + serverNonce;
        String authMessage = bare + "," + serverFirst + "," + withoutProof;
        byte[] proof = ScramClientHelper.proof(PASSWORD, Base64.getDecoder().decode(salt),
                iterations, authMessage);
        String clientFinal = withoutProof + ",p=" + Base64.getEncoder().encodeToString(proof);
        expect(b64(clientFinal), "235");
    }

    @Test
    public void testScramBadProof() {
        ehlo();
        String bare = "n=" + USER + ",r=abc";
        endpoint.sentData.clear();
        send("AUTH SCRAM-SHA-256 " + b64("n,," + bare));
        String line = last();
        assertTrue(line, line.startsWith("334 "));
        expect(b64("c=biws,r=abcwrong,p=AAAA"), "535");
    }

    @Test
    public void testScramUnknownUserAndMalformed() {
        ehlo();
        expect("AUTH SCRAM-SHA-256 " + b64("n,,n=nobody,r=abc"), "535");
        expect("AUTH SCRAM-SHA-256 " + b64("n,,garbage"), "535");
        expect("AUTH SCRAM-SHA-256", "334");
        expect(b64("n,,n=nobody,r=abc"), "535");
    }

    @Test
    public void testAuthWithoutTlsRejected() {
        SmtpListener plainListener = new SmtpListener();
        plainListener.realm(new StubRealm());
        SmtpProtocolHandler h = new SmtpProtocolHandler(plainListener, null);
        SMTPProtocolHandlerTest.StubEndpoint ep = new SMTPProtocolHandlerTest.StubEndpoint();
        h.connected(ep);
        h.receive(ByteBuffer.wrap("EHLO c.example.com\r\n".getBytes(StandardCharsets.US_ASCII)));
        ep.sentData.clear();
        h.receive(ByteBuffer.wrap("AUTH PLAIN\r\n".getBytes(StandardCharsets.US_ASCII)));
        List<String> responses = ep.getResponses();
        assertTrue(responses.toString(), responses.get(0).startsWith("538"));
    }

    @Test
    public void testHelpListsAuth() {
        endpoint.sentData.clear();
        send("HELP");
        boolean sawAuth = false;
        List<String> responses = endpoint.getResponses();
        for (int i = 0; i < responses.size(); i++) {
            if (responses.get(i).contains("AUTH")) {
                sawAuth = true;
            }
        }
        assertTrue(sawAuth);
    }

    /** Client side of RFC 5802 proof computation. */
    private static final class ScramClientHelper {
        static byte[] proof(String password, byte[] salt, int iterations,
                String authMessage) throws Exception {
            javax.crypto.SecretKeyFactory factory =
                    javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                    password.toCharArray(), salt, iterations, 256);
            byte[] salted = factory.generateSecret(spec).getEncoded();
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(salted, "HmacSHA256"));
            byte[] clientKey = mac.doFinal("Client Key".getBytes(StandardCharsets.UTF_8));
            byte[] storedKey = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(clientKey);
            byte[] signature = SaslUtils.hmacSHA256(storedKey,
                    authMessage.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[signature.length];
            for (int i = 0; i < result.length; i++) {
                result[i] = (byte) (clientKey[i] ^ signature[i]);
            }
            return result;
        }
    }

    /** Realm accepting one user with fixed credentials and one token. */
    private static final class StubRealm implements SynchronousRealm {
        private final Set<SaslMechanism> supported = Collections.unmodifiableSet(
                EnumSet.of(SaslMechanism.PLAIN, SaslMechanism.LOGIN,
                        SaslMechanism.CRAM_MD5, SaslMechanism.SCRAM_SHA_256,
                        SaslMechanism.OAUTHBEARER));


        @Override
        public Set<SaslMechanism> getSupportedSASLMechanisms() {
            return supported;
        }

        @Override
        public boolean passwordMatch(String username, String password) {
            return USER.equals(username) && PASSWORD.equals(password);
        }

        @Override
        public String getDigestHA1(String username, String realmName) {
            if (USER.equals(username)) {
                return SaslUtils.computeDigestHA1(username, realmName, PASSWORD);
            }
            return null;
        }


        @Override
        public boolean isUserInRole(String username, String role) {
            return false;
        }

        @Override
        public String getCramMD5Response(String username, String challenge) {
            if (USER.equals(username)) {
                return SaslUtils.computeCramMD5Response(PASSWORD, challenge);
            }
            return null;
        }

        @Override
        public ScramCredentials getScramCredentials(String username) {
            if (!USER.equals(username)) {
                return null;
            }
            byte[] salt = new byte[16];
            for (int i = 0; i < salt.length; i++) {
                salt[i] = (byte) (i + 1);
            }
            return ScramCredentials.derive(PASSWORD, salt, ITERATIONS, "SHA-256");
        }

        @Override
        public TokenValidationResult validateBearerToken(String token) {
            if ("goodtoken".equals(token)) {
                return TokenValidationResult.success(USER, new String[0], "Bearer");
            }
            return TokenValidationResult.failure();
        }
    }
}
