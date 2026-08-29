/*
 * PacketProtection.java
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

import javax.crypto.Cipher;
import javax.crypto.spec.ChaCha20ParameterSpec;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;

/**
 * AEAD packet protection and header protection for QUIC (RFC 9001
 * sections 5.3-5.4).
 *
 * <p>Every method is a pure, stateless transformation over the keys and
 * bytes it is given -- there is no per-connection state here, so all of
 * it may be called directly on the {@code SelectorLoop} thread.
 *
 * <h2>Header protection sequencing</h2>
 *
 * <p>Applying header protection (sending) and removing it (receiving)
 * both reduce to XOR-ing the same mask from {@link #headerProtectionMask}
 * into the first byte and packet-number field, via {@link #xorFirstByte}
 * and {@link #xorPacketNumberBytes} -- but the two directions must call
 * them in different sequence:
 * <ul>
 * <li>Sending: the packet number length is already known (the sender
 *     chose it), so both calls can be made back to back.</li>
 * <li>Receiving: the packet number length is <em>not</em> known until
 *     the low bits of the first byte have been unmasked, so
 *     {@link #xorFirstByte} must be called alone first, the real length
 *     read from its result, and only then should
 *     {@link #xorPacketNumberBytes} be called with that length.</li>
 * </ul>
 * The two methods are deliberately separate rather than one combined
 * operation, since combining them would XOR the first byte a second time
 * on the receive path if both phases were driven through it.
 * The header-protection sample is always taken from a fixed offset
 * relative to the start of the packet-number field (RFC 9001
 * section 5.4.2), which is why the sample can be read before the packet
 * number length is known.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-5.3">RFC 9001 section 5.3</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-5.4">RFC 9001 section 5.4</a>
 */
public final class PacketProtection {

    /** RFC 9001 section 5.4.1: long-header packets protect the low 4 bits of the first byte. */
    public static final int LONG_HEADER_FIRST_BYTE_MASK = 0x0f;

    /** RFC 9001 section 5.4.1: short-header packets protect the low 5 bits of the first byte. */
    public static final int SHORT_HEADER_FIRST_BYTE_MASK = 0x1f;

    private PacketProtection() {
    }

    /**
     * Constructs the per-packet AEAD nonce (RFC 9001 section 5.3): the
     * IV with the packet number XORed into its low-order bytes, the
     * packet number treated as a big-endian, left-zero-padded value of
     * the same length as the IV.
     *
     * @param iv the 12-byte IV from {@link PacketProtectionKeys#getIv()}
     * @param packetNumber the full (reconstructed) packet number
     * @return the 12-byte nonce
     */
    public static byte[] computeNonce(byte[] iv, long packetNumber) {
        byte[] nonce = new byte[iv.length];
        System.arraycopy(iv, 0, nonce, 0, iv.length);
        for (int i = 0; i < 8; i++) {
            int shift = 8 * i;
            byte pnByte = (byte) ((packetNumber >>> shift) & 0xff);
            int nonceIndex = nonce.length - 1 - i;
            nonce[nonceIndex] ^= pnByte;
        }
        return nonce;
    }

    /**
     * Seals (encrypts and authenticates) a QUIC packet payload
     * (RFC 9001 section 5.3).
     *
     * @param keys the sender's packet-protection keys for this level
     * @param packetNumber the full packet number, used to construct the nonce
     * @param associatedData the packet header, unprotected, used as
     *                       additional authenticated data
     * @param plaintext the unprotected frame bytes
     * @return the ciphertext, {@code plaintext.length + 16} bytes
     * @throws PacketProtectionException if sealing fails
     */
    public static byte[] seal(PacketProtectionKeys keys, long packetNumber,
            byte[] associatedData, byte[] plaintext) throws PacketProtectionException {
        byte[] nonce = computeNonce(keys.getIv(), packetNumber);
        try {
            Cipher cipher = Cipher.getInstance(keys.getAlgorithm().getAeadTransformation());
            cipher.init(Cipher.ENCRYPT_MODE, keys.getAeadKey(), aeadParameterSpec(keys.getAlgorithm(), nonce));
            cipher.updateAAD(associatedData);
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new PacketProtectionException("AEAD seal failed", e);
        }
    }

    // JCE's "ChaCha20-Poly1305" transformation only accepts an
    // IvParameterSpec (its tag length is fixed at 16 bytes -- QUIC's own
    // requirement -- so there's nothing to configure); the AES-GCM
    // transformations need a GCMParameterSpec to carry the tag length
    // explicitly.
    private static AlgorithmParameterSpec aeadParameterSpec(QuicAeadAlgorithm algorithm, byte[] nonce) {
        return algorithm == QuicAeadAlgorithm.CHACHA20_POLY1305
                ? new IvParameterSpec(nonce)
                : new GCMParameterSpec(QuicAeadAlgorithm.TAG_LENGTH * 8, nonce);
    }

