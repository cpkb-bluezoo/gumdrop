/*
 * AuthEdgeCasesTest.java
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

import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.security.Principal;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.security.auth.Subject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Edge-case unit tests for {@link SaslUtils} parsing and verification and for
 * {@link BasicRealm} certificate authentication when the certificate cannot
 * be encoded.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AuthEdgeCasesTest {

    /** Certificate whose encoding always fails. */
    private static final class UnencodableCertificate extends X509Certificate {
        private static final long serialVersionUID = 1L;

        @Override
        public byte[] getEncoded() throws CertificateEncodingException {
            throw new CertificateEncodingException("no encoding");
        }

        @Override
        public void verify(PublicKey key) throws InvalidKeyException, SignatureException {
        }

        @Override
        public void verify(PublicKey key, String sigProvider)
                throws InvalidKeyException, SignatureException {
        }

        @Override
        public String toString() {
            return "unencodable";
        }

        @Override
        public PublicKey getPublicKey() {
            return null;
        }

        @Override
        public void checkValidity() {
        }

        @Override
        public void checkValidity(Date date) {
        }

        @Override
        public int getVersion() {
            return 3;
        }

        @Override
        public BigInteger getSerialNumber() {
            return BigInteger.ONE;
        }

        @Override
        public Principal getIssuerDN() {
            return null;
        }

        @Override
        public Principal getSubjectDN() {
            return null;
        }

        @Override
        public Date getNotBefore() {
            return null;
        }

        @Override
        public Date getNotAfter() {
            return null;
        }

        @Override
        public byte[] getTBSCertificate() {
            return new byte[0];
        }

        @Override
        public byte[] getSignature() {
            return new byte[0];
        }

        @Override
        public String getSigAlgName() {
            return "none";
        }

        @Override
        public String getSigAlgOID() {
            return "0";
        }

        @Override
        public byte[] getSigAlgParams() {
            return null;
        }

        @Override
        public boolean[] getIssuerUniqueID() {
            return null;
        }

        @Override
        public boolean[] getSubjectUniqueID() {
            return null;
        }

        @Override
        public boolean[] getKeyUsage() {
            return null;
        }

        @Override
        public int getBasicConstraints() {
            return -1;
        }

        @Override
        public boolean hasUnsupportedCriticalExtension() {
            return false;
        }

        @Override
        public java.util.Set<String> getCriticalExtensionOIDs() {
            return null;
        }

        @Override
        public java.util.Set<String> getNonCriticalExtensionOIDs() {
            return null;
        }

        @Override
        public byte[] getExtensionValue(String oid) {
            return null;
        }
    }

    @Test
    public void unencodableCertificateFailsAuthenticationWithoutException() {
        BasicRealm realm = new BasicRealm();
        realm.certFingerprints.put("aa:bb", "carol");
        Realm.CertificateAuthenticationResult result =
                realm.authenticateCertificate(new UnencodableCertificate());
        assertFalse(result.valid);
        assertNull(BasicRealm.computeSHA256Fingerprint(new UnencodableCertificate()));
    }

    @Test
    public void scramClientFinalWithProofFirstIsRejected() {
        Realm.ScramCredentials creds =
                new Realm.ScramCredentials("AA==", 1, new byte[32], new byte[32]);
        assertNull(SaslUtils.verifyScramClientFinal(creds, "c", "p=AA==,r=n", "n"));
    }

    @Test
    public void digestParametersEndingInBackslashKeepIt() {
        Map<String, String> params = SaslUtils.parseDigestParams("user=\"a\\");
        assertEquals("a\\", params.get("user"));
        Map<String, String> escaped = SaslUtils.parseDigestParams("user=\"a\\\"b\",nc=1");
        assertEquals("a\"b", escaped.get("user"));
        assertEquals("1", escaped.get("nc"));
    }

    @Test
    public void digestVerificationRejectsEachMissingParameter() {
        String[] names = {"nonce", "nc", "cnonce", "qop", "digest-uri", "response"};
        for (int skip = 0; skip < names.length; skip++) {
            Map<String, String> p = new HashMap<String, String>();
            for (int i = 0; i < names.length; i++) {
                if (i != skip) {
                    p.put(names[i], "n");
                }
            }
            assertNull(names[skip], SaslUtils.verifyDigestMD5ClientResponse("00", "n", p));
        }
    }

    @Test
    public void oauthBearerWithoutTrailingSeparatorsStillYieldsToken() {
        Map<String, String> parsed = SaslUtils.parseOAuthBearerCredentials(
                "n,a=user@example.com,\u0001auth=Bearer tok");
        assertEquals("user@example.com", parsed.get("user"));
        assertEquals("tok", parsed.get("token"));
        Map<String, String> none = SaslUtils.parseOAuthBearerCredentials("n,,");
        assertTrue(none.isEmpty());
        Map<String, String> other = SaslUtils.parseOAuthBearerCredentials(
                "n,,\u0001host=example\u0001auth=Bearer t2\u0001\u0001");
        assertEquals("t2", other.get("token"));
        assertNull(other.get("user"));
    }

    @Test
    public void gssapiClientNeedsSubjectAndHost() {
        assertNull(SaslUtils.createClient("GSSAPI", "u", null, "host", null));
        assertNull(SaslUtils.createClient("GSSAPI", "u", null, null, new Subject()));
        assertNull(SaslUtils.createClient("NOPE", "u", null, "host", null));
        assertNull(SaslUtils.createClient(null, "u", null, "host", null));
    }
}
