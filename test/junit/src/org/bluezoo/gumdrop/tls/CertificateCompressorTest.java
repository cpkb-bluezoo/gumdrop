/*
 * CertificateCompressorTest.java
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

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link CertificateCompressor}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
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
