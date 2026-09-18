/*
 * HttpContentCodingTest.java
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

public class HttpContentCodingTest {

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
        assertEquals(HttpContentCoding.Coding.BR,
                HttpContentCoding.selectFromAcceptEncoding("gzip, deflate, br"));
    }

    @Test
    public void selectFromAcceptEncodingSkipsIdentity() {
        assertNull(HttpContentCoding.selectFromAcceptEncoding("identity"));
    }

    @Test
    public void gzipRoundTripViaEncoderAndDecompress() throws Exception {
        byte[] encoded = encodeFully(HttpContentCoding.Coding.GZIP, PAYLOAD);
        assertTrue(encoded.length < PAYLOAD.length);
        byte[] decoded = HttpContentCoding.decompress(
                HttpContentCoding.Coding.GZIP, encoded,
                HttpContentCoding.DEFAULT_MAX_DECOMPRESSED_SIZE);
        assertArrayEquals(PAYLOAD, decoded);
    }

    @Test
    public void deflateRoundTrip() throws Exception {
        byte[] encoded = encodeFully(HttpContentCoding.Coding.DEFLATE, PAYLOAD);
        byte[] decoded = HttpContentCoding.decompress(
                HttpContentCoding.Coding.DEFLATE, encoded,
                HttpContentCoding.DEFAULT_MAX_DECOMPRESSED_SIZE);
        assertArrayEquals(PAYLOAD, decoded);
    }

    @Test
    public void brotliRoundTrip() throws Exception {
        byte[] encoded = encodeFully(HttpContentCoding.Coding.BR, PAYLOAD);
        assertTrue(encoded.length < PAYLOAD.length);
        byte[] decoded = HttpContentCoding.decompress(
                HttpContentCoding.Coding.BR, encoded,
                HttpContentCoding.DEFAULT_MAX_DECOMPRESSED_SIZE);
        assertArrayEquals(PAYLOAD, decoded);
    }

    private static byte[] encodeFully(HttpContentCoding.Coding coding, byte[] payload)
            throws HttpContentCoding.HttpContentCodingException {
        HttpContentCoding.Encoder encoder = HttpContentCoding.createEncoder(coding);
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
