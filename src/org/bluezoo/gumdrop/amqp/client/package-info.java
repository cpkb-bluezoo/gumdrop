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
 * against a broker such as RabbitMQ (issue #154).
 *
 * <p>This package is under active development.
 *
 * <p>Implemented and unit tested so far:
 * <ul>
 *   <li>The wire-format codec: {@link org.bluezoo.gumdrop.amqp.client.AMQPFrameParser}
 *       (a push-parser for the frame envelope, in the same style as
 *       {@link org.bluezoo.gumdrop.http.h2.H2Parser} — no assumption is ever
 *       made that a read from the network contains a complete frame, or that
 *       a message body fits in memory), {@link org.bluezoo.gumdrop.amqp.client.FieldTable}
 *       (AMQP field-table type), {@link org.bluezoo.gumdrop.amqp.client.BasicProperties}
 *       (content-header properties for the {@code basic} class), and the
 *       per-class method-argument codecs ({@code ConnectionMethods},
 *       {@code ChannelMethods}, {@code ExchangeMethods}, {@code QueueMethods},
 *       {@code BasicMethods}, {@code TxMethods}, {@code ConfirmMethods}).</li>
 *   <li>{@link org.bluezoo.gumdrop.amqp.client.AMQPClientProtocolHandler}: the
 *       connection and channel lifecycle — protocol header, {@code
 *       connection.start}/{@code start-ok} (SASL PLAIN), {@code tune}/
 *       {@code tune-ok}, {@code open}/{@code open-ok}, {@code channel.open}/
 *       {@code open-ok}, graceful and unsolicited close on both; exchange/
 *       queue declaration and binding; publish ({@link
 *       org.bluezoo.gumdrop.amqp.client.handler.PublishBody} — a message body
 *       is written in whatever chunks the caller has them in, never
 *       materialised as one buffer, mirroring {@link
 *       org.bluezoo.gumdrop.smtp.client.handler.ClientMessageData}); and
 *       consume ({@link org.bluezoo.gumdrop.amqp.client.handler.DeliveryHandler}
 *       — a delivered body is likewise streamed to the application as
 *       content-body frames arrive, one chunk per callback, never
 *       accumulated by this layer) plus ack/nack/reject/cancel; transactions
 *       ({@code tx.select}/{@code commit}/{@code rollback}); and flow control
 *       ({@code channel.flow} in both directions — broker-initiated flow is
 *       always acknowledged automatically and surfaced via {@link
 *       org.bluezoo.gumdrop.amqp.client.handler.FlowListener}, client-initiated
 *       flow is a normal request/reply call). All via the typed-state handler
 *       API in {@link org.bluezoo.gumdrop.amqp.client.handler} (mirroring
 *       {@link org.bluezoo.gumdrop.smtp.client.handler}).</li>
 *   <li>{@link org.bluezoo.gumdrop.amqp.client.AMQPClientRecovery}: automatic
 *       reconnect (exponential backoff, see {@link
 *       org.bluezoo.gumdrop.amqp.client.RecoveryPolicy}) and topology
 *       recovery — exchange/queue declarations, bindings, and consumers
 *       registered once via {@link
 *       org.bluezoo.gumdrop.amqp.client.handler.RecoveryHandler#onFirstConnect}
 *       are automatically replayed against each new connection after a
 *       reconnect, so the application's {@code ClientChannel} references
 *       keep working without re-running setup. This is the facade most
 *       applications should use; {@code AMQPClientProtocolHandler} directly
 *       is for callers that want to handle reconnection themselves.</li>
 *   <li>Publisher confirms ({@code confirm.select}, RabbitMQ extension):
 *       {@link org.bluezoo.gumdrop.amqp.client.handler.ClientChannel#confirmSelect}
 *       puts a channel into confirm mode, after which every {@link
 *       org.bluezoo.gumdrop.amqp.client.handler.PublishBody#getSequenceNumber}
 *       is acknowledged (or rejected) by the broker via {@link
 *       org.bluezoo.gumdrop.amqp.client.handler.ConfirmListener}.
 *       {@code basic.get} was considered and deliberately left out — it's a
 *       polling pattern (send {@code basic.get}, get one message or
 *       {@code basic.get-empty} back) that {@code basic.consume}/{@code
 *       basic.deliver} already covers strictly better in an async client,
 *       down to the one-at-a-time case via {@code basic.qos}
 *       prefetch=1.</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.smtp.client
 * @see <a href="https://www.rabbitmq.com/resources/specs/amqp0-9-1.pdf">AMQP 0-9-1 specification</a>
 */
package org.bluezoo.gumdrop.amqp.client;
