/*
 * PerformativeReader.java
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

package org.bluezoo.gumdrop.amqp1.codec;

import java.nio.ByteBuffer;

/**
 * Assembles the performative at the front of a frame body from the
 * chunks delivered by {@link Amqp1FrameHandler#frameBody}.
 *
 * <p>A frame body is a performative optionally followed by a payload
 * (for a {@code transfer}, the message bytes). The performative is
 * small and bounded, so it is gathered and decoded; the payload can be
 * arbitrarily large and is never touched. The reader consumes from each
 * chunk exactly the octets that belong to the performative and leaves
 * the buffer positioned at the first payload octet, so the caller can
 * forward the remainder onwards as it arrives.
 *
 * <p>When a whole performative is already present in a chunk (the usual
 * case) it is decoded directly from that chunk without copying.
 *
 * <p>Usage, from a frame handler:
 * <pre>{@code
 * startFrame(...) { reader.reset(); performative = null; }
 * frameBody(chunk) {
 *     if (performative == null) {
 *         performative = reader.receive(chunk);
 *         if (performative == null) return;   // need more octets
 *         // act on the performative
 *     }
 *     if (chunk.hasRemaining()) {
 *         // payload octets
 *     }
 * }
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Amqp1FrameParser
 */
public final class PerformativeReader {

    private final int maxSize;
    private byte[] gathered = new byte[64];
    private int gatheredLength;

    /**
     * @param maxSize the largest performative to accept, normally the
     *      connection's max-frame-size; bounds the memory used
     */
    public PerformativeReader(int maxSize) {
        this.maxSize = maxSize;
    }

    /** Discards any partial performative; call at the start of each frame. */
    public void reset() {
        gatheredLength = 0;
    }

    /**
     * Consumes the next chunk of a frame body.
     *
     * @param chunk body octets; on return positioned after the
     *      performative if one completed, otherwise fully consumed
     * @return the performative once complete, or {@code null} if more
     *      octets are needed
     * @throws Amqp1ProtocolException if the octets are not a valid
     *      performative or it exceeds the size limit
     */
    public Performative receive(ByteBuffer chunk) throws Amqp1ProtocolException {
        if (gatheredLength == 0) {
            long length = Amqp1Decoder.encodedLength(chunk);
            if (length > maxSize) {
                throw new Amqp1ProtocolException("Performative of " + length
                        + " octets exceeds limit " + maxSize);
            }
            if (length >= 0 && length <= chunk.remaining()) {
                return decodeFrom(chunk, (int) length);
            }
        }
        // Gather one octet at a time until the length is known, then the
        // exact remainder, so nothing beyond the performative is taken
        while (chunk.hasRemaining()) {
            ByteBuffer view = ByteBuffer.wrap(gathered, 0, gatheredLength);
            long length = Amqp1Decoder.encodedLength(view);
            if (length > maxSize) {
                throw new Amqp1ProtocolException("Performative of " + length
                        + " octets exceeds limit " + maxSize);
            }
            if (length >= 0 && length == gatheredLength) {
                return decodeGathered();
            }
            int want = length < 0 ? 1 : (int) length - gatheredLength;
            int n = Math.min(want, chunk.remaining());
            append(chunk, n);
        }
        ByteBuffer view = ByteBuffer.wrap(gathered, 0, gatheredLength);
        long length = Amqp1Decoder.encodedLength(view);
        if (length >= 0 && length == gatheredLength) {
            return decodeGathered();
        }
        return null;
    }

    private void append(ByteBuffer chunk, int n) throws Amqp1ProtocolException {
        if (gatheredLength + n > maxSize) {
            throw new Amqp1ProtocolException("Performative exceeds limit " + maxSize);
        }
        if (gatheredLength + n > gathered.length) {
            byte[] bigger = new byte[Math.max(gatheredLength + n, gathered.length * 2)];
            System.arraycopy(gathered, 0, bigger, 0, gatheredLength);
            gathered = bigger;
        }
        chunk.get(gathered, gatheredLength, n);
        gatheredLength += n;
    }

    private Performative decodeFrom(ByteBuffer chunk, int length) throws Amqp1ProtocolException {
        ByteBuffer value = chunk.slice();
        value.limit(length);
        chunk.position(chunk.position() + length);
        return PerformativeCodec.decode(value);
    }

    private Performative decodeGathered() throws Amqp1ProtocolException {
        ByteBuffer value = ByteBuffer.wrap(gathered, 0, gatheredLength);
        gatheredLength = 0;
        return PerformativeCodec.decode(value);
    }
}
