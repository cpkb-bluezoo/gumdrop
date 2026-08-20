/*
 * PacketProtectionRfc9001Test.java
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

import java.util.Arrays;

import org.junit.Test;

import org.bluezoo.gumdrop.quic.tls.Hkdf;
import org.bluezoo.gumdrop.quic.tls.InitialSecrets;
import org.bluezoo.util.ByteArrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Verifies key derivation, AEAD packet protection, and header protection
 * against the worked "Client Initial" and "Server Initial" examples of
 * RFC 9001 Appendix A -- the ground truth every later QUIC transport
 * increment is built on. All hex constants below were extracted
 * programmatically from the RFC 9001 text (not hand-transcribed) to
 * eliminate transcription risk in this security-critical code.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#appendix-A">RFC 9001 Appendix A</a>
 */
public class PacketProtectionRfc9001Test {

    private static final byte[] DCID = ByteArrays.toByteArray("8394c8f03e515708");

    private static final String CLIENT_KEY =
            "1f369613dd76d5467730efcbe3b1a22d";

    private static final String CLIENT_IV =
            "fa044b2f42a3fd3b46fb255c";

    private static final String CLIENT_HP =
            "9f50449e04a0e810283a1e9933adedd2";

    private static final String SERVER_KEY =
            "cf3a5331653c364c88f0f379b6067e37";

    private static final String SERVER_IV =
            "0ac1493ca1905853b0bba03e";

    private static final String SERVER_HP =
            "c206b8d9b9f0f37644430b490eeaa314";

    /** RFC 9001 A.2: the CRYPTO frame; the full payload is this, zero-padded to 1162 bytes. */
    private static final String CLIENT_CRYPTO_FRAME =
            "060040f1010000ed0303ebf8fa56f12939b9584a3896472ec40bb863cfd3e86804fe3a47f06a2b69484c00000413011302010000c000000010000e00000b6578616d706c652e636f6dff01000100000a00080006001d0017001800100007000504616c706e000500050100000000003300260024001d00209370b2c9caa47fbabaf4559fedba753de171fa71f50f1ce15d43e994ec74d748002b0003020304000d0010000e0403050306030203080408050806002d00020101001c00024001003900320408ffffffffffffffff05048000ffff07048000ffff0801100104800075300901100f088394c8f03e51570806048000ffff";

    private static final int CLIENT_PLAINTEXT_LENGTH = 1162;

    private static final String CLIENT_UNPROTECTED_HEADER =
            "c300000001088394c8f03e5157080000449e00000002";

    private static final String CLIENT_MASK = "437b9aec36";

