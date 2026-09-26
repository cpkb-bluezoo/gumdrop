/*
 * QuicLbConfigTest.java
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

package org.bluezoo.gumdrop.quic.cid;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import org.bluezoo.util.ByteArrays;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies {@link QuicLbConfig} against the load balancer test vectors in
 * appendix B of draft-ietf-quic-load-balancers-21.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLbConfigTest {

    private static final byte[] KEY = ByteArrays.toByteArray("8f95f09245765f80256934e50c66207f");

    private static byte[] hex(String s) {
        return ByteArrays.toByteArray(s);
    }

    private static void check(int configId, String sid, String nonce, byte[] key, String cid) {
        QuicLbConfig config = new QuicLbConfig(configId, hex(sid), hex(nonce).length, key, true);
        byte[] encoded = config.encode(hex(nonce));
        assertArrayEquals(hex(cid), encoded);
        assertArrayEquals(hex(sid), config.decodeServerId(hex(cid)));
        assertTrue(config.isOwn(hex(cid)));
    }

    @Test
    public void unencryptedVector() {
        check(0, "c4605e", "4504cc4f", null, "07c4605e4504cc4f");
    }

    @Test
    public void singlePassVector() {
        check(0, "ed793a", "ee080dbf", KEY, "0720b1d07b359d3c");
    }

    @Test
    public void fourPassEvenVector() {
        check(1, "ed793a51d49b8f5fab65", "ee080dbf48", KEY, "2fcc381bc74cb4fbad2823a3d1f8fed2");
    }

    @Test
    public void fourPassServerIdLongerThanNonceVector() {
        check(2, "ed793a51d49b8f5f", "ee080dbf48c0d1e5", KEY, "504dd2d05a7b0de9b2b9907afb5ecf8cc3");
    }

    /** The draft's table labels this row cr_bits 3 but its first octet 0x12 carries config id 0. */
    @Test
    public void fourPassOddVector() {
        check(0, "ed793a51d49b8f5fab", "ee080dbf48c0d1e55d", KEY, "125779c9cc86beb3a3a4a3ca96fce4bfe0cdbc");
    }

    @Test
    public void wrongConfigIdOrLengthDoesNotDecode() {
        QuicLbConfig config = new QuicLbConfig(0, hex("ed793a"), 4, KEY, true);
        assertNull(config.decodeServerId(hex("2720b1d07b359d3c")));
        assertNull(config.decodeServerId(hex("0720b1d07b359d")));
    }

    @Test
    public void otherServerIdIsNotOwn() {
        QuicLbConfig mine = new QuicLbConfig(0, hex("000001"), 4, KEY, false);
        QuicLbConfig other = new QuicLbConfig(0, hex("000002"), 4, KEY, false);
        byte[] cid = other.generate(new SecureRandom());
        assertFalse(mine.isOwn(cid));
        assertTrue(other.isOwn(cid));
    }

    @Test
    public void generatedIdsAreUniqueAndSelfDescribe() {
        QuicLbConfig config = new QuicLbConfig(3, hex("0102"), 6, KEY, true);
        SecureRandom random = new SecureRandom();
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < 500; i++) {
            byte[] cid = config.generate(random);
            assertEquals(config.getConnectionIdLength(), cid.length);
            assertEquals(3, QuicLbConfig.configIdOf(cid[0]));
            assertEquals(cid.length, QuicLbConfig.selfEncodedLength(cid[0]));
            assertNotNull(config.decodeServerId(cid));
            assertTrue(seen.add(ByteArrays.toHexString(cid)));
        }
    }

    @Test
    public void plaintextNonceIsRandom() {
        QuicLbConfig config = new QuicLbConfig(0, hex("aa"), 4, null, false);
        SecureRandom random = new SecureRandom();
        assertFalse(java.util.Arrays.equals(config.generate(random), config.generate(random)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsConfigId7() {
        new QuicLbConfig(7, hex("aa"), 4, null, false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsShortNonce() {
        new QuicLbConfig(0, hex("aa"), 3, null, false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOverlongPlaintext() {
        new QuicLbConfig(0, hex("aabbcc"), 17, null, false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBadKeyLength() {
        new QuicLbConfig(0, hex("aa"), 4, new byte[15], false);
    }

    @Test
    public void envoyProfile() {
        new QuicLbConfig(0, hex("aa"), 17, null, true).requireEnvoyProfile();
        try {
            new QuicLbConfig(0, hex("aa"), 18, null, true).requireEnvoyProfile();
            org.junit.Assert.fail();
        } catch (IllegalArgumentException expected) {
            // 19 octets exceeds Envoy's limit of 18
        }
        try {
            new QuicLbConfig(0, hex("aa"), 4, null, false).requireEnvoyProfile();
            org.junit.Assert.fail();
        } catch (IllegalArgumentException expected) {
            // length not self-encoded
        }
    }
}
