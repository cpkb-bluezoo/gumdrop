/*
 * DecoderMalformedInputTest.java
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

import org.bluezoo.gumdrop.http.HeaderFieldHandler;
import org.junit.Test;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.http.Header;

/**
 * Regression tests for issue #255 — malformed HPACK input found by JQF/Zest
 * fuzzing that threw an unchecked exception instead of the documented
 * {@link IOException}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DecoderMalformedInputTest {

    private static final HeaderFieldHandler NOOP_HANDLER = new HeaderFieldHandler() {
        @Override public void field(java.nio.ByteBuffer name, java.nio.ByteBuffer value) {
                Header header = Header.ofOctets(name, value); }
    };

    /**
     * Literal header field without indexing, new name "x" (1 byte), then
     * the buffer ends before the value-length byte. RFC 7541 section 6.2.2.
     */
    @Test
    public void testTruncatedValueThrowsIOException() {
        byte[] data = new byte[] {
            0x00, // literal without indexing, index=0 (new name)
            0x01, // name length=1, no Huffman
            'x',  // name "x"
            // value-length byte and value are missing entirely
        };
        Decoder decoder = new Decoder(4096);
        try {
            decoder.decode(ByteBuffer.wrap(data), NOOP_HANDLER);
            fail("expected an IOException for a truncated header field");
        } catch (IOException expected) {
            // expected: a clean decode error, not an unchecked exception
        }
    }

    /**
     * Literal header field without indexing, new name "x", value containing
     * a bare CR (0x0D), which is not valid in an HTTP field value
     * (RFC 9110 section 5.5). RFC 7541 section 6.2.2.
     *
     * <p>The decoder does not judge it: it must consume the whole block to keep
     * its dynamic table in step with the peer's (RFC 9113 section 4.3), so it
     * delivers the field. The receiver's field check then rejects it, as a
     * stream error (RFC 9113 section 8.1.1).
     */
    @Test
    public void testInvalidValueCharacterIsDeliveredAndRejectedByTheReceiver() throws IOException {
        byte[] data = new byte[] {
            0x00, // literal without indexing, index=0 (new name)
            0x01, // name length=1, no Huffman
            'x',  // name "x"
            0x01, // value length=1, no Huffman
            0x0D, // value: bare CR, invalid in a header value
        };
        final java.util.List<Header> seen = new java.util.ArrayList<Header>();
        final boolean[] rejected = new boolean[1];
        new Decoder(4096).decode(ByteBuffer.wrap(data), new HeaderFieldHandler() {
            @Override
            public void field(ByteBuffer name, ByteBuffer value) {
                try {
                    seen.add(Header.ofOctets(name, value));
                } catch (IllegalArgumentException e) {
                    rejected[0] = true;
                }
            }
        });
        org.junit.Assert.assertTrue("the receiver's check refuses the field", rejected[0]);
        org.junit.Assert.assertTrue("and nothing invalid got through", seen.isEmpty());
    }
}
