/*
 * PacketProtectionCipherCachingTest.java
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

import java.security.SecureRandom;

import org.junit.Test;

import org.bluezoo.gumdrop.quic.tls.Hkdf;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for issue #308: {@code PacketProtection} called
 * {@code Cipher.getInstance(...)} fresh on every seal/open/header-protection
 * operation, rather than caching one {@code Cipher} per algorithm on the
 * {@link PacketProtectionKeys} instance it operates on and re-{@code init}ing
 * it per use.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class PacketProtectionCipherCachingTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /**
     * A realistic per-packet workload -- seal plus header-protection mask
     * on send, open plus header-protection mask on receive, the exact
     * four operations the issue calls out -- repeated many times against
     * a single {@link PacketProtectionKeys} instance (one key phase, as
     * would be used for an entire connection's worth of packets at a
     * given encryption level). Asserts the cumulative cost stays far
     * below what fresh {@code Cipher.getInstance} calls on every
     * operation would take.
     */
    @Test(timeout = 15000)
    public void testRepeatedPacketOperationsAvoidPerCallCipherInstantiation() throws Exception {
        PacketProtectionKeys keys = PacketProtectionKeys.derive(
                Hkdf.sha256(), randomBytes(32), QuicAeadAlgorithm.AES_128_GCM);
        byte[] header = randomBytes(20);
        byte[] plaintext = randomBytes(100);
        byte[] sample = randomBytes(QuicAeadAlgorithm.SAMPLE_LENGTH);

        int iterations = 100000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            byte[] ciphertext = PacketProtection.seal(keys, i, header, plaintext);
            PacketProtection.headerProtectionMask(keys, sample);
            byte[] recovered = PacketProtection.open(keys, i, header, ciphertext);
            PacketProtection.headerProtectionMask(keys, sample);
            assertArrayEquals(plaintext, recovered);
        }
        long elapsedMs = (System.nanoTime() - start) / 1000000;
        assertTrue(iterations + " seal/mask/open/mask cycles took " + elapsedMs
                + "ms -- expected a Cipher cached per PacketProtectionKeys instance to keep this "
                + "far below the cost of Cipher.getInstance on every operation",
                elapsedMs < 600);
    }

    /**
     * Same AEAD-only workload for ChaCha20-Poly1305 -- seal and open,
     * without header-protection mask calls, since ChaCha20's
     * header-protection Cipher is deliberately left uncached (see
     * {@link PacketProtection#headerProtectionMask}'s own comment: the
     * JDK's raw ChaCha20 Cipher rejects an exact key/nonce repeat across
     * consecutive calls, which an ordinary duplicated UDP datagram would
     * trigger if that Cipher were reused). The AEAD transformation is a
     * separate Cipher from the header-protection one and is cached
     * exactly like the AES case.
     */
    @Test(timeout = 15000)
    public void testRepeatedAeadOperationsAvoidPerCallCipherInstantiationChaCha20() throws Exception {
        PacketProtectionKeys keys = PacketProtectionKeys.derive(
                Hkdf.sha256(), randomBytes(32), QuicAeadAlgorithm.CHACHA20_POLY1305);
        byte[] header = randomBytes(20);
        byte[] plaintext = randomBytes(100);

        int iterations = 400000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            byte[] ciphertext = PacketProtection.seal(keys, i, header, plaintext);
            byte[] recovered = PacketProtection.open(keys, i, header, ciphertext);
            assertArrayEquals(plaintext, recovered);
        }
        long elapsedMs = (System.nanoTime() - start) / 1000000;
        assertTrue(iterations + " seal/open cycles took " + elapsedMs
                + "ms -- expected the AEAD Cipher cached per PacketProtectionKeys instance to keep this "
                + "far below the cost of Cipher.getInstance on every operation",
                elapsedMs < 1400);
    }

    /**
     * Two independent {@link PacketProtectionKeys} instances (e.g. two
     * different encryption levels, or a key update to a new phase) must
     * cache and use independent {@code Cipher}s -- reusing one key's
     * cached cipher for another's operations would be a correctness bug,
     * not just a performance one.
     */
    @Test
    public void testIndependentKeysDoNotShareCachedCipherState() throws Exception {
        PacketProtectionKeys keysA = PacketProtectionKeys.derive(
                Hkdf.sha256(), randomBytes(32), QuicAeadAlgorithm.AES_128_GCM);
        PacketProtectionKeys keysB = PacketProtectionKeys.derive(
                Hkdf.sha256(), randomBytes(32), QuicAeadAlgorithm.AES_128_GCM);
        byte[] header = randomBytes(20);
        byte[] plaintext = randomBytes(100);

        byte[] ciphertextA1 = PacketProtection.seal(keysA, 0, header, plaintext);
        byte[] ciphertextB = PacketProtection.seal(keysB, 0, header, plaintext);
        byte[] ciphertextA2 = PacketProtection.seal(keysA, 1, header, plaintext);

        byte[] recoveredA1 = PacketProtection.open(keysA, 0, header, ciphertextA1);
        byte[] recoveredB = PacketProtection.open(keysB, 0, header, ciphertextB);
        byte[] recoveredA2 = PacketProtection.open(keysA, 1, header, ciphertextA2);

        assertArrayEquals(plaintext, recoveredA1);
        assertArrayEquals(plaintext, recoveredB);
        assertArrayEquals(plaintext, recoveredA2);
    }
}
