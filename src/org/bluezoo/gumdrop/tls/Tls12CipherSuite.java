/*
 * Tls12CipherSuite.java
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

package org.bluezoo.gumdrop.tls;

/**
 * TLS 1.2 cipher suites this engine offers/accepts (RFC 5289 GCM, RFC 7905
 * ChaCha20-Poly1305) -- ECDHE key exchange only, AEAD only. No CBC suites:
 * MAC-then-encrypt CBC has a real, recurring timing-side-channel history
 * (Lucky Thirteen and repeated re-discoveries); AEAD is judged sufficient,
 * matching hopf's own permanent (not deferred) exclusion.
 *
 * <p>Unlike {@link CipherSuite} (TLS 1.3), each suite here also carries the
 * server certificate key type it requires ({@code "RSA"} or {@code "EC"}),
 * since a TLS 1.2 suite name bakes in the signature algorithm
 * ({@code ECDHE_ECDSA_*} vs {@code ECDHE_RSA_*}) -- suite selection must
 * match the server's actual configured key type, not just be mutually
 * offered.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5289">RFC 5289</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7905">RFC 7905</a>
 */
public enum Tls12CipherSuite {

    TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256(0xC02B, "SHA-256", "EC", 16, 4, "AES", "AES/GCM/NoPadding"),
    TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256(0xC02F, "SHA-256", "RSA", 16, 4, "AES", "AES/GCM/NoPadding"),
    TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256(0xCCA9, "SHA-256", "EC", 32, 12, "ChaCha20", "ChaCha20-Poly1305"),
    TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256(0xCCA8, "SHA-256", "RSA", 32, 12, "ChaCha20", "ChaCha20-Poly1305"),
    TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384(0xC02C, "SHA-384", "EC", 32, 4, "AES", "AES/GCM/NoPadding"),
    TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384(0xC030, "SHA-384", "RSA", 32, 4, "AES", "AES/GCM/NoPadding");

    private final int code;
    private final String prfHashAlgorithm;
    private final String keyType;
    private final int aeadKeyLength;
    private final int fixedIvLength;
    private final String aeadKeyAlgorithm;
    private final String aeadTransformation;

    Tls12CipherSuite(int code, String prfHashAlgorithm, String keyType, int aeadKeyLength, int fixedIvLength,
            String aeadKeyAlgorithm, String aeadTransformation) {
        this.code = code;
        this.prfHashAlgorithm = prfHashAlgorithm;
        this.keyType = keyType;
        this.aeadKeyLength = aeadKeyLength;
        this.fixedIvLength = fixedIvLength;
        this.aeadKeyAlgorithm = aeadKeyAlgorithm;
        this.aeadTransformation = aeadTransformation;
    }

    /**
     * Returns the IANA codepoint for this suite.
     *
     * @return the two-octet codepoint
     */
    public int getCode() {
        return code;
    }

    /**
     * Returns the JCA digest algorithm name driving this suite's PRF
     * (RFC 5246 section 5) and transcript hash -- {@code "SHA-256"} or
     * {@code "SHA-384"}.
     *
     * @return the PRF hash algorithm name
     */
    public String getPrfHashAlgorithm() {
        return prfHashAlgorithm;
    }

    /**
     * Returns the server certificate key type this suite requires,
     * {@code "RSA"} or {@code "EC"} (matching {@link java.security.Key#getAlgorithm()}).
     *
     * @return the required key type
     */
    public String getKeyType() {
        return keyType;
    }

    /**
     * Returns this suite's AEAD key length in bytes.
     *
     * @return the AEAD key length
     */
    public int getAeadKeyLength() {
        return aeadKeyLength;
    }

    /**
     * Returns this suite's fixed IV length in bytes: 4 for GCM suites
     * (RFC 5288 section 3's {@code salt}, concatenated with an 8-byte
     * explicit per-record nonce carried on the wire), 12 for
     * ChaCha20-Poly1305 (RFC 7905 section 2's full IV, XORed with the
     * sequence number, no wire nonce at all).
     *
     * @return the fixed IV length
     */
    public int getFixedIvLength() {
        return fixedIvLength;
    }

    /**
     * Returns the JCE secret-key algorithm name for this suite's AEAD.
     *
     * @return the AEAD key algorithm name
     */
    public String getAeadKeyAlgorithm() {
        return aeadKeyAlgorithm;
    }

    /**
     * Returns the JCE cipher transformation for this suite's AEAD seal/open.
     *
     * @return the AEAD cipher transformation
     */
    public String getAeadTransformation() {
        return aeadTransformation;
    }

    /**
     * Looks up a suite by its IANA codepoint.
     *
     * @param code the codepoint
     * @return the matching suite, or null if unrecognised
     */
    public static Tls12CipherSuite fromCode(int code) {
        Tls12CipherSuite[] values = values();
        for (int i = 0; i < values.length; i++) {
            if (values[i].code == code) {
                return values[i];
            }
        }
        return null;
    }

}
