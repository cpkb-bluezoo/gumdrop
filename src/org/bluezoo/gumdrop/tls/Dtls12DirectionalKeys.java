/*
 * Dtls12DirectionalKeys.java
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
 * One direction's DTLS 1.2 record-layer AEAD state (RFC 6347 section 4.1.2).
 * Sibling of {@link Tls12DirectionalKeys} with epoch-aware sequence numbers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class Dtls12DirectionalKeys {

    private static final int TAG_LENGTH = 16;
    private static final long AES_GCM_CONFIDENTIALITY_LIMIT = 23_726_566L;

    private final Tls12CipherSuite suite;
    private final byte[] key;
    private final byte[] fixedIv;
    private final SecretKeySpec keySpec;
    private final Cipher encryptCipher;
    private final Cipher decryptCipher;
    private final byte[] nonceScratch = new byte[12];
    private final byte[] combinedSeqBytesScratch = new byte[8];
    private final byte[] aadScratch = new byte[13];

    final int epoch;

    long seq;

    private Dtls12DirectionalKeys(Tls12CipherSuite suite, byte[] key, byte[] fixedIv, int epoch) {
        this.suite = suite;
        this.key = key;
        this.fixedIv = fixedIv;
        this.epoch = epoch;
        this.keySpec = new SecretKeySpec(key, suite.getAeadKeyAlgorithm());
        try {
            String transformation = suite.getAeadTransformation();
            this.encryptCipher = Cipher.getInstance(transformation);
            this.decryptCipher = Cipher.getInstance(transformation);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not initialize AEAD ciphers", e);
        }
    }

    static Dtls12DirectionalKeys fromMaterial(Tls12CipherSuite suite, DirectionalKeyMaterial material, int epoch) {
        return new Dtls12DirectionalKeys(suite, material.key, material.fixedIv, epoch);
    }

    long combinedSeq() {
        return ((long) epoch << 48) | (seq & 0x0000FFFFFFFFFFFFL);
    }

    boolean hasExplicitNonce() {
        return fixedIv.length == 4;
    }

    byte[] localNonce() {
        return hasExplicitNonce() ? gcmNonce(combinedSeqBytes()) : chachaNonce();
    }

    byte[] nonceFromWire(byte[] explicitNonce) {
        return hasExplicitNonce() ? gcmNonce(explicitNonce) : chachaNonce();
    }

    private byte[] gcmNonce(byte[] explicitNonce) {
        System.arraycopy(fixedIv, 0, nonceScratch, 0, 4);
        System.arraycopy(explicitNonce, 0, nonceScratch, 4, 8);
        return nonceScratch;
    }

    private byte[] chachaNonce() {
        System.arraycopy(fixedIv, 0, nonceScratch, 0, fixedIv.length);
        byte[] seqBytes = combinedSeqBytes();
        for (int i = 0; i < 8; i++) {
            nonceScratch[4 + i] ^= seqBytes[i];
        }
        return nonceScratch;
    }

    byte[] combinedSeqBytes() {
        combinedSeqBytesScratch[0] = (byte) ((epoch >> 8) & 0xff);
        combinedSeqBytesScratch[1] = (byte) (epoch & 0xff);
        for (int i = 0; i < 6; i++) {
            combinedSeqBytesScratch[2 + i] = (byte) (seq >>> (40 - 8 * i));
        }
        return combinedSeqBytesScratch;
    }

    byte[] additionalData(int contentType, int plaintextLen) {
        byte[] combined = combinedSeqBytes();
        System.arraycopy(combined, 0, aadScratch, 0, 8);
        aadScratch[8] = (byte) contentType;
        aadScratch[9] = (byte) 0xfe;
        aadScratch[10] = (byte) 0xfd;
        aadScratch[11] = (byte) ((plaintextLen >> 8) & 0xff);
        aadScratch[12] = (byte) (plaintextLen & 0xff);
        return aadScratch;
    }

    void advance() {
        seq++;
    }

    boolean overConfidentialityLimit() {
        return hasExplicitNonce() && seq >= AES_GCM_CONFIDENTIALITY_LIMIT;
    }

    byte[] sealAppendTag(byte[] nonce, byte[] aad, byte[] plaintext) throws GeneralSecurityException {
        return sealAppendTag(nonce, aad, plaintext, 0, plaintext.length);
    }

    byte[] sealAppendTag(byte[] nonce, byte[] aad, byte[] plaintext, int offset, int length)
            throws GeneralSecurityException {
        encryptCipher.init(Cipher.ENCRYPT_MODE, keySpec, parameterSpec(nonce));
        encryptCipher.updateAAD(aad);
        return encryptCipher.doFinal(plaintext, offset, length);
    }

    byte[] openInPlace(byte[] nonce, byte[] aad, byte[] ciphertext) throws GeneralSecurityException {
        decryptCipher.init(Cipher.DECRYPT_MODE, keySpec, parameterSpec(nonce));
        decryptCipher.updateAAD(aad);
        try {
            return decryptCipher.doFinal(ciphertext);
        } catch (AEADBadTagException e) {
            return null;
        }
    }

    private AlgorithmParameterSpec parameterSpec(byte[] nonce) {
        return hasExplicitNonce() ? new GCMParameterSpec(TAG_LENGTH * 8, nonce) : new IvParameterSpec(nonce);
    }

}
