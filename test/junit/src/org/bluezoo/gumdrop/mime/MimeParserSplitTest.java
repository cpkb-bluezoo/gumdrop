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
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Split-point tests for {@link MimeParser}: long body lines in multipart
 * parts, header folding, malformed header lines and the unusual transfer
 * encodings, each fed in chunks of every size from one byte upwards so that
 * every position inside a line, a fold and a boundary delimiter is a chunk
 * edge at least once.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MimeParserSplitTest {

    /** Exposes the protected header-value decoder. */
    static final class Exposed extends MimeParser {
        String token(String value) {
            ByteBuffer buf = ByteBuffer.wrap(value.getBytes(StandardCharsets.ISO_8859_1));
            CharsetDecoder decoder = StandardCharsets.ISO_8859_1.newDecoder();
            return decodeTokenHeaderValue(buf, decoder);
        }
    }

    private static MimeParserBranchesTest.Rec run(MimeParser parser, String text, int chunk)
            throws MimeParseException {
        MimeParserBranchesTest.Rec rec = new MimeParserBranchesTest.Rec();
        parser.setHandler(rec);
        byte[] data = text.getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer buf = ByteBuffer.allocate(data.length + 8);
        int off = 0;
        while (off < data.length) {
            int n = Math.min(chunk, data.length - off);
            buf.put(data, off, n);
            off += n;
            buf.flip();
            parser.receive(buf);
            buf.compact();
        }
        buf.flip();
        parser.receive(buf);
        parser.close();
        return rec;
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    @Test
    public void longBodyLinesInsideMultipartPartsSurviveIntact() throws MimeParseException {
        String longLine = repeat('x', 20000);
        String msg = "Content-Type: multipart/mixed; boundary=b\r\n\r\n"
            + "--b\r\n\r\nshort\r\n" + longLine + "\r\nafter\r\n"
            + "--b\r\n\r\n" + longLine + "\r\n"
            + "--b--\r\n";
        int[] chunks = {1, 7, 4096, 30000};
        for (int i = 0; i < chunks.length; i++) {
            MimeParserBranchesTest.Rec rec = run(new MimeParser(), msg, chunks[i]);
            String expected = "short\r\n" + longLine + "\r\nafter" + longLine;
            assertEquals("chunk " + chunks[i], expected, rec.body.toString());
        }
    }

    @Test
    public void foldedTokenHeaderValuesAreUnfolded() {
        Exposed p = new Exposed();
        assertEquals("a b", p.token("a\r\n b"));
        assertEquals("a b", p.token("a\n\tb"));
        assertEquals("a b c", p.token("a\r\n b\r\n c"));
        assertEquals("a b", p.token("a\r\n \r\n b"));
        assertEquals("a", p.token("a\r\n "));
        assertEquals("a\r\nb", p.token("a\r\nb"));
        assertEquals("", p.token(""));
        assertEquals("x", p.token("  x  "));
    }

    @Test
    public void malformedHeaderLinesAreRejectedAtEverySplit() {
        String[] bad = {
            "No colon here\r\n\r\n",
            ": empty name\r\n\r\n",
            "Bad Name: x\r\n\r\n",
            "Ctl\u0001Name: x\r\n\r\n",
            " continuation first\r\n\r\n",
        };
        for (int i = 0; i < bad.length; i++) {
            for (int chunk = 1; chunk <= 4; chunk++) {
                try {
                    run(new MimeParser(), bad[i], chunk);
                    fail("accepted: " + bad[i]);
                } catch (MimeParseException expected) {
                    assertTrue(expected.getMessage() != null);
                }
            }
        }
    }

    @Test
    public void overlongHeaderLineAndValueAreRejected() {
        String line = "X-Long: " + repeat('a', 1000) + "\r\n\r\n";
        try {
            run(new MimeParser(), line, 100);
            fail("long line accepted");
        } catch (HeaderLineTooLongException expected) {
            assertTrue(expected.getMessage() != null);
        } catch (MimeParseException other) {
            fail("wrong exception " + other);
        }
        MimeParser small = new MimeParser();
        small.setMaxHeaderValueSize(20);
        String folded = "X-Folded: aaaaaaaaaa\r\n bbbbbbbbbb\r\n cccccccccc\r\n\r\n";
        try {
            run(small, folded, 3);
            fail("long value accepted");
        } catch (HeaderValueTooLongException expected) {
            assertTrue(expected.getMessage() != null);
        } catch (MimeParseException other) {
            fail("wrong exception " + other);
        }
    }

    @Test
    public void foldedHeadersGrowTheValueBufferAcrossSplits() throws MimeParseException {
        StringBuilder msg = new StringBuilder("Content-Description: start");
        for (int i = 0; i < 40; i++) {
            msg.append("\r\n ").append(repeat('d', 60));
        }
        msg.append("\r\n\r\nbody\r\n");
        for (int chunk = 1; chunk <= 64; chunk *= 4) {
            MimeParserBranchesTest.Rec rec = run(new MimeParser(), msg.toString(), chunk);
            boolean found = false;
            for (int i = 0; i < rec.events.size(); i++) {
                if (rec.events.get(i).startsWith("desc:start ")) {
                    found = true;
                }
            }
            assertTrue("chunk " + chunk, found);
            assertEquals("body\r\n", rec.body.toString());
        }
    }

    @Test
    public void unknownTransferEncodingPassesBodyThrough() throws MimeParseException {
        String msg = "Content-Transfer-Encoding: x-weird\r\n\r\nraw =3D body\r\n";
        for (int chunk = 1; chunk <= 5; chunk++) {
            MimeParserBranchesTest.Rec rec = run(new MimeParser(), msg, chunk);
            assertEquals("raw =3D body\r\n", rec.body.toString());
        }
    }

    @Test
    public void lenientBase64AcceptsOverlongLines() throws MimeParseException {
        String longLine = repeat('A', 100);
        String msg = "Content-Transfer-Encoding: base64\r\n\r\n" + longLine + "\r\n";
        MimeParser lenient = new MimeParser();
        lenient.setAllowMalformed(true);
        MimeParserBranchesTest.Rec rec = run(lenient, msg, 9);
        assertEquals(75, rec.body.length());
    }
}
