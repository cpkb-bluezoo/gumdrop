/*
 * Amqp1Frame.java
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
 * Constants and encoders for the AMQP 1.0 protocol header and frame
 * envelope (core specification, sections 2.3 and 2.4).
 *
 * <p>Frame wire format:
 * <pre>
 *  0         4      5      6         8      4*doff        size
 * +---------+------+------+---------+--------------+-----------+
 * |  size   | doff | type | channel | ext. header  |   body    |
 * +---------+------+------+---------+--------------+-----------+
 *   uint32   octet octet   uint16    (ignored)
 * </pre>
 * {@code size} is the size of the whole frame including the eight-octet
 * header; {@code doff} is the offset of the body in four-octet words
 * (minimum 2).
 *
 * <p>Unlike AMQP 0-9-1, an AMQP 1.0 frame has no trailing frame-end
 * octet. A frame with an empty body is a heartbeat.
 *
 * <p>Parsing is handled incrementally by {@link Amqp1FrameParser}; this
 * class holds the shared constants and the encode side.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Amqp1FrameParser
 */
public final class Amqp1Frame {

    private Amqp1Frame() {
    }

    /** Frame type: AMQP frame (performatives and message transfer). */
    public static final int TYPE_AMQP = 0x00;
    /** Frame type: SASL frame. */
    public static final int TYPE_SASL = 0x01;

    /** Size of the frame header (size, doff, type, channel). */
    public static final int HEADER_SIZE = 8;

    /** Size of the protocol header. */
    public static final int PROTOCOL_HEADER_SIZE = 8;

    /**
     * The smallest max-frame-size a peer may accept. It also bounds every
     * frame until the peer's {@code open} performative has been received.
     */
    public static final int MIN_MAX_FRAME_SIZE = 512;

    /** Protocol id in the protocol header: plain AMQP. */
    public static final int PROTOCOL_ID_AMQP = 0;
    /** Protocol id in the protocol header: TLS negotiation (not used; TLS is implicit). */
    public static final int PROTOCOL_ID_TLS = 2;
    /** Protocol id in the protocol header: SASL. */
    public static final int PROTOCOL_ID_SASL = 3;

    /**
     * Returns the eight-octet protocol header for the given protocol id:
     * {@code "AMQP"}, the protocol id, then version 1.0.0.
     */
    public static ByteBuffer protocolHeader(int protocolId) {
        ByteBuffer buf = ByteBuffer.allocate(PROTOCOL_HEADER_SIZE);
        buf.put((byte) 'A').put((byte) 'M').put((byte) 'Q').put((byte) 'P');
        buf.put((byte) protocolId);
        buf.put((byte) 1).put((byte) 0).put((byte) 0);
        buf.flip();
        return buf;
    }

    /**
     * Encodes a frame with the given type, channel and body into a new
     * buffer ready to write to the wire. The extended header is always
     * empty ({@code doff} = 2).
     *
     * @param type {@link #TYPE_AMQP} or {@link #TYPE_SASL}
     * @param channel the channel number (0 for SASL frames)
     * @param body the frame body; may be empty (a heartbeat)
     */
    public static ByteBuffer encode(int type, int channel, ByteBuffer body) {
        int bodyLength = body == null ? 0 : body.remaining();
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + bodyLength);
        buf.putInt(HEADER_SIZE + bodyLength);
        buf.put((byte) 2);
        buf.put((byte) type);
        buf.putShort((short) channel);
        if (body != null) {
            buf.put(body.duplicate());
        }
        buf.flip();
        return buf;
    }

    /**
     * Encodes a frame whose body is a performative followed by payload
     * octets (the message bytes of a {@code transfer}), copying both into
     * one buffer.
     *
     * @param type {@link #TYPE_AMQP}
     * @param channel the channel number
     * @param performative the encoded performative
     * @param payload the payload octets, may be empty
     */
    public static ByteBuffer encode(int type, int channel, ByteBuffer performative,
            ByteBuffer payload) {
        int size = performative.remaining() + payload.remaining();
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + size);
        buf.putInt(HEADER_SIZE + size);
        buf.put((byte) 2);
        buf.put((byte) type);
        buf.putShort((short) channel);
        buf.put(performative.duplicate());
        buf.put(payload.duplicate());
        buf.flip();
        return buf;
    }

    /** Encodes an empty AMQP frame, used as a heartbeat. */
    public static ByteBuffer encodeHeartbeat(int channel) {
        return encode(TYPE_AMQP, channel, null);
    }
}
