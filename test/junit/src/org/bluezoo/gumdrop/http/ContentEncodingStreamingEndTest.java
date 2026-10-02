/*
 * ContentEncodingStreamingEndTest.java
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import static org.junit.Assert.*;

/**
 * Regression tests: encoders must emit the full stream when the body is
 * ended by an empty final write following earlier non-final writes (the
 * pattern the HTTP client uses).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContentEncodingStreamingEndTest {

    private static byte[] payload(int n) {
        byte[] b = new byte[n];
        int x = 12345;
        for (int i = 0; i < n; i++) {
            x = x * 1103515245 + 12345;
            b[i] = (byte) ('a' + ((x >>> 16) % 7));
        }
        return b;
    }

    private static byte[] encode(ContentEncoding.Coding coding, byte[] data, int chunk)
            throws Exception {
        ContentEncoding.Encoder enc = ContentEncoding.createEncoder(coding);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            for (int off = 0; off < data.length; off += chunk) {
                int len = Math.min(chunk, data.length - off);
                enc.write(ByteBuffer.wrap(data, off, len), false);
                collect(enc, out);
            }
            enc.write(ByteBuffer.allocate(0), true);
            collect(enc, out);
        } finally {
            enc.close();
        }
        return out.toByteArray();
    }

    private static void collect(ContentEncoding.Encoder enc, ByteArrayOutputStream out) {
        ByteBuffer b = enc.readEncoded();
        while (b != null) {
            out.write(b.array(), b.position(), b.remaining());
            b = enc.readEncoded();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n = in.read(buf);
        while (n >= 0) {
            out.write(buf, 0, n);
            n = in.read(buf);
        }
        return out.toByteArray();
    }

    private static void check(ContentEncoding.Coding coding, int size, int chunk)
            throws Exception {
        byte[] data = payload(size);
        byte[] packed = encode(coding, data, chunk);
        InputStream in;
        if (coding == ContentEncoding.Coding.GZIP) {
            in = new GZIPInputStream(new ByteArrayInputStream(packed));
        } else {
            in = new InflaterInputStream(new ByteArrayInputStream(packed));
        }
        assertArrayEquals("size=" + size + " chunk=" + chunk, data, readAll(in));
    }

    @Test
    public void gzipEmptyFinalWriteAfterData() throws Exception {
        int[] sizes = {1, 100, 5000, 200000};
        int[] chunks = {1, 7, 1000, 4096, 65536};
        for (int s : sizes) {
            for (int c : chunks) {
                if (s == 200000 && c == 1) {
                    continue;
                }
                check(ContentEncoding.Coding.GZIP, s, c);
            }
        }
    }

    @Test
    public void deflateEmptyFinalWriteAfterData() throws Exception {
        int[] sizes = {1, 100, 5000, 200000};
        int[] chunks = {1, 7, 1000, 4096, 65536};
        for (int s : sizes) {
            for (int c : chunks) {
                if (s == 200000 && c == 1) {
                    continue;
                }
                check(ContentEncoding.Coding.DEFLATE, s, c);
            }
        }
    }

    @Test
    public void gzipTrailerAppearsOnce() throws Exception {
        byte[] data = payload(300);
        ContentEncoding.Encoder enc = ContentEncoding.createEncoder(ContentEncoding.Coding.GZIP);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        enc.write(ByteBuffer.wrap(data), false);
        enc.write(ByteBuffer.allocate(0), true);
        enc.write(ByteBuffer.allocate(0), true);
        collect(enc, out);
        enc.close();
        byte[] packed = out.toByteArray();
        assertArrayEquals(data, readAll(new GZIPInputStream(new ByteArrayInputStream(packed))));
        byte[] once = encode(ContentEncoding.Coding.GZIP, data, 300);
        assertEquals(once.length, packed.length);
    }

    private static byte[] decodeChunked(ContentEncoding.Coding coding, byte[] packed, int chunk)
            throws Exception {
        ContentEncoding.Decoder dec = ContentEncoding.createDecoder(coding, 1 << 24);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            for (int off = 0; off < packed.length; off += chunk) {
                int len = Math.min(chunk, packed.length - off);
                boolean last = off + len >= packed.length;
                dec.write(ByteBuffer.wrap(packed, off, len), last);
                ByteBuffer b = dec.readDecoded();
                while (b != null) {
                    out.write(b.array(), b.position(), b.remaining());
                    b = dec.readDecoded();
                }
            }
        } finally {
            dec.close();
        }
        return out.toByteArray();
    }

    @Test
    public void decodersHandleAnyChunking() throws Exception {
        byte[] data = payload(20000);
        int[] chunks = {1, 3, 13, 4096};
        ContentEncoding.Coding[] codings = {ContentEncoding.Coding.GZIP,
            ContentEncoding.Coding.DEFLATE};
        for (ContentEncoding.Coding coding : codings) {
            byte[] packed = encode(coding, data, 5000);
            for (int c : chunks) {
                assertArrayEquals(coding + " chunk=" + c, data, decodeChunked(coding, packed, c));
            }
        }
    }

    @Test
    public void gzipDecoderRejectsCorruptTrailer() throws Exception {
        byte[] packed = encode(ContentEncoding.Coding.GZIP, payload(500), 500);
        packed[packed.length - 8] ^= 0x55;
        try {
            decodeChunked(ContentEncoding.Coding.GZIP, packed, 64);
            fail("corrupt CRC accepted");
        } catch (ContentEncoding.ContentEncodingException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
