/*
 * DirectionalKeys.java
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

import org.bluezoo.gumdrop.crypto.Hkdf;

/**
 * One direction's TLS 1.3 record-layer AEAD state (RFC 8446 section 5.3):
 * key, IV, and the running sequence number the per-record nonce is
 * derived from. RFC 8446's AEADs (unlike TLS 1.2's GCM suites) all share
 * the same nonce construction -- IV XOR sequence number, no explicit
 * per-record nonce on the wire -- so only the key type varies between
 * suites, not the framing.
 *
 * <p>Package-private: an implementation detail of {@link TlsRecordEngine}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8446#section-5.3">RFC 8446 section 5.3</a>
 */
final class DirectionalKeys {

    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH = 16;

    /**
     * RFC 8446 section 5.5 / RFC 9325 section 4.4: an AES-GCM key SHOULD
     * be retired after protecting 2^24.5 (approximately 23,726,566)
     * full-size records -- the point where the AEAD's own
     * authenticated-encryption security margin starts to erode, well
     * before the sequence number could ever wrap. ChaCha20-Poly1305 has
     * no analogous limit here: its 64-bit sequence number would wrap
     * before that cipher's own safety bound is reached.
     */
    private static final long AES_GCM_CONFIDENTIALITY_LIMIT = 23_726_566L;

    private final CipherSuite suite;
    private final byte[] key;
    private final byte[] iv;
    private final SecretKeySpec keySpec;
    private final Cipher encryptCipher;
    private final Cipher decryptCipher;
    private final byte[] nonceScratch = new byte[NONCE_LENGTH];

    // Package-private and mutable: TlsRecordEngineTest fast-forwards this
    // directly to exercise the confidentiality-limit rotation without
    // actually protecting 23 million records.
    long seq;

    private DirectionalKeys(CipherSuite suite, byte[] key, byte[] iv) {
        this.suite = suite;
        this.key = key;
        this.iv = iv;
        this.keySpec = new SecretKeySpec(key, suite.getAeadKeyAlgorithm());
        try {
            String transformation = suite.getAeadTransformation();
            this.encryptCipher = Cipher.getInstance(transformation);
            this.decryptCipher = Cipher.getInstance(transformation);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not initialize AEAD ciphers", e);
        }
    }

    /**
     * Derives one direction's key and IV from a traffic secret (RFC 8446
     * section 7.3): {@code key = HKDF-Expand-Label(Secret, "key", "",
     * key_length)}, {@code iv = HKDF-Expand-Label(Secret, "iv", "", 12)}.
     * The sequence number starts at 0.
     *
     * @param suite the negotiated cipher suite
     * @param secret the traffic secret for this direction
     * @return the derived directional keys
     */
    static DirectionalKeys fromSecret(CipherSuite suite, byte[] secret) {
        Hkdf hkdf = suite.newHkdf();
        byte[] key = hkdf.expandLabel(secret, "key", new byte[0], suite.getAeadKeyLength());
        byte[] iv = hkdf.expandLabel(secret, "iv", new byte[0], NONCE_LENGTH);
        return new DirectionalKeys(suite, key, iv);
    }

    /**
     * Returns the nonce for the current sequence number: {@link #iv} XOR
     * the sequence number as 8 big-endian octets in the last 8 bytes.
     * The returned array is scratch space reused across calls on this
     * direction; consume it before the next {@link #nonce()} or record
     * operation on the same {@code DirectionalKeys} instance.
     *
     * @return the 12-byte nonce scratch buffer
     */
    byte[] nonce() {
        System.arraycopy(iv, 0, nonceScratch, 0, NONCE_LENGTH);
        for (int i = 0; i < 8; i++) {
            nonceScratch[4 + i] ^= (byte) (seq >>> (56 - 8 * i));
        }
        return nonceScratch;
    }

    /** Advances to the next record's sequence number. */
    void advance() {
        seq++;
    }

    /**
     * Returns whether this direction has protected enough records under
     * its current key to warrant retiring it (RFC 8446 section 5.5).
     *
     * @return true if the confidentiality limit has been reached
     */
    boolean overConfidentialityLimit() {
        return suite != CipherSuite.TLS_CHACHA20_POLY1305_SHA256 && seq >= AES_GCM_CONFIDENTIALITY_LIMIT;
    }

    /**
     * Seals {@code plaintext} in place, appending the authentication tag.
     *
     * @param nonce the per-record nonce, from {@link #nonce}
     * @param aad the additional authenticated data (the record's outer header)
     * @param plaintext the plaintext to seal
     * @return the ciphertext with the tag appended
     */
    byte[] sealAppendTag(byte[] nonce, byte[] aad, byte[] plaintext) throws GeneralSecurityException {
        return sealAppendTag(nonce, aad, 0, aad.length, plaintext, 0, plaintext.length);
    }

    byte[] sealAppendTag(byte[] nonce, byte[] aad, int aadOffset, int aadLength,
            byte[] plaintext, int plaintextOffset, int plaintextLength) throws GeneralSecurityException {
        encryptCipher.init(Cipher.ENCRYPT_MODE, keySpec, parameterSpec(nonce));
        encryptCipher.updateAAD(aad, aadOffset, aadLength);
        return encryptCipher.doFinal(plaintext, plaintextOffset, plaintextLength);
    }

    /**
     * Opens {@code ciphertext} (with its trailing authentication tag),
     * returning the verified plaintext.
     *
     * @param nonce the per-record nonce, from {@link #nonce}
     * @param aad the additional authenticated data (the record's outer header)
     * @param ciphertext the ciphertext, tag included
     * @return the plaintext, or null if the tag does not verify
     */
    byte[] openInPlace(byte[] nonce, byte[] aad, byte[] ciphertext) throws GeneralSecurityException {
        return openInPlace(nonce, aad, 0, aad.length, ciphertext, 0, ciphertext.length);
    }

    byte[] openInPlace(byte[] nonce, byte[] aad, int aadOffset, int aadLength,
            byte[] ciphertext, int ciphertextOffset, int ciphertextLength) throws GeneralSecurityException {
        decryptCipher.init(Cipher.DECRYPT_MODE, keySpec, parameterSpec(nonce));
        decryptCipher.updateAAD(aad, aadOffset, aadLength);
        try {
            return decryptCipher.doFinal(ciphertext, ciphertextOffset, ciphertextLength);
        } catch (AEADBadTagException e) {
            return null;
        }
    }

    private AlgorithmParameterSpec parameterSpec(byte[] nonce) {
        return (suite == CipherSuite.TLS_CHACHA20_POLY1305_SHA256)
                ? new IvParameterSpec(nonce)
                : new GCMParameterSpec(TAG_LENGTH * 8, nonce);
    }

}
