/*
 * QuicVersion2Test.java
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

import org.junit.Test;

import org.bluezoo.gumdrop.crypto.Hkdf;
import org.bluezoo.gumdrop.quic.tls.InitialSecrets;
import org.bluezoo.util.ByteArrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the QUIC version 2 (RFC 9369) differences from version 1 at
 * the packet layer: Initial secrets and packet protection keys
 * (Appendix A.1), long-header packet type bits (section 3.2) and the
 * Retry Integrity Tag (section 3.3.3).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9369">RFC 9369</a>
 */
public class QuicVersion2Test {

    private static final byte[] DCID = ByteArrays.toByteArray("8394c8f03e515708");

    @Test
    public void testSupportedVersions() {
        assertEquals(QuicVersion.V1, QuicVersion.fromWireValue(1));
        assertEquals(QuicVersion.V2, QuicVersion.fromWireValue(0x6b3343cf));
        assertNull(QuicVersion.fromWireValue(0));
        assertNull(QuicVersion.fromWireValue(0x1a2a3a4a));
    }

    @Test
    public void testV2ClientInitialSecretAndKeys() {
        byte[] secret = InitialSecrets.clientSecret(QuicVersion.V2, DCID);
        assertEquals("14ec9d6eb9fd7af83bf5a668bc17a7e283766aade7ecd0891f70f9ff7f4bf47b",
                ByteArrays.toHexString(secret));
        PacketProtectionKeys keys = PacketProtectionKeys.derive(
                Hkdf.sha256(), secret, QuicAeadAlgorithm.AES_128_GCM, QuicVersion.V2);
        assertEquals("8b1a0bc121284290a29e0971b5cd045d", ByteArrays.toHexString(keys.getAeadKey().getEncoded()));
        assertEquals("91f73e2351d8fa91660e909f", ByteArrays.toHexString(keys.getIv()));
        assertEquals("45b95e15235d6f45a6b19cbcb0294ba9",
                ByteArrays.toHexString(keys.getHeaderProtectionKey().getEncoded()));
    }

    @Test
    public void testV2ServerInitialSecretAndKeys() {
        byte[] secret = InitialSecrets.serverSecret(QuicVersion.V2, DCID);
        assertEquals("0263db1782731bf4588e7e4d93b7463907cb8cd8200b5da55a8bd488eafc37c1",
                ByteArrays.toHexString(secret));
        PacketProtectionKeys keys = PacketProtectionKeys.derive(
                Hkdf.sha256(), secret, QuicAeadAlgorithm.AES_128_GCM, QuicVersion.V2);
        assertEquals("82db637861d55e1d011f19ea71d5d2a7", ByteArrays.toHexString(keys.getAeadKey().getEncoded()));
        assertEquals("dd13c276499c0249d3310652", ByteArrays.toHexString(keys.getIv()));
        assertEquals("edf6d05c83121201b436e16877593c3a",
                ByteArrays.toHexString(keys.getHeaderProtectionKey().getEncoded()));
    }

    @Test
    public void testV2KeysDifferFromV1KeysForSameSecret() {
        byte[] secret = InitialSecrets.clientSecret(QuicVersion.V1, DCID);
        PacketProtectionKeys v1 = PacketProtectionKeys.derive(
                Hkdf.sha256(), secret, QuicAeadAlgorithm.AES_128_GCM, QuicVersion.V1);
        PacketProtectionKeys v2 = PacketProtectionKeys.derive(
                Hkdf.sha256(), secret, QuicAeadAlgorithm.AES_128_GCM, QuicVersion.V2);
        assertFalse(java.util.Arrays.equals(v1.getAeadKey().getEncoded(), v2.getAeadKey().getEncoded()));
    }

    @Test
    public void testV2LongHeaderPacketTypeBits() {
        byte[] empty = new byte[0];
        int[] logical = { LongHeaderCodec.TYPE_INITIAL, LongHeaderCodec.TYPE_0RTT, LongHeaderCodec.TYPE_HANDSHAKE };
        int[] wire = { 1, 2, 3 };
        for (int i = 0; i < logical.length; i++) {
            byte[] header = LongHeaderCodec.build(logical[i], QuicVersion.V2.getWireValue(), DCID, empty, empty, 0, 1, 20);
            assertEquals("wire type bits", wire[i], (header[0] >>> 4) & 0x03);
            byte[] withVarint = header;
            LongHeaderPrefix prefix = LongHeaderCodec.parsePrefix(withVarint);
            assertEquals("parsed as logical type", logical[i], prefix.getPacketType());
            assertEquals(QuicVersion.V2.getWireValue(), prefix.getVersion());
        }
    }

    @Test
    public void testV2RetryUsesWireTypeZero() {
        byte[] retry = LongHeaderCodec.buildRetryWithoutTag(QuicVersion.V2, DCID, DCID, new byte[] { 1, 2, 3 });
        assertEquals(0, (retry[0] >>> 4) & 0x03);
        assertEquals(LongHeaderCodec.TYPE_RETRY, LongHeaderCodec.packetType(QuicVersion.V2.getWireValue(), retry[0]));
        byte[] retryV1 = LongHeaderCodec.buildRetryWithoutTag(QuicVersion.V1, DCID, DCID, new byte[] { 1, 2, 3 });
        assertEquals(3, (retryV1[0] >>> 4) & 0x03);
    }

    @Test
    public void testV2RetryIntegrityTag() {
        byte[] retry = LongHeaderCodec.buildRetryWithoutTag(QuicVersion.V2, DCID, DCID, new byte[] { 1, 2, 3 });
        byte[] tagV2 = RetryIntegrityTag.compute(QuicVersion.V2, DCID, retry);
        assertTrue(RetryIntegrityTag.verify(QuicVersion.V2, DCID, retry, tagV2));
        assertFalse("v1 key must not validate a v2 tag", RetryIntegrityTag.verify(QuicVersion.V1, DCID, retry, tagV2));
        assertFalse(java.util.Arrays.equals(tagV2, RetryIntegrityTag.compute(QuicVersion.V1, DCID, retry)));
    }

    @Test
    public void testV2RetryIntegrityTagKeyAndNonce() {
        // RFC 9369 section 3.3.3 fixed key and nonce, checked via the tag
        // of the RFC 9369 Appendix A.4 Retry packet.
        byte[] packet = ByteArrays.toByteArray(
                "cf6b3343cf0008f067a5502a4262b5746f6b656e");
        byte[] tag = RetryIntegrityTag.compute(QuicVersion.V2, DCID, packet);
        assertEquals("c8646ce8bfe33952d955543665dcc7b6", ByteArrays.toHexString(tag));
    }
}
