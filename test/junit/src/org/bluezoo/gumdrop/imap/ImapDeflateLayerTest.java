/*
 * ImapDeflateLayerTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.imap;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ImapDeflateLayer}.
 */
public class ImapDeflateLayerTest {

    @Test
    public void testRoundTripSingleLine() throws DataFormatException {
        ImapDeflateLayer client = new ImapDeflateLayer();
        ImapDeflateLayer server = new ImapDeflateLayer();
        byte[] plain = "a001 NOOP\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] onWire = client.compressAndFlush(plain);
        byte[] decoded = server.inflate(ByteBuffer.wrap(onWire));
        assertArrayEquals(plain, decoded);
        client.close();
        server.close();
    }

    @Test
    public void testRoundTripMultipleLines() throws DataFormatException {
        ImapDeflateLayer enc = new ImapDeflateLayer();
        ImapDeflateLayer dec = new ImapDeflateLayer();
        String[] lines = {
            "tag1 OK DEFLATE active\r\n",
            "* CAPABILITY IMAP4rev2\r\n",
            "tag2 OK NOOP completed\r\n"
        };
        for (String line : lines) {
            byte[] plain = line.getBytes(StandardCharsets.US_ASCII);
            byte[] wire = enc.compressAndFlush(plain);
            byte[] out = dec.inflate(ByteBuffer.wrap(wire));
            assertEquals(line, new String(out, StandardCharsets.US_ASCII));
        }
        enc.close();
        dec.close();
    }
}
