/*
 * DirectionalKeysTest.java
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

import java.util.Arrays;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link DirectionalKeys}, {@link CipherSuite} and
 * {@link AlertDescription}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DirectionalKeysTest {

    private static byte[] secret(CipherSuite suite) {
        byte[] s = new byte[suite.getHashLength()];
        for (int i = 0; i < s.length; i++) {
            s[i] = (byte) (i + 1);
        }
        return s;
    }

    @Test
    public void sealOpenRoundTripForEverySuite() throws Exception {
        CipherSuite[] suites = CipherSuite.values();
        for (int i = 0; i < suites.length; i++) {
            CipherSuite suite = suites[i];
            DirectionalKeys seal = DirectionalKeys.fromSecret(suite, secret(suite));
            DirectionalKeys open = DirectionalKeys.fromSecret(suite, secret(suite));
            byte[] aad = new byte[] { 23, 3, 3, 0, 20 };
            byte[] plain = "hello tls".getBytes("US-ASCII");
            byte[] ct = seal.sealAppendTag(seal.nonce(), aad, plain);
            assertEquals(plain.length + 16, ct.length);
            byte[] pt = open.openInPlace(open.nonce(), aad, ct);
            assertArrayEquals(plain, pt);
        }
    }

    @Test
    public void tamperedCiphertextOrAadOrNonceFailsOpen() throws Exception {
        CipherSuite suite = CipherSuite.TLS_AES_128_GCM_SHA256;
        DirectionalKeys k = DirectionalKeys.fromSecret(suite, secret(suite));
        byte[] aad = new byte[] { 1, 2, 3 };
        byte[] nonce = k.nonce().clone();
        byte[] ct = k.sealAppendTag(nonce, aad, new byte[] { 9, 9, 9 });
        byte[] bad = ct.clone();
        bad[0] ^= 1;
        assertNull(k.openInPlace(nonce, aad, bad));
        assertNull(k.openInPlace(nonce, new byte[] { 1, 2, 4 }, ct));
        k.advance();
        assertNull(k.openInPlace(k.nonce(), aad, ct));
        assertNotNull(k.openInPlace(nonce, aad, ct));
    }

    @Test
    public void nonceXorsSequenceNumberAndAdvanceIncrements() {
        CipherSuite suite = CipherSuite.TLS_AES_128_GCM_SHA256;
        DirectionalKeys k = DirectionalKeys.fromSecret(suite, secret(suite));
        byte[] n0 = k.nonce().clone();
        k.advance();
        byte[] n1 = k.nonce().clone();
        assertEquals(1L, k.seq);
        assertFalse(Arrays.equals(n0, n1));
        assertEquals(1, (n0[11] ^ n1[11]));
        for (int i = 0; i < 11; i++) {
            assertEquals(n0[i], n1[i]);
        }
        k.seq = 0x0102030405060708L;
        byte[] big = k.nonce().clone();
        k.seq = 0;
        byte[] zero = k.nonce().clone();
        assertEquals(zero[0], big[0]);
        assertEquals(0x01, (zero[4] ^ big[4]) & 0xff);
        assertEquals(0x08, (zero[11] ^ big[11]) & 0xff);
    }

    @Test
    public void confidentialityLimitAppliesToGcmOnly() {
        DirectionalKeys gcm = DirectionalKeys.fromSecret(CipherSuite.TLS_AES_256_GCM_SHA384,
                secret(CipherSuite.TLS_AES_256_GCM_SHA384));
        assertFalse(gcm.overConfidentialityLimit());
        gcm.seq = 23_726_566L;
        assertTrue(gcm.overConfidentialityLimit());
        DirectionalKeys cc = DirectionalKeys.fromSecret(CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
                secret(CipherSuite.TLS_CHACHA20_POLY1305_SHA256));
        cc.seq = Long.MAX_VALUE;
        assertFalse(cc.overConfidentialityLimit());
    }

    @Test
    public void cipherSuiteLookupAndAccessors() {
        assertEquals(CipherSuite.TLS_AES_128_GCM_SHA256, CipherSuite.fromCode(0x1301));
        assertEquals(CipherSuite.TLS_AES_256_GCM_SHA384, CipherSuite.fromCode(0x1302));
        assertEquals(CipherSuite.TLS_CHACHA20_POLY1305_SHA256, CipherSuite.fromCode(0x1303));
        assertNull(CipherSuite.fromCode(0x1304));
        assertEquals(0x1302, CipherSuite.TLS_AES_256_GCM_SHA384.getCode());
        assertEquals("SHA-384", CipherSuite.TLS_AES_256_GCM_SHA384.getHashAlgorithm());
        assertEquals(32, CipherSuite.TLS_AES_256_GCM_SHA384.getAeadKeyLength());
        assertEquals("AES", CipherSuite.TLS_AES_128_GCM_SHA256.getAeadKeyAlgorithm());
        assertEquals("ChaCha20-Poly1305", CipherSuite.TLS_CHACHA20_POLY1305_SHA256.getAeadTransformation());
        assertNotNull(CipherSuite.TLS_AES_128_GCM_SHA256.newHkdf());
        assertNotNull(CipherSuite.TLS_AES_256_GCM_SHA384.newHkdf());
    }

    @Test
    public void alertDescriptionCodesRoundTrip() {
        AlertDescription[] values = AlertDescription.values();
        for (int i = 0; i < values.length; i++) {
            assertEquals(values[i], AlertDescription.fromCode(values[i].getCode()));
        }
        assertEquals(0, AlertDescription.CLOSE_NOTIFY.getCode());
        assertEquals(40, AlertDescription.HANDSHAKE_FAILURE.getCode());
        assertNull(AlertDescription.fromCode(255));
        assertNull(AlertDescription.fromCode(-1));
    }
}
