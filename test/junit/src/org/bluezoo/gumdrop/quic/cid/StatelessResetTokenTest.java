/*
 * StatelessResetTokenTest.java
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

import org.junit.Test;

import org.bluezoo.util.ByteArrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Verifies {@link StatelessResetToken} (RFC 9000 section 10.3): fixed
 * length, determinism, and uniqueness across differing inputs.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class StatelessResetTokenTest {

    private static final byte[] STATIC_KEY = ByteArrays.toByteArray(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e");

    @Test
    public void testTokenIsFixedLength() {
        byte[] connectionId = ByteArrays.toByteArray("a1a2a3a4a5a6a7a8");
        byte[] token = StatelessResetToken.generate(STATIC_KEY, connectionId);
        assertEquals(StatelessResetToken.LENGTH, token.length);
    }

    @Test
    public void testTokenIsDeterministic() {
        byte[] connectionId = ByteArrays.toByteArray("a1a2a3a4a5a6a7a8");
        byte[] token1 = StatelessResetToken.generate(STATIC_KEY, connectionId);
        byte[] token2 = StatelessResetToken.generate(STATIC_KEY, connectionId);
        assertEquals(ByteArrays.toHexString(token1), ByteArrays.toHexString(token2));
    }

    @Test
    public void testDifferentConnectionIdsProduceDifferentTokens() {
        byte[] token1 = StatelessResetToken.generate(STATIC_KEY, ByteArrays.toByteArray("a1a2a3a4a5a6a7a8"));
        byte[] token2 = StatelessResetToken.generate(STATIC_KEY, ByteArrays.toByteArray("b1b2b3b4b5b6b7b8"));
        assertFalse(ByteArrays.toHexString(token1).equals(ByteArrays.toHexString(token2)));
    }

    @Test
    public void testDifferentStaticKeysProduceDifferentTokens() {
        byte[] connectionId = ByteArrays.toByteArray("a1a2a3a4a5a6a7a8");
        byte[] otherKey = ByteArrays.toByteArray(
                "1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100");
        byte[] token1 = StatelessResetToken.generate(STATIC_KEY, connectionId);
        byte[] token2 = StatelessResetToken.generate(otherKey, connectionId);
        assertFalse(ByteArrays.toHexString(token1).equals(ByteArrays.toHexString(token2)));
    }
}
