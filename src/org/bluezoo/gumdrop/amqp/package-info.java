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
 * AMQP 0-9-1 wire protocol: frame envelope, method/class ID tables, and
 * field-table encoding shared by any AMQP implementation.
 *
 * <p>{@link AmqpFrameParser} is a push-parser for the frame envelope,
 * delivering decoded frames to an {@link AmqpFrameHandler}; {@link
 * AmqpFrame} and {@link AmqpBits} hold framing constants and bit-packing
 * helpers. {@link BasicMethods}, {@link ChannelMethods}, {@link
 * ConfirmMethods}, {@link ConnectionMethods}, {@link ExchangeMethods},
 * {@link QueueMethods}, and {@link TxMethods} encode/decode each AMQP
 * method class; {@link BasicProperties} and {@link FieldTable} encode the
 * content-header property list and AMQP field-table type system.
 *
 * <p>These classes have no client (or server) coupling — only
 * {@code org.bluezoo.gumdrop.amqp.client} currently builds on them, but
 * a future server implementation would share this package rather than
 * duplicate the wire format.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.amqp.client
 * @see <a href="https://www.rabbitmq.com/resources/specs/amqp0-9-1.pdf">AMQP 0-9-1 specification</a>
 */
package org.bluezoo.gumdrop.amqp;
