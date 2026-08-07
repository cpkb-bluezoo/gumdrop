/*
 * AMQPFrameParser.java
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
 * Push-parser for AMQP 0-9-1 frames (specification §2.3.5).
 *
 * <p>Like {@link org.bluezoo.gumdrop.http.h2.H2Parser}, this parser makes
 * no assumption that a call to {@link #receive(ByteBuffer)} is handed a
 * complete frame, or even a whole number of frames — bytes arrive off
 * the network in whatever chunks the transport delivers, and a frame
 * (particularly a content-body frame carrying a large message) may span
 * many reads. The parser consumes as many complete frames as are present
 * in the buffer, dispatching each to a typed {@link AMQPFrameHandler}
 * callback with a zero-copy slice of the payload, and leaves any trailing
 * partial frame's bytes untouched (buffer position left at its start) for
 * the next call once more data has arrived.
 *
 * <p>Usage:
 * <pre>{@code
 * AMQPFrameParser parser = new AMQPFrameParser(handler);
 * // ... as bytes arrive from the connection:
 * parser.receive(buf);
 * buf.compact(); // preserve any partial frame for the next receive()
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see AMQPFrameHandler
 */
public final class AMQPFrameParser {

    /** AMQP 0-9-1 §4.2.3: default max-frame-size before negotiation. */
    public static final int DEFAULT_MAX_FRAME_SIZE = 131072;

    private final AMQPFrameHandler handler;
    private int maxFrameSize = DEFAULT_MAX_FRAME_SIZE;

    public AMQPFrameParser(AMQPFrameHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        this.handler = handler;
    }

    public int getMaxFrameSize() {
        return maxFrameSize;
    }

    /** Updated once {@code connection.tune}/{@code tune-ok} negotiates frame-max. */
    public void setMaxFrameSize(int maxFrameSize) {
        this.maxFrameSize = maxFrameSize;
    }

    /**
     * Consumes as many complete frames as are present in {@code buf},
     * dispatching each to the handler. If the buffer ends mid-frame, its
     * position is left at the start of that incomplete frame (nothing of
     * it is consumed) so the caller can compact and retry once more data
     * arrives.
     *
     * <p>Stops (without consuming the offending frame) the first time a
     * malformed frame is encountered and reports it via
     * {@link AMQPFrameHandler#frameError}, since a wire-format violation
     * is always a connection-level error in AMQP — there is no
     * well-defined resynchronisation point to keep parsing from.
     *
     * @param buf the buffer containing frame data, in read mode
     */
    public void receive(ByteBuffer buf) {
        while (buf.remaining() >= AMQPFrame.HEADER_SIZE) {
            int start = buf.position();

            int type = buf.get(start) & 0xFF;
            int channel = buf.getShort(start + 1) & 0xFFFF;
            long size = buf.getInt(start + 3) & 0xFFFFFFFFL;

            if (size > maxFrameSize) {
                handler.frameError("Frame size " + size + " exceeds max-frame-size " + maxFrameSize);
                return;
            }

            long total = AMQPFrame.HEADER_SIZE + size + 1;
            if (buf.remaining() < total) {
                return; // Underflow — wait for more data, buffer position untouched
            }

            int payloadStart = start + AMQPFrame.HEADER_SIZE;
            int end = (int) (payloadStart + size);

            int frameEnd = buf.get(end) & 0xFF;
            if (frameEnd != AMQPFrame.FRAME_END) {
                handler.frameError("Malformed frame: expected frame-end 0xCE, got 0x"
                        + Integer.toHexString(frameEnd));
                return;
            }

            int savedLimit = buf.limit();
            buf.limit(end);
            buf.position(payloadStart);
            ByteBuffer payload = buf.slice();
            buf.limit(savedLimit);
            buf.position(end + 1); // consume the whole frame including frame-end

            dispatch(type, channel, payload);
        }
    }

    private void dispatch(int type, int channel, ByteBuffer payload) {
        switch (type) {
            case AMQPFrame.TYPE_METHOD:
                handler.methodFrame(channel, payload);
                break;
            case AMQPFrame.TYPE_HEADER:
                handler.headerFrame(channel, payload);
                break;
            case AMQPFrame.TYPE_BODY:
                handler.bodyFrame(channel, payload);
                break;
            case AMQPFrame.TYPE_HEARTBEAT:
                handler.heartbeatFrame();
                break;
            default:
                // AMQP 0-9-1 has no "ignore unknown frame types" provision like
                // HTTP/2 — an unrecognised type is a protocol violation.
                handler.frameError("Unknown frame type: " + type);
        }
    }
}
