/*
 * DANEVerifier.java
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

package org.bluezoo.gumdrop.dns;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;

/**
 * Matches an X.509 certificate chain against TLSA records.
 * RFC 6698 section 2: a TLSA record's certificate association data is
 * compared against either the full certificate or its
 * SubjectPublicKeyInfo (selector), taken either verbatim or as a
 * SHA-256/SHA-512 hash (matching type), of either the end-entity
 * certificate or a certificate authority in the chain (certificate
 * usage).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DANETrustManager
 */
public final class DANEVerifier {

    /** PKIX-TA: a CA constrained by a WebPKI-valid chain. RFC 6698 section 2.1.1. */
    public static final int USAGE_PKIX_TA = 0;

    /** PKIX-EE: the end-entity cert, constrained by a WebPKI-valid chain. RFC 6698 section 2.1.1. */
    public static final int USAGE_PKIX_EE = 1;

    /** DANE-TA: a CA trusted directly, no WebPKI validation required. RFC 6698 section 2.1.1. */
    public static final int USAGE_DANE_TA = 2;

    /** DANE-EE: the end-entity cert, trusted directly. RFC 6698 section 2.1.1. */
    public static final int USAGE_DANE_EE = 3;

    /** Selector: match against the full certificate. RFC 6698 section 2.1.2. */
    public static final int SELECTOR_FULL_CERT = 0;

    /** Selector: match against the SubjectPublicKeyInfo only. RFC 6698 section 2.1.2. */
    public static final int SELECTOR_SPKI = 1;

    /** Matching type: certificate association data is the selected content itself. RFC 6698 section 2.1.3. */
    public static final int MATCHING_TYPE_FULL = 0;

    /** Matching type: certificate association data is a SHA-256 hash. RFC 6698 section 2.1.3. */
    public static final int MATCHING_TYPE_SHA256 = 1;

    /** Matching type: certificate association data is a SHA-512 hash. RFC 6698 section 2.1.3. */
    public static final int MATCHING_TYPE_SHA512 = 2;

    private DANEVerifier() {
    }

    /**
     * Returns true if the given certificate matches a TLSA record's
     * certificate association data, per RFC 6698 section 2.1.
     *
     * @param cert the certificate to check
     * @param tlsa the TLSA record to match against
     * @return true if the selected content (or its hash) matches
     * @throws CertificateEncodingException if the certificate cannot
     *                                      be re-encoded
     * @throws IllegalStateException if {@code tlsa} is not a TLSA
     *                                record, or uses an unsupported
     *                                selector or matching type
     */
    public static boolean matches(X509Certificate cert,
                                  DNSResourceRecord tlsa)
            throws CertificateEncodingException {
        byte[] selected = select(cert, tlsa.getTLSASelector());
        byte[] candidate = applyMatchingType(selected,
                tlsa.getTLSAMatchingType());
        return Arrays.equals(candidate,
                tlsa.getTLSACertificateAssociationData());
    }

    /**
     * Finds the first TLSA record in {@code tlsaRecords} that matches
     * a certificate in {@code chain}, restricting which certificates
     * are considered based on each record's certificate usage field
     * (RFC 6698 section 2.1.1): usage 1 (PKIX-EE) and 3 (DANE-EE)
     * only match the end-entity certificate ({@code chain[0]}); usage
     * 0 (PKIX-TA) and 2 (DANE-TA) may match any certificate in the
     * chain.
     *
     * @param chain the certificate chain, end-entity certificate first
     * @param tlsaRecords the TLSA records to check
     * @return the first matching TLSA record, or null if none match
     * @throws CertificateEncodingException if a certificate cannot be
     *                                      re-encoded
     */
    public static DNSResourceRecord findMatch(X509Certificate[] chain,
            List<DNSResourceRecord> tlsaRecords)
            throws CertificateEncodingException {
        if (chain == null || chain.length == 0) {
            return null;
        }
        for (DNSResourceRecord tlsa : tlsaRecords) {
            int usage = tlsa.getTLSACertUsage();
            if (usage == USAGE_PKIX_EE || usage == USAGE_DANE_EE) {
                if (matches(chain[0], tlsa)) {
                    return tlsa;
                }
            } else {
                for (X509Certificate cert : chain) {
                    if (matches(cert, tlsa)) {
                        return tlsa;
                    }
                }
            }
        }
        return null;
    }

    private static byte[] select(X509Certificate cert, int selector)
            throws CertificateEncodingException {
        switch (selector) {
            case SELECTOR_FULL_CERT:
                return cert.getEncoded();
            case SELECTOR_SPKI:
                return spki(cert.getPublicKey());
            default:
                throw new IllegalStateException(
                        "Unsupported TLSA selector: " + selector);
        }
    }

    private static byte[] spki(PublicKey key) {
        // X.509 SubjectPublicKeyInfo is exactly what PublicKey.getEncoded()
        // returns for the standard "X.509" key encoding format.
        return key.getEncoded();
    }

    private static byte[] applyMatchingType(byte[] selected, int matchingType) {
        switch (matchingType) {
            case MATCHING_TYPE_FULL:
                return selected;
            case MATCHING_TYPE_SHA256:
                return digest(selected, "SHA-256");
            case MATCHING_TYPE_SHA512:
                return digest(selected, "SHA-512");
            default:
                throw new IllegalStateException(
                        "Unsupported TLSA matching type: " + matchingType);
        }
    }

    private static byte[] digest(byte[] data, String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "Missing digest algorithm: " + algorithm, e);
        }
    }

}
