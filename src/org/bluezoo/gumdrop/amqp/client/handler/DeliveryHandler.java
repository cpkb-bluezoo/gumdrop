/*
 * DeliveryHandler.java
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

import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.amqp.client.BasicProperties;

/**
 * Receives {@code basic.deliver} events for a consumer registered via
 * {@link ClientChannel#basicConsume}, for the lifetime of that consumer
 * (until it is cancelled or the channel/connection closes) — not a
 * one-shot reply handler like the others in this package.
 *
 * <p>Callbacks fire in this fixed order for every delivery:
 * {@link #onDeliveryStart} once, then {@link #onDeliveryProperties}
 * once, then {@link #onDeliveryBodyChunk} zero or more times (as
 * content-body frames arrive off the wire — a message's body is
 * <strong>never</strong> buffered whole by this client; each chunk is
 * handed to the application as it arrives, exactly like
 * {@code responseBodyContent} on the HTTP server side), then
 * {@link #onDeliveryComplete} once. Only after {@code onDeliveryComplete}
 * is it valid to ack/nack/reject the delivery tag.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientChannel#basicConsume
 */
public interface DeliveryHandler {

    /**
     * @param consumerTag this consumer's tag (matches what {@link
     *      ServerConsumeHandler#handleConsumeOk} reported)
     * @param deliveryTag identifies this delivery for ack/nack/reject
     * @param redelivered true if this message was previously delivered
     *      and requeued (e.g. after a nack)
     * @param exchange the exchange the message was published to
     * @param routingKey the routing key it was published with
     */
    void onDeliveryStart(String consumerTag, long deliveryTag, boolean redelivered,
            String exchange, String routingKey);

    /**
     * @param properties the message's content-header properties
     * @param bodySize total bytes of body content to expect across the
     *      following {@link #onDeliveryBodyChunk} calls (0 if the
     *      message has no body)
     */
    void onDeliveryProperties(BasicProperties properties, long bodySize);

    /**
     * A chunk of the message body. {@code chunk} is only valid for the
     * duration of this call — copy it if it must outlive the callback.
     */
    void onDeliveryBodyChunk(ByteBuffer chunk);

    /** All body chunks for this delivery have arrived. */
    void onDeliveryComplete();
}
