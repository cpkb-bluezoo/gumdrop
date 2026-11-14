/*
 * MimeParserConstantMemoryTest.java
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

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Shows that {@link MimeParser} holds memory that does not depend on the size
 * of the entity. A child JVM with a small heap parses an entity far larger
 * than that heap, a base64 part and a part of one enormous line. If the parser
 * held the body, or a line of it, the child would run out of memory.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MimeParserConstantMemoryTest {

    private static final String HEAP = "-Xmx32m";
    private static final long BASE64_BYTES = 512L << 20;
    private static final long LONG_LINE_BYTES = 256L << 20;

    /** An entity generated as it is read, so that none of it is held. */
    private static final class Generated extends InputStream {
        private final byte[][] pieces;
        private final long[] lengths;
        private int piece;
        private long done;

        Generated(byte[][] pieces, long[] lengths) {
            this.pieces = pieces;
            this.lengths = lengths;
        }

        @Override
        public int read() {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            while (piece < pieces.length && done >= lengths[piece]) {
                piece++;
                done = 0;
            }
            if (piece >= pieces.length) {
                return -1;
            }
            byte[] src = pieces[piece];
            int n = (int) Math.min(len, lengths[piece] - done);
            for (int i = 0; i < n; i++) {
                b[off + i] = src[(int) ((done + i) % src.length)];
            }
            done += n;
            return n;
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Run in the child JVM. */
    public static void main(String[] args) throws Exception {
        byte[] b64line = bytes("QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVphYmNkZWZnaGlqa2xtbm9wcXJzdHV2d3h5ejAxMjM0\r\n");
        byte[] head1 = bytes("Content-Type: multipart/mixed; boundary=\"bb\"\r\n\r\n--bb\r\n"
                + "Content-Type: application/octet-stream\r\nContent-Transfer-Encoding: base64\r\n\r\n");
        byte[] head2 = bytes("--bb\r\nContent-Type: application/octet-stream\r\n\r\n");
        byte[] tail = bytes("\r\n--bb--\r\n");
        long b64 = BASE64_BYTES / b64line.length * b64line.length;
        InputStream in = new Generated(
                new byte[][] {head1, b64line, head2, bytes("x"), tail},
                new long[] {head1.length, b64, head2.length, LONG_LINE_BYTES, tail.length});
        MimeParserBodyStreamingTest.Counter counter = new MimeParserBodyStreamingTest.Counter();
        MimeParser parser = new MimeParser();
        parser.setHandler(counter);
        parser.parse(in);
        long expected = b64 / b64line.length * 54 + LONG_LINE_BYTES;
        if (counter.starts != 3 || counter.largest > 8192 || counter.body < expected) {
            System.err.println("starts=" + counter.starts + " largest=" + counter.largest
                    + " body=" + counter.body + " expected>=" + expected);
            System.exit(2);
        }
    }

    @Test
    public void entityLargerThanTheHeapIsParsed() throws Exception {
        String java = new File(new File(System.getProperty("java.home"), "bin"), "java").getPath();
        ProcessBuilder pb = new ProcessBuilder(java, HEAP, "-cp", System.getProperty("java.class.path"),
                MimeParserConstantMemoryTest.class.getName());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) {
            out.append(line).append('\n');
        }
        assertEquals(out.toString(), 0, p.waitFor());
        assertTrue(out.toString(), out.length() == 0 || !out.toString().contains("OutOfMemory"));
    }
}
