/*
 * SignatureScheme.java
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

import java.security.AsymmetricKey;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECKey;
import java.security.interfaces.EdECKey;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PSSParameterSpec;

/**
 * TLS 1.3 {@code SignatureScheme} values (RFC 8446 section 4.2.3) used for
 * {@code CertificateVerify}, over JCA {@link Signature}, extended with
 * the post-quantum ML-DSA schemes of draft-ietf-tls-mldsa (TLS 1.3 only).
 * Certificate chain
 * <em>signature</em> verification (checking that an issuer signed a
 * subject certificate) is a separate concern handled by
 * {@link CertificateVerifier} via JCA's PKIX path validator, not by this
 * class.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8446#section-4.2.3">RFC 8446 section 4.2.3</a>
 * @see <a href="https://datatracker.ietf.org/doc/draft-ietf-tls-mldsa/">draft-ietf-tls-mldsa</a>
 */
public enum SignatureScheme {

    RSA_PKCS1_SHA256(0x0401, "SHA256withRSA", null, 0),
    RSA_PKCS1_SHA384(0x0501, "SHA384withRSA", null, 0),
    RSA_PKCS1_SHA512(0x0601, "SHA512withRSA", null, 0),
    RSA_PSS_RSAE_SHA256(0x0804, "RSASSA-PSS", "SHA-256", 32),
    RSA_PSS_RSAE_SHA384(0x0805, "RSASSA-PSS", "SHA-384", 48),
    RSA_PSS_RSAE_SHA512(0x0806, "RSASSA-PSS", "SHA-512", 64),
    ECDSA_SECP256R1_SHA256(0x0403, "SHA256withECDSA", null, 0),
    ECDSA_SECP384R1_SHA384(0x0503, "SHA384withECDSA", null, 0),
    ED25519(0x0807, "Ed25519", null, 0),

    // Pure ML-DSA (FIPS 204) with an empty context string, which is what
    // JCA's ML-DSA Signature computes by default. The parameter-set
    // specific JCA names are used, not the generic "ML-DSA": these refuse
    // a key of another parameter set, where the generic one accepts any.
    MLDSA44(0x0904, "ML-DSA-44", null, 0),
    MLDSA65(0x0905, "ML-DSA-65", null, 0),
    MLDSA87(0x0906, "ML-DSA-87", null, 0);

    private final int code;
    private final String jcaAlgorithm;
    private final String pssDigest;
    private final int pssSaltLength;

    SignatureScheme(int code, String jcaAlgorithm, String pssDigest, int pssSaltLength) {
        this.code = code;
        this.jcaAlgorithm = jcaAlgorithm;
        this.pssDigest = pssDigest;
        this.pssSaltLength = pssSaltLength;
    }

    /**
     * Returns the IANA codepoint for this scheme, as carried on the wire
     * in {@code signature_algorithms} and a signature message's own
     * {@code algorithm} field.
     *
     * @return the two-octet codepoint
     */
    public int getCode() {
        return code;
    }

    /**
     * Returns whether this scheme may sign a TLS 1.3 handshake
     * ({@code CertificateVerify}). RFC 8446 section 4.4.3 excludes the
     * RSASSA-PKCS1-v1_5 schemes, which exist in TLS 1.3 only to describe
     * signatures inside certificates.
     *
     * @return false for the {@code rsa_pkcs1_*} schemes
     */
    public boolean isHandshakeSignature() {
        return this != RSA_PKCS1_SHA256 && this != RSA_PKCS1_SHA384 && this != RSA_PKCS1_SHA512;
    }

    /**
     * Returns whether a key is of the kind this scheme signs with, as
     * TLS 1.3 defines it: an RSA key for the {@code rsa_*} schemes, an EC
     * key <em>on the named curve</em> for the {@code ecdsa_*} schemes, an
     * Ed25519 key for {@link #ED25519}, and an ML-DSA key of the matching
     * parameter set for the {@code mldsa*} schemes.
     *
     * @param key a public or private key
     * @return true if the key matches this scheme
     */
    public boolean isCompatible(Key key) {
        String algorithm = key.getAlgorithm();
        switch (this) {
            case ECDSA_SECP256R1_SHA256:
                return ecFieldSize(key) == 256;
            case ECDSA_SECP384R1_SHA384:
                return ecFieldSize(key) == 384;
            case ED25519:
                return key instanceof EdECKey
                        && "Ed25519".equalsIgnoreCase(((EdECKey) key).getParams().getName());
            case MLDSA44:
            case MLDSA65:
            case MLDSA87:
                if (!"ML-DSA".equals(algorithm) || !(key instanceof AsymmetricKey)) {
                    return false;
                }
                AlgorithmParameterSpec params = ((AsymmetricKey) key).getParams();
                return params instanceof NamedParameterSpec
                        && jcaAlgorithm.equalsIgnoreCase(((NamedParameterSpec) params).getName());
            default:
                return "RSA".equals(algorithm);
        }
    }

    private static int ecFieldSize(Key key) {
        if (!"EC".equals(key.getAlgorithm()) || !(key instanceof ECKey)) {
            return -1;
        }
        return ((ECKey) key).getParams().getCurve().getField().getFieldSize();
    }

    /**
     * Signs a message under this scheme.
     *
     * @param key the private key; must match this scheme's key type
     *            (RSA for the {@code rsa_*} schemes, EC for the
     *            {@code ecdsa_*} schemes, Ed25519 for {@link #ED25519},
     *            ML-DSA for the {@code mldsa*} schemes)
     * @param message the exact bytes to sign (the caller builds the
     *                TLS 1.3 {@code CertificateVerify} signature content,
     *                including its 64-space padding and context string)
     * @return the signature bytes
     * @throws GeneralSecurityException if signing fails
     */
    public byte[] sign(PrivateKey key, byte[] message) throws GeneralSecurityException {
        Signature signature = newSignature();
        signature.initSign(key);
        signature.update(message);
        return signature.sign();
    }

    /**
     * Verifies a signature under this scheme.
     *
     * @param key the public key
     * @param message the exact bytes that were signed
     * @param signatureBytes the signature to verify
     * @return true if the signature is valid
     * @throws GeneralSecurityException if verification cannot be
     *         attempted (a malformed key, for instance) -- an invalid
     *         signature returns false, it does not throw
     */
    public boolean verify(PublicKey key, byte[] message, byte[] signatureBytes) throws GeneralSecurityException {
        Signature signature = newSignature();
        signature.initVerify(key);
        signature.update(message);
        return signature.verify(signatureBytes);
    }

    private Signature newSignature() throws GeneralSecurityException {
        Signature signature = Signature.getInstance(jcaAlgorithm);
        if (pssDigest != null) {
            signature.setParameter(new PSSParameterSpec(
                    pssDigest, "MGF1", new MGF1ParameterSpec(pssDigest), pssSaltLength, 1));
        }
        return signature;
    }

    /**
     * Looks up a scheme by its IANA codepoint.
     *
     * @param code the codepoint
     * @return the matching scheme, or null if unrecognised
     */
    public static SignatureScheme fromCode(int code) {
        SignatureScheme[] values = values();
        for (int i = 0; i < values.length; i++) {
            if (values[i].code == code) {
                return values[i];
            }
        }
        return null;
    }

}