    private static final String CLIENT_PROTECTED_PACKET =
            "c000000001088394c8f03e5157080000449e7b9aec34d1b1c98dd7689fb8ec11d242b123dc9bd8bab936b47d92ec356c0bab7df5976d27cd449f63300099f3991c260ec4c60d17b31f8429157bb35a1282a643a8d2262cad67500cadb8e7378c8eb7539ec4d4905fed1bee1fc8aafba17c750e2c7ace01e6005f80fcb7df621230c83711b39343fa028cea7f7fb5ff89eac2308249a02252155e2347b63d58c5457afd84d05dfffdb20392844ae812154682e9cf012f9021a6f0be17ddd0c2084dce25ff9b06cde535d0f920a2db1bf362c23e596d11a4f5a6cf3948838a3aec4e15daf8500a6ef69ec4e3feb6b1d98e610ac8b7ec3faf6ad760b7bad1db4ba3485e8a94dc250ae3fdb41ed15fb6a8e5eba0fc3dd60bc8e30c5c4287e53805db059ae0648db2f64264ed5e39be2e20d82df566da8dd5998ccabdae053060ae6c7b4378e846d29f37ed7b4ea9ec5d82e7961b7f25a9323851f681d582363aa5f89937f5a67258bf63ad6f1a0b1d96dbd4faddfcefc5266ba6611722395c906556be52afe3f565636ad1b17d508b73d8743eeb524be22b3dcbc2c7468d54119c7468449a13d8e3b95811a198f3491de3e7fe942b330407abf82a4ed7c1b311663ac69890f4157015853d91e923037c227a33cdd5ec281ca3f79c44546b9d90ca00f064c99e3dd97911d39fe9c5d0b23a229a234cb36186c4819e8b9c5927726632291d6a418211cc2962e20fe47feb3edf330f2c603a9d48c0fcb5699dbfe5896425c5bac4aee82e57a85aaf4e2513e4f05796b07ba2ee47d80506f8d2c25e50fd14de71e6c418559302f939b0e1abd576f279c4b2e0feb85c1f28ff18f58891ffef132eef2fa09346aee33c28eb130ff28f5b766953334113211996d20011a198e3fc433f9f2541010ae17c1bf202580f6047472fb36857fe843b19f5984009ddc324044e847a4f4a0ab34f719595de37252d6235365e9b84392b061085349d73203a4a13e96f5432ec0fd4a1ee65accdd5e3904df54c1da510b0ff20dcc0c77fcb2c0e0eb605cb0504db87632cf3d8b4dae6e705769d1de354270123cb11450efc60ac47683d7b8d0f811365565fd98c4c8eb936bcab8d069fc33bd801b03adea2e1fbc5aa463d08ca19896d2bf59a071b851e6c239052172f296bfb5e72404790a2181014f3b94a4e97d117b438130368cc39dbb2d198065ae3986547926cd2162f40a29f0c3c8745c0f50fba3852e566d44575c29d39a03f0cda721984b6f440591f355e12d439ff150aab7613499dbd49adabc8676eef023b15b65bfc5ca06948109f23f350db82123535eb8a7433bdabcb909271a6ecbcb58b936a88cd4e8f2e6ff5800175f113253d8fa9ca8885c2f552e657dc603f252e1a8e308f76f0be79e2fb8f5d5fbbe2e30ecadd220723c8c0aea8078cdfcb3868263ff8f0940054da48781893a7e49ad5aff4af300cd804a6b6279ab3ff3afb64491c85194aab760d58a606654f9f4400e8b38591356fbf6425aca26dc85244259ff2b19c41b9f96f3ca9ec1dde434da7d2d392b905ddf3d1f9af93d1af5950bd493f5aa731b4056df31bd267b6b90a079831aaf579be0a39013137aac6d404f518cfd46840647e78bfe706ca4cf5e9c5453e9f7cfd2b8b4c8d169a44e55c88d4a9a7f9474241e221af44860018ab0856972e194cd934";

    private static final String SERVER_PAYLOAD_PLAINTEXT =
            "02000000000600405a020000560303eefce7f7b37ba1d1632e96677825ddf73988cfc79825df566dc5430b9a045a1200130100002e00330024001d00209d3c940d89690b84d08a60993c144eca684d1081287c834d5311bcf32bb9da1a002b00020304";

    private static final String SERVER_UNPROTECTED_HEADER =
            "c1000000010008f067a5502a4262b50040750001";

    private static final String SERVER_MASK = "2ec0d8356a";

    private static final String SERVER_PROTECTED_PACKET =
            "cf000000010008f067a5502a4262b5004075c0d95a482cd0991cd25b0aac406a5816b6394100f37a1c69797554780bb38cc5a99f5ede4cf73c3ec2493a1839b3dbcba3f6ea46c5b7684df3548e7ddeb9c3bf9c73cc3f3bded74b562bfb19fb84022f8ef4cdd93795d77d06edbb7aaf2f58891850abbdca3d20398c276456cbc42158407dd074ee";

    @Test
    public void testDerivedInitialKeys() {
        Hkdf hkdf = Hkdf.sha256();

        PacketProtectionKeys clientKeys = PacketProtectionKeys.derive(
                hkdf, InitialSecrets.clientSecretV1(DCID), QuicAeadAlgorithm.AES_128_GCM);
        assertEquals(CLIENT_KEY, ByteArrays.toHexString(clientKeys.getAeadKey().getEncoded()));
        assertEquals(CLIENT_IV, ByteArrays.toHexString(clientKeys.getIv()));
        assertEquals(CLIENT_HP, ByteArrays.toHexString(clientKeys.getHeaderProtectionKey().getEncoded()));

        PacketProtectionKeys serverKeys = PacketProtectionKeys.derive(
                hkdf, InitialSecrets.serverSecretV1(DCID), QuicAeadAlgorithm.AES_128_GCM);
        assertEquals(SERVER_KEY, ByteArrays.toHexString(serverKeys.getAeadKey().getEncoded()));
        assertEquals(SERVER_IV, ByteArrays.toHexString(serverKeys.getIv()));
        assertEquals(SERVER_HP, ByteArrays.toHexString(serverKeys.getHeaderProtectionKey().getEncoded()));
    }

