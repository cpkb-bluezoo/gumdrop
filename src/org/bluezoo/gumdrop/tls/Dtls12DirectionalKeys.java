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
    final int epoch;

    long seq;

    private Dtls12DirectionalKeys(Tls12CipherSuite suite, byte[] key, byte[] fixedIv, int epoch) {
        this.suite = suite;
        this.key = key;
        this.fixedIv = fixedIv;
        this.epoch = epoch;
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
        byte[] n = new byte[12];
        System.arraycopy(fixedIv, 0, n, 0, 4);
        System.arraycopy(explicitNonce, 0, n, 4, 8);
        return n;
    }

    private byte[] chachaNonce() {
        byte[] n = fixedIv.clone();
        byte[] seqBytes = combinedSeqBytes();
        for (int i = 0; i < 8; i++) {
            n[4 + i] ^= seqBytes[i];
        }
        return n;
    }

    byte[] combinedSeqBytes() {
        byte[] b = new byte[8];
        b[0] = (byte) ((epoch >> 8) & 0xff);
        b[1] = (byte) (epoch & 0xff);
        for (int i = 0; i < 6; i++) {
            b[2 + i] = (byte) (seq >>> (40 - 8 * i));
        }
        return b;
    }

    byte[] additionalData(int contentType, int plaintextLen) {
        byte[] aad = new byte[13];
        byte[] combined = combinedSeqBytes();
        System.arraycopy(combined, 0, aad, 0, 8);
        aad[8] = (byte) contentType;
        aad[9] = (byte) 0xfe;
        aad[10] = (byte) 0xfd;
        aad[11] = (byte) ((plaintextLen >> 8) & 0xff);
        aad[12] = (byte) (plaintextLen & 0xff);
        return aad;
    }

    void advance() {
        seq++;
    }

    boolean overConfidentialityLimit() {
        return hasExplicitNonce() && seq >= AES_GCM_CONFIDENTIALITY_LIMIT;
    }

    byte[] sealAppendTag(byte[] nonce, byte[] aad, byte[] plaintext) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(suite.getAeadTransformation());
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, suite.getAeadKeyAlgorithm()), parameterSpec(nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(plaintext);
    }

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
