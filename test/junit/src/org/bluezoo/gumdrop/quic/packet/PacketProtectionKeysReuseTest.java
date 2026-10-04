/*
 * PacketProtectionKeysReuseTest.java
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

import org.bluezoo.gumdrop.crypto.Hkdf;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.fail;

/**
 * One {@link PacketProtectionKeys} protects every packet of its level and
 * direction, and keeps its cipher objects between packets rather than
 * making new ones each time. Whatever it has done before, it must give for
 * each packet exactly what a keys object used for nothing else gives.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class PacketProtectionKeysReuseTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static PacketProtectionKeys keys(QuicAeadAlgorithm algorithm, byte[] secret) {
        Hkdf hkdf = algorithm == QuicAeadAlgorithm.AES_256_GCM ? Hkdf.sha384() : Hkdf.sha256();
        return PacketProtectionKeys.derive(hkdf, secret, algorithm, QuicVersion.V1);
    }

    @Test
    public void testManyPacketsMatchFreshKeys() throws Exception {
        QuicAeadAlgorithm[] algorithms = QuicAeadAlgorithm.values();
        for (int a = 0; a < algorithms.length; a++) {
            QuicAeadAlgorithm algorithm = algorithms[a];
            byte[] secret = randomBytes(algorithm == QuicAeadAlgorithm.AES_256_GCM ? 48 : 32);
            PacketProtectionKeys reused = keys(algorithm, secret);
            for (int pn = 0; pn < 200; pn++) {
                byte[] header = randomBytes(12 + pn % 9);
                byte[] plaintext = randomBytes(20 + pn * 5);
                PacketProtectionKeys fresh = keys(algorithm, secret);

                byte[] sealed = reused.seal(pn, header, plaintext);
                assertArrayEquals(algorithm + " seal " + pn, fresh.seal(pn, header, plaintext), sealed);
                assertArrayEquals(algorithm + " open " + pn, plaintext, reused.open(pn, header, sealed));

                byte[] sample = randomBytes(QuicAeadAlgorithm.SAMPLE_LENGTH);
                byte[] expected = fresh.headerProtectionMask(sample).clone();
                assertArrayEquals(algorithm + " mask " + pn, expected, reused.headerProtectionMask(sample));
                // the same sample again, as when a packet is protected and
                // then checked
                assertArrayEquals(algorithm + " mask again " + pn, expected, reused.headerProtectionMask(sample));
            }
        }
    }

    /** Sealing one packet number twice running gives the same answer both times. */
    @Test
    public void testSamePacketNumberSealedTwice() throws Exception {
        QuicAeadAlgorithm[] algorithms = QuicAeadAlgorithm.values();
        for (int a = 0; a < algorithms.length; a++) {
            QuicAeadAlgorithm algorithm = algorithms[a];
            PacketProtectionKeys keys = keys(algorithm,
                    randomBytes(algorithm == QuicAeadAlgorithm.AES_256_GCM ? 48 : 32));
            byte[] header = randomBytes(16);
            byte[] plaintext = randomBytes(100);
            byte[] first = keys.seal(7, header, plaintext);
            assertArrayEquals(algorithm.toString(), first, keys.seal(7, header, plaintext));
            assertArrayEquals(algorithm.toString(), first, keys.seal(7, header, plaintext));
            assertArrayEquals(algorithm.toString(), plaintext, keys.open(7, header, keys.seal(7, header, plaintext)));
        }
    }

    /** A packet that fails authentication leaves the keys fit for the next one. */
    @Test
    public void testFailedOpenDoesNotSpoilLaterPackets() throws Exception {
        QuicAeadAlgorithm[] algorithms = QuicAeadAlgorithm.values();
        for (int a = 0; a < algorithms.length; a++) {
            QuicAeadAlgorithm algorithm = algorithms[a];
            PacketProtectionKeys keys = keys(algorithm,
                    randomBytes(algorithm == QuicAeadAlgorithm.AES_256_GCM ? 48 : 32));
            byte[] header = randomBytes(16);
            byte[] plaintext = randomBytes(100);
            for (int pn = 0; pn < 20; pn++) {
                byte[] sealed = keys.seal(pn, header, plaintext);
                byte[] forged = sealed.clone();
                forged[pn % forged.length] ^= 0x40;
                try {
                    keys.open(pn, header, forged);
                    fail(algorithm + ": a forged packet was accepted");
                } catch (PacketProtectionException expected) {
                    // rejected
                }
                assertArrayEquals(algorithm + " open after forgery " + pn, plaintext,
                        keys.open(pn, header, sealed));
            }
        }
    }

}
