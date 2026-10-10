/*
 * IMAPSessionSaslTest.java
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

package org.bluezoo.gumdrop.imap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.Test;

import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;

import static org.junit.Assert.*;

/**
 * Re-runs the {@link IMAPSessionCoverageTest} scenarios against a realm that
 * advertises every SASL mechanism, then drives the AUTHENTICATE state
 * machine for SCRAM-SHA-256, OAUTHBEARER, EXTERNAL,
 * GSSAPI and LOGIN through their success and failure branches.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IMAPSessionSaslTest extends IMAPSessionCoverageTest {

    private static final int SCRAM_ITERATIONS = 4096;
    private static final byte[] SCRAM_SALT = "0123456789abcdef".getBytes(
            StandardCharsets.US_ASCII);

    /** Realm with a deterministic answer for every challenge-response mechanism. */
    private static final class SaslRealm extends AcceptingRealm {
        private static final Set<SaslMechanism> ALL =
                Collections.unmodifiableSet(EnumSet.allOf(SaslMechanism.class));

        SaslRealm() {
            super("editor", "editor", false);
        }

        @Override
        public Set<SaslMechanism> getSupportedSASLMechanisms() {
            return ALL;
        }

        @Override
        public Realm.ScramCredentials getScramCredentials(String username) {
            if ("editor".equals(username)) {
                return Realm.ScramCredentials.derive("editor", SCRAM_SALT,
                        SCRAM_ITERATIONS, "SHA-256");
            }
            if ("boom".equals(username)) {
                throw new IllegalStateException("credential store down");
            }
            return null;
        }

        @Override
        public Realm.TokenValidationResult validateBearerToken(String token) {
            if ("good".equals(token)) {
                return Realm.TokenValidationResult.success("editor",
                        new String[0], "Bearer");
            }
            if ("other".equals(token)) {
                return Realm.TokenValidationResult.success("someone",
                        new String[0], "Bearer");
            }
            if ("expired".equals(token)) {
                return Realm.TokenValidationResult.failure();
            }
            return null;
        }
    }

    @Override
    protected void configureListener(ImapListener l) {
        l.realm(new SaslRealm());
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(
                s.getBytes(StandardCharsets.UTF_8));
    }

    /** Sends AUTHENTICATE and returns the tag used. */
    private String startAuth(String args) throws Exception {
        tagCounter++;
        String tag = "t" + tagCounter;
        endpoint.clearResponses();
        send(tag + " AUTHENTICATE " + args + "\r\n");
        return tag;
    }

    private String finish(String tag) throws Exception {
        return endpoint.awaitLineStartingWith(tag + " ");
    }

    private String respond(String tag, String data) throws Exception {
        endpoint.clearResponses();
        send(data + "\r\n");
        return finish(tag);
    }

    private String challenge() throws Exception {
        String line = endpoint.awaitLineStartingWith("+");
        return new String(Base64.getDecoder().decode(
                line.length() > 2 ? line.substring(2) : ""),
                StandardCharsets.UTF_8);
    }

    @Test(timeout = 30000)
    public void testAuthenticateGeneralFailures() throws Exception {
        no("AUTHENTICATE BOGUS");
        no("AUTHENTICATE PLAIN " + b64("\u0000editor\u0000editor"));
        endpoint.setSecure(true);
        String tag = startAuth("PLAIN");
        endpoint.awaitLineStartingWith("+");
        assertTrue(respond(tag, "*").contains(" BAD"));
        tag = startAuth("PLAIN");
        endpoint.awaitLineStartingWith("+");
        assertTrue(respond(tag, "!!!not base64").contains(" NO"));
        tag = startAuth("PLAIN " + b64("\u0000editor\u0000wrong"));
        assertTrue(finish(tag).contains(" NO"));
        tag = startAuth("LOGIN " + b64("editor"));
        challenge();
        assertTrue(respond(tag, "!!!").contains(" NO"));
        tag = startAuth("LOGIN !!!");
        assertTrue(finish(tag).contains(" NO"));
        tag = startAuth("LOGIN");
        challenge();
        endpoint.clearResponses();
        send(b64("editor") + "\r\n");
        challenge();
        assertTrue(respond(tag, b64("wrong")).contains(" NO"));
        tag = startAuth("LOGIN");
        challenge();
        endpoint.clearResponses();
        send("!!!\r\n");
        assertTrue(finish(tag).contains(" NO"));
    }

    @Test(timeout = 30000)
    public void testMd5MechanismsRejected() throws Exception {
        // Removed mechanisms are unknown names now, with or without a
        // initial response, and over TLS or not.
        no("AUTHENTICATE CRAM-MD5");
        no("AUTHENTICATE DIGEST-MD5");
        no("AUTHENTICATE DIGEST-MD5 " + b64("x"));
        endpoint.setSecure(true);
        String tag = startAuth("CRAM-MD5");
        String line = finish(tag);
        assertTrue(line, line.contains(" NO"));
        assertTrue(line, line.contains("Unsupported authentication mechanism"));
        tag = startAuth("DIGEST-MD5");
        line = finish(tag);
        assertTrue(line, line.contains(" NO"));
    }

    @Test(timeout = 30000)
    public void testCapabilityNeverListsMd5Mechanisms() throws Exception {
        endpoint.setSecure(true);
        endpoint.clearResponses();
        send("cap1 CAPABILITY\r\n");
        endpoint.awaitLineStartingWith("cap1 ");
        String all = endpoint.getResponses().toString();
        assertTrue(all, all.contains("AUTH="));
        assertFalse(all, all.contains("CRAM-MD5"));
        assertFalse(all, all.contains("DIGEST-MD5"));
    }

    @Test(timeout = 30000)
    public void testScramFailures() throws Exception {
        String tag = startAuth("SCRAM-SHA-256 " + b64("p=tls-unique,,n=editor,r=abc"));
        assertTrue(finish(tag).contains(" NO"));
        tag = startAuth("SCRAM-SHA-256 " + b64("n,,r=abc"));
        assertTrue(finish(tag).contains(" NO"));
        tag = startAuth("SCRAM-SHA-256 " + b64("n,,n=editor"));
        assertTrue(finish(tag).contains(" NO"));
        tag = startAuth("SCRAM-SHA-256 " + b64("n,,n=ghost,r=abc"));
        assertTrue(finish(tag).contains(" NO"));
        tag = startAuth("SCRAM-SHA-256 " + b64("n,,n=boom,r=abc"));
        assertTrue(finish(tag).contains(" NO"));
        tag = startAuth("SCRAM-SHA-256 !!!");
        assertTrue(finish(tag).contains(" NO"));
        tag = startAuth("SCRAM-SHA-256");
        endpoint.awaitLineStartingWith("+");
        assertTrue(respond(tag, b64("n,,n=ghost,r=abc")).contains(" NO"));

        tag = startAuth("SCRAM-SHA-256 " + b64("n,,n=editor,r=abc"));
        String serverFirst = challenge();
        assertTrue(serverFirst, serverFirst.startsWith("r=abc"));
        String nonce = serverFirst.substring(2, serverFirst.indexOf(','));
        String bad = "c=biws,r=" + nonce + ",p=" + Base64.getEncoder()
                .encodeToString(new byte[32]);
        assertTrue(respond(tag, b64(bad)).contains(" NO"));

        tag = startAuth("SCRAM-SHA-256 " + b64("n,,n=editor,r=abc"));
        challenge();
        assertTrue(respond(tag, "!!!").contains(" NO"));
    }

    @Test(timeout = 30000)
    public void testScramSuccess() throws Exception {
        String clientFirstBare = "n=editor,r=abc";
        String tag = startAuth("SCRAM-SHA-256 " + b64("n,," + clientFirstBare));
        String serverFirst = challenge();
        String nonce = serverFirst.substring(2, serverFirst.indexOf(','));
        String withoutProof = "c=biws,r=" + nonce;
        String authMessage = clientFirstBare + "," + serverFirst + ","
                + withoutProof;
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] salted = f.generateSecret(new PBEKeySpec("editor".toCharArray(),
                SCRAM_SALT, SCRAM_ITERATIONS, 256)).getEncoded();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salted, "HmacSHA256"));
        byte[] clientKey = mac.doFinal("Client Key".getBytes(StandardCharsets.UTF_8));
        byte[] storedKey = MessageDigest.getInstance("SHA-256").digest(clientKey);
        mac.init(new SecretKeySpec(storedKey, "HmacSHA256"));
        byte[] sig = mac.doFinal(authMessage.getBytes(StandardCharsets.UTF_8));
        byte[] proof = new byte[sig.length];
        for (int i = 0; i < proof.length; i++) {
            proof[i] = (byte) (clientKey[i] ^ sig[i]);
        }
        String clientFinal = withoutProof + ",p="
                + Base64.getEncoder().encodeToString(proof);
        String done = respond(tag, b64(clientFinal));
        assertTrue(done, done.contains(" OK"));
        assertNotNull(endpoint.findLineStartingWith("+"));
    }

    @Test(timeout = 30000)
    public void testOAuthBearer() throws Exception {
        endpoint.setSecure(true);
        String tag = startAuth("OAUTHBEARER " + b64("n,a=editor,\u0001auth=Bearer good\u0001\u0001"));
        assertTrue(finish(tag).contains(" OK"));
    }

    @Test(timeout = 30000)
    public void testOAuthBearerFailures() throws Exception {
        endpoint.setSecure(true);
        String tag = startAuth("OAUTHBEARER " + b64("n,a=editor,\u0001auth=Bearer other\u0001\u0001"));
        assertTrue(finish(tag).contains(" NO"));
        tag = startAuth("OAUTHBEARER " + b64("n,a=editor,\u0001auth=Bearer expired\u0001\u0001"));
        assertTrue(finish(tag).contains(" NO"));
        tag = startAuth("OAUTHBEARER " + b64("n,a=editor,\u0001auth=Bearer unknown\u0001\u0001"));
        assertTrue(finish(tag).contains(" NO"));
        tag = startAuth("OAUTHBEARER " + b64("n,,\u0001\u0001"));
        assertTrue(finish(tag).contains(" NO"));
        tag = startAuth("OAUTHBEARER !!!");
        assertTrue(finish(tag).contains(" NO"));
        tag = startAuth("OAUTHBEARER");
        endpoint.awaitLineStartingWith("+");
        assertTrue(respond(tag, b64("n,a=editor,\u0001auth=Bearer good\u0001\u0001"))
                .contains(" OK"));
    }

    @Test(timeout = 30000)
    public void testExternalAndGssapi() throws Exception {
        endpoint.setSecure(true);
        String tag = startAuth("EXTERNAL");
        assertTrue(finish(tag).contains(" NO"));
        tag = startAuth("EXTERNAL " + b64("editor"));
        assertTrue(finish(tag).contains(" NO"));
        tag = startAuth("EXTERNAL =");
        assertTrue(finish(tag).contains(" NO"));
        tag = startAuth("EXTERNAL !!!");
        assertTrue(finish(tag).contains(" BAD"));
        tag = startAuth("GSSAPI");
        assertTrue(finish(tag).contains(" NO"));
    }
}
