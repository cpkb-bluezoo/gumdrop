/*
 * EchConfigTest.java
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

import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import org.bluezoo.gumdrop.crypto.Hpke;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link EchConfig}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class EchConfigTest {

    private static final byte[] RFC9180_PK_RM = hex(
            "3948cfe0ad1ddb695d780e59077195da6c56506b027329794ab02bca80815c4d");

    @Test
    public void encodeParseRoundTrip() throws HandshakeFormatException {
        EchConfig original = EchConfig.createV13(42, RFC9180_PK_RM, "public.example", 64);
        EchConfig parsed = EchConfig.parse(original.encode());
        assertEquals(original.getConfigId(), parsed.getConfigId());
        assertEquals(original.getKemId(), parsed.getKemId());
        assertArrayEquals(original.getPublicKey(), parsed.getPublicKey());
        assertEquals(original.getMaximumNameLength(), parsed.getMaximumNameLength());
        assertEquals(original.getPublicName(), parsed.getPublicName());
        assertArrayEquals(new int[] { Hpke.KDF_HKDF_SHA256, Hpke.AEAD_AES_128_GCM },
                parsed.selectHpkeCipherSuite());
    }

    @Test
    public void parseListPreservesOrder() throws HandshakeFormatException {
        EchConfig first = EchConfig.createV13(1, RFC9180_PK_RM, "a.example", 0);
        EchConfig second = EchConfig.createV13(2, RFC9180_PK_RM, "b.example", 128);
        byte[] listBytes = EchConfig.encodeList(new EchConfig[] { first, second });
        EchConfig[] parsed = EchConfig.parseList(listBytes);
        assertEquals(2, parsed.length);
        assertEquals(1, parsed[0].getConfigId());
        assertEquals(2, parsed[1].getConfigId());
    }

    @Test
    public void hpkeSetupInfoSealsClientHelloInnerPlaceholder() throws Exception {
        EchConfig config = EchConfig.createV13(7, RFC9180_PK_RM, "ech.example", 32);
        Hpke hpke = Hpke.x25519HkdfSha256(Hpke.AEAD_AES_128_GCM);
        byte[] skRm = hex("4612c550263fc8ad58375df3f557aac531d26850903e55a9f23f21d8534e8ac8");
        SecureRandom random = new SecureRandom();
        Hpke.SenderContext sender = hpke.setupBaseS(RFC9180_PK_RM, config.hpkeSetupInfo(), random);
        Hpke.RecipientContext recipient = hpke.setupBaseR(sender.getEnc(), skRm, RFC9180_PK_RM,
                config.hpkeSetupInfo());
        byte[] inner = new byte[] { 1, 2, 3, 4 };
        byte[] aad = new byte[0];
        assertArrayEquals(inner, recipient.open(aad, sender.seal(aad, inner)));
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static EchConfig configWith(int kemId, int[][] suites) throws HandshakeFormatException {
        WireWriter contents = new WireWriter();
        contents.u8(5);
        contents.u16(kemId);
        contents.opaque16(RFC9180_PK_RM);
        WireWriter suiteBytes = new WireWriter();
        for (int i = 0; i < suites.length; i++) {
            suiteBytes.u16(suites[i][0]);
            suiteBytes.u16(suites[i][1]);
        }
        contents.opaque16(suiteBytes.toByteArray());
        contents.u8(32);
        contents.opaque8Ascii("public.example");
        contents.opaque16(new byte[0]);
        byte[] body = contents.toByteArray();
        WireWriter out = new WireWriter();
        out.u16(EchConfig.VERSION_ECH13);
        out.u16(body.length);
        out.bytes(body);
        return EchConfig.parse(out.toByteArray());
    }

    @Test
    public void selectionFollowsConfigOrderAmongSupportedSuites() throws HandshakeFormatException {
        EchConfig config = configWith(Hpke.KEM_X25519_HKDF_SHA256, new int[][] {
                { 0x0002, Hpke.AEAD_AES_128_GCM },
                { Hpke.KDF_HKDF_SHA256, Hpke.AEAD_CHACHA20_POLY1305 },
                { Hpke.KDF_HKDF_SHA256, Hpke.AEAD_AES_128_GCM } });
        assertArrayEquals(new int[] { Hpke.KDF_HKDF_SHA256, Hpke.AEAD_CHACHA20_POLY1305 },
                config.selectHpkeCipherSuite());
    }

    @Test
    public void selectionAcceptsAes256Gcm() throws HandshakeFormatException {
        EchConfig config = configWith(Hpke.KEM_X25519_HKDF_SHA256, new int[][] {
                { Hpke.KDF_HKDF_SHA256, Hpke.AEAD_AES_256_GCM } });
        assertArrayEquals(new int[] { Hpke.KDF_HKDF_SHA256, Hpke.AEAD_AES_256_GCM },
                config.selectHpkeCipherSuite());
    }

    @Test
    public void selectionRejectsUnsupportedKem() throws HandshakeFormatException {
        EchConfig config = configWith(0x0010, new int[][] {
                { Hpke.KDF_HKDF_SHA256, Hpke.AEAD_AES_128_GCM } });
        assertNull(config.selectHpkeCipherSuite());
    }

    @Test
    public void selectionRejectsConfigWithOnlyUnsupportedSuites() throws HandshakeFormatException {
        EchConfig config = configWith(Hpke.KEM_X25519_HKDF_SHA256, new int[][] {
                { 0x0002, Hpke.AEAD_AES_128_GCM }, { Hpke.KDF_HKDF_SHA256, 0x0009 } });
        assertNull(config.selectHpkeCipherSuite());
    }

    @Test
    public void advertisesReportsExactConfigEntries() throws HandshakeFormatException {
        EchConfig config = configWith(Hpke.KEM_X25519_HKDF_SHA256, new int[][] {
                { Hpke.KDF_HKDF_SHA256, Hpke.AEAD_CHACHA20_POLY1305 } });
        assertTrue(config.advertisesCipherSuite(Hpke.KDF_HKDF_SHA256, Hpke.AEAD_CHACHA20_POLY1305));
        assertTrue(!config.advertisesCipherSuite(Hpke.KDF_HKDF_SHA256, Hpke.AEAD_AES_128_GCM));
    }

}
