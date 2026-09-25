/*
 * Amqp1FrameParser.java
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
 * Push-parser for the AMQP 1.0 protocol header and frames (core
 * specification 2.2 and 2.3).
 *
 * <p>Like {@code H2Parser}, this parser makes no assumption that a call
 * to {@link #receive(ByteBuffer)} is handed a whole number of frames. It
 * is a small state machine that advances as bytes arrive:
 * <ul>
 *   <li>{@code PROTOCOL_HEADER}: waits for the eight-octet header.</li>
 *   <li>{@code FRAME_HEADER}: waits for the eight-octet frame header and
 *       validates it (size, data offset, type) before any body arrives,
 *       so an oversize frame is rejected without waiting for it.</li>
 *   <li>{@code EXTENDED_HEADER}: skips the extended header, which
 *       AMQP 1.0 requires receivers to ignore.</li>
 *   <li>{@code FRAME_BODY}: forwards body octets to the handler as
 *       zero-copy slices as they arrive, never accumulating them.</li>
 * </ul>
 * Only the two fixed-size headers can be left partial; when the buffer
 * ends inside one, its position is left at the start of that header for
 * the caller to {@code compact()} and retry.
 *
 * <p>A connection begins with the peer's protocol header, then frames.
 * SASL negotiation interposes a second protocol header once the SASL
 * outcome has been received; the handler tells the parser to expect it
 * by calling {@link #expectProtocolHeader()}.
 *
 * <p>Usage:
 * <pre>{@code
 * Amqp1FrameParser parser = new Amqp1FrameParser(handler);
 * // ... as bytes arrive from the connection:
 * parser.receive(buf);
 * buf.compact(); // preserve any partial header for the next receive()
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Amqp1FrameHandler
 */
public final class Amqp1FrameParser {

    private enum State { PROTOCOL_HEADER, FRAME_HEADER, EXTENDED_HEADER, FRAME_BODY }

    private final Amqp1FrameHandler handler;
    private int maxFrameSize = Amqp1Frame.MIN_MAX_FRAME_SIZE;
    private State state = State.PROTOCOL_HEADER;
    private boolean failed;

    // Current frame (valid from FRAME_HEADER until the frame completes)
    private int frameType;
    private int frameChannel;
    private int extendedRemaining;
    private int bodyLength;
    private int bodyRemaining;

    public Amqp1FrameParser(Amqp1FrameHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        this.handler = handler;
    }

    public int getMaxFrameSize() {
        return maxFrameSize;
    }

    /**
     * Sets the largest frame the parser will accept. Defaults to
     * {@link Amqp1Frame#MIN_MAX_FRAME_SIZE}, the limit that applies until
     * the connection is open; raise it to the max-frame-size advertised
     * in our own {@code open} performative once the peer's has arrived.
     *
     * @throws IllegalArgumentException if below the specification minimum
     */
    public void setMaxFrameSize(int maxFrameSize) {
        if (maxFrameSize < Amqp1Frame.MIN_MAX_FRAME_SIZE) {
            throw new IllegalArgumentException("max-frame-size must be at least "
                    + Amqp1Frame.MIN_MAX_FRAME_SIZE);
        }
        this.maxFrameSize = maxFrameSize;
    }

    /**
     * Arms the parser to read a protocol header next. Called by the
     * handler, from within its {@link Amqp1FrameHandler#endFrame}
     * callback, when the SASL exchange completes and the peer is about
     * to send the AMQP protocol header.
     */
    public void expectProtocolHeader() {
        state = State.PROTOCOL_HEADER;
    }

    /**
     * Consumes as much of {@code buf} as can be acted on, dispatching
     * events to the handler. Body octets are always consumed; only an
     * incomplete protocol header or frame header is left in the buffer
     * (position at its start).
     *
     * <p>Stops the first time a violation is found and reports it via
     * {@link Amqp1FrameHandler#frameError}. The parser then ignores any
     * further input: framing errors are fatal in AMQP and there is no
     * resynchronisation point.
     *
     * @param buf the buffer containing data, in read mode
     */
    public void receive(ByteBuffer buf) {
        while (!failed) {
            switch (state) {
                case PROTOCOL_HEADER:
                    if (!processProtocolHeader(buf)) {
                        return;
                    }
                    break;
                case FRAME_HEADER:
                    if (!processFrameHeader(buf)) {
                        return;
                    }
                    break;
                case EXTENDED_HEADER:
                    if (!processExtendedHeader(buf)) {
                        return;
                    }
                    break;
                case FRAME_BODY:
                    if (!processBody(buf)) {
                        return;
                    }
                    break;
            }
        }
    }

