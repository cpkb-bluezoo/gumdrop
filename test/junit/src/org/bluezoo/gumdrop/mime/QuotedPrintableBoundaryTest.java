/*
 * StreamH2WebSocketUpgradeTest.java
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
package org.bluezoo.gumdrop.mime;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Boundary tests for {@link QuotedPrintableDecoder}: escapes cut at the end of
 * a chunk (with and without end of stream), soft line breaks, invalid escapes
 * and output limits. Every case is run on heap and direct buffers, which use
 * different code paths and must agree exactly.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuotedPrintableBoundaryTest {

    private static final String[] INPUTS = {
        "plain", "a=3Db", "a=3db", "=41=42=43", "soft=\r\nbreak", "soft=\nbreak", "=\r\n", "=\n",
        "x=ZZy", "x=Z", "x=Zy", "=", "=A", "=\r", "=\rX", "=\r\nX", "ab=", "ab=A", "ab=\r", "=3D=",
        "=ZZ=ZZ", "end=", "=4", "=41", "=4G", "=G4", "a=\nb=\r\nc=3D", "", "=\r\r\n", "=\n\n",
    };

    private static String run(String input, int dstCapacity, int max, boolean eos, boolean direct) {
        byte[] data = input.getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer src;
        ByteBuffer dst;
        if (direct) {
            src = ByteBuffer.allocateDirect(data.length);
            src.put(data);
            src.flip();
            dst = ByteBuffer.allocateDirect(dstCapacity);
        } else {
            src = ByteBuffer.wrap(data);
            dst = ByteBuffer.allocate(dstCapacity);
        }
        int consumed = QuotedPrintableDecoder.decode(src, dst, max, eos);
        dst.flip();
        byte[] out = new byte[dst.remaining()];
        dst.get(out);
        return consumed + "|" + src.position() + "|" + new String(out, StandardCharsets.ISO_8859_1);
    }

    @Test
    public void heapAndDirectBuffersAgreeOnEveryBoundary() {
        int[][] limits = {{64, 64}, {2, 64}, {64, 2}, {1, 1}, {3, 3}, {0, 5}, {5, 0}};
        for (int i = 0; i < INPUTS.length; i++) {
            for (int j = 0; j < limits.length; j++) {
                for (int e = 0; e < 2; e++) {
                    boolean eos = e == 1;
                    String heap = run(INPUTS[i], limits[j][0], limits[j][1], eos, false);
                    String direct = run(INPUTS[i], limits[j][0], limits[j][1], eos, true);
                    assertEquals("input=" + INPUTS[i].replace("\r", "\\r").replace("\n", "\\n")
                        + " cap=" + limits[j][0] + " max=" + limits[j][1] + " eos=" + eos, heap, direct);
                }
            }
        }
    }

    @Test
    public void completeInputDecodesWithoutEndOfStream() {
        assertEquals("5|5|plain", run("plain", 64, 64, false, false));
        assertEquals("5|5|a=b", run("a=3Db", 64, 64, false, false));
        assertEquals("5|5|a=b", run("a=3db", 64, 64, false, false));
        assertEquals("9|9|ABC", run("=41=42=43", 64, 64, false, false));
        assertEquals("12|12|softbreak", run("soft=\r\nbreak", 64, 64, false, false));
        assertEquals("11|11|softbreak", run("soft=\nbreak", 64, 64, false, false));
        assertEquals("5|5|x=ZZy", run("x=ZZy", 64, 64, false, false));
    }

    @Test
    public void incompleteEscapeAtEndWaitsForMoreData() {
        assertEquals("0|0|", run("=", 64, 64, false, false));
        assertEquals("0|0|", run("=A", 64, 64, false, false));
        assertEquals("0|0|", run("=\r", 64, 64, false, false));
        assertEquals("2|2|ab", run("ab=", 64, 64, false, false));
        assertEquals("2|2|ab", run("ab=A", 64, 64, false, false));
        assertEquals("2|2|", run("=\n", 64, 64, false, false));
        assertEquals("2|2|", run("=\n", 64, 64, true, false));
    }

    @Test
    public void incompleteEscapeAtEndIsLiteralAtEndOfStream() {
        assertEquals("1|1|=", run("=", 64, 64, true, false));
        assertEquals("2|2|=A", run("=A", 64, 64, true, false));
        assertEquals("2|2|=\r", run("=\r", 64, 64, true, false));
        assertEquals("4|4|ab=A", run("ab=A", 64, 64, true, false));
        assertEquals("3|3|ab=", run("ab=", 64, 64, true, false));
    }

    @Test
    public void invalidEscapesAreKeptLiterally() {
        assertEquals("6|6|=ZZ=ZZ", run("=ZZ=ZZ", 64, 64, false, false));
        assertEquals("3|3|=4G", run("=4G", 64, 64, false, false));
        assertEquals("3|3|=G4", run("=G4", 64, 64, false, false));
    }

    @Test
    public void outputLimitStopsBeforeTheNextByte() {
        assertEquals("3|3|abc", run("abcdef", 64, 3, false, false));
        assertEquals("3|3|abc", run("abcdef", 3, 64, false, false));
        assertEquals("6|6|AB", run("=41=42=43", 64, 2, false, false));
        assertEquals("2|2|ab", run("ab=ZZ", 64, 2, false, false));
        assertEquals("0|0|", run("abc", 0, 5, false, false));
        assertEquals("0|0|", run("abc", 5, 0, false, false));
    }

    @Test
    public void estimateDecodedSizeNeverExceedsTheInput() {
        assertEquals(0, QuotedPrintableDecoder.estimateDecodedSize(0));
        assertEquals(123, QuotedPrintableDecoder.estimateDecodedSize(123));
    }
}
