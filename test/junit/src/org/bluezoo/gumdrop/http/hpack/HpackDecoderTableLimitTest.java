/*
 * HpackDecoderTableLimitTest.java
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


package org.bluezoo.gumdrop.http.hpack;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.bluezoo.gumdrop.http.HeaderFieldHandler;
import org.junit.Test;

/**
 * RFC 7541 section 4.2: the dynamic table's maximum size starts at the
 * protocol maximum (SETTINGS_HEADER_TABLE_SIZE) and an encoder may only lower
 * it with a size update. Until then a decoder must already evict to stay
 * within that maximum, or a peer could grow its memory use without bound by
 * sending literals with incremental indexing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HpackDecoderTableLimitTest {

    private static final HeaderFieldHandler IGNORE = new HeaderFieldHandler() {
        @Override
        public void field(ByteBuffer name, ByteBuffer value) {
        }
    };

    /** Literal with incremental indexing, new name, no Huffman coding. */
    private static byte[] indexedLiteral(String name, String value) {
        byte[] n = name.getBytes(StandardCharsets.US_ASCII);
        byte[] v = value.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x40);
        out.write(n.length);
        out.write(n, 0, n.length);
        out.write(v.length);
        out.write(v, 0, v.length);
        return out.toByteArray();
    }

    private static boolean canReference(Decoder d, int dynamicIndex) throws IOException {
        try {
            d.decode(ByteBuffer.wrap(new byte[] {(byte) (0x80 | (61 + dynamicIndex))}), IGNORE);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    public void tableIsBoundedByTheNegotiatedMaximumBeforeAnySizeUpdate() throws Exception {
        // Each entry is 5 + 8 + 32 = 45 octets; a 100-octet table holds two.
        Decoder d = new Decoder(100);
        for (int i = 0; i < 10; i++) {
            d.decode(ByteBuffer.wrap(indexedLiteral("x-h" + i + "xx", "valueNN")), IGNORE);
        }
        assertEquals("newest entry is kept", true, canReference(d, 1));
        assertEquals("second newest is kept", true, canReference(d, 2));
        assertEquals("older entries were evicted", false, canReference(d, 3));
    }

    @Test
    public void entryLargerThanTheTableEmptiesItAndIsNotAdded() throws Exception {
        Decoder d = new Decoder(50);
        d.decode(ByteBuffer.wrap(indexedLiteral("a", "b")), IGNORE);          // 34 octets: fits
        assertEquals(true, canReference(d, 1));
        d.decode(ByteBuffer.wrap(indexedLiteral("long-name", "long-value")), IGNORE);   // 51: does not
        assertEquals(false, canReference(d, 1));
    }

    @Test
    public void sizeUpdateAboveTheNegotiatedMaximumIsStillRefused() throws Exception {
        Decoder d = new Decoder(100);
        try {
            // 0x3F then 0xE1 0x1F is a dynamic table size update to 4096.
            d.decode(ByteBuffer.wrap(new byte[] {0x3F, (byte) 0xE1, 0x1F}), IGNORE);
            fail("expected an error");
        } catch (IOException expected) {
            // expected
        }
    }
}
