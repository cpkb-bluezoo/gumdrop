/*
 * FTPAsciiLineEndingsTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 */

package org.bluezoo.gumdrop.ftp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for {@link FTPAsciiLineEndings}, covering RFC 959 section 3.1.1.1
 * NVT-ASCII line-ending conversion in both directions, including the
 * cross-chunk CRLF handling that a streaming transfer requires.
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
        FTPAsciiLineEndings codec = new FTPAsciiLineEndings();
        assertEquals("a\r\nb\r\n", str(codec.encode(wrap("a\nb\n"))));
    }

    @Test
    public void testEncodePreservesExistingCrlf() {
        FTPAsciiLineEndings codec = new FTPAsciiLineEndings();
        assertEquals("a\r\nb\r\n", str(codec.encode(wrap("a\r\nb\r\n"))));
    }

    @Test
    public void testEncodeNoNewlinesUnchanged() {
        FTPAsciiLineEndings codec = new FTPAsciiLineEndings();
        byte[] in = {0x00, 0x01, 0x02, 0x7F, (byte) 0x80};
        byte[] out = bytes(codec.encode(ByteBuffer.wrap(in)));
        assertArrayEquals(in, out);
    }

    @Test
    public void testEncodeCrlfSplitAcrossChunksNotDoubled() {
        // A CRLF straddling two reads must stay a single CRLF: the LF at the
        // start of chunk 2 must not gain a second CR.
        FTPAsciiLineEndings codec = new FTPAsciiLineEndings();
        String c1 = str(codec.encode(wrap("line1\r")));
        String c2 = str(codec.encode(wrap("\nline2\n")));
        assertEquals("line1\r", c1);
        assertEquals("\nline2\r\n", c2);
        assertEquals("line1\r\nline2\r\n", c1 + c2);
    }

    // ── decode: network -> local (CR stripped so CRLF becomes LF) ──

    @Test
    public void testDecodeStripsCr() {
        assertEquals("a\nb\n", str(FTPAsciiLineEndings.decode(wrap("a\r\nb\r\n"))));
    }

    @Test
    public void testDecodeBareLfUnchanged() {
        assertEquals("a\nb\n", str(FTPAsciiLineEndings.decode(wrap("a\nb\n"))));
    }

    @Test
    public void testDecodeCrSplitAcrossChunks() {
        // Stateless CR-stripping is inherently correct across chunk boundaries.
        String c1 = str(FTPAsciiLineEndings.decode(wrap("line1\r")));
        String c2 = str(FTPAsciiLineEndings.decode(wrap("\nline2\r\n")));
        assertEquals("line1", c1);
        assertEquals("\nline2\n", c2);
        assertEquals("line1\nline2\n", c1 + c2);
    }

    @Test
    public void testDecodeAllCrProducesEmpty() {
        assertEquals(0, FTPAsciiLineEndings.decode(wrap("\r\r\r")).remaining());
    }

    // ── round trip ──

    @Test
    public void testRoundTripLocalThroughNetworkBackToLocal() {
        String local = "alpha\nbeta\ngamma\n";
        FTPAsciiLineEndings encoder = new FTPAsciiLineEndings();
        byte[] network = bytes(encoder.encode(wrap(local)));
        assertEquals("alpha\r\nbeta\r\ngamma\r\n",
                new String(network, StandardCharsets.US_ASCII));
        byte[] back = bytes(FTPAsciiLineEndings.decode(ByteBuffer.wrap(network)));
        assertEquals(local, new String(back, StandardCharsets.US_ASCII));
    }
}
