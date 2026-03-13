/*
 * DNSSECAlgorithm.java
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

/**
 * DNSSEC algorithm numbers with mappings to JCA signature and digest
 * algorithm names.
 * RFC 4034 Appendix A.1 defines the algorithm registry.
 * RFC 8624 specifies which algorithms are mandatory or recommended.
 *
 * <p>DS digest type mappings are also included per RFC 4034 section 5.1.3
 * and RFC 4509.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum DNSSECAlgorithm {

    /** RSA/SHA-256. RFC 5702. MUST implement per RFC 8624. */
    RSASHA256(8, "SHA256withRSA", "RSA"),

    /** RSA/SHA-512. RFC 5702. MUST implement per RFC 8624. */
    RSASHA512(10, "SHA512withRSA", "RSA"),

    /** ECDSA P-256 with SHA-256. RFC 6605. MUST implement per RFC 8624. */
    ECDSAP256SHA256(13, "SHA256withECDSA", "EC"),

    /** ECDSA P-384 with SHA-384. RFC 6605. RECOMMENDED per RFC 8624. */
    ECDSAP384SHA384(14, "SHA384withECDSA", "EC"),

    /** Ed25519. RFC 8080. RECOMMENDED per RFC 8624. */
    ED25519(15, "Ed25519", "EdDSA"),

    /** Ed448. RFC 8080. MAY implement per RFC 8624. */
    ED448(16, "Ed448", "EdDSA");

    private final int number;
    private final String signatureAlgorithm;
    private final String keyAlgorithm;

    DNSSECAlgorithm(int number, String signatureAlgorithm,
                    String keyAlgorithm) {
        this.number = number;
        this.signatureAlgorithm = signatureAlgorithm;
        this.keyAlgorithm = keyAlgorithm;
    }

    /**
     * Returns the IANA algorithm number.
     *
     * @return the algorithm number
     */
    public int getNumber() {
        return number;
    }

    /**
     * Returns the JCA {@code Signature} algorithm name.
     *
     * @return the JCA signature algorithm
     */
    public String getSignatureAlgorithm() {
        return signatureAlgorithm;
    }

    /**
     * Returns the JCA {@code KeyFactory} algorithm name.
     *
     * @return the JCA key algorithm
     */
    public String getKeyAlgorithm() {
        return keyAlgorithm;
    }

    /**
     * Returns the DNSSECAlgorithm for the given IANA number.
     *
     * @param number the algorithm number
     * @return the algorithm, or null if unsupported
     */
    public static DNSSECAlgorithm fromNumber(int number) {
        for (DNSSECAlgorithm alg : values()) {
            if (alg.number == number) {
                return alg;
            }
        }
        return null;
    }

    /**
     * Returns the JCA {@code MessageDigest} algorithm name for
     * a DS digest type.
     * RFC 4034 section 5.1.3 and RFC 4509.
     *
     * @param digestType the DS digest type number
     * @return the JCA digest algorithm, or null if unsupported
     */
    public static String dsDigestAlgorithm(int digestType) {
        switch (digestType) {
            case 1:
                return "SHA-1";
            case 2:
                return "SHA-256";
            case 4:
                return "SHA-384";
            default:
                return null;
        }
    }

    /**
     * Returns the ECDSA curve size in bytes for ECDSA algorithms.
     * RFC 6605: P-256 uses 32-byte coordinates, P-384 uses 48-byte.
     *
     * @return the curve size in bytes, or 0 if not ECDSA
     */
    public int getECDSACurveSize() {
        switch (this) {
            case ECDSAP256SHA256:
                return 32;
            case ECDSAP384SHA384:
                return 48;
            default:
                return 0;
        }
    }

    /**
     * Returns the EC named curve for ECDSA algorithms.
     *
     * @return the curve name, or null if not ECDSA
     */
    public String getECCurveName() {
        switch (this) {
            case ECDSAP256SHA256:
                return "secp256r1";
            case ECDSAP384SHA384:
                return "secp384r1";
            default:
                return null;
        }
    }

}
