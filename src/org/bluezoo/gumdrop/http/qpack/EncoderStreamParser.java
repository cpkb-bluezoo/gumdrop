/*
 * EncoderStreamParser.java
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

import java.io.ByteArrayOutputStream;
import java.net.ProtocolException;
import java.nio.ByteBuffer;

/**
 * Stateful, incremental parser for QPACK encoder-stream instructions
 * (RFC 9204 section 4.3), one instance per connection (there is exactly
 * one encoder stream per peer, RFC 9204 section 4.2). Like
 * {@code org.bluezoo.gumdrop.http.h3.H3Parser}, {@link #receive} may be
 * called any number of times with arbitrary byte chunks -- unlike QUIC
 * or HTTP/3 frames, encoder-stream instructions carry no explicit
 * length field at all, so determining "is a complete instruction
 * present yet" requires attempting to parse it.
 *
 * <p>This parser does not distinguish "instruction is merely truncated
 * so far" from "instruction is genuinely malformed": both surface as an
 * underflow from {@link PrefixedInteger#decode} or {@link QPACKStrings#read}
 * partway through a parse attempt, and both are currently treated as
 * "not enough data yet, retry once more bytes arrive" -- a real gap
 * against a hostile or buggy peer (a malformed instruction could stall
 * forever rather than reporting an error), acceptable for now since
 * this codec is not yet exposed to untrusted peers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see EncoderStreamHandler
 * @see EncoderStreamWriter
 */
final class EncoderStreamParser {

    private final EncoderStreamHandler handler;
    private final ByteArrayOutputStream accumulator = new ByteArrayOutputStream();
    private int consumedLength;

    EncoderStreamParser(EncoderStreamHandler handler) {
        this.handler = handler;
    }

    /**
     * Parses as many complete instructions as the accumulated bytes
     * (this call's {@code data} plus anything buffered from previous
     * calls) contain.
     *
     * @param data newly received encoder-stream bytes
     */
    void receive(ByteBuffer data) {
        byte[] chunk = new byte[data.remaining()];
        data.get(chunk);
        accumulator.write(chunk, 0, chunk.length);

        while (true) {
            byte[] all = accumulator.toByteArray();
            if (consumedLength >= all.length) {
                break;
            }
            ByteBuffer buf = ByteBuffer.wrap(all, consumedLength, all.length - consumedLength);
            int startPosition = buf.position();
            try {
                if (!parseOne(buf)) {
                    break; // genuinely unsupported leading bits; already reported
                }
                consumedLength = buf.position();
            } catch (ProtocolException notEnoughDataYet) {
                buf.position(startPosition);
                break;
            }
        }

        if (consumedLength > 0) {
            byte[] remaining = accumulator.toByteArray();
            accumulator.reset();
            accumulator.write(remaining, consumedLength, remaining.length - consumedLength);
            consumedLength = 0;
        }
    }

    // Returns false (after reporting an error) only for a leading-bits
    // pattern that isn't actually ambiguous with "not enough data yet"
    // -- there is none for this instruction set (every leading-bit
    // combination is a valid instruction type), so this always
    // returns true or throws ProtocolException; the boolean return is
    // kept for symmetry with a possible future genuinely-invalid case.
    private boolean parseOne(ByteBuffer buf) throws ProtocolException {
        if (!buf.hasRemaining()) {
            throw new ProtocolException("no data");
        }
        int firstByte = buf.get(buf.position()) & 0xff;
        if ((firstByte & 0x80) != 0) {
            parseInsertWithNameReference(buf);
        } else if ((firstByte & 0x40) != 0) {
            parseInsertWithLiteralName(buf);
        } else if ((firstByte & 0x20) != 0) {
            parseSetDynamicTableCapacity(buf);
        } else {
            parseDuplicate(buf);
        }
        return true;
    }

    // RFC 9204 section 4.3.2: '1|T|NameIndex(6+)' + value string literal
    private void parseInsertWithNameReference(ByteBuffer buf) throws ProtocolException {
        int firstByte = buf.get() & 0xff;
        boolean isStatic = (firstByte & 0x40) != 0;
        long nameIndex = PrefixedInteger.decode(buf, firstByte, 6);
        byte[] value = QPACKStrings.read(buf, 7);
        handler.insertWithNameReference(isStatic, nameIndex, value);
    }

    // RFC 9204 section 4.3.3: '01|H|NameLength(5+)' name + value string literal
    private void parseInsertWithLiteralName(ByteBuffer buf) throws ProtocolException {
        byte[] name = QPACKStrings.read(buf, 5);
        byte[] value = QPACKStrings.read(buf, 7);
        handler.insertWithLiteralName(name, value);
    }

    // RFC 9204 section 4.3.1: '001|Capacity(5+)'
    private void parseSetDynamicTableCapacity(ByteBuffer buf) throws ProtocolException {
        int firstByte = buf.get() & 0xff;
        long capacity = PrefixedInteger.decode(buf, firstByte, 5);
        handler.setDynamicTableCapacity(capacity);
    }

    // RFC 9204 section 4.3.4: '000|Index(5+)'
    private void parseDuplicate(ByteBuffer buf) throws ProtocolException {
        int firstByte = buf.get() & 0xff;
        long relativeIndex = PrefixedInteger.decode(buf, firstByte, 5);
        handler.duplicate(relativeIndex);
    }
}
