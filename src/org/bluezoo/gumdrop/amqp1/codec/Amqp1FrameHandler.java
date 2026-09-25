/*
 * Amqp1FrameHandler.java
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
 * Callback interface for receiving AMQP 1.0 protocol headers and frames
 * from an {@link Amqp1FrameParser}.
 *
 * <p>A frame body is delivered as a stream: one {@link #startFrame}, zero
 * or more {@link #frameBody} chunks, then {@link #endFrame}. Nothing is
 * buffered by the parser, so a large {@code transfer} payload reaches the
 * handler as soon as it arrives off the network. Use a
 * {@link PerformativeReader} to assemble the (small) performative at the
 * front of the body; any bytes after it are payload.
 *
 * <p>{@code ByteBuffer} parameters are slices of the parser's input
 * buffer and are only valid for the duration of the callback: copy
 * anything that must outlive it.
 *
 * <p>This interface knows nothing about clients or servers; both build
 * their connection state machines on top of it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Amqp1FrameParser
 */
public interface Amqp1FrameHandler {

    /**
     * The peer's eight-octet protocol header (core specification 2.2).
     * The parser delivers exactly one after construction and after each
     * call to {@link Amqp1FrameParser#expectProtocolHeader()}.
     *
     * @param protocolId {@link Amqp1Frame#PROTOCOL_ID_AMQP},
     *      {@link Amqp1Frame#PROTOCOL_ID_SASL} or another value the peer
     *      offered
     * @param major the major protocol version
     * @param minor the minor protocol version
     * @param revision the protocol revision
     */
    void protocolHeader(int protocolId, int major, int minor, int revision);

    /**
     * A frame with a non-empty body has begun. Delivered as soon as the
     * frame header has been read, before any of the body.
     *
     * @param type {@link Amqp1Frame#TYPE_AMQP} or {@link Amqp1Frame#TYPE_SASL}
     * @param channel the channel number
     * @param bodyLength the total number of body octets that will follow
     */
    void startFrame(int type, int channel, int bodyLength);

    /**
     * The next chunk of the current frame's body, in order. May be
     * called any number of times between {@link #startFrame} and
     * {@link #endFrame}, with chunks as small as one octet.
     *
     * @param chunk body octets; only valid during this call
     */
    void frameBody(ByteBuffer chunk);

    /** The current frame's body is complete. */
    void endFrame();

    /**
     * An AMQP frame with an empty body: a heartbeat used to keep the
     * connection alive (core specification 2.4.5).
     *
     * @param channel the channel number
     */
    void heartbeat(int channel);

    /**
     * The peer violated the framing rules: a bad protocol header, a frame
     * size outside the permitted range, an invalid data offset, or an
     * unknown frame type. This is always fatal to the connection; the
     * parser stops and consumes nothing further.
     *
     * @param message a description of the violation
     */
    void frameError(String message);
}
