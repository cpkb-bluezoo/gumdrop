/*
 * DecoderStreamParser.java
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
 * Stateful, incremental parser for QPACK decoder-stream instructions
 * (RFC 9204 section 4.4), one instance per connection (there is exactly
 * one decoder stream per peer, RFC 9204 section 4.2). See
 * {@link EncoderStreamParser}'s documentation for why this has to be
 * incremental (no explicit instruction length) and for the same
 * truncated-vs-malformed caveat.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DecoderStreamHandler
 * @see DecoderStreamWriter
 */
final class DecoderStreamParser {

    private final DecoderStreamHandler handler;
    private final ByteArrayOutputStream accumulator = new ByteArrayOutputStream();
    private int consumedLength;

    DecoderStreamParser(DecoderStreamHandler handler) {
        this.handler = handler;
    }

    /**
     * Parses as many complete instructions as the accumulated bytes
     * (this call's {@code data} plus anything buffered from previous
     * calls) contain.
     *
     * @param data newly received decoder-stream bytes
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
            try {
                parseOne(buf);
                consumedLength = buf.position();
            } catch (ProtocolException notEnoughDataYet) {
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

    private void parseOne(ByteBuffer buf) throws ProtocolException {
        if (!buf.hasRemaining()) {
            throw new ProtocolException("no data");
        }
        int firstByte = buf.get() & 0xff;
        if ((firstByte & 0x80) != 0) {
            // RFC 9204 section 4.4.1: '1|StreamID(7+)'
            long streamId = PrefixedInteger.decode(buf, firstByte, 7);
            handler.sectionAcknowledgment(streamId);
        } else if ((firstByte & 0x40) != 0) {
            // RFC 9204 section 4.4.2: '01|StreamID(6+)'
            long streamId = PrefixedInteger.decode(buf, firstByte, 6);
            handler.streamCancellation(streamId);
        } else {
            // RFC 9204 section 4.4.3: '00|Increment(6+)'
            long increment = PrefixedInteger.decode(buf, firstByte, 6);
            handler.insertCountIncrement(increment);
        }
    }
}
