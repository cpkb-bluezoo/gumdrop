/*
 * PacketProtectionKeysDtls13Test.java
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
import java.util.Arrays;

import org.bluezoo.gumdrop.crypto.Hkdf;
import org.bluezoo.gumdrop.tls.CipherSuite;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * RFC 9147 section 5.9 uses the {@code "dtls13"} HKDF label prefix with no
 * trailing space -- distinct from TLS/QUIC's {@code "tls13 "}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class PacketProtectionKeysDtls13Test {

    @Test
    public void dtls13LabelPrefixHasNoTrailingSpace() {
        assertArrayEquals("dtls13".getBytes(StandardCharsets.US_ASCII), Hkdf.dtls13LabelPrefix());
    }

    @Test
    public void deriveForDtlsUsesDtls13LabelsNotQuicLabels() throws Exception {
        Hkdf hkdf = CipherSuite.TLS_AES_128_GCM_SHA256.newHkdf();
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 0x42);

        PacketProtectionKeys quicKeys = PacketProtectionKeys.derive(hkdf, secret, QuicAeadAlgorithm.AES_128_GCM, QuicVersion.V1);
        PacketProtectionKeys dtlsKeys = PacketProtectionKeys.deriveForDtls(hkdf, secret, QuicAeadAlgorithm.AES_128_GCM);

        assertFalse(Arrays.equals(quicKeys.getAeadKey().getEncoded(), dtlsKeys.getAeadKey().getEncoded()));
        assertFalse(Arrays.equals(quicKeys.getIv(), dtlsKeys.getIv()));
        assertFalse(Arrays.equals(
                quicKeys.getHeaderProtectionKey().getEncoded(),
                dtlsKeys.getHeaderProtectionKey().getEncoded()));
    }

    @Test
    public void expandLabelWithPrefixMatchesManualDtls13LabelBytes() throws Exception {
        Hkdf hkdf = CipherSuite.TLS_AES_128_GCM_SHA256.newHkdf();
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 0x11);

        byte[] fromPrefix = hkdf.expandLabelWithPrefix(Hkdf.dtls13LabelPrefix(), secret, "key", new byte[0], 16);
        byte[] fromDerive = PacketProtectionKeys.deriveForDtls(hkdf, secret, QuicAeadAlgorithm.AES_128_GCM)
                .getAeadKey().getEncoded();
        assertArrayEquals(fromPrefix, fromDerive);
        assertTrue(fromDerive.length == 16);
    }
}
