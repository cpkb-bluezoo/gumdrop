/*
 * AMQPFrameHandler.java
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
 * Callback interface for receiving parsed AMQP 0-9-1 frames from an
 * {@link AMQPFrameParser}.
 *
 * <p>Implementations receive frame data directly from the parser without
 * intermediate frame-object allocation. {@code ByteBuffer} parameters are
 * slices of the parser's input buffer and are only valid for the
 * duration of the callback — copy anything that must outlive it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see AMQPFrameParser
 */
public interface AMQPFrameHandler {

    /**
     * A method frame (AMQP frame type 1): a class/method ID pair
     * followed by the method's arguments, not yet decoded.
     *
     * @param channel the channel number (0 for connection-level methods)
     * @param payload the frame payload — class ID (2 bytes), method ID
     *      (2 bytes), then the method arguments
     */
    void methodFrame(int channel, ByteBuffer payload);

    /**
     * A content-header frame (AMQP frame type 2): class ID, weight,
     * body size, and property-flags/property-list, not yet decoded.
     *
     * @param channel the channel number
     * @param payload the frame payload, suitable for {@link BasicProperties#decode}
     */
    void headerFrame(int channel, ByteBuffer payload);

    /**
     * A content-body frame (AMQP frame type 3): a chunk of message
     * payload. A message body may be split across several body frames;
     * the content-header frame's body-size says how many bytes to expect
     * in total.
     *
     * @param channel the channel number
     * @param payload the body chunk
     */
    void bodyFrame(int channel, ByteBuffer payload);

    /** A heartbeat frame (AMQP frame type 8, always on channel 0, empty payload). */
    void heartbeatFrame();

    /**
     * A frame violated the wire format: a bad frame-end octet, a frame
     * exceeding the negotiated max-frame-size, or an unrecognised frame
     * type. This is always a connection-level error per the AMQP spec.
     *
     * @param message a description of the violation
     */
    void frameError(String message);
}
