/*
 * AMQPFrame.java
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

package org.bluezoo.gumdrop.amqp.client;

import java.nio.ByteBuffer;

/**
 * Constants and encoder for the AMQP 0-9-1 frame envelope shared by all
 * frame types (method, content-header, content-body, heartbeat).
 *
 * <p>Wire format (AMQP 0-9-1 §2.3.5):
 * <pre>
 *  0      1         3      7                  size+7  size+8
 * +------+---------+---------+ +-------------+ +-----------+
 * | type | channel |  size   | |   payload   | | frame-end |
 * +------+---------+---------+ +-------------+ +-----------+
 *  octet   short     long        'size' octets    octet
 * </pre>
 *
 * <p>Parsing frames off the wire is handled by {@link AMQPFrameParser},
 * not this class — bytes arrive incrementally and a frame is never
 * assumed to be complete in a single read, so parsing is a push
 * (event-driven) process dispatching to {@link AMQPFrameHandler}, not a
 * synchronous "parse one complete frame" call. This class only provides
 * the shared constants and the encode side, where a complete frame's
 * bytes are always available up front.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see AMQPFrameParser
 * @see <a href="https://www.rabbitmq.com/resources/specs/amqp0-9-1.pdf">AMQP 0-9-1 specification</a>
 */
public final class AMQPFrame {

    private AMQPFrame() {
    }

    /** Frame type: method frame (carries a class/method ID + arguments). */
    public static final int TYPE_METHOD = 1;
    /** Frame type: content header (carries class ID + basic properties). */
    public static final int TYPE_HEADER = 2;
    /** Frame type: content body (carries a chunk of message payload). */
    public static final int TYPE_BODY = 3;
    /** Frame type: heartbeat (empty payload). */
    public static final int TYPE_HEARTBEAT = 8;

    /** Marks the end of every frame. */
    public static final int FRAME_END = 0xCE;

    /** Fixed size of the frame header: type(1) + channel(2) + size(4). */
    public static final int HEADER_SIZE = 7;

    /** Total per-frame overhead: header (7) + frame-end (1). */
    public static final int OVERHEAD = HEADER_SIZE + 1;

    /**
     * Encodes a frame with the given type/channel/payload into a new
     * buffer, ready to write to the wire (position 0, limit at the end).
     */
    public static ByteBuffer encode(int type, int channel, ByteBuffer payload) {
        int size = payload.remaining();
        ByteBuffer buf = ByteBuffer.allocate(OVERHEAD + size);
        buf.put((byte) type);
        buf.putShort((short) channel);
        buf.putInt(size);
        buf.put(payload.duplicate());
        buf.put((byte) FRAME_END);
        buf.flip();
        return buf;
    }

    /** Encodes a zero-payload heartbeat frame (channel 0). */
    public static ByteBuffer encodeHeartbeat() {
        return encode(TYPE_HEARTBEAT, 0, ByteBuffer.allocate(0));
    }
}
