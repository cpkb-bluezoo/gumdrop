/*
 * EncryptedClientHelloTest.java
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

import org.bluezoo.gumdrop.crypto.Hpke;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link EncryptedClientHello}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class EncryptedClientHelloTest {

    @Test
    public void clientHelloInnerRoundTrip() throws HandshakeFormatException {
        byte[] encoded = EncryptedClientHello.encodeClientHelloInner();
        EncryptedClientHello.Parsed parsed = EncryptedClientHello.parseClientHelloPayload(encoded);
        assertTrue(parsed.isInner());
    }

    @Test
    public void clientHelloOuterRoundTrip() throws HandshakeFormatException {
        byte[] enc = hex("37fda3567bdbd628e88668c3c8d7e97d1d1253b6d4ea6d44c150f741f1bf4431");
        byte[] payload = new byte[] { 1, 2, 3, 4, 5 };
        EncryptedClientHello.Outer outer = new EncryptedClientHello.Outer(
                Hpke.KDF_HKDF_SHA256,
                Hpke.AEAD_AES_128_GCM,
                42,
                enc,
                payload);
        byte[] encoded = EncryptedClientHello.encodeClientHelloOuter(outer);
        EncryptedClientHello.Parsed parsed = EncryptedClientHello.parseClientHelloPayload(encoded);
        assertTrue(parsed.isOuter());
        EncryptedClientHello.Outer round = parsed.getOuter();
        assertEquals(Hpke.KDF_HKDF_SHA256, round.kdfId);
        assertEquals(Hpke.AEAD_AES_128_GCM, round.aeadId);
        assertEquals(42, round.configId);
        assertArrayEquals(enc, round.enc);
        assertArrayEquals(payload, round.payload);
    }

    @Test
    public void clientHelloOuterAllowsEmptyEncAfterHelloRetryRequest() throws HandshakeFormatException {
        EncryptedClientHello.Outer outer = new EncryptedClientHello.Outer(
                Hpke.KDF_HKDF_SHA256,
                Hpke.AEAD_AES_128_GCM,
                1,
                new byte[0],
                new byte[] { 9 });
        EncryptedClientHello.Parsed parsed = EncryptedClientHello.parseClientHelloPayload(
                EncryptedClientHello.encodeClientHelloOuter(outer));
        assertEquals(0, parsed.getOuter().enc.length);
    }

    @Test
    public void encryptedExtensionsCarriesRetryList() throws HandshakeFormatException {
        EchConfig config = EchConfig.createV13(3, hex("3948cfe0ad1ddb695d780e59077195da6c56506b027329794ab02bca80815c4d"),
                "public.example", 0);
        byte[] list = EchConfig.encodeList(new EchConfig[] { config });
        byte[] ext = EncryptedClientHello.encodeEncryptedExtensions(list);
        EchConfig[] parsed = EncryptedClientHello.parseEncryptedExtensions(ext);
        assertEquals(1, parsed.length);
        assertEquals(3, parsed[0].getConfigId());
    }

    @Test
    public void helloRetryRequestConfirmationRoundTrip() throws HandshakeFormatException {
        byte[] confirmation = hex("0123456789abcdef");
        byte[] encoded = EncryptedClientHello.encodeHelloRetryRequest(confirmation);
        assertArrayEquals(confirmation, EncryptedClientHello.parseHelloRetryRequest(encoded));
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
