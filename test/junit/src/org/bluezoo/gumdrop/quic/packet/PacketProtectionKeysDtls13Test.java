/*
 * PacketProtectionKeysDtls13Test.java
 * Copyright (C) 2026 Chris Burdess
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

        PacketProtectionKeys quicKeys = PacketProtectionKeys.derive(hkdf, secret, QuicAeadAlgorithm.AES_128_GCM);
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
