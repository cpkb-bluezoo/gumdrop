/*
 * ContentEncodingTest.java
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

package org.bluezoo.gumdrop.http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link ContentEncoding}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContentEncodingTest {

    private static final byte[] PAYLOAD = repeat("Hello HTTP content coding! ", 40);

    private static byte[] repeat(String s, int n) {
        byte[] one = s.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[one.length * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(one, 0, out, i * one.length, one.length);
        }
        return out;
    }

    @Test
    public void selectFromAcceptEncodingPrefersBrotli() {
        assertEquals(ContentEncoding.Coding.BR,
                ContentEncoding.selectFromAcceptEncoding("gzip, deflate, br"));
    }

    @Test
    public void selectFromAcceptEncodingSkipsIdentity() {
        assertNull(ContentEncoding.selectFromAcceptEncoding("identity"));
    }

    @Test
    public void gzipRoundTripViaEncoderAndDecompress() throws Exception {
        byte[] encoded = encodeFully(ContentEncoding.Coding.GZIP, PAYLOAD);
        assertTrue(encoded.length < PAYLOAD.length);
        byte[] decoded = ContentEncoding.decompress(
                ContentEncoding.Coding.GZIP, encoded,
                ContentEncoding.DEFAULT_MAX_DECOMPRESSED_SIZE);
        assertArrayEquals(PAYLOAD, decoded);
    }

    @Test
    public void deflateRoundTrip() throws Exception {
        byte[] encoded = encodeFully(ContentEncoding.Coding.DEFLATE, PAYLOAD);
        byte[] decoded = ContentEncoding.decompress(
                ContentEncoding.Coding.DEFLATE, encoded,
                ContentEncoding.DEFAULT_MAX_DECOMPRESSED_SIZE);
        assertArrayEquals(PAYLOAD, decoded);
    }

    @Test
    public void brotliRoundTrip() throws Exception {
        byte[] encoded = encodeFully(ContentEncoding.Coding.BR, PAYLOAD);
        assertTrue(encoded.length < PAYLOAD.length);
        byte[] decoded = ContentEncoding.decompress(
                ContentEncoding.Coding.BR, encoded,
                ContentEncoding.DEFAULT_MAX_DECOMPRESSED_SIZE);
        assertArrayEquals(PAYLOAD, decoded);
    }

    @Test
    public void gzipIncrementalDecodeMatchesOneShot() throws Exception {
        byte[] encoded = encodeFully(ContentEncoding.Coding.GZIP, PAYLOAD);
        ContentEncoding.Decoder decoder = ContentEncoding.createDecoder(
                ContentEncoding.Coding.GZIP, ContentEncoding.DEFAULT_MAX_DECOMPRESSED_SIZE);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            int chunk = 37;
            for (int off = 0; off < encoded.length; off += chunk) {
                int len = Math.min(chunk, encoded.length - off);
                boolean end = off + len >= encoded.length;
                decoder.write(ByteBuffer.wrap(encoded, off, len), end);
                ByteBuffer plain;
                while ((plain = decoder.readDecoded()) != null) {
                    out.write(plain.array(), plain.position(), plain.remaining());
                }
            }
        } finally {
            decoder.close();
        }
        assertArrayEquals(PAYLOAD, out.toByteArray());
    }

    private static byte[] encodeFully(ContentEncoding.Coding coding, byte[] payload)
            throws ContentEncoding.ContentEncodingException {
        ContentEncoding.Encoder encoder = ContentEncoding.createEncoder(coding);
        try {
            encoder.write(ByteBuffer.wrap(payload), true);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            ByteBuffer chunk;
            while ((chunk = encoder.readEncoded()) != null) {
                out.write(chunk.array(), chunk.position(), chunk.remaining());
            }
            return out.toByteArray();
        } finally {
            encoder.close();
        }
    }
}
