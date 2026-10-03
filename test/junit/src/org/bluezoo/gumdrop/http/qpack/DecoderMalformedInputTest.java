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
 * Malformed QPACK input (issue #256: fuzzing once found input that threw an
 * unchecked exception instead of the documented {@link ProtocolException}).
 *
 * <p>The decoder used to build a validating {@code Header} per field and so
 * turned a bad field into a decode error. It now delivers fields as octets and
 * the receiver checks them, so a field with an invalid value is a rejected
 * message, not a decode error; the section is still decoded and acknowledged
 * in full (RFC 9204 section 4.4.1).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DecoderMalformedInputTest {

    /**
     * Required Insert Count=0, Base delta=0, then a literal field line with
     * literal name (RFC 9204 section 4.5.6): name "x" (1 byte, no Huffman),
     * value containing a bare CR (0x0D) — not a syntactically valid HTTP
     * header value. (Buffer-underflow shapes analogous to HPACK's #255 are
     * not reachable here: QpackStrings.read and PrefixedInteger.decode
     * already check remaining bytes and throw ProtocolException cleanly.)
     */
    @Test
    public void testInvalidValueCharacterIsDeliveredAndRejectedByTheReceiver() throws ProtocolException {
        byte[] data = new byte[] {
            0x00, // Required Insert Count byte: encoded RIC = 0
            0x00, // Base: sign=0, delta=0
            0x21, // literal field line w/ literal name: N=0, H=0, NameLen=1
            'x',  // name "x"
            0x01, // value length=1, no Huffman
            0x0D, // value: bare CR, invalid in a header value
        };
        Decoder decoder = new Decoder(4096);
        // The decoder does not judge the field: it consumes and acknowledges
        // the whole section (RFC 9204 section 4.4.1) and the receiver decides.
        // Here the receiver's field check refuses the bare CR.
        org.bluezoo.gumdrop.http.HeaderCollector collector =
                new org.bluezoo.gumdrop.http.HeaderCollector();
        decoder.decode(1L, ByteBuffer.wrap(data), collector);
        org.junit.Assert.assertTrue("the field was refused by the receiver", collector.isMalformed());
        org.junit.Assert.assertTrue("and nothing invalid got through", collector.headers().isEmpty());
    }
}
