/*
 * H3Datagram.java
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

package org.bluezoo.gumdrop.http.h3;

import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.quic.packet.VarInt;

/**
 * HTTP/3 Datagrams (RFC 9297 section 2.1) carried in QUIC DATAGRAM
 * frames (RFC 9221): a Quarter Stream ID varint followed by the HTTP
 * Datagram payload.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9297#section-2.1">RFC 9297 section 2.1</a>
 */
public final class H3Datagram {

    /**
     * Largest legal quarter-stream-ID (RFC 9297 section 2.1):
     * {@code (2^62 - 1) / 4}.
     */
    static final long MAX_QUARTER_STREAM_ID = (1L << 60) - 1;

    private final long streamId;
    private final byte[] payload;

    private H3Datagram(long streamId, byte[] payload) {
        this.streamId = streamId;
        this.payload = payload;
    }

    /**
     * Returns the client-initiated bidirectional QUIC stream ID this
     * datagram is associated with.
     *
     * @return the stream ID (0 mod 4)
     */
    public long getStreamId() {
        return streamId;
    }

    /**
     * Returns the HTTP Datagram payload (not including the Quarter Stream
     * ID). The returned array is the decoder's own copy.
     *
     * @return the payload
     */
    public byte[] getPayload() {
        return payload;
    }

    /**
     * Encodes an HTTP/3 Datagram for {@code streamId}.
     *
     * @param streamId a client-initiated bidirectional stream ID (0 mod 4)
     * @param payload the HTTP Datagram payload; copied
     * @return the encoded QUIC DATAGRAM payload, or {@code null} if
     *         {@code streamId} is not a client-initiated bidi ID
     */
    public static byte[] encode(long streamId, byte[] payload) {
        if (streamId < 0 || (streamId & 3L) != 0) {
            return null;
        }
        long quarter = streamId / 4L;
        if (quarter > MAX_QUARTER_STREAM_ID) {
            return null;
        }
        byte[] body = payload != null ? payload : new byte[0];
        ByteBuffer buf = ByteBuffer.allocate(VarInt.encodedLength(quarter) + body.length);
        VarInt.encode(quarter, buf);
        buf.put(body);
        return buf.array();
    }

    /**
     * Decodes an HTTP/3 Datagram from a QUIC DATAGRAM payload.
     *
     * @param data the QUIC DATAGRAM payload
     * @return the decoded datagram, or {@code null} if truncated or the
     *         Quarter Stream ID is out of range
     */
    public static H3Datagram decode(ByteBuffer data) {
        if (data == null || !data.hasRemaining()) {
            return null;
        }
        int typeLen = VarInt.peekEncodedLength(data, data.position());
        if (data.remaining() < typeLen) {
            return null;
        }
        long quarter = VarInt.decode(data);
        if (quarter < 0 || quarter > MAX_QUARTER_STREAM_ID) {
            return null;
        }
        if (quarter > Long.MAX_VALUE / 4L) {
            return null;
        }
        long streamId = quarter * 4L;
        byte[] payload = new byte[data.remaining()];
        data.get(payload);
        return new H3Datagram(streamId, payload);
    }
}
