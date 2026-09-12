/*
 * Tls12DirectionalKeys.java
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

import java.security.GeneralSecurityException;
import java.security.spec.AlgorithmParameterSpec;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * One direction's TLS 1.2 record-layer AEAD state (RFC 5246 section 6.2,
 * RFC 5288 section 3, RFC 7905 section 2) -- <strong>not</strong> a
 * parameterization of {@link DirectionalKeys} (TLS 1.3): GCM suites here
 * carry an 8-byte explicit nonce on the wire ({@link DirectionalKeys} has
 * none -- TLS 1.3 nonces are always implicit IV XOR sequence number), and
 * this class has no {@code KeyUpdate} escape hatch at the confidentiality
 * limit, unlike TLS 1.3's record layer.
 *
 * <p>Package-private: an implementation detail of {@link Tls12RecordEngine}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class Tls12DirectionalKeys {

    private static final int TAG_LENGTH = 16;

    /** See {@link DirectionalKeys#AES_GCM_CONFIDENTIALITY_LIMIT}'s doc -- the same RFC 8446 section 5.5 / RFC 9325 section 4.4 bound applies here. */
    private static final long AES_GCM_CONFIDENTIALITY_LIMIT = 23_726_566L;

    private final Tls12CipherSuite suite;
    private final byte[] key;
    private final byte[] fixedIv;

    // Package-private and mutable: Tls12RecordEngineTest fast-forwards
    // this directly to exercise the confidentiality-limit close without
    // actually protecting 23 million records.
    long seq;

    private Tls12DirectionalKeys(Tls12CipherSuite suite, byte[] key, byte[] fixedIv) {
        this.suite = suite;
        this.key = key;
        this.fixedIv = fixedIv;
    }

    /**
     * Wraps key material staged by {@link Tls12HandshakeEngine} for one
     * direction. The sequence number starts at 0 -- a fresh instance is
     * constructed on every {@code ChangeCipherSpec} activation, so this
     * also implements RFC 5246 section 6.1's "sequence number is set to
     * zero whenever a connection state is made the active state" rule.
     *
     * @param suite the negotiated cipher suite
     * @param material the key and fixed IV for this direction
     * @return the directional keys
     */
    static Tls12DirectionalKeys fromMaterial(Tls12CipherSuite suite, DirectionalKeyMaterial material) {
        return new Tls12DirectionalKeys(suite, material.key, material.fixedIv);
    }

    /**
     * Whether this direction's cipher carries an explicit per-record
     * nonce on the wire (GCM, RFC 5288 section 3) or derives it purely
     * from the sequence number (ChaCha20-Poly1305, RFC 7905 section 2).
     *
     * @return true for a GCM suite
     */
    boolean hasExplicitNonce() {
        return fixedIv.length == 4;
    }

    /**
     * The nonce for the write side, or for a cipher with no wire nonce at
     * all (ChaCha20-Poly1305, both directions).
     *
     * @return the 12-byte nonce
     */
    byte[] localNonce() {
        return hasExplicitNonce() ? gcmNonce(seqBytes()) : chachaNonce();
    }

    /**
     * The nonce for the read side of a GCM direction, from the wire's
     * explicit nonce bytes (never called for ChaCha20-Poly1305 -- see
     * {@link #localNonce}).
     *
     * @param explicitNonce the 8-byte explicit nonce read from the wire
     * @return the 12-byte nonce
     */
    byte[] nonceFromWire(byte[] explicitNonce) {
        return hasExplicitNonce() ? gcmNonce(explicitNonce) : chachaNonce();
    }

    /** RFC 5288 section 3: {@code fixed_iv (4 bytes) || explicit_nonce (8 bytes)}. */
    private byte[] gcmNonce(byte[] explicitNonce) {
        byte[] n = new byte[12];
        System.arraycopy(fixedIv, 0, n, 0, 4);
        System.arraycopy(explicitNonce, 0, n, 4, 8);
        return n;
    }

    /** RFC 7905 section 2: {@code fixed_iv (12 bytes) XOR seq} in the last 8 bytes -- same construction TLS 1.3 uses throughout. */
    private byte[] chachaNonce() {
        byte[] n = fixedIv.clone();
        for (int i = 0; i < 8; i++) {
            n[4 + i] ^= (byte) (seq >>> (56 - 8 * i));
        }
        return n;
    }

    /** The write sequence counter's own 8 big-endian bytes -- the GCM explicit nonce sent on the wire verbatim (not random). */
    byte[] seqBytes() {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) {
            b[i] = (byte) (seq >>> (56 - 8 * i));
        }
        return b;
    }

    /** Advances to the next record's sequence number. */
    void advance() {
        seq++;
    }

    /**
     * Returns whether this direction has protected enough records under
     * its current AES-GCM key to warrant closing the connection (RFC
     * 8446 section 5.5) -- TLS 1.2 has no {@code KeyUpdate} to fall back
     * on instead, unlike TLS 1.3.
     *
     * @return true if the confidentiality limit has been reached
     */
    boolean overConfidentialityLimit() {
        return hasExplicitNonce() && seq >= AES_GCM_CONFIDENTIALITY_LIMIT;
    }

    /**
     * Seals {@code plaintext} in place, appending the authentication tag.
     *
     * @param nonce the per-record nonce, from {@link #localNonce}
     * @param aad the additional authenticated data
     * @param plaintext the plaintext to seal
     * @return the ciphertext with the tag appended
     */
    byte[] sealAppendTag(byte[] nonce, byte[] aad, byte[] plaintext) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(suite.getAeadTransformation());
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, suite.getAeadKeyAlgorithm()), parameterSpec(nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(plaintext);
    }

    /**
     * Opens {@code ciphertext} (with its trailing authentication tag),
     * returning the verified plaintext.
     *
     * @param nonce the per-record nonce, from {@link #nonceFromWire} or {@link #localNonce}
     * @param aad the additional authenticated data
     * @param ciphertext the ciphertext, tag included
     * @return the plaintext, or null if the tag does not verify
     */
    byte[] openInPlace(byte[] nonce, byte[] aad, byte[] ciphertext) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(suite.getAeadTransformation());
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, suite.getAeadKeyAlgorithm()), parameterSpec(nonce));
        cipher.updateAAD(aad);
        try {
            return cipher.doFinal(ciphertext);
        } catch (AEADBadTagException e) {
            return null;
        }
    }

    private AlgorithmParameterSpec parameterSpec(byte[] nonce) {
        return hasExplicitNonce() ? new GCMParameterSpec(TAG_LENGTH * 8, nonce) : new IvParameterSpec(nonce);
    }

}
