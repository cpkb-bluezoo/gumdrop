/*
 * Amqp1Sender.java
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

package org.bluezoo.gumdrop.amqp1.client;

import java.nio.ByteBuffer;
import java.util.Map;

import org.bluezoo.gumdrop.amqp1.codec.Amqp1Error;
import org.bluezoo.gumdrop.amqp1.codec.Attach;
import org.bluezoo.gumdrop.amqp1.codec.MessageHeader;
import org.bluezoo.gumdrop.amqp1.codec.MessageProperties;

/**
 * An attached sending link.
 *
 * <p>A delivery consumes one unit of link credit when it starts. Send
 * small messages with {@link #send}; stream large ones with
 * {@link #startDelivery}, which lets the body be written in chunks that
 * are framed and sent as they are produced, never assembled in memory.
 *
 * <p>Not thread-safe: use a sender from the connection's event loop (any
 * handler callback), or through {@link Amqp1ClientRecovery#execute}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface Amqp1Sender {

    /**
     * The link name.
     *
     * @return the name
     */
    String getName();

    /**
     * The broker's {@code attach} for this link.
     *
     * @return the peer's attach
     */
    Attach getPeerAttach();

    /**
     * The number of deliveries that may be started now: the credit the
     * broker has granted and not yet used.
     *
     * @return the link credit
     */
    long getLinkCredit();

    /**
     * Starts a delivery, to be completed with {@link Amqp1OutgoingDelivery#write}
     * and {@link Amqp1OutgoingDelivery#finish()}. One delivery at a time
     * may be in progress on a link.
     *
     * @param deliveryTag a tag identifying the delivery, 1 to 32 octets,
     *      unique among the link's unsettled deliveries
     * @param header the message header, or {@code null}
     * @param properties the message properties, or {@code null}
     * @param applicationProperties application-defined properties, or {@code null}
     * @param settled whether to send the delivery pre-settled (at-most-once,
     *      no outcome reported); must be compatible with the link's
     *      sender settle mode
     * @return the delivery
     * @throws IllegalStateException if there is no link credit, another
     *      delivery is in progress, or the link is not attached
     * @throws IllegalArgumentException if the tag is invalid or
     *      {@code settled} conflicts with the sender settle mode
     */
    Amqp1OutgoingDelivery startDelivery(byte[] deliveryTag, MessageHeader header,
            MessageProperties properties, Map<Object, Object> applicationProperties,
            boolean settled);

    /**
     * Sends a whole message whose body is already in hand. The delivery
     * is sent unsettled unless the link's sender settle mode is
     * {@code settled}, in which case it is sent pre-settled.
     *
     * @param deliveryTag a tag identifying the delivery, 1 to 32 octets
     * @param properties the message properties, or {@code null}
     * @param applicationProperties application-defined properties, or {@code null}
     * @param body the body octets, sent as a {@code data} section
     * @return the delivery, to observe its outcome
     * @throws IllegalStateException if there is no link credit
     */
    Amqp1OutgoingDelivery send(byte[] deliveryTag, MessageProperties properties,
            Map<Object, Object> applicationProperties, ByteBuffer body);

    /**
     * Detaches the link. The broker's answering {@code detach} is
     * reported through {@link Amqp1SenderHandler#handleDetached}.
     *
     * @param error why, or {@code null}
     * @param close {@code true} to close (destroy) the link, {@code false}
     *      to leave it resumable
     * @throws IllegalStateException if the link is not attached
     */
    void detach(Amqp1Error error, boolean close);
}
