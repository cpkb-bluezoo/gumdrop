/*
 * SaslUtilsScramExternalTest.java
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.util.ByteArrays;
import org.junit.Test;

/**
 * Tests for the SCRAM verification, EXTERNAL authentication, client
 * mechanism and mechanism enumeration code in {@link SaslUtils} and
 * {@link SaslMechanism}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SaslUtilsScramExternalTest {

    private static final String CERT_B64 =
            "MIIBPzCB5qADAgECAgkAq4yq548MdtwwCgYIKoZIzj0EAwMwEzERMA8GA1UEAxMI"
            + "Y29yZXRlc3QwIBcNMjYxMDAyMDcwNDU2WhgPMjEyNjA5MDgwNzA0NTZaMBMxETAP"
            + "BgNVBAMTCGNvcmV0ZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEiWdYBF51"
            + "K/pRe6OXwsHiOgFphDQQZWJ8HAtqoXpJZwm7ePY9YwbQ+kv1oo+GkXZIPj4qD3Nh"
            + "0BeVkgudsuNJ2aMhMB8wHQYDVR0OBBYEFIFSgLfCliRJ2QmLooQ7Guf3R+vCMAoG"
            + "CCqGSM49BAMDA0gAMEUCIQD2dSmZ+j1JvsS7/BlxdcTs/ftxO9Nj2jVwi82Ij7gz"
            + "qwIga/njuOmiHUkCMYvZRUVnnzqLLehdKUsYCGfEU7ILztU=";

    private static X509Certificate loadCert() throws Exception {
        byte[] der = Base64.getMimeDecoder().decode(CERT_B64);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
    }

    private static Endpoint endpoint(final boolean secure, final Certificate[] certs) {
        final SecurityInfo info = (SecurityInfo) Proxy.newProxyInstance(
                SaslUtilsScramExternalTest.class.getClassLoader(),
                new Class<?>[] {SecurityInfo.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("getPeerCertificates".equals(method.getName())) {
                            return certs;
                        }
                        return null;
                    }
                });
        return (Endpoint) Proxy.newProxyInstance(
                SaslUtilsScramExternalTest.class.getClassLoader(),
                new Class<?>[] {Endpoint.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("isSecure".equals(method.getName())) {
                            return Boolean.valueOf(secure);
                        }
                        if ("getSecurityInfo".equals(method.getName())) {
                            return info;
                        }
                        return null;
                    }
                });
    }

    /** Realm that maps any certificate to a fixed user. */
    private static class CertRealm extends BasicRealm {
        private final Realm.CertificateAuthenticationResult result;
        private final boolean authorize;

        CertRealm(Realm.CertificateAuthenticationResult result, boolean authorize) {
            this.result = result;
            this.authorize = authorize;
        }

        @Override
        public Realm.CertificateAuthenticationResult authenticateCertificate(X509Certificate c) {
            return result;
        }

        @Override
        public boolean authorizeAs(String authenticated, String requested) {
            return authorize;
        }
    }

    @Test
    public void externalAuthenticationPaths() throws Exception {
        X509Certificate cert = loadCert();
        Endpoint secure = endpoint(true, new Certificate[] {cert});
        Realm.CertificateAuthenticationResult ok = Realm.CertificateAuthenticationResult.success("carol");

        assertFalse(external(secure, null, null).valid);
        assertFalse(external(endpoint(false, null), new CertRealm(ok, true), null).valid);
        assertFalse(external(endpoint(true, null), new CertRealm(ok, true), null).valid);
        assertFalse(external(endpoint(true, new Certificate[0]),
                new CertRealm(ok, true), null).valid);
        assertFalse(external(endpoint(true, new Certificate[] {null}),
                new CertRealm(ok, true), null).valid);
        assertFalse(external(secure, new CertRealm(null, true), null).valid);
        assertFalse(external(secure,
                new CertRealm(Realm.CertificateAuthenticationResult.failure(), true), null).valid);

        Realm.CertificateAuthenticationResult r = external(secure, new CertRealm(ok, true), null);
        assertTrue(r.valid);
        assertEquals("carol", r.username);
        r = external(secure, new CertRealm(ok, true), "");
        assertEquals("carol", r.username);
        r = external(secure, new CertRealm(ok, true), "dave");
        assertTrue(r.valid);
        assertEquals("dave", r.username);
        r = external(secure, new CertRealm(ok, false), "dave");
        assertFalse(r.valid);
    }

    private static Realm.CertificateAuthenticationResult external(final Endpoint endpoint,
            final Realm realm, final String authzid) {
        return CapturedCallback.await(new CapturedCallback.Call<Realm.CertificateAuthenticationResult>() {
            @Override
            public void invoke(RealmCallback<Realm.CertificateAuthenticationResult> cb) {
                SaslUtils.authenticateExternal(endpoint, realm, authzid, cb);
            }
        });
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return f.generateSecret(spec).getEncoded();
    }

    private static String scramFinal(String password, byte[] salt, int iterations,
            String authChallenge, String nonce, boolean tamper) throws Exception {
        byte[] salted = pbkdf2(password, salt, iterations);
        byte[] clientKey = SaslUtils.hmacSHA256(salted, "Client Key".getBytes(StandardCharsets.UTF_8));
        byte[] storedKey = SaslUtils.sha256(clientKey);
        String withoutProof = "c=biws,r=" + nonce;
        String authMessage = authChallenge + "," + withoutProof;
        byte[] sig = SaslUtils.hmacSHA256(storedKey, authMessage.getBytes(StandardCharsets.UTF_8));
        byte[] proof = new byte[sig.length];
        for (int i = 0; i < proof.length; i++) {
            proof[i] = (byte) (clientKey[i] ^ sig[i]);
        }
        if (tamper) {
            proof[0] ^= 1;
        }
        return withoutProof + ",p=" + Base64.getEncoder().encodeToString(proof);
    }

    @Test
    public void scramClientFinalVerification() throws Exception {
        byte[] salt = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
        Realm.ScramCredentials creds = Realm.ScramCredentials.derive("pencil", salt, 64, "SHA-256");
        String challenge = "n=u,r=cnonce,r=cnoncesnonce,s=AQID,i=64";
        String nonce = "cnoncesnonce";

        String good = scramFinal("pencil", salt, 64, challenge, nonce, false);
        byte[] serverSig = SaslUtils.verifyScramClientFinal(creds, challenge, good, nonce);
        assertNotNull(serverSig);
        byte[] expected = SaslUtils.hmacSHA256(creds.serverKey,
                (challenge + ",c=biws,r=" + nonce).getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(expected, serverSig);

        String bad = scramFinal("pencil", salt, 64, challenge, nonce, true);
        assertNull(SaslUtils.verifyScramClientFinal(creds, challenge, bad, nonce));
        String wrongPw = scramFinal("other", salt, 64, challenge, nonce, false);
        assertNull(SaslUtils.verifyScramClientFinal(creds, challenge, wrongPw, nonce));
        assertNull(SaslUtils.verifyScramClientFinal(creds, challenge, good, "different"));
    }

    @Test
    public void scramClientFinalRejectsMalformedInput() {
        Realm.ScramCredentials creds = new Realm.ScramCredentials("AA==", 1, new byte[32], new byte[32]);
        assertNull(SaslUtils.verifyScramClientFinal(null, "c", "r=n,p=AA==", "n"));
        assertNull(SaslUtils.verifyScramClientFinal(creds, null, "r=n,p=AA==", "n"));
        assertNull(SaslUtils.verifyScramClientFinal(creds, "c", null, "n"));
        assertNull(SaslUtils.verifyScramClientFinal(creds, "c", "r=n,p=AA==", null));
        assertNull(SaslUtils.verifyScramClientFinal(creds, "c", "c=biws,p=AA==", "n"));
        assertNull(SaslUtils.verifyScramClientFinal(creds, "c", "c=biws,r=n", "n"));
        assertNull(SaslUtils.verifyScramClientFinal(creds, "c", "c=biws,r=n,p=!!!", "n"));
        assertNull(SaslUtils.verifyScramClientFinal(creds, "c", "c=biws,r=n,p=AA==", "n"));
    }

    @Test
    public void plainClientWithNullPassword() throws Exception {
        SaslClientMechanism c = SaslUtils.createClient("PLAIN", "u", null, "h");
        assertTrue(c.hasInitialResponse());
        byte[] r = c.evaluateChallenge(new byte[0]);
        assertArrayEquals(new byte[] {0, 'u', 0}, r);
        assertTrue(c.isComplete());
    }

    @Test
    public void createClientGssapiNeedsHost() {
        assertNull(SaslUtils.createClient("GSSAPI", "u", "p", null, new javax.security.auth.Subject()));
    }

    @Test
    public void mechanismEnumeration() {
        SaslMechanism[] all = SaslMechanism.values();
        for (int i = 0; i < all.length; i++) {
            SaslMechanism m = all[i];
            assertEquals(m, SaslMechanism.fromName(m.getMechanismName().toLowerCase()));
            assertEquals(m.getMechanismName(), m.toString());
            if (m == SaslMechanism.EXTERNAL) {
                assertTrue(m.requiresTLS());
            }
        }
        assertNull(SaslMechanism.fromName(null));
        assertNull(SaslMechanism.fromName("NOPE"));
        Set<SaslMechanism> basic = new BasicRealm().getSupportedSASLMechanisms();
        assertTrue(basic.contains(SaslMechanism.PLAIN));
    }
}
