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

package org.bluezoo.gumdrop.http.qpack;

import org.junit.Test;
import static org.junit.Assert.fail;

import java.net.ProtocolException;
import java.nio.ByteBuffer;

/**
 * Regression tests for issue #256 — malformed QPACK input found by JQF/Zest
 * fuzzing that threw an unchecked exception instead of the documented
 * {@link ProtocolException}. Same root cause as HPACK issue #255: both
 * decoders share {@link org.bluezoo.gumdrop.http.Header}, whose constructor
 * validates the name/value and throws unchecked on failure.
 */
public class DecoderMalformedInputTest {

    /**
     * Required Insert Count=0, Base delta=0, then a literal field line with
     * literal name (RFC 9204 section 4.5.6): name "x" (1 byte, no Huffman),
     * value containing a bare CR (0x0D) — not a syntactically valid HTTP
     * header value. (Buffer-underflow shapes analogous to HPACK's #255 are
     * not reachable here: QPACKStrings.read and PrefixedInteger.decode
     * already check remaining bytes and throw ProtocolException cleanly.)
     */
    @Test
    public void testInvalidValueCharacterThrowsProtocolException() {
        byte[] data = new byte[] {
            0x00, // Required Insert Count byte: encoded RIC = 0
            0x00, // Base: sign=0, delta=0
            0x21, // literal field line w/ literal name: N=0, H=0, NameLen=1
            'x',  // name "x"
            0x01, // value length=1, no Huffman
            0x0D, // value: bare CR, invalid in a header value
        };
        Decoder decoder = new Decoder(4096);
        try {
            decoder.decode(1L, ByteBuffer.wrap(data));
            fail("expected a ProtocolException for an invalid header value");
        } catch (ProtocolException expected) {
            // expected: a clean decode error, not an unchecked exception
        }
    }
}
