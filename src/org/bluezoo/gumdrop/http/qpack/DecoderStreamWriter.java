/*
 * DecoderStreamWriter.java
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

import java.nio.ByteBuffer;

/**
 * Writes QPACK decoder-stream instructions (RFC 9204 section 4.4) into
 * a {@link ByteBuffer}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DecoderStreamParser
 */
final class DecoderStreamWriter {

    private DecoderStreamWriter() {
    }

    /**
     * Writes a Section Acknowledgment instruction (RFC 9204 section 4.4.1).
     *
     * @param out the destination buffer
     * @param streamId the stream ID
     */
    static void writeSectionAcknowledgment(ByteBuffer out, long streamId) {
        PrefixedInteger.encode(out, 0x80, streamId, 7);
    }

    /**
     * Writes a Stream Cancellation instruction (RFC 9204 section 4.4.2).
     *
     * @param out the destination buffer
     * @param streamId the stream ID
     */
    static void writeStreamCancellation(ByteBuffer out, long streamId) {
        PrefixedInteger.encode(out, 0x40, streamId, 6);
    }

    /**
     * Writes an Insert Count Increment instruction (RFC 9204 section 4.4.3).
     *
     * @param out the destination buffer
     * @param increment the increment
     */
    static void writeInsertCountIncrement(ByteBuffer out, long increment) {
        PrefixedInteger.encode(out, 0x00, increment, 6);
    }
}
