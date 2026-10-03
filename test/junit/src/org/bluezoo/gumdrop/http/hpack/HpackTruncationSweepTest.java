/*
 * HpackTruncationSweepTest.java
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

import java.util.List;
import java.util.ArrayList;
import org.bluezoo.gumdrop.http.HeaderFieldHandler;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.http.Header;
import org.junit.Test;

/**
 * Feeds every prefix of an encoded header block, and every single-byte
 * value as a lone block, to the HPACK {@link Decoder}: truncated peer
 * input must be an {@link IOException} (a compression error), never a
 * runtime exception.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HpackTruncationSweepTest {

    private static class Collect implements HeaderFieldHandler {
        int count;

        @Override
        public void field(java.nio.ByteBuffer name, java.nio.ByteBuffer value) {
                Header header = Header.ofOctets(name, value);
            count++;
        }
    }

    @Test
    public void testEveryPrefixOfAHeaderBlockIsDecodedOrAnIoError() throws Exception {
        Encoder encoder = new Encoder(4096, 65536);
        List<Header> headers = new ArrayList<Header>();
        headers.add(new Header(":method", "GET"));
        headers.add(new Header(":path", "/a/long/path/for/literal/coding"));
        headers.add(new Header("x-custom", "value-that-needs-a-literal-representation"));
        headers.add(new Header("cookie", "a=b; c=d"));
        ByteBuffer buf = ByteBuffer.allocate(4096);
        encoder.encode(buf, headers);
        buf.flip();
        byte[] whole = new byte[buf.remaining()];
        buf.get(whole);
        for (int cut = 0; cut <= whole.length; cut++) {
            byte[] prefix = new byte[cut];
            System.arraycopy(whole, 0, prefix, 0, cut);
            Decoder decoder = new Decoder(4096, 65536);
            Collect collect = new Collect();
            try {
                decoder.decode(ByteBuffer.wrap(prefix), collect);
                if (cut == whole.length) {
                    assertEquals(4, collect.count);
                }
            } catch (IOException expected) {
                assertTrue("cut " + cut, cut < whole.length);
            }
        }
    }

    @Test
    public void testEveryLoneByteIsDecodedOrAnIoError() {
        for (int value = 0; value < 256; value++) {
            Decoder decoder = new Decoder(4096, 65536);
            Collect collect = new Collect();
            try {
                decoder.decode(ByteBuffer.wrap(new byte[] {(byte) value}), collect);
            } catch (IOException expected) {
                assertEquals(0, collect.count);
            }
        }
    }
}
