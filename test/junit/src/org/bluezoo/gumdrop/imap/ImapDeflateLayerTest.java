/*
 * ImapDeflateLayerTest.java
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

package org.bluezoo.gumdrop.imap;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ImapDeflateLayer}.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
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
