/*
 * PacketProtectionKeysUpdateTest.java
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Arrays;

import org.bluezoo.gumdrop.crypto.Hkdf;
import org.junit.Test;

/**
 * RFC 9001 section 6.1: the next generation's secret is
 * {@code HKDF-Expand-Label(secret, "quic ku", "", Hash.length)} (with the
 * version's label prefix), the AEAD key and IV follow from it, and the
 * header protection key is not updated.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class PacketProtectionKeysUpdateTest {

    private static byte[] secret() {
        byte[] s = new byte[32];
        for (int i = 0; i < s.length; i++) {
            s[i] = (byte) (0x40 + i);
        }
        return s;
    }

    @Test
    public void nextSecretIsOneHashLongAndDiffersPerVersion() {
        Hkdf hkdf = Hkdf.sha256();
        byte[] v1 = PacketProtectionKeys.nextSecret(hkdf, secret(), QuicVersion.V1);
        byte[] v2 = PacketProtectionKeys.nextSecret(hkdf, secret(), QuicVersion.V2);
        assertEquals(32, v1.length);
        assertFalse(Arrays.equals(secret(), v1));
        assertFalse("v2 uses the quicv2 label prefix", Arrays.equals(v1, v2));
        assertArrayEquals("derivation is deterministic", v1,
                PacketProtectionKeys.nextSecret(hkdf, secret(), QuicVersion.V1));
    }

    @Test
    public void updatedKeysKeepHeaderProtectionAndChangeTheRest() {
        Hkdf hkdf = Hkdf.sha256();
        PacketProtectionKeys current = PacketProtectionKeys.derive(hkdf, secret(), QuicAeadAlgorithm.AES_128_GCM,
                QuicVersion.V1);
        byte[] next = PacketProtectionKeys.nextSecret(hkdf, secret(), QuicVersion.V1);
        PacketProtectionKeys updated = PacketProtectionKeys.update(hkdf, next, QuicVersion.V1, current);
        assertArrayEquals(current.getHeaderProtectionKey().getEncoded(), updated.getHeaderProtectionKey().getEncoded());
        assertFalse(Arrays.equals(current.getAeadKey().getEncoded(), updated.getAeadKey().getEncoded()));
        assertFalse(Arrays.equals(current.getIv(), updated.getIv()));
        assertEquals(current.getAlgorithm(), updated.getAlgorithm());
        // The same as deriving afresh from the next secret, except for header protection.
        PacketProtectionKeys fresh = PacketProtectionKeys.derive(hkdf, next, QuicAeadAlgorithm.AES_128_GCM, QuicVersion.V1);
        assertArrayEquals(fresh.getAeadKey().getEncoded(), updated.getAeadKey().getEncoded());
        assertArrayEquals(fresh.getIv(), updated.getIv());
    }

}
