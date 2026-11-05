/*
 * ScramSha256ClientTest.java
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The SCRAM-SHA-256 client mechanism (RFC 5802, RFC 7677).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ScramSha256ClientTest {

    // RFC 7677 section 3
    private static final String NONCE = "rOprNGfwEbeRWgbNEkqO";
    private static final String SERVER_FIRST =
            "r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096";

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    @Test
    public void testRfc7677Exchange() throws IOException {
        SaslClientMechanism m = new ScramSha256Client("user", "pencil", NONCE);
        assertEquals("SCRAM-SHA-256", m.getMechanismName());
        assertTrue(m.hasInitialResponse());
        assertEquals("n,,n=user,r=rOprNGfwEbeRWgbNEkqO", text(m.evaluateChallenge(new byte[0])));
        assertFalse(m.isComplete());
        assertEquals("c=biws,r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,"
                + "p=dHzbZapWIk4jUhN+Ute9ytag9zjfMHgsqmmiz7AndVQ=",
                text(m.evaluateChallenge(bytes(SERVER_FIRST))));
        assertFalse(m.isComplete());
        m.evaluateChallenge(bytes("v=6rriTRBi23WpRR/wtup+mMhUZUn/dB5nLTJRsjl95G4="));
        assertTrue(m.isComplete());
    }

    @Test
    public void testCreatedByName() {
        assertNotNull(SaslUtils.createClient("scram-sha-256", "user", "pencil", "host"));
        assertEquals(null, SaslUtils.createClient("SCRAM-SHA-256", null, "pencil", "host"));
    }

    @Test
    public void testNameIsEscaped() throws IOException {
        SaslClientMechanism m = new ScramSha256Client("a=b,c", "x", NONCE);
        assertEquals("n,,n=a=3Db=2Cc,r=" + NONCE, text(m.evaluateChallenge(new byte[0])));
    }

    @Test
    public void testServerSignatureMustMatch() throws IOException {
        SaslClientMechanism m = new ScramSha256Client("user", "pencil", NONCE);
        m.evaluateChallenge(new byte[0]);
        m.evaluateChallenge(bytes(SERVER_FIRST));
        try {
            m.evaluateChallenge(bytes("v=" + Base64.getEncoder().encodeToString(new byte[32])));
            fail("expected a mismatch");
        } catch (IOException expected) {
            assertFalse(m.isComplete());
        }
    }

    @Test
    public void testWrongPasswordFailsTheServerCheck() throws IOException {
        SaslClientMechanism m = new ScramSha256Client("user", "wrong", NONCE);
        m.evaluateChallenge(new byte[0]);
        m.evaluateChallenge(bytes(SERVER_FIRST));
        try {
            m.evaluateChallenge(bytes("v=6rriTRBi23WpRR/wtup+mMhUZUn/dB5nLTJRsjl95G4="));
            fail("a different password cannot produce the same server signature");
        } catch (IOException expected) {
            // good
        }
    }

    @Test
    public void testBadServerFirstMessages() throws IOException {
        String[] bad = {
            "r=other,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096",            // nonce does not extend ours
            "r=" + NONCE + ",s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096",    // adds nothing
            "r=" + NONCE + "x,s=!!!,i=4096",                         // salt is not base64
            "r=" + NONCE + "x,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=0",
            "r=" + NONCE + "x,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=notnumber",
            "r=" + NONCE + "x,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=2000000", // too costly
            "r=" + NONCE + "x,s=W22ZaJ0SNY7soEsUEjb6gQ==",
            "e=unknown-user",
        };
        for (int i = 0; i < bad.length; i++) {
            SaslClientMechanism m = new ScramSha256Client("user", "pencil", NONCE);
            m.evaluateChallenge(new byte[0]);
            try {
                m.evaluateChallenge(bytes(bad[i]));
                fail("accepted: " + bad[i]);
            } catch (IOException expected) {
                // good
            }
        }
    }

    @Test
    public void testServerErrorInFinal() throws IOException {
        SaslClientMechanism m = new ScramSha256Client("user", "pencil", NONCE);
        m.evaluateChallenge(new byte[0]);
        m.evaluateChallenge(bytes(SERVER_FIRST));
        try {
            m.evaluateChallenge(bytes("e=invalid-proof"));
            fail("expected an error");
        } catch (IOException expected) {
            assertFalse(m.isComplete());
        }
    }

    @Test
    public void testAgainstTheServerHelpers() throws IOException {
        // the client's proof is accepted by SaslUtils.verifyScramClientFinal, and
        // the signature that returns is accepted by the client
        byte[] salt = bytes("0123456789abcdef");
        Realm.ScramCredentials creds = Realm.ScramCredentials.derive("secret", salt, 4096, "SHA-256");
        SaslClientMechanism m = SaslUtils.createClient("SCRAM-SHA-256", "alice", "secret", "host");
        String clientFirst = text(m.evaluateChallenge(new byte[0]));
        String bare = clientFirst.substring(3);
        String clientNonce = bare.substring(bare.indexOf("r=") + 2);
        String serverNonce = clientNonce + SaslUtils.generateNonce(16);
        String serverFirst = SaslUtils.generateScramServerFirst(serverNonce, creds.salt, creds.iterations);
        String clientFinal = text(m.evaluateChallenge(bytes(serverFirst)));
        byte[] signature = SaslUtils.verifyScramClientFinal(creds, bare + "," + serverFirst, clientFinal,
                serverNonce);
        assertNotNull("the server rejected the client's proof", signature);
        m.evaluateChallenge(bytes("v=" + Base64.getEncoder().encodeToString(signature)));
        assertTrue(m.isComplete());
    }

    @Test
    public void testNothingAfterCompletion() throws IOException {
        SaslClientMechanism m = new ScramSha256Client("user", "pencil", NONCE);
        m.evaluateChallenge(new byte[0]);
        m.evaluateChallenge(bytes(SERVER_FIRST));
        m.evaluateChallenge(bytes("v=6rriTRBi23WpRR/wtup+mMhUZUn/dB5nLTJRsjl95G4="));
        try {
            m.evaluateChallenge(new byte[0]);
            fail("expected an error");
        } catch (IOException expected) {
            // good
        }
    }
}
