/*
 * FTPAsciiLineEndingsTest.java
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

package org.bluezoo.gumdrop.ftp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for {@link FtpAsciiLineEndings}, covering RFC 959 section 3.1.1.1
 * NVT-ASCII line-ending conversion in both directions, including the
 * cross-chunk CRLF handling that a streaming transfer requires.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FTPAsciiLineEndingsTest {

    private static ByteBuffer wrap(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] bytes(ByteBuffer b) {
        byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }

    private static String str(ByteBuffer b) {
        return new String(bytes(b), StandardCharsets.US_ASCII);
    }

    // ── encode: local -> network (bare LF becomes CRLF) ──

    @Test
    public void testEncodeBareLfBecomesCrlf() {
        FtpAsciiLineEndings codec = new FtpAsciiLineEndings();
        assertEquals("a\r\nb\r\n", str(codec.encode(wrap("a\nb\n"))));
    }

    @Test
    public void testEncodePreservesExistingCrlf() {
        FtpAsciiLineEndings codec = new FtpAsciiLineEndings();
        assertEquals("a\r\nb\r\n", str(codec.encode(wrap("a\r\nb\r\n"))));
    }

    @Test
    public void testEncodeNoNewlinesUnchanged() {
        FtpAsciiLineEndings codec = new FtpAsciiLineEndings();
        byte[] in = {0x00, 0x01, 0x02, 0x7F, (byte) 0x80};
        byte[] out = bytes(codec.encode(ByteBuffer.wrap(in)));
        assertArrayEquals(in, out);
    }

    @Test
    public void testEncodeCrlfSplitAcrossChunksNotDoubled() {
        // A CRLF straddling two reads must stay a single CRLF: the LF at the
        // start of chunk 2 must not gain a second CR.
        FtpAsciiLineEndings codec = new FtpAsciiLineEndings();
        String c1 = str(codec.encode(wrap("line1\r")));
        String c2 = str(codec.encode(wrap("\nline2\n")));
        assertEquals("line1\r", c1);
        assertEquals("\nline2\r\n", c2);
        assertEquals("line1\r\nline2\r\n", c1 + c2);
    }

    // ── decode: network -> local (CR stripped so CRLF becomes LF) ──

    @Test
    public void testDecodeStripsCr() {
        assertEquals("a\nb\n", str(FtpAsciiLineEndings.decode(wrap("a\r\nb\r\n"))));
    }

    @Test
    public void testDecodeBareLfUnchanged() {
        assertEquals("a\nb\n", str(FtpAsciiLineEndings.decode(wrap("a\nb\n"))));
    }

    @Test
    public void testDecodeCrSplitAcrossChunks() {
        // Stateless CR-stripping is inherently correct across chunk boundaries.
        String c1 = str(FtpAsciiLineEndings.decode(wrap("line1\r")));
        String c2 = str(FtpAsciiLineEndings.decode(wrap("\nline2\r\n")));
        assertEquals("line1", c1);
        assertEquals("\nline2\n", c2);
        assertEquals("line1\nline2\n", c1 + c2);
    }

    @Test
    public void testDecodeAllCrProducesEmpty() {
        assertEquals(0, FtpAsciiLineEndings.decode(wrap("\r\r\r")).remaining());
    }

    // ── round trip ──

    @Test
    public void testRoundTripLocalThroughNetworkBackToLocal() {
        String local = "alpha\nbeta\ngamma\n";
        FtpAsciiLineEndings encoder = new FtpAsciiLineEndings();
        byte[] network = bytes(encoder.encode(wrap(local)));
        assertEquals("alpha\r\nbeta\r\ngamma\r\n",
                new String(network, StandardCharsets.US_ASCII));
        byte[] back = bytes(FtpAsciiLineEndings.decode(ByteBuffer.wrap(network)));
        assertEquals(local, new String(back, StandardCharsets.US_ASCII));
    }
}
