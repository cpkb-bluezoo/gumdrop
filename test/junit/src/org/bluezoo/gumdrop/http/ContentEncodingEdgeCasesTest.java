/*
 * ContentEncodingEdgeCasesTest.java
 * Copyright (C) 2025 Chris Burdess
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

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * Edge-case tests for {@link ContentEncoding}: token parsing, decode limits,
 * truncation and argument validation. Round trips live in
 * {@code ContentEncodingTest}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContentEncodingEdgeCasesTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void tokenParsing() {
        assertNull(ContentEncoding.Coding.fromToken(null));
        assertNull(ContentEncoding.Coding.fromToken("identity"));
        assertEquals(ContentEncoding.Coding.BR, ContentEncoding.Coding.fromToken(" BR "));
        assertEquals(ContentEncoding.Coding.GZIP, ContentEncoding.Coding.fromToken("x-gzip"));
        assertEquals(ContentEncoding.Coding.GZIP, ContentEncoding.Coding.fromToken("gzip"));
        assertEquals(ContentEncoding.Coding.DEFLATE, ContentEncoding.Coding.fromToken("deflate"));
        assertEquals("gzip", ContentEncoding.Coding.GZIP.token());
    }

    @Test
    public void acceptEncodingSelection() {
        assertNull(ContentEncoding.selectFromAcceptEncoding(null));
        assertNull(ContentEncoding.selectFromAcceptEncoding(""));
        assertNull(ContentEncoding.selectFromAcceptEncoding("identity, *"));
        assertEquals(ContentEncoding.Coding.GZIP,
            ContentEncoding.selectFromAcceptEncoding("deflate;q=0.5, gzip;q=1.0"));
        assertEquals(ContentEncoding.Coding.DEFLATE,
            ContentEncoding.selectFromAcceptEncoding("deflate"));
    }

    @Test
    public void contentEncodingParsing() {
        assertNull(ContentEncoding.parseContentEncoding(null));
        assertNull(ContentEncoding.parseContentEncoding(""));
        assertNull(ContentEncoding.parseContentEncoding("identity"));
        assertEquals(ContentEncoding.Coding.GZIP, ContentEncoding.parseContentEncoding("gzip"));
        assertEquals(ContentEncoding.Coding.GZIP,
            ContentEncoding.parseContentEncoding("identity, gzip;q=1"));
        assertNull(ContentEncoding.parseContentEncoding("unknown"));
    }

    @Test(expected = NullPointerException.class)
    public void createEncoderRejectsNull() {
        ContentEncoding.createEncoder(null);
    }

    @Test(expected = NullPointerException.class)
    public void compressFullyRejectsNull() throws Exception {
        ContentEncoding.compressFully(null, new byte[0]);
    }

    @Test(expected = NullPointerException.class)
    public void createDecoderRejectsNull() {
        ContentEncoding.createDecoder(null, 10);
    }

    @Test(expected = IllegalArgumentException.class)
    public void createDecoderRejectsNonPositiveLimit() {
        ContentEncoding.createDecoder(ContentEncoding.Coding.GZIP, 0);
    }

    @Test(expected = NullPointerException.class)
    public void decompressRejectsNull() throws Exception {
        ContentEncoding.decompress(null, new byte[1], 10);
    }

    @Test
    public void decompressEmptyInputIsEmpty() throws Exception {
        byte[] empty = new byte[0];
        assertSame(empty, ContentEncoding.decompress(ContentEncoding.Coding.GZIP, empty, 10));
    }

    @Test
    public void gzipDecodeEnforcesSizeLimit() throws Exception {
        byte[] packed = ContentEncoding.compressFully(ContentEncoding.Coding.GZIP,
            new byte[10000]);
        try {
            ContentEncoding.decompress(ContentEncoding.Coding.GZIP, packed, 100);
            fail("expected limit failure");
        } catch (ContentEncoding.ContentEncodingException expected) {
            assertTrue(expected.getMessage().contains("exceeds limit"));
        }
    }

    @Test
    public void deflateDecodeEnforcesSizeLimit() throws Exception {
        byte[] packed = ContentEncoding.compressFully(ContentEncoding.Coding.DEFLATE,
            new byte[10000]);
        try {
            ContentEncoding.decompress(ContentEncoding.Coding.DEFLATE, packed, 100);
            fail("expected limit failure");
        } catch (ContentEncoding.ContentEncodingException expected) {
            assertTrue(expected.getMessage().contains("exceeds limit"));
        }
    }

    @Test
    public void truncatedGzipIsRejected() throws Exception {
        byte[] packed = ContentEncoding.compressFully(ContentEncoding.Coding.GZIP,
            bytes("hello hello hello hello hello"));
        byte[] cut = new byte[packed.length - 6];
        System.arraycopy(packed, 0, cut, 0, cut.length);
        try {
            ContentEncoding.decompress(ContentEncoding.Coding.GZIP, cut, 1000);
            fail("expected truncation failure");
        } catch (ContentEncoding.ContentEncodingException expected) {
            assertTrue(expected.getMessage().contains("truncated"));
        }
    }

    @Test
    public void gzipShorterThanHeaderIsRejected() {
        try {
            ContentEncoding.decompress(ContentEncoding.Coding.GZIP, new byte[] { 0x1f, (byte) 0x8b, 8 }, 100);
            fail("expected truncation failure");
        } catch (ContentEncoding.ContentEncodingException expected) {
            assertTrue(expected.getMessage().contains("truncated"));
        }
    }

    @Test
    public void truncatedDeflateIsRejected() throws Exception {
        byte[] packed = ContentEncoding.compressFully(ContentEncoding.Coding.DEFLATE,
            bytes("hello hello hello hello hello"));
        byte[] cut = new byte[packed.length / 2];
        System.arraycopy(packed, 0, cut, 0, cut.length);
        try {
            ContentEncoding.decompress(ContentEncoding.Coding.DEFLATE, cut, 1000);
            fail("expected truncation failure");
        } catch (ContentEncoding.ContentEncodingException expected) {
            assertTrue(expected.getMessage().contains("truncated"));
        }
    }

    @Test
    public void garbageDeflateIsRejected() {
        byte[] garbage = new byte[64];
        Arrays.fill(garbage, (byte) 0xFF);
        try {
            ContentEncoding.decompress(ContentEncoding.Coding.DEFLATE, garbage, 1000);
            fail("expected inflation failure");
        } catch (ContentEncoding.ContentEncodingException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static void assertIncrementalRoundTrip(ContentEncoding.Coding coding) throws Exception {
        ContentEncoding.Encoder encoder = ContentEncoding.createEncoder(coding);
        encoder.write(ByteBuffer.wrap(bytes("part one ")), false);
        encoder.write(ByteBuffer.wrap(bytes("part two")), true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer chunk = encoder.readEncoded();
        while (chunk != null) {
            out.write(chunk.array(), chunk.position(), chunk.remaining());
            chunk = encoder.readEncoded();
        }
        encoder.close();
        byte[] plain = ContentEncoding.decompress(coding, out.toByteArray(), 1000);
        assertEquals("part one part two", new String(plain, StandardCharsets.UTF_8));
    }

    /**
     * Output queued by one write() must not be overwritten by a later write():
     * the encoders once queued views of a shared scratch buffer.
     */
    @Test
    public void incrementalDeflateEncoderDoesNotCorruptQueuedOutput() throws Exception {
        assertIncrementalRoundTrip(ContentEncoding.Coding.DEFLATE);
    }

    @Test
    public void incrementalGzipEncoderDoesNotCorruptQueuedOutput() throws Exception {
        assertIncrementalRoundTrip(ContentEncoding.Coding.GZIP);
    }

    @Test
    public void exceptionConstructors() {
        RuntimeException cause = new RuntimeException("c");
        assertEquals("m", new ContentEncoding.ContentEncodingException("m").getMessage());
        assertSame(cause, new ContentEncoding.ContentEncodingException("m", cause).getCause());
    }
}
