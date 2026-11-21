/*
 * RespDecoderChunkingTest.java
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

package org.bluezoo.gumdrop.redis.codec;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The decoder must give the same values however the bytes are split, and
 * must not parse a reply again for each read that completes part of it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RespDecoderChunkingTest {

    private static String describe(RespValue v) {
        if (v.getType() == null) {
            return "NULLVALUE";
        }
        switch (v.getType()) {
            case ARRAY:
            case SET:
            case PUSH:
                List<RespValue> elements = v.asArray();
                StringBuilder sb = new StringBuilder(v.getType().name()).append('[');
                for (RespValue e : elements) {
                    sb.append(describe(e)).append(',');
                }
                return sb.append(']').toString();
            case MAP:
                StringBuilder m = new StringBuilder("MAP{");
                for (Map.Entry<RespValue, RespValue> e : v.asMap().entrySet()) {
                    m.append(describe(e.getKey())).append("=>").append(describe(e.getValue())).append(',');
                }
                return m.append('}').toString();
            case BLOB_ERROR:
                return "BLOB<" + v.getErrorMessage() + ">";
            case BULK_STRING:
                return v.getType() + "<" + new String(v.asBytes(), StandardCharsets.UTF_8) + ">";
            case VERBATIM_STRING:
                return "VERBATIM<" + v.getVerbatimEncoding() + ":"
                        + v.asString() + ">";
            case INTEGER:
                return "INT<" + v.asLong() + ">";
            case DOUBLE:
                return "DOUBLE<" + v.asDouble() + ">";
            case BOOLEAN:
                return "BOOL<" + v.asBoolean() + ">";
            default:
                return v.getType() + "<" + (v.isNull() ? "" : v.asString()) + ">";
        }
    }

    private static List<String> decode(byte[] wire, int chunk) throws RespException {
        ValueDecoder decoder = new ValueDecoder();
        List<String> out = new ArrayList<String>();
        int off = 0;
        while (off < wire.length) {
            int n = Math.min(chunk, wire.length - off);
            decoder.receive(ByteBuffer.wrap(wire, off, n));
            off += n;
            RespValue v;
            while ((v = decoder.next()) != null) {
                out.add(describe(v));
            }
        }
        return out;
    }

    private static String wire() {
        StringBuilder sb = new StringBuilder();
        sb.append("+OK\r\n");
        sb.append("-ERR unknown command\r\n");
        sb.append(":-12345\r\n");
        sb.append(":+7\r\n");
        sb.append("$5\r\nhello\r\n");
        sb.append("$0\r\n\r\n");
        sb.append("$-1\r\n");
        sb.append("*-1\r\n");
        sb.append("*0\r\n");
        sb.append("*3\r\n:1\r\n$3\r\nabc\r\n*2\r\n+x\r\n*1\r\n:9\r\n");
        sb.append("%2\r\n+a\r\n:1\r\n$1\r\nb\r\n*2\r\n:2\r\n:3\r\n");
        sb.append("%0\r\n");
        sb.append("~3\r\n:1\r\n:2\r\n:3\r\n");
        sb.append(">3\r\n$7\r\nmessage\r\n$2\r\nch\r\n$4\r\ndata\r\n");
        sb.append(",3.14\r\n,inf\r\n,-inf\r\n,nan\r\n");
        sb.append("#t\r\n#f\r\n_\r\n");
        sb.append("(3492890328409238509324850943850943825024385\r\n");
        sb.append("=15\r\ntxt:Some string\r\n");
        sb.append("=8\r\nmkd:abcd\r\n");
        sb.append("!21\r\nSYNTAX invalid syntax\r\n");
        sb.append("*2\r\n%1\r\n+k\r\n~2\r\n:1\r\n:2\r\n>1\r\n+p\r\n");
        sb.append("$2\r\né\r\n");
        sb.append("*1000\r\n");
        for (int i = 0; i < 1000; i++) {
            sb.append(":").append(i).append("\r\n");
        }
        return sb.toString();
    }

    @Test
    public void everyChunkSizeGivesTheSameValues() throws RespException {
        byte[] bytes = wire().getBytes(StandardCharsets.UTF_8);
        List<String> whole = decode(bytes, bytes.length);
        assertEquals(28, whole.size());
        int[] chunks = { 1, 2, 3, 5, 7, 64, 512, 16384 };
        for (int i = 0; i < chunks.length; i++) {
            assertEquals("chunk " + chunks[i], whole, decode(bytes, chunks[i]));
        }
    }

    @Test
    public void integersAtTheLimitsOfALongDecode() throws RespException {
        byte[] bytes = (":-9223372036854775808\r\n:9223372036854775807\r\n:0\r\n:-0\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        assertEquals("[INT<-9223372036854775808>, INT<9223372036854775807>, INT<0>, INT<0>]",
                decode(bytes, 1).toString());
    }

    @Test
    public void anIntegerOutOfRangeOrMalformedIsRejected() {
        String[] bad = { ":9223372036854775808\r\n", ":-9223372036854775809\r\n", ":\r\n",
            ":-\r\n", ":+\r\n", ":1 \r\n", ":12a\r\n", "*-2147483649\r\n" };
        for (int i = 0; i < bad.length; i++) {
            ValueDecoder decoder = new ValueDecoder();
            try {
                decoder.receive(ByteBuffer.wrap(bad[i].getBytes(StandardCharsets.US_ASCII)));
                decoder.next();
                fail("expected RespException for " + bad[i]);
            } catch (RespException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    @Test
    public void aMalformedLengthIsRejectedWhenSplit() {
        byte[] bytes = "$1x\r\n".getBytes(StandardCharsets.US_ASCII);
        ValueDecoder decoder = new ValueDecoder();
        try {
            for (int i = 0; i < bytes.length; i++) {
                decoder.receive(ByteBuffer.wrap(bytes, i, 1));
                decoder.next();
            }
            fail("expected RespException");
        } catch (RespException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    /**
     * A reply that arrives in many reads must be parsed once. An array of
     * 100 bulk strings of 1 KiB in 512-byte reads used to allocate about
     * 11 MB, by parsing the whole array again on every read.
     */
    @Test
    public void aReplyInManyReadsIsNotParsedAgainOnEachRead() throws RespException {
        StringBuilder sb = new StringBuilder("*100\r\n");
        for (int i = 0; i < 100; i++) {
            sb.append("$1024\r\n");
            for (int j = 0; j < 1024; j++) {
                sb.append('x');
            }
            sb.append("\r\n");
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.US_ASCII);
        ValueDecoder decoder = new ValueDecoder();
        decodeOnce(decoder, bytes, 512); // warm up
        com.sun.management.ThreadMXBean mx =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().getId();
        long before = mx.getThreadAllocatedBytes(id);
        decodeOnce(decoder, bytes, 512);
        long allocated = mx.getThreadAllocatedBytes(id) - before;
        assertTrue("allocated " + allocated + " bytes", allocated < 1024 * 1024);
    }

    private static void decodeOnce(ValueDecoder decoder, byte[] bytes, int chunk) throws RespException {
        RespValue v = null;
        int off = 0;
        while (off < bytes.length) {
            int n = Math.min(chunk, bytes.length - off);
            decoder.receive(ByteBuffer.wrap(bytes, off, n));
            off += n;
            v = decoder.next();
        }
        assertEquals(100, v.asArray().size());
    }

}
