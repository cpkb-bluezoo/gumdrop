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

    private static byte[] largeMessage() {
        byte[] msg = new byte[100000];
        java.util.Random rnd = new java.util.Random(42);
        for (int i = 0; i < msg.length; i++) {
            msg[i] = (byte) (i % 7 == 0 ? rnd.nextInt(256) : 'a' + (i % 13));
        }
        return msg;
    }

    private static byte[] streamDecode(CertificateCompressionAlgorithm alg, byte[] compressed,
            int step, int max, final int[] maxChunk) throws Exception {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        CertificateCompressor.Decompressor d = CertificateCompressor.newDecompressor(alg, max,
                new CertificateCompressor.Sink() {
                    @Override
                    public void decoded(java.nio.ByteBuffer data) {
                        maxChunk[0] = Math.max(maxChunk[0], data.remaining());
                        byte[] b = new byte[data.remaining()];
                        data.get(b);
                        out.write(b, 0, b.length);
                    }
                });
        int pos = 0;
        while (pos < compressed.length) {
            int n = Math.min(step, compressed.length - pos);
            pos += n;
            d.write(java.nio.ByteBuffer.wrap(compressed, pos - n, n), pos == compressed.length);
        }
        return out.toByteArray();
    }

    private void chunked(CertificateCompressionAlgorithm alg) throws Exception {
        byte[] msg = largeMessage();
        byte[] compressed = CertificateCompressor.compress(alg, msg);
        int[] steps = { 1, 3, 100, 4096 };
        for (int step : steps) {
            int[] maxChunk = new int[1];
            byte[] restored = streamDecode(alg, compressed, step, 1 << 20, maxChunk);
            assertArrayEquals("step " + step, msg, restored);
            assertTrue("bounded output chunks: " + maxChunk[0], maxChunk[0] <= 65536);
        }
    }

    @Test
    public void brotliStreamsInSmallChunks() throws Exception {
        chunked(CertificateCompressionAlgorithm.BROTLI);
    }

    @Test
    public void zlibStreamsInSmallChunks() throws Exception {
        chunked(CertificateCompressionAlgorithm.ZLIB);
    }

    private void limitDuringDecode(CertificateCompressionAlgorithm alg) throws Exception {
        byte[] compressed = CertificateCompressor.compress(alg, largeMessage());
        final int[] delivered = new int[1];
        CertificateCompressor.Decompressor d = CertificateCompressor.newDecompressor(alg, 5000,
                new CertificateCompressor.Sink() {
                    @Override
                    public void decoded(java.nio.ByteBuffer data) {
                        delivered[0] += data.remaining();
                        data.position(data.limit());
                    }
                });
        try {
            d.write(java.nio.ByteBuffer.wrap(compressed), true);
            org.junit.Assert.fail("expected HandshakeFormatException");
        } catch (HandshakeFormatException expected) {
            assertTrue(expected.getMessage().contains("limit"));
        }
        assertTrue("never delivers past limit: " + delivered[0], delivered[0] <= 5000);
    }

    @Test
    public void brotliLimitEnforcedDuringDecode() throws Exception {
        limitDuringDecode(CertificateCompressionAlgorithm.BROTLI);
    }

    @Test
    public void zlibLimitEnforcedDuringDecode() throws Exception {
        limitDuringDecode(CertificateCompressionAlgorithm.ZLIB);
    }

    @Test
    public void truncatedInputRejectedAtEnd() throws Exception {
        byte[] compressed = CertificateCompressor.compress(
                CertificateCompressionAlgorithm.ZLIB, largeMessage());
        byte[] cut = java.util.Arrays.copyOf(compressed, compressed.length / 2);
        try {
            streamDecode(CertificateCompressionAlgorithm.ZLIB, cut, 100, 1 << 20, new int[1]);
            org.junit.Assert.fail("expected HandshakeFormatException");
        } catch (HandshakeFormatException expected) {
            assertTrue(expected.getMessage().contains("truncated"));
        }
    }
}