    @Test
    public void testClientInitialPacketProtection() throws PacketProtectionException {
        PacketProtectionKeys clientKeys = PacketProtectionKeys.derive(
                Hkdf.sha256(), InitialSecrets.clientSecretV1(DCID), QuicAeadAlgorithm.AES_128_GCM);

        byte[] cryptoFrame = ByteArrays.toByteArray(CLIENT_CRYPTO_FRAME);
        // RFC 9001 A.2: "plus enough PADDING frames to make a 1162-byte
        // payload" -- a PADDING frame is a single zero byte (RFC 9000
        // section 19.1), so the remainder is left as the array's default
        // zero-fill.
        byte[] plaintext = new byte[CLIENT_PLAINTEXT_LENGTH];
        System.arraycopy(cryptoFrame, 0, plaintext, 0, cryptoFrame.length);

        byte[] header = ByteArrays.toByteArray(CLIENT_UNPROTECTED_HEADER);
        long packetNumber = 2;
        int pnOffset = 18;
        int pnLength = 4;
        boolean longHeader = true;

        byte[] ciphertext = PacketProtection.seal(clientKeys, packetNumber, header, plaintext);
        assertEquals(CLIENT_PLAINTEXT_LENGTH + QuicAeadAlgorithm.TAG_LENGTH, ciphertext.length);

        byte[] expectedPacket = ByteArrays.toByteArray(CLIENT_PROTECTED_PACKET);
        byte[] expectedCiphertext = Arrays.copyOfRange(expectedPacket, header.length, expectedPacket.length);
        assertArrayEquals(expectedCiphertext, ciphertext);

        // pnOffset + 4 == header.length here (4-byte packet number
        // encoding), so the sample is exactly the first 16 bytes of the
        // freshly sealed ciphertext.
        byte[] sample = Arrays.copyOfRange(ciphertext, 0, QuicAeadAlgorithm.SAMPLE_LENGTH);
        byte[] mask = PacketProtection.headerProtectionMask(clientKeys, sample);
        assertEquals(CLIENT_MASK, ByteArrays.toHexString(mask));

        byte[] packet = new byte[header.length + ciphertext.length];
        System.arraycopy(header, 0, packet, 0, header.length);
        System.arraycopy(ciphertext, 0, packet, header.length, ciphertext.length);
        PacketProtection.xorFirstByte(packet, mask, longHeader);
        PacketProtection.xorPacketNumberBytes(packet, pnOffset, pnLength, mask);
        assertArrayEquals(expectedPacket, packet);

        // Round-trip: remove protection from the RFC's own protected
        // packet bytes (a fresh copy -- xorFirstByte/xorPacketNumberBytes
        // mutate in place) and recover the original header and plaintext,
        // exactly as a receiver would.
        byte[] received = ByteArrays.toByteArray(CLIENT_PROTECTED_PACKET);
        byte[] receivedSample = Arrays.copyOfRange(
                received, pnOffset + 4, pnOffset + 4 + QuicAeadAlgorithm.SAMPLE_LENGTH);
        byte[] receivedMask = PacketProtection.headerProtectionMask(clientKeys, receivedSample);

        PacketProtection.xorFirstByte(received, receivedMask, longHeader);
        int decodedPnLength = (received[0] & 0x03) + 1;
        assertEquals(pnLength, decodedPnLength);
        PacketProtection.xorPacketNumberBytes(received, pnOffset, decodedPnLength, receivedMask);

        byte[] recoveredHeader = Arrays.copyOfRange(received, 0, pnOffset + decodedPnLength);
        assertArrayEquals(header, recoveredHeader);

        long decodedPacketNumber = 0;
        for (int i = 0; i < decodedPnLength; i++) {
            decodedPacketNumber = (decodedPacketNumber << 8) | (received[pnOffset + i] & 0xff);
        }
        assertEquals(packetNumber, decodedPacketNumber);

        byte[] recoveredCiphertext = Arrays.copyOfRange(received, recoveredHeader.length, received.length);
        byte[] recoveredPlaintext = PacketProtection.open(
                clientKeys, decodedPacketNumber, recoveredHeader, recoveredCiphertext);
        assertArrayEquals(plaintext, recoveredPlaintext);
    }

