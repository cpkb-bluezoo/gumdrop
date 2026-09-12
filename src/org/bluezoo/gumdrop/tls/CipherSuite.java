/*
 * CipherSuite.java
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

import org.bluezoo.gumdrop.crypto.Hkdf;

/**
 * TLS 1.3 cipher suites (RFC 8446 section B.4). Only the three AEAD
 * suites QUIC packet protection already implements
 * ({@code org.bluezoo.gumdrop.quic.packet.QuicAeadAlgorithm}) are offered
 * here -- this engine has no reason to negotiate a suite the transport
 * layer cannot protect packets with.
 *
 * <p>This is a replacement for {@code tech.kwik.agent15.TlsConstants.CipherSuite},
 * which it otherwise mirrors in spirit: each suite's hash algorithm
 * determines both its transcript hash and its key schedule ({@link Hkdf}
 * instance), per RFC 8446 section 7.1.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8446#appendix-B.4">RFC 8446 appendix B.4</a>
 */
public enum CipherSuite {

    TLS_AES_128_GCM_SHA256(0x1301, "SHA-256", 32, 16, "AES", "AES/GCM/NoPadding"),
    TLS_AES_256_GCM_SHA384(0x1302, "SHA-384", 48, 32, "AES", "AES/GCM/NoPadding"),
    TLS_CHACHA20_POLY1305_SHA256(0x1303, "SHA-256", 32, 32, "ChaCha20", "ChaCha20-Poly1305");

    private final int code;
    private final String hashAlgorithm;
    private final int hashLength;
    private final int aeadKeyLength;
    private final String aeadKeyAlgorithm;
    private final String aeadTransformation;

    CipherSuite(int code, String hashAlgorithm, int hashLength, int aeadKeyLength, String aeadKeyAlgorithm,
            String aeadTransformation) {
        this.code = code;
        this.hashAlgorithm = hashAlgorithm;
        this.hashLength = hashLength;
        this.aeadKeyLength = aeadKeyLength;
        this.aeadKeyAlgorithm = aeadKeyAlgorithm;
        this.aeadTransformation = aeadTransformation;
    }

    /**
     * Returns the IANA codepoint for this suite, as carried on the wire
     * in {@code cipher_suites} and {@code ServerHello.cipher_suite}.
     *
     * @return the two-octet codepoint
     */
    public int getCode() {
        return code;
    }

    /**
     * Returns the JCE digest algorithm name for this suite's transcript
     * hash and key schedule, {@code "SHA-256"} or {@code "SHA-384"}.
     *
     * @return the digest algorithm name
     */
    public String getHashAlgorithm() {
        return hashAlgorithm;
    }

    /**
     * Returns the output length in bytes of this suite's hash.
     *
     * @return the hash length
     */
    public int getHashLength() {
        return hashLength;
    }

    /**
     * Returns the {@link Hkdf} instance for this suite's hash algorithm.
     *
     * @return an HKDF instance matching this suite's hash
     */
    public Hkdf newHkdf() {
        return (hashLength == 32) ? Hkdf.sha256() : Hkdf.sha384();
    }

    /**
     * Returns this suite's AEAD key length in bytes (RFC 8446 section
     * 5.3): 16 for {@code TLS_AES_128_GCM_SHA256}, 32 for the other two.
     *
     * @return the AEAD key length
     */
    public int getAeadKeyLength() {
        return aeadKeyLength;
    }

    /**
     * Returns the JCE secret-key algorithm name for this suite's AEAD,
     * e.g. {@code "AES"}.
     *
     * @return the AEAD key algorithm name
     */
    public String getAeadKeyAlgorithm() {
        return aeadKeyAlgorithm;
    }

    /**
     * Returns the JCE cipher transformation for this suite's AEAD seal/open,
     * e.g. {@code "AES/GCM/NoPadding"} or {@code "ChaCha20-Poly1305"} --
     * both standard JCE transformations (the latter since JDK 11), no
     * custom AEAD implementation needed.
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
    public static CipherSuite fromCode(int code) {
        CipherSuite[] values = values();
        for (int i = 0; i < values.length; i++) {
            if (values[i].code == code) {
                return values[i];
            }
        }
        return null;
    }

}
