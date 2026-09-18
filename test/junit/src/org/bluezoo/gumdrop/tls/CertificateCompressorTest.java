/*
 * CertificateCompressorTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.tls;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CertificateCompressorTest {

    private static final byte[] SAMPLE_CERT_MESSAGE = "TLS-Certificate-handshake-payload-for-compression-test"
            .getBytes(StandardCharsets.US_ASCII);

    @Test
    public void selectAlgorithmPrefersBrotliWhenBothSupportIt() {
        List<CertificateCompressionAlgorithm> local =
                CertificateCompressor.defaultEnabledAlgorithms();
        byte[] offer = new byte[] {
                (byte) CertificateCompressionAlgorithm.ZLIB.getId(),
                (byte) CertificateCompressionAlgorithm.BROTLI.getId()
        };
        assertEquals(CertificateCompressionAlgorithm.BROTLI,
                CertificateCompressor.selectAlgorithm(local, offer));
    }

    @Test
    public void selectAlgorithmFallsBackToZlib() {
        List<CertificateCompressionAlgorithm> local =
                CertificateCompressor.defaultEnabledAlgorithms();
        byte[] offer = new byte[] { (byte) CertificateCompressionAlgorithm.ZLIB.getId() };
        assertEquals(CertificateCompressionAlgorithm.ZLIB,
                CertificateCompressor.selectAlgorithm(local, offer));
    }

    @Test
    public void selectAlgorithmReturnsNullWhenNoOverlap() {
        List<CertificateCompressionAlgorithm> local = Collections.singletonList(
                CertificateCompressionAlgorithm.BROTLI);
        byte[] offer = new byte[] { (byte) CertificateCompressionAlgorithm.ZLIB.getId() };
        assertNull(CertificateCompressor.selectAlgorithm(local, offer));
    }

    @Test
    public void brotliRoundTrip() throws Exception {
        byte[] compressed = CertificateCompressor.compress(
                CertificateCompressionAlgorithm.BROTLI, SAMPLE_CERT_MESSAGE);
        assertTrue(compressed.length > 0);
        byte[] restored = CertificateCompressor.decompress(
                CertificateCompressionAlgorithm.BROTLI, compressed,
                CertificateCompressor.DEFAULT_MAX_DECOMPRESSED_SIZE);
        assertArrayEquals(SAMPLE_CERT_MESSAGE, restored);
    }

    @Test
    public void zlibRoundTrip() throws Exception {
        byte[] compressed = CertificateCompressor.compress(
                CertificateCompressionAlgorithm.ZLIB, SAMPLE_CERT_MESSAGE);
        assertTrue(compressed.length > 0);
        byte[] restored = CertificateCompressor.decompress(
                CertificateCompressionAlgorithm.ZLIB, compressed,
                CertificateCompressor.DEFAULT_MAX_DECOMPRESSED_SIZE);
        assertArrayEquals(SAMPLE_CERT_MESSAGE, restored);
    }

    @Test
    public void decompressEnforcesSizeLimit() throws Exception {
        byte[] compressed = CertificateCompressor.compress(
                CertificateCompressionAlgorithm.ZLIB, SAMPLE_CERT_MESSAGE);
        try {
            CertificateCompressor.decompress(
                    CertificateCompressionAlgorithm.ZLIB, compressed, 8);
            org.junit.Assert.fail("expected HandshakeFormatException");
        } catch (HandshakeFormatException expected) {
            assertTrue(expected.getMessage().contains("limit"));
        }
    }
}
