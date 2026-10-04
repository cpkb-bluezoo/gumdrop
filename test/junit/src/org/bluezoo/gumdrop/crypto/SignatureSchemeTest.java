/*
 * SignatureSchemeTest.java
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

package org.bluezoo.gumdrop.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.nio.charset.StandardCharsets;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Sign/verify round-trips for every {@link SignatureScheme} this engine
 * supports, plus a tamper-detection check per scheme.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SignatureSchemeTest {

    private static final byte[] MESSAGE = "gumdrop CertificateVerify test content"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TAMPERED = "gumdrop CertificateVerify TAMPERED content"
            .getBytes(StandardCharsets.US_ASCII);

    @Test
    public void rsaSchemesRoundTrip() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        checkRoundTrip(SignatureScheme.RSA_PKCS1_SHA256, kp);
        checkRoundTrip(SignatureScheme.RSA_PKCS1_SHA384, kp);
        checkRoundTrip(SignatureScheme.RSA_PKCS1_SHA512, kp);
        checkRoundTrip(SignatureScheme.RSA_PSS_RSAE_SHA256, kp);
        checkRoundTrip(SignatureScheme.RSA_PSS_RSAE_SHA384, kp);
        checkRoundTrip(SignatureScheme.RSA_PSS_RSAE_SHA512, kp);
    }

    @Test
    public void ecdsaP256RoundTrips() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        checkRoundTrip(SignatureScheme.ECDSA_SECP256R1_SHA256, kpg.generateKeyPair());
    }

    @Test
    public void ecdsaP384RoundTrips() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp384r1"));
        checkRoundTrip(SignatureScheme.ECDSA_SECP384R1_SHA384, kpg.generateKeyPair());
    }

    @Test
    public void ed25519RoundTrips() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        checkRoundTrip(SignatureScheme.ED25519, kpg.generateKeyPair());
    }

    @Test
    public void mlDsaSchemesRoundTripWithIanaCodepoints() throws Exception {
        assertEquals(0x0904, SignatureScheme.MLDSA44.getCode());
        assertEquals(0x0905, SignatureScheme.MLDSA65.getCode());
        assertEquals(0x0906, SignatureScheme.MLDSA87.getCode());
        assertSame(SignatureScheme.MLDSA65, SignatureScheme.fromCode(0x0905));
        checkRoundTrip(SignatureScheme.MLDSA44, KeyPairGenerator.getInstance("ML-DSA-44").generateKeyPair());
        checkRoundTrip(SignatureScheme.MLDSA65, KeyPairGenerator.getInstance("ML-DSA-65").generateKeyPair());
        checkRoundTrip(SignatureScheme.MLDSA87, KeyPairGenerator.getInstance("ML-DSA-87").generateKeyPair());
    }

    @Test
    public void mlDsaSchemeIsBoundToItsParameterSet() throws Exception {
        KeyPair kp44 = KeyPairGenerator.getInstance("ML-DSA-44").generateKeyPair();
        assertTrue(SignatureScheme.MLDSA44.isCompatible(kp44.getPublic()));
        assertTrue(SignatureScheme.MLDSA44.isCompatible(kp44.getPrivate()));
        assertFalse(SignatureScheme.MLDSA65.isCompatible(kp44.getPublic()));
        assertFalse(SignatureScheme.MLDSA87.isCompatible(kp44.getPublic()));
        // Not merely a policy check: the verifier itself refuses the key.
        byte[] signature = SignatureScheme.MLDSA44.sign(kp44.getPrivate(), MESSAGE);
        try {
            assertFalse(SignatureScheme.MLDSA65.verify(kp44.getPublic(), MESSAGE, signature));
        } catch (GeneralSecurityException expected) {
            // refusing the key outright is equally acceptable
        }
    }

    @Test
    public void schemesMatchOnlyTheirOwnKeyType() throws Exception {
        KeyPairGenerator rsaGen = KeyPairGenerator.getInstance("RSA");
        rsaGen.initialize(2048);
        KeyPair rsa = rsaGen.generateKeyPair();
        KeyPairGenerator ecGen = KeyPairGenerator.getInstance("EC");
        ecGen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair p256 = ecGen.generateKeyPair();
        ecGen.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair p384 = ecGen.generateKeyPair();
        KeyPair ed25519 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair ed448 = KeyPairGenerator.getInstance("Ed448").generateKeyPair();
        KeyPair mldsa65 = KeyPairGenerator.getInstance("ML-DSA-65").generateKeyPair();

        assertTrue(SignatureScheme.RSA_PSS_RSAE_SHA256.isCompatible(rsa.getPublic()));
        assertTrue(SignatureScheme.RSA_PKCS1_SHA256.isCompatible(rsa.getPrivate()));
        assertFalse(SignatureScheme.RSA_PSS_RSAE_SHA256.isCompatible(p256.getPublic()));
        assertTrue(SignatureScheme.ECDSA_SECP256R1_SHA256.isCompatible(p256.getPublic()));
        assertFalse(SignatureScheme.ECDSA_SECP256R1_SHA256.isCompatible(p384.getPublic()));
        assertTrue(SignatureScheme.ECDSA_SECP384R1_SHA384.isCompatible(p384.getPrivate()));
        assertFalse(SignatureScheme.ECDSA_SECP384R1_SHA384.isCompatible(p256.getPublic()));
        assertTrue(SignatureScheme.ED25519.isCompatible(ed25519.getPublic()));
        assertFalse(SignatureScheme.ED25519.isCompatible(ed448.getPublic()));
        assertFalse(SignatureScheme.ED25519.isCompatible(mldsa65.getPublic()));
        assertTrue(SignatureScheme.MLDSA65.isCompatible(mldsa65.getPublic()));
        assertFalse(SignatureScheme.MLDSA65.isCompatible(rsa.getPublic()));
    }

    @Test
    public void pkcs1SchemesAreNotHandshakeSignatures() {
        for (SignatureScheme scheme : SignatureScheme.values()) {
            boolean pkcs1 = scheme.name().startsWith("RSA_PKCS1_");
            assertEquals(scheme.toString(), !pkcs1, scheme.isHandshakeSignature());
        }
    }

    private void checkRoundTrip(SignatureScheme scheme, KeyPair kp) throws Exception {
        byte[] signature = scheme.sign(kp.getPrivate(), MESSAGE);
        assertTrue(scheme + " valid signature verifies", scheme.verify(kp.getPublic(), MESSAGE, signature));
        assertFalse(scheme + " tampered message is rejected", scheme.verify(kp.getPublic(), TAMPERED, signature));
    }

}
