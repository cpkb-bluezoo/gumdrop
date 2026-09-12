/*
 * KeyExchangeTest.java
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

package org.bluezoo.gumdrop.crypto;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Round-trips {@link KeyExchange} for every {@link NamedGroup}, including
 * the ML-KEM-768/X25519 hybrid, proving both sides agree on the same
 * shared secret and that wire share lengths match what {@link NamedGroup}
 * declares.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class KeyExchangeTest {

    @Test
    public void everyGroupAgreesOnASharedSecret() throws Exception {
        for (NamedGroup group : NamedGroup.values()) {
            KeyExchange client = KeyExchange.generate(group);
            byte[] clientShare = client.getShareBytes();
            assertEquals(group + " client share length", group.getClientShareLength(), clientShare.length);

            KeyExchange.ServerResult server = KeyExchange.agreeAsServer(group, clientShare);
            assertEquals(group + " server share length", group.getServerShareLength(), server.getShareBytes().length);

            byte[] clientSecret = client.agree(server.getShareBytes());
            assertArrayEquals(group + " shared secret", server.getSharedSecret(), clientSecret);
        }
    }

    @Test
    public void hybridGroupCombinesBothComponents() throws Exception {
        KeyExchange client = KeyExchange.generate(NamedGroup.X25519_MLKEM768);
        KeyExchange.ServerResult server = KeyExchange.agreeAsServer(NamedGroup.X25519_MLKEM768, client.getShareBytes());
        byte[] secret = client.agree(server.getShareBytes());
        // ML-KEM-768's 32-byte shared secret concatenated with X25519's 32-byte shared secret.
        assertEquals(64, secret.length);
    }

    @Test
    public void secp256r1MlKem768HybridCombinesBothComponents() throws Exception {
        // Classical-first: P-256 ECDH shared secret (32 bytes) concatenated
        // with ML-KEM-768's 32-byte shared secret -- the opposite
        // concatenation order from X25519_MLKEM768.
        KeyExchange client = KeyExchange.generate(NamedGroup.SECP256R1_MLKEM768);
        KeyExchange.ServerResult server =
                KeyExchange.agreeAsServer(NamedGroup.SECP256R1_MLKEM768, client.getShareBytes());
        byte[] secret = client.agree(server.getShareBytes());
        assertEquals(64, secret.length);
    }

    @Test
    public void secp384r1MlKem1024HybridCombinesBothComponents() throws Exception {
        // Classical-first: P-384 ECDH shared secret (48 bytes) concatenated
        // with ML-KEM-1024's 32-byte shared secret.
        KeyExchange client = KeyExchange.generate(NamedGroup.SECP384R1_MLKEM1024);
        KeyExchange.ServerResult server =
                KeyExchange.agreeAsServer(NamedGroup.SECP384R1_MLKEM1024, client.getShareBytes());
        byte[] secret = client.agree(server.getShareBytes());
        assertEquals(80, secret.length);
    }

}