    /**
     * Opens (decrypts and verifies) a QUIC packet payload (RFC 9001
     * section 5.3).
     *
     * @param keys the receiver's packet-protection keys for this level
     * @param packetNumber the full (reconstructed) packet number, used
     *                     to construct the nonce
     * @param associatedData the packet header, unprotected, as sent by
     *                       the peer
     * @param ciphertext the received ciphertext, including its 16-byte tag
     * @return the recovered plaintext frame bytes
     * @throws PacketProtectionException if authentication fails or the
     *         ciphertext is otherwise malformed -- routine for a
     *         corrupted, spoofed, or stale-key packet, not necessarily
     *         an implementation bug
     */
    public static byte[] open(PacketProtectionKeys keys, long packetNumber,
            byte[] associatedData, byte[] ciphertext) throws PacketProtectionException {
        byte[] nonce = computeNonce(keys.getIv(), packetNumber);
        try {
            Cipher cipher = Cipher.getInstance(keys.getAlgorithm().getAeadTransformation());
            cipher.init(Cipher.DECRYPT_MODE, keys.getAeadKey(), aeadParameterSpec(keys.getAlgorithm(), nonce));
            cipher.updateAAD(associatedData);
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new PacketProtectionException("AEAD open failed", e);
        }
    }

    /**
     * Computes the 5-byte header-protection mask from a ciphertext
     * sample (RFC 9001 section 5.4.3 for AES, section 5.4.4 for ChaCha20).
     *
     * <p>For the two AES algorithms, only bytes 0-4 of the AES-ECB block
     * (a direct {@code Cipher.doFinal(sample)}) are used: byte 0 protects
     * the first byte of the packet, bytes 1-4 protect up to 4
     * packet-number bytes. ChaCha20 is structurally different -- not a
     * block-cipher encryption of the sample at all, but the ChaCha20
     * stream cipher applied to 5 zero bytes, keyed with the sample's
     * first 4 bytes (little-endian) as the initial block counter and its
     * last 12 bytes as the nonce; the 5-byte keystream output is already
     * exactly the mask, with nothing to truncate.
     *
     * @param keys the packet-protection keys holding the header-protection key
     * @param sample the 16-byte ciphertext sample (RFC 9001 section 5.4.2)
     * @return the 5-byte mask
     * @throws PacketProtectionException if the mask computation fails
     */
    public static byte[] headerProtectionMask(PacketProtectionKeys keys, byte[] sample)
            throws PacketProtectionException {
        if (sample.length != QuicAeadAlgorithm.SAMPLE_LENGTH) {
            throw new PacketProtectionException(
                    "Header protection sample must be " + QuicAeadAlgorithm.SAMPLE_LENGTH
                    + " bytes, got " + sample.length);
        }
        try {
            if (keys.getAlgorithm() == QuicAeadAlgorithm.CHACHA20_POLY1305) {
                int counter = (sample[0] & 0xff) | ((sample[1] & 0xff) << 8)
                        | ((sample[2] & 0xff) << 16) | ((sample[3] & 0xff) << 24);
                byte[] nonce = new byte[12];
                System.arraycopy(sample, 4, nonce, 0, 12);
                Cipher cipher = Cipher.getInstance(keys.getAlgorithm().getHeaderProtectionTransformation());
                cipher.init(Cipher.ENCRYPT_MODE, keys.getHeaderProtectionKey(),
                        new ChaCha20ParameterSpec(nonce, counter));
                return cipher.doFinal(new byte[5]);
            }
            Cipher cipher = Cipher.getInstance(keys.getAlgorithm().getHeaderProtectionTransformation());
            cipher.init(Cipher.ENCRYPT_MODE, keys.getHeaderProtectionKey());
            byte[] block = cipher.doFinal(sample);
            byte[] mask = new byte[5];
            System.arraycopy(block, 0, mask, 0, mask.length);
            return mask;
        } catch (GeneralSecurityException e) {
            throw new PacketProtectionException("Header protection mask computation failed", e);
        }
    }

    /**
     * XORs the header-protection mask into the first byte of an
     * assembled packet, in place (RFC 9001 section 5.4.1). Applying this
     * twice with the same mask restores the original byte, since XOR is
     * its own inverse -- see the class documentation for why sending and
     * receiving nonetheless call this (and {@link #xorPacketNumberBytes})
     * in different sequence.
     *
     * @param packet the full packet bytes; mutated in place
     * @param mask the 5-byte mask from {@link #headerProtectionMask}
     * @param longHeader true for a long-header packet (RFC 9000
     *                   section 17.2), false for a short-header packet
     *                   (RFC 9000 section 17.3)
     */
    public static void xorFirstByte(byte[] packet, byte[] mask, boolean longHeader) {
        int firstByteMask = longHeader ? LONG_HEADER_FIRST_BYTE_MASK : SHORT_HEADER_FIRST_BYTE_MASK;
        packet[0] ^= (byte) (mask[0] & firstByteMask);
    }

    /**
     * XORs the header-protection mask into the packet-number field of
     * an assembled packet, in place (RFC 9001 section 5.4.1).
     *
     * @param packet the full packet bytes; mutated in place
     * @param pnOffset the offset of the packet-number field within {@code packet}
     * @param pnLength the number of packet-number bytes to XOR (1-4)
     * @param mask the 5-byte mask from {@link #headerProtectionMask}
     */
    public static void xorPacketNumberBytes(byte[] packet, int pnOffset, int pnLength, byte[] mask) {
        for (int i = 0; i < pnLength; i++) {
            packet[pnOffset + i] ^= mask[1 + i];
        }
    }
}
