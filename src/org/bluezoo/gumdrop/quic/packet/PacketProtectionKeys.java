/*
 * PacketProtectionKeys.java
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

import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import org.bluezoo.gumdrop.quic.tls.Hkdf;

/**
 * The packet- and header-protection keys derived from a single traffic
 * secret for one direction (RFC 9001 section 5.1).
 *
 * <pre>
 *   key = HKDF-Expand-Label(secret, "quic key", "", key_length)
 *   iv  = HKDF-Expand-Label(secret, "quic iv",  "", 12)
 *   hp  = HKDF-Expand-Label(secret, "quic hp",  "", key_length)
 * </pre>
 *
 * <p>One instance covers one (direction, encryption level) pair -- a
 * connection holds up to four of these at a time while a handshake is in
 * progress: client and server, at whichever of Initial/Handshake/1-RTT
 * are currently active.
 *
 * <p>The key material is immutable, but {@link PacketProtection} lazily
 * populates and reuses one AEAD and one header-protection {@link Cipher}
 * on this instance across every seal/open/mask operation it performs with
 * these keys, avoiding {@code Cipher.getInstance}'s JCE provider-selection
 * and SPI-instantiation cost on every packet. A {@code Cipher} is not
 * thread-safe, so this relies on the same single-threaded-per-connection
 * access {@link org.bluezoo.gumdrop.quic.QuicConnection} already requires
 * of all its other state -- an instance of this class must never be used
 * concurrently by more than one thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-5.1">RFC 9001 section 5.1</a>
 */
public final class PacketProtectionKeys {

    private static final byte[] EMPTY_CONTEXT = new byte[0];

    private final QuicAeadAlgorithm algorithm;
    private final SecretKeySpec aeadKey;
    private final byte[] iv;
    private final SecretKeySpec headerProtectionKey;

    private Cipher aeadCipher;
    private Cipher headerProtectionCipher;

    private PacketProtectionKeys(QuicAeadAlgorithm algorithm, SecretKeySpec aeadKey,
            byte[] iv, SecretKeySpec headerProtectionKey) {
        this.algorithm = algorithm;
        this.aeadKey = aeadKey;
        this.iv = iv;
        this.headerProtectionKey = headerProtectionKey;
    }

    /**
     * Derives packet- and header-protection keys from a traffic secret.
     *
     * @param hkdf the HKDF instance for the hash bound to this secret
     *             (SHA-256 for Initial secrets and for the SHA-256 cipher
     *             suites; SHA-384 for {@code TLS_AES_256_GCM_SHA384})
     * @param secret the traffic secret for this direction and level
     * @param algorithm the negotiated (or, for Initial, fixed) AEAD algorithm
     * @return the derived keys
     */
    public static PacketProtectionKeys derive(Hkdf hkdf, byte[] secret, QuicAeadAlgorithm algorithm) {
        int keyLength = algorithm.getKeyLength();
        byte[] keyBytes = hkdf.expandLabel(secret, "quic key", EMPTY_CONTEXT, keyLength);
        byte[] ivBytes = hkdf.expandLabel(secret, "quic iv", EMPTY_CONTEXT, QuicAeadAlgorithm.IV_LENGTH);
        byte[] hpBytes = hkdf.expandLabel(secret, "quic hp", EMPTY_CONTEXT, keyLength);

        SecretKeySpec aeadKey = new SecretKeySpec(keyBytes, algorithm.getKeyAlgorithm());
        SecretKeySpec headerProtectionKey = new SecretKeySpec(hpBytes, algorithm.getKeyAlgorithm());
        return new PacketProtectionKeys(algorithm, aeadKey, ivBytes, headerProtectionKey);
    }

    /**
     * Returns the AEAD algorithm these keys were derived for.
     *
     * @return the AEAD algorithm
     */
    public QuicAeadAlgorithm getAlgorithm() {
        return algorithm;
    }

    /**
     * Returns the AEAD packet-protection key.
     *
     * @return the AEAD key
     */
    public SecretKeySpec getAeadKey() {
        return aeadKey;
    }

    /**
     * Returns the 12-byte AEAD IV, before combination with a packet
     * number (RFC 9001 section 5.3).
     *
     * @return the IV
     */
    public byte[] getIv() {
        return iv;
    }

    /**
     * Returns the header-protection key (RFC 9001 section 5.4.3).
     *
     * @return the header-protection key
     */
    public SecretKeySpec getHeaderProtectionKey() {
        return headerProtectionKey;
    }

    /**
     * Returns this instance's cached AEAD {@link Cipher}, creating it on
     * first use. Package-private: only {@link PacketProtection} calls
     * this, immediately re-{@code init}ing the returned instance for its
     * own operation before use, so no other caller can observe or
     * disturb its transient per-operation state.
     *
     * @return the AEAD cipher for {@link #getAlgorithm()}
     * @throws GeneralSecurityException if no provider supports the transformation
     */
    Cipher getAeadCipher() throws GeneralSecurityException {
        if (aeadCipher == null) {
            aeadCipher = Cipher.getInstance(algorithm.getAeadTransformation());
        }
        return aeadCipher;
    }

    /**
     * Returns this instance's cached header-protection {@link Cipher},
     * creating it on first use. Package-private for the same reason as
     * {@link #getAeadCipher()}.
     *
     * <p>Only used for the AES header-protection transformations, whose
     * ECB mode takes no nonce and so has no reuse state to corrupt across
     * calls. {@link PacketProtection#headerProtectionMask} does not use
     * this for ChaCha20 -- see its own comment for why that one is left
     * uncached.
     *
     * @return the header-protection cipher for {@link #getAlgorithm()}
     * @throws GeneralSecurityException if no provider supports the transformation
     */
    Cipher getHeaderProtectionCipher() throws GeneralSecurityException {
        if (headerProtectionCipher == null) {
            headerProtectionCipher = Cipher.getInstance(algorithm.getHeaderProtectionTransformation());
        }
        return headerProtectionCipher;
    }
}
