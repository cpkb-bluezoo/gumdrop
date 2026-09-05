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
 * Non-blocking AMQP 0-9-1 client for publishing and consuming messages
 * against a broker such as RabbitMQ.
 *
 * <p>{@link org.bluezoo.gumdrop.amqp.client.AMQPFrameParser} is a
 * push-parser for the frame envelope: it never assumes a network read
 * contains a complete frame, or that a message body fits in memory.
 * {@link org.bluezoo.gumdrop.amqp.client.AMQPClientProtocolHandler}
 * drives the connection and channel lifecycle -- the protocol header,
 * {@code connection.start}/{@code tune}/{@code open} and their replies,
 * {@code channel.open}, exchange/queue declaration and binding, publish
 * and consume (both streamed to/from the application in whatever chunks
 * arrive, never materialised as one buffer), ack/nack/reject/cancel,
 * transactions, and flow control -- entirely through the typed-state
 * handler API in {@link org.bluezoo.gumdrop.amqp.client.handler}.
 *
 * <p>{@link org.bluezoo.gumdrop.amqp.client.AMQPClientRecovery} is the
 * facade most applications should use: automatic reconnect with
 * exponential backoff ({@link
 * org.bluezoo.gumdrop.amqp.client.RecoveryPolicy}), replaying recorded
 * topology (declarations, bindings, consumers) against each new
 * connection so the application's channel references keep working
 * across a reconnect. {@code AMQPClientProtocolHandler} directly is for
 * callers that want to handle reconnection themselves.
 *
 * <p>Publisher confirms (RabbitMQ's {@code confirm.select} extension)
 * and SASL authentication ({@code PLAIN}, {@code AMQPLAIN}, {@code
 * EXTERNAL}, {@code GSSAPI}) are both supported.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.amqp.client.handler
 * @see <a href="https://www.rabbitmq.com/resources/specs/amqp0-9-1.pdf">AMQP 0-9-1 specification</a>
 */
package org.bluezoo.gumdrop.amqp.client;
