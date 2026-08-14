/*
 * QuicAeadAlgorithm.java
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

package org.bluezoo.gumdrop.quic.packet;

/**
 * The AEAD algorithms usable for QUIC packet protection (RFC 9001
 * section 5.3), and the JCE names needed to drive them.
 *
 * <p>Every algorithm here uses a 12-byte IV and a 16-byte authentication
 * tag; only the key length and header-protection cipher differ.
 *
 * <p>{@code CHACHA20_POLY1305} (RFC 9001 section 5.4.4 header protection)
 * is not yet implemented -- Initial packets always use
 * {@link #AES_128_GCM} (RFC 9001 section 5.2), so its absence does not
 * block Initial packet protection; it is required before a handshake or
 * 1-RTT connection negotiating {@code TLS_CHACHA20_POLY1305_SHA256} can
 * be supported.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-5.3">RFC 9001 section 5.3</a>
 */
public enum QuicAeadAlgorithm {

    /** {@code TLS_AES_128_GCM_SHA256}; also the fixed algorithm for Initial packets. */
    AES_128_GCM(16, "AES", "AES/GCM/NoPadding", "AES/ECB/NoPadding"),

    /** {@code TLS_AES_256_GCM_SHA384}. */
    AES_256_GCM(32, "AES", "AES/GCM/NoPadding", "AES/ECB/NoPadding");

    /** RFC 9001 section 5.3: the IV length is 12 bytes for every AEAD algorithm QUIC uses. */
    public static final int IV_LENGTH = 12;

    /** RFC 9001 section 5.3: the authentication tag is 16 bytes for every AEAD algorithm QUIC uses. */
    public static final int TAG_LENGTH = 16;

    /** RFC 9001 section 5.4.2: the header-protection sample is always 16 bytes. */
    public static final int SAMPLE_LENGTH = 16;

    private final int keyLength;
    private final String keyAlgorithm;
    private final String aeadTransformation;
    private final String headerProtectionTransformation;

    QuicAeadAlgorithm(int keyLength, String keyAlgorithm, String aeadTransformation,
            String headerProtectionTransformation) {
        this.keyLength = keyLength;
        this.keyAlgorithm = keyAlgorithm;
        this.aeadTransformation = aeadTransformation;
        this.headerProtectionTransformation = headerProtectionTransformation;
    }

    /**
     * Returns the AEAD key length in bytes (also the header-protection
     * key length, per RFC 9001 section 5.4.3).
     *
     * @return the key length in bytes
     */
    public int getKeyLength() {
        return keyLength;
    }

    /**
     * Returns the JCE secret-key algorithm name, e.g. {@code "AES"}.
     *
     * @return the key algorithm name
     */
    public String getKeyAlgorithm() {
        return keyAlgorithm;
    }

    /**
     * Returns the JCE cipher transformation used for AEAD seal/open,
     * e.g. {@code "AES/GCM/NoPadding"}.
     *
     * @return the AEAD cipher transformation
     */
    public String getAeadTransformation() {
        return aeadTransformation;
    }

    /**
     * Returns the JCE cipher transformation used for header protection,
     * e.g. {@code "AES/ECB/NoPadding"} (RFC 9001 section 5.4.3).
     *
     * @return the header-protection cipher transformation
     */
    public String getHeaderProtectionTransformation() {
        return headerProtectionTransformation;
    }
}
