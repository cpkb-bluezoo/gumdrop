/*
 * ClientChannel.java
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

package org.bluezoo.gumdrop.amqp.client.handler;

import org.bluezoo.gumdrop.amqp.client.BasicProperties;
import org.bluezoo.gumdrop.amqp.client.FieldTable;

/**
 * An open AMQP channel: exchange/queue declaration, and publish/consume.
 *
 * <p>Administration operations ({@code exchange.delete}, {@code
 * queue.unbind}, {@code queue.purge}, {@code queue.delete}) are
 * deliberately not exposed — this client targets publish/consume
 * workloads, not broker administration (issue #154).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ClientChannel {

    /** The channel number, as passed to {@link ClientConnection#channelOpen}. */
    int getChannelId();

    /**
     * Registers a listener for this channel closing, whether by
     * {@link #close} or unsolicited by the broker. Replaces any
     * previously registered listener.
     */
    void setCloseListener(ChannelClosedListener listener);

    /**
     * Closes this channel.
     *
     * @param replyCode an AMQP reply code (200 for normal closure)
     * @param replyText a human-readable reason
     * @param handler receives {@code channel.close-ok}
     */
    void close(int replyCode, String replyText, ServerChannelCloseHandler handler);

    /**
     * Declares an exchange.
     *
     * @param exchange the exchange name
     * @param type the exchange type ({@code direct}, {@code fanout},
     *      {@code topic}, {@code headers}, or a plugin-provided type)
     * @param durable survives a broker restart
     * @param autoDelete deleted when the last queue is unbound from it
     * @param arguments broker-specific arguments, or {@code null}
     * @param handler receives {@code exchange.declare-ok}
     */
    void exchangeDeclare(String exchange, String type, boolean durable, boolean autoDelete,
            FieldTable arguments, ServerExchangeDeclareHandler handler);

    /**
     * Declares a queue.
     *
     * @param queue the queue name, or {@code ""} to have the server
     *      generate one (returned in the {@code declare-ok} callback)
     * @param durable survives a broker restart
     * @param exclusive usable only by this connection, deleted when it closes
     * @param autoDelete deleted once its last consumer unsubscribes
     * @param arguments broker-specific arguments, or {@code null}
     * @param handler receives {@code queue.declare-ok}
     */
    void queueDeclare(String queue, boolean durable, boolean exclusive, boolean autoDelete,
            FieldTable arguments, ServerQueueDeclareHandler handler);

    /**
     * Binds a queue to an exchange.
     *
     * @param arguments broker-specific arguments, or {@code null}
     * @param handler receives {@code queue.bind-ok}
     */
    void queueBind(String queue, String exchange, String routingKey, FieldTable arguments,
            ServerQueueBindHandler handler);

    /**
     * Publishes a message. AMQP has no delivery acknowledgement for a
     * plain publish (that's what publisher confirms, a follow-up
     * feature, are for) — this returns the streaming body writer
     * directly rather than taking a reply-handler callback.
     *
     * @param exchange the exchange to publish to ({@code ""} for the
     *      default exchange, which routes by queue name)
     * @param routingKey the routing key
     * @param mandatory ask the broker to {@code basic.return} the
     *      message if it can't be routed to any queue (not yet surfaced
     *      to the application — logged if it occurs)
     * @param properties content-header properties, or {@code null} for none
     * @param bodySize the total number of body bytes that will be
     *      written via the returned {@link PublishBody} — AMQP requires
     *      this declared up front, but not materialised as one buffer;
     *      see {@link PublishBody}
     * @return the streaming body writer for this publish
     */
    PublishBody basicPublish(String exchange, String routingKey, boolean mandatory,
            BasicProperties properties, long bodySize);

    /**
     * Registers a consumer. Deliveries stream to {@code deliveryHandler}
     * for the consumer's lifetime (until {@link #basicCancel} or this
     * channel/the connection closes) — see {@link DeliveryHandler}.
     *
     * @param queue the queue to consume from
     * @param consumerTag a tag identifying this consumer, or {@code ""}
     *      to have the server generate one
     * @param noAck if true, the broker doesn't wait for {@link #basicAck}
     *      (messages are considered delivered as soon as sent)
     * @param exclusive only this consumer may access the queue
     * @param arguments broker-specific arguments, or {@code null}
     * @param deliveryHandler receives deliveries for the life of the consumer
     * @param handler receives {@code basic.consume-ok}
     */
    void basicConsume(String queue, String consumerTag, boolean noAck, boolean exclusive,
            FieldTable arguments, DeliveryHandler deliveryHandler, ServerConsumeHandler handler);

    /**
     * Cancels a consumer. No more deliveries will arrive for it after
     * {@code handler} is invoked.
     */
    void basicCancel(String consumerTag, ServerCancelHandler handler);

    /** Acknowledges one or more deliveries (see {@link DeliveryHandler}). */
    void basicAck(long deliveryTag, boolean multiple);

    /** Negative-acknowledges one or more deliveries (RabbitMQ extension). */
    void basicNack(long deliveryTag, boolean multiple, boolean requeue);

    /** Rejects a single delivery. */
    void basicReject(long deliveryTag, boolean requeue);

    // ── transactions ──

    /**
     * Makes this channel transactional. Publishes and acks/nacks/rejects
     * sent afterwards are held by the broker until {@link #txCommit} or
     * {@link #txRollback}.
     */
    void txSelect(ServerTxSelectHandler handler);

    /** Commits the current transaction. Requires a prior {@link #txSelect}. */
    void txCommit(ServerTxCommitHandler handler);

    /** Rolls back the current transaction. Requires a prior {@link #txSelect}. */
    void txRollback(ServerTxRollbackHandler handler);

    // ── flow control ──

    /**
     * Registers a listener for broker-initiated {@code channel.flow}
     * (asking this client to pause/resume sending). Replaces any
     * previously registered listener. The client always acknowledges
     * with {@code channel.flow-ok} whether or not a listener is set;
     * this is purely a notification — pausing is left to the application.
     */
    void setFlowListener(FlowListener listener);

    /**
     * Client-initiated {@code channel.flow}: asks the broker to
     * pause ({@code active=false}) or resume ({@code active=true})
     * sending content on this channel (e.g. slowing deliveries to a
     * consumer that can't keep up).
     */
    void flow(boolean active, ServerFlowHandler handler);

    // ── publisher confirms ──

    /**
     * Puts this channel into publisher confirm mode (RabbitMQ extension):
     * every subsequent {@link #basicPublish} is assigned a sequence
     * number (see {@link PublishBody#getSequenceNumber}) and the broker
     * acknowledges or rejects it via {@link ConfirmListener}.
     */
    void confirmSelect(ServerConfirmSelectHandler handler);

    /**
     * Registers the listener for publisher confirms after {@link
     * #confirmSelect}. Replaces any previously registered listener.
     */
    void setConfirmListener(ConfirmListener listener);
}