    @Test
    public void testServerInitialPacketProtection() throws PacketProtectionException {
        PacketProtectionKeys serverKeys = PacketProtectionKeys.derive(
                Hkdf.sha256(), InitialSecrets.serverSecretV1(DCID), QuicAeadAlgorithm.AES_128_GCM);

        byte[] plaintext = ByteArrays.toByteArray(SERVER_PAYLOAD_PLAINTEXT);
        byte[] header = ByteArrays.toByteArray(SERVER_UNPROTECTED_HEADER);
        long packetNumber = 1;
        int pnOffset = 18;
        int pnLength = 2;
        boolean longHeader = true;

        byte[] ciphertext = PacketProtection.seal(serverKeys, packetNumber, header, plaintext);
        assertEquals(plaintext.length + QuicAeadAlgorithm.TAG_LENGTH, ciphertext.length);

        byte[] expectedPacket = ByteArrays.toByteArray(SERVER_PROTECTED_PACKET);
        byte[] expectedCiphertext = Arrays.copyOfRange(expectedPacket, header.length, expectedPacket.length);
        assertArrayEquals(expectedCiphertext, ciphertext);

        // pnOffset + 4 == 22, but header.length == 20 (2-byte packet
        // number encoding), so the sample starts 2 bytes into the
        // ciphertext, not at its start.
        byte[] packet = new byte[header.length + ciphertext.length];
        System.arraycopy(header, 0, packet, 0, header.length);
        System.arraycopy(ciphertext, 0, packet, header.length, ciphertext.length);
        byte[] sample = Arrays.copyOfRange(packet, pnOffset + 4, pnOffset + 4 + QuicAeadAlgorithm.SAMPLE_LENGTH);
        byte[] mask = PacketProtection.headerProtectionMask(serverKeys, sample);
        assertEquals(SERVER_MASK, ByteArrays.toHexString(mask));

        PacketProtection.xorFirstByte(packet, mask, longHeader);
        PacketProtection.xorPacketNumberBytes(packet, pnOffset, pnLength, mask);
        assertArrayEquals(expectedPacket, packet);

        // Round-trip, as above.
        byte[] received = ByteArrays.toByteArray(SERVER_PROTECTED_PACKET);
        byte[] receivedSample = Arrays.copyOfRange(
                received, pnOffset + 4, pnOffset + 4 + QuicAeadAlgorithm.SAMPLE_LENGTH);
        byte[] receivedMask = PacketProtection.headerProtectionMask(serverKeys, receivedSample);

        PacketProtection.xorFirstByte(received, receivedMask, longHeader);
        int decodedPnLength = (received[0] & 0x03) + 1;
        assertEquals(pnLength, decodedPnLength);
        PacketProtection.xorPacketNumberBytes(received, pnOffset, decodedPnLength, receivedMask);

        byte[] recoveredHeader = Arrays.copyOfRange(received, 0, pnOffset + decodedPnLength);
        assertArrayEquals(header, recoveredHeader);

        long decodedPacketNumber = 0;
        for (int i = 0; i < decodedPnLength; i++) {
            decodedPacketNumber = (decodedPacketNumber << 8) | (received[pnOffset + i] & 0xff);
        }
        assertEquals(packetNumber, decodedPacketNumber);

        byte[] recoveredCiphertext = Arrays.copyOfRange(received, recoveredHeader.length, received.length);
        byte[] recoveredPlaintext = PacketProtection.open(
                serverKeys, decodedPacketNumber, recoveredHeader, recoveredCiphertext);
        assertArrayEquals(plaintext, recoveredPlaintext);
    }
}
