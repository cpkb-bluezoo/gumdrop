/*
 * package-info.java
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

/**
 * AMQP 1.0 wire codec: the type system, frame envelope, performatives and
 * message sections.
 *
 * <p>{@link org.bluezoo.gumdrop.amqp1.codec.Amqp1FrameParser} is a
 * streaming push-parser delivering the protocol header and frames to an
 * {@link org.bluezoo.gumdrop.amqp1.codec.Amqp1FrameHandler};
 * {@link org.bluezoo.gumdrop.amqp1.codec.Amqp1Frame} encodes them.
 * {@link org.bluezoo.gumdrop.amqp1.codec.Amqp1Decoder} and
 * {@link org.bluezoo.gumdrop.amqp1.codec.Amqp1Encoder} implement the
 * type system, and {@link org.bluezoo.gumdrop.amqp1.codec.PerformativeCodec}
 * turns frame bodies into typed
 * {@link org.bluezoo.gumdrop.amqp1.codec.Performative} objects.
 *
 * {@link org.bluezoo.gumdrop.amqp1.codec.PerformativeReader} gathers just
 * the performative at the front of a frame body, leaving any payload to
 * stream. Messages are handled by
 * {@link org.bluezoo.gumdrop.amqp1.codec.MessageParser}, a push-parser that
 * delivers sections to a
 * {@link org.bluezoo.gumdrop.amqp1.codec.MessageHandler} and streams
 * {@code data} bodies without buffering them, and by
 * {@link org.bluezoo.gumdrop.amqp1.codec.MessageWriter}.
 *
 * <p>Nothing in this package depends on a client or server API, so both
 * roles build on it.
 *
 * <p>This is unrelated to the AMQP 0-9-1 support in
 * {@code org.bluezoo.gumdrop.amqp}: the two protocols share only the
 * four-octet {@code AMQP} protocol header prefix.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://docs.oasis-open.org/amqp/core/v1.0/os/amqp-core-v1.0-os.html">AMQP 1.0 core</a>
 * @see <a href="https://docs.oasis-open.org/amqp/sasl/v1.0/os/amqp-sasl-v1.0-os.html">AMQP SASL profile</a>
 */
package org.bluezoo.gumdrop.amqp1.codec;
