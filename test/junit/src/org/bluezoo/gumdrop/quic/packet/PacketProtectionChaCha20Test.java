/*
 * PacketProtectionChaCha20Test.java
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

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import org.junit.Test;

import org.bluezoo.gumdrop.quic.tls.Hkdf;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

/**
 * Round-trip verification for {@link QuicAeadAlgorithm#CHACHA20_POLY1305}
 * (RFC 9001 section 5.4.4). Unlike {@link PacketProtectionRfc9001Test},
 * RFC 9001 Appendix A publishes worked examples for AES-128-GCM Initial
 * secrets only -- there is no equivalent published ChaCha20 packet vector
 * to check byte-for-byte, so this instead verifies internal consistency
 * (seal/open and header-protection apply/remove both round-trip correctly,
 * and a tampered ciphertext is rejected) the same way AES-GCM support was
 * exercised before the RFC 9001 vectors were added to this suite.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class PacketProtectionChaCha20Test {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    @Test
    public void testDerivedKeySizes() {
        PacketProtectionKeys keys = PacketProtectionKeys.derive(
                Hkdf.sha256(), randomBytes(32), QuicAeadAlgorithm.CHACHA20_POLY1305);
        assertEquals(32, keys.getAeadKey().getEncoded().length);
        assertEquals("ChaCha20", keys.getAeadKey().getAlgorithm());
        assertEquals(QuicAeadAlgorithm.IV_LENGTH, keys.getIv().length);
        assertEquals(32, keys.getHeaderProtectionKey().getEncoded().length);
    }

    @Test
    public void testSealOpenRoundTrip() throws PacketProtectionException {
        PacketProtectionKeys keys = PacketProtectionKeys.derive(
                Hkdf.sha256(), randomBytes(32), QuicAeadAlgorithm.CHACHA20_POLY1305);

        byte[] header = randomBytes(20);
        byte[] plaintext = "a QUIC frame payload, protected with ChaCha20-Poly1305"
                .getBytes(StandardCharsets.US_ASCII);
        long packetNumber = 42;

        byte[] ciphertext = PacketProtection.seal(keys, packetNumber, header, plaintext);
        assertEquals(plaintext.length + QuicAeadAlgorithm.TAG_LENGTH, ciphertext.length);

        byte[] recovered = PacketProtection.open(keys, packetNumber, header, ciphertext);
        assertArrayEquals(plaintext, recovered);
    }

    @Test
    public void testOpenRejectsTamperedCiphertext() throws PacketProtectionException {
        PacketProtectionKeys keys = PacketProtectionKeys.derive(
                Hkdf.sha256(), randomBytes(32), QuicAeadAlgorithm.CHACHA20_POLY1305);

        byte[] header = randomBytes(20);
        byte[] plaintext = "authenticated data must not be tamperable".getBytes(StandardCharsets.US_ASCII);
        byte[] ciphertext = PacketProtection.seal(keys, 7, header, plaintext);
        ciphertext[0] ^= 0x01;

        try {
            PacketProtection.open(keys, 7, header, ciphertext);
            fail("Expected PacketProtectionException for a tampered ciphertext");
        } catch (PacketProtectionException expected) {
            // AEAD authentication correctly rejected the tampered ciphertext.
        }
    }

    @Test
    public void testHeaderProtectionMaskRoundTrip() throws PacketProtectionException {
        PacketProtectionKeys keys = PacketProtectionKeys.derive(
                Hkdf.sha256(), randomBytes(32), QuicAeadAlgorithm.CHACHA20_POLY1305);

        int pnOffset = 18;
        int pnLength = 4;
        boolean longHeader = true;

        byte[] header = randomBytes(pnOffset);
        header[0] = (byte) (0xc0 | (header[0] & 0x30)); // long-header form bits set, low nibble arbitrary
        byte[] plaintext = randomBytes(64);
        byte[] ciphertext = PacketProtection.seal(keys, 5, header, plaintext);

        byte[] packet = new byte[header.length + ciphertext.length];
        System.arraycopy(header, 0, packet, 0, header.length);
        System.arraycopy(ciphertext, 0, packet, header.length, ciphertext.length);
        byte[] originalPacket = packet.clone();

        byte[] sample = Arrays.copyOfRange(packet, pnOffset + 4, pnOffset + 4 + QuicAeadAlgorithm.SAMPLE_LENGTH);
        byte[] mask = PacketProtection.headerProtectionMask(keys, sample);
        assertEquals(5, mask.length);

        PacketProtection.xorFirstByte(packet, mask, longHeader);
        PacketProtection.xorPacketNumberBytes(packet, pnOffset, pnLength, mask);
        assertFalse("Header protection should have changed the protected bytes",
                Arrays.equals(originalPacket, packet));

        // Removing protection with the same mask (XOR is its own inverse)
        // must recover the original packet exactly.
        PacketProtection.xorFirstByte(packet, mask, longHeader);
        PacketProtection.xorPacketNumberBytes(packet, pnOffset, pnLength, mask);
        assertArrayEquals(originalPacket, packet);
    }

    @Test
    public void testHeaderProtectionMaskIsDeterministic() throws PacketProtectionException {
        PacketProtectionKeys keys = PacketProtectionKeys.derive(
                Hkdf.sha256(), randomBytes(32), QuicAeadAlgorithm.CHACHA20_POLY1305);
        byte[] sample = randomBytes(QuicAeadAlgorithm.SAMPLE_LENGTH);

        byte[] mask1 = PacketProtection.headerProtectionMask(keys, sample);
        byte[] mask2 = PacketProtection.headerProtectionMask(keys, sample);
        assertArrayEquals(mask1, mask2);
    }
}
