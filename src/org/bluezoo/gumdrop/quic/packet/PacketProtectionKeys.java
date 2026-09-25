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
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.ChaCha20ParameterSpec;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.bluezoo.gumdrop.crypto.Hkdf;

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
 * <p>The key material is immutable. Each {@link PacketProtection#seal}/
 * {@link PacketProtection#open} call allocates its own {@link Cipher}
 * because {@code QuicConnection} can reenter packet protection on the
 * same instance before a previous operation finishes; a shared mutable
 * {@code Cipher} corrupts concurrent or reentrant use (issue #365).
 * Per-instance scratch buffers are reused for nonce and header-mask
 * construction only.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-5.1">RFC 9001 section 5.1</a>
 */
public final class PacketProtectionKeys {

    private static final byte[] EMPTY_CONTEXT = new byte[0];
    private static final byte[] HEADER_MASK_ZEROS = new byte[5];

    private final QuicAeadAlgorithm algorithm;
    private final SecretKeySpec aeadKey;
    private final byte[] iv;
    private final SecretKeySpec headerProtectionKey;

    private final byte[] headerMaskScratch = new byte[5];
    private final byte[] lastChachaHpSample = new byte[QuicAeadAlgorithm.SAMPLE_LENGTH];
    private boolean chachaHpMaskCached;

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
     * @param version the QUIC version, which selects the HKDF label prefix
     *                (RFC 9369 section 3.3.2)
     * @return the derived keys
     */
    public static PacketProtectionKeys derive(Hkdf hkdf, byte[] secret, QuicAeadAlgorithm algorithm,
            QuicVersion version) {
        int keyLength = algorithm.getKeyLength();
        String prefix = version.getLabelPrefix();
        byte[] keyBytes = hkdf.expandLabel(secret, prefix + " key", EMPTY_CONTEXT, keyLength);
        byte[] ivBytes = hkdf.expandLabel(secret, prefix + " iv", EMPTY_CONTEXT, QuicAeadAlgorithm.IV_LENGTH);
        byte[] hpBytes = hkdf.expandLabel(secret, prefix + " hp", EMPTY_CONTEXT, keyLength);

        SecretKeySpec aeadKey = new SecretKeySpec(keyBytes, algorithm.getKeyAlgorithm());
        SecretKeySpec headerProtectionKey = new SecretKeySpec(hpBytes, algorithm.getKeyAlgorithm());
        return new PacketProtectionKeys(algorithm, aeadKey, ivBytes, headerProtectionKey);
    }

    /**
     * Derives DTLS 1.3 record protection keys (RFC 9147 section 5.9).
     *
     * @param hkdf the HKDF instance for the negotiated hash
     * @param secret the traffic secret for this direction and epoch
     * @param algorithm the negotiated AEAD algorithm
     * @return the derived keys
     */
    public static PacketProtectionKeys deriveForDtls(Hkdf hkdf, byte[] secret, QuicAeadAlgorithm algorithm) {
        byte[] prefix = Hkdf.dtls13LabelPrefix();
        int keyLength = algorithm.getKeyLength();
        byte[] keyBytes = hkdf.expandLabelWithPrefix(prefix, secret, "key", EMPTY_CONTEXT, keyLength);
        byte[] ivBytes = hkdf.expandLabelWithPrefix(prefix, secret, "iv", EMPTY_CONTEXT, QuicAeadAlgorithm.IV_LENGTH);
        byte[] hpBytes = hkdf.expandLabelWithPrefix(prefix, secret, "hp", EMPTY_CONTEXT, keyLength);
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
     * Seals (encrypts and authenticates) a packet payload (RFC 9001 section 5.3).
     *
     * @param packetNumber the full packet number, used to construct the nonce
     * @param associatedData the packet header, unprotected, used as AAD
     * @param plaintext the unprotected frame bytes
     * @return the ciphertext, {@code plaintext.length + 16} bytes
     * @throws PacketProtectionException if sealing fails
     */
    public byte[] seal(long packetNumber, byte[] associatedData, byte[] plaintext)
            throws PacketProtectionException {
        byte[] nonce = PacketProtection.computeNonce(iv, packetNumber);
        try {
            Cipher cipher = Cipher.getInstance(algorithm.getAeadTransformation());
            cipher.init(Cipher.ENCRYPT_MODE, aeadKey, aeadParameterSpec(algorithm, nonce));
            cipher.updateAAD(associatedData);
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new PacketProtectionException("AEAD seal failed", e);
        }
    }

    /**
     * Opens (decrypts and verifies) a packet payload (RFC 9001 section 5.3).
     *
     * @param packetNumber the full (reconstructed) packet number
     * @param associatedData the packet header, unprotected, as sent by the peer
     * @param ciphertext the received ciphertext, including its 16-byte tag
     * @return the recovered plaintext frame bytes
     * @throws PacketProtectionException if authentication fails
     */
    public byte[] open(long packetNumber, byte[] associatedData, byte[] ciphertext)
            throws PacketProtectionException {
        return open(packetNumber, associatedData, 0, associatedData.length, ciphertext, 0, ciphertext.length);
    }

    /**
     * Opens (decrypts and verifies) a packet payload (RFC 9001 section
     * 5.3), reading the AAD and ciphertext directly out of caller-owned
     * arrays instead of requiring pre-sliced copies -- the receive path
     * already holds both regions contiguously in the same {@code
     * byte[]} (the header, then the ciphertext, immediately after), so
     * this avoids two {@code Arrays.copyOfRange} calls per received
     * packet.
     *
     * @param packetNumber the full (reconstructed) packet number
     * @param aad the array holding the packet header, unprotected, as sent by the peer
     * @param aadOffset the start of the header within {@code aad}
     * @param aadLength the header's length
     * @param ciphertext the array holding the received ciphertext, tag included
     * @param ciphertextOffset the start of the ciphertext within {@code ciphertext}
     * @param ciphertextLength the ciphertext's length, tag included
     * @return the recovered plaintext frame bytes
     * @throws PacketProtectionException if authentication fails
     */
    public byte[] open(long packetNumber, byte[] aad, int aadOffset, int aadLength,
            byte[] ciphertext, int ciphertextOffset, int ciphertextLength) throws PacketProtectionException {
        byte[] nonce = PacketProtection.computeNonce(iv, packetNumber);
        try {
            Cipher cipher = Cipher.getInstance(algorithm.getAeadTransformation());
            cipher.init(Cipher.DECRYPT_MODE, aeadKey, aeadParameterSpec(algorithm, nonce));
            cipher.updateAAD(aad, aadOffset, aadLength);
            return cipher.doFinal(ciphertext, ciphertextOffset, ciphertextLength);
        } catch (GeneralSecurityException e) {
            throw new PacketProtectionException("AEAD open failed", e);
        }
    }

    /**
     * Computes the 5-byte header-protection mask from a ciphertext sample.
     *
     * @param sample the 16-byte ciphertext sample (RFC 9001 section 5.4.2)
     * @return the 5-byte mask scratch buffer (reused across calls on this instance)
     * @throws PacketProtectionException if the mask computation fails
     */
    public byte[] headerProtectionMask(byte[] sample) throws PacketProtectionException {
        if (sample.length != QuicAeadAlgorithm.SAMPLE_LENGTH) {
            throw new PacketProtectionException(
                    "Header protection sample must be " + QuicAeadAlgorithm.SAMPLE_LENGTH
                    + " bytes, got " + sample.length);
        }
        return headerProtectionMask(sample, 0);
    }

    /**
     * Computes the 5-byte header-protection mask from a ciphertext
     * sample read directly out of a caller-owned array at {@code
     * sampleOffset} -- the receive path already holds the sample as a
     * 16-byte window into the full packet array, so this avoids a
     * dedicated copy of just that window per received packet.
     *
     * @param sample the array holding the 16-byte ciphertext sample (RFC 9001 section 5.4.2)
     * @param sampleOffset the sample's start offset within {@code sample};
     *                     {@code sample.length - sampleOffset} must be at
     *                     least {@link QuicAeadAlgorithm#SAMPLE_LENGTH}
     * @return the 5-byte mask scratch buffer (reused across calls on this instance)
     * @throws PacketProtectionException if the mask computation fails
     */
    public byte[] headerProtectionMask(byte[] sample, int sampleOffset) throws PacketProtectionException {
        try {
            if (algorithm == QuicAeadAlgorithm.CHACHA20_POLY1305) {
                if (chachaHpMaskCached && Arrays.equals(sample, sampleOffset,
                        sampleOffset + QuicAeadAlgorithm.SAMPLE_LENGTH, lastChachaHpSample, 0,
                        QuicAeadAlgorithm.SAMPLE_LENGTH)) {
                    return headerMaskScratch;
                }
                int counter = (sample[sampleOffset] & 0xff) | ((sample[sampleOffset + 1] & 0xff) << 8)
                        | ((sample[sampleOffset + 2] & 0xff) << 16) | ((sample[sampleOffset + 3] & 0xff) << 24);
                byte[] hpNonce = Arrays.copyOfRange(sample, sampleOffset + 4, sampleOffset + 16);
                Cipher cipher = Cipher.getInstance(algorithm.getHeaderProtectionTransformation());
                cipher.init(Cipher.ENCRYPT_MODE, headerProtectionKey,
                        new ChaCha20ParameterSpec(hpNonce, counter));
                byte[] mask = cipher.doFinal(HEADER_MASK_ZEROS);
                System.arraycopy(mask, 0, headerMaskScratch, 0, mask.length);
                System.arraycopy(sample, sampleOffset, lastChachaHpSample, 0, QuicAeadAlgorithm.SAMPLE_LENGTH);
                chachaHpMaskCached = true;
                return headerMaskScratch;
            }
            Cipher cipher = Cipher.getInstance(algorithm.getHeaderProtectionTransformation());
            cipher.init(Cipher.ENCRYPT_MODE, headerProtectionKey);
            byte[] block = cipher.doFinal(sample, sampleOffset, QuicAeadAlgorithm.SAMPLE_LENGTH);
            System.arraycopy(block, 0, headerMaskScratch, 0, headerMaskScratch.length);
            return headerMaskScratch;
        } catch (GeneralSecurityException e) {
            throw new PacketProtectionException("Header protection mask computation failed", e);
        }
    }

    private static AlgorithmParameterSpec aeadParameterSpec(QuicAeadAlgorithm algorithm, byte[] nonce) {
        return algorithm == QuicAeadAlgorithm.CHACHA20_POLY1305
                ? new IvParameterSpec(nonce)
                : new GCMParameterSpec(QuicAeadAlgorithm.TAG_LENGTH * 8, nonce);
    }
}
