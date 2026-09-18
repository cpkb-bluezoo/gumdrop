/*
 * ContentEncodingTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