    private boolean processProtocolHeader(ByteBuffer buf) {
        if (buf.remaining() < Amqp1Frame.PROTOCOL_HEADER_SIZE) {
            return false;
        }
        int p = buf.position();
        if (buf.get(p) != 'A' || buf.get(p + 1) != 'M'
                || buf.get(p + 2) != 'Q' || buf.get(p + 3) != 'P') {
            fail("Invalid AMQP protocol header");
            return false;
        }
        int protocolId = buf.get(p + 4) & 0xFF;
        int major = buf.get(p + 5) & 0xFF;
        int minor = buf.get(p + 6) & 0xFF;
        int revision = buf.get(p + 7) & 0xFF;
        buf.position(p + Amqp1Frame.PROTOCOL_HEADER_SIZE);
        state = State.FRAME_HEADER;
        handler.protocolHeader(protocolId, major, minor, revision);
        return true;
    }

    private boolean processFrameHeader(ByteBuffer buf) {
        if (buf.remaining() < Amqp1Frame.HEADER_SIZE) {
            return false;
        }
        int start = buf.position();
        long size = buf.getInt(start) & 0xFFFFFFFFL;
        int doff = buf.get(start + 4) & 0xFF;
        int type = buf.get(start + 5) & 0xFF;
        int channel = buf.getShort(start + 6) & 0xFFFF;

        if (size < Amqp1Frame.HEADER_SIZE) {
            fail("Frame size " + size + " smaller than the frame header");
            return false;
        }
        if (size > maxFrameSize) {
            fail("Frame size " + size + " exceeds max-frame-size " + maxFrameSize);
            return false;
        }
        if (doff < 2 || doff * 4L > size) {
            fail("Invalid frame data offset " + doff + " for frame size " + size);
            return false;
        }
        if (type != Amqp1Frame.TYPE_AMQP && type != Amqp1Frame.TYPE_SASL) {
            fail("Unknown frame type 0x" + Integer.toHexString(type));
            return false;
        }
        buf.position(start + Amqp1Frame.HEADER_SIZE);
        frameType = type;
        frameChannel = channel;
        extendedRemaining = doff * 4 - Amqp1Frame.HEADER_SIZE;
        bodyLength = (int) size - doff * 4;
        bodyRemaining = bodyLength;
        state = State.EXTENDED_HEADER;
        return true;
    }

    private boolean processExtendedHeader(ByteBuffer buf) {
        int skip = Math.min(extendedRemaining, buf.remaining());
        buf.position(buf.position() + skip);
        extendedRemaining -= skip;
        if (extendedRemaining > 0) {
            return false;
        }
        state = State.FRAME_HEADER;
        if (bodyLength == 0) {
            if (frameType == Amqp1Frame.TYPE_SASL) {
                fail("Empty SASL frame");
                return false;
            }
            handler.heartbeat(frameChannel);
            return true;
        }
        state = State.FRAME_BODY;
        handler.startFrame(frameType, frameChannel, bodyLength);
        return true;
    }

    private boolean processBody(ByteBuffer buf) {
        if (!buf.hasRemaining()) {
            return false;
        }
        int n = Math.min(bodyRemaining, buf.remaining());
        int savedLimit = buf.limit();
        buf.limit(buf.position() + n);
        ByteBuffer chunk = buf.slice();
        buf.position(buf.limit());
        buf.limit(savedLimit);
        bodyRemaining -= n;
        if (bodyRemaining == 0) {
            // Move on first so a handler re-arming the parser in endFrame wins
            state = State.FRAME_HEADER;
            handler.frameBody(chunk);
            handler.endFrame();
        } else {
            handler.frameBody(chunk);
        }
        return true;
    }

    private void fail(String message) {
        failed = true;
        handler.frameError(message);
    }
}
