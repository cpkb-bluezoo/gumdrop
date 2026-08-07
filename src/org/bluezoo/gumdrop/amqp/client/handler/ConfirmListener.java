/*
 * ConfirmListener.java
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

/**
 * Receives publisher confirms — {@code basic.ack}/{@code basic.nack}
 * sent by the broker to acknowledge (or reject) previously published
 * messages — once a channel is in confirm mode via {@link
 * ClientChannel#confirmSelect}. Delivery tags here correlate to the
 * sequence number returned by {@link PublishBody#getSequenceNumber},
 * not to {@link DeliveryHandler} delivery tags (those are a completely
 * separate, consumer-side numbering).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientChannel#confirmSelect
 * @see ClientChannel#setConfirmListener
 */
public interface ConfirmListener {

    /**
     * @param sequenceNumber the publish this confirms (see {@link
     *      PublishBody#getSequenceNumber})
     * @param multiple if true, this also confirms every unconfirmed
     *      publish with a lower sequence number
     */
    void onAck(long sequenceNumber, boolean multiple);

    /**
     * @param sequenceNumber the publish that failed
     * @param multiple if true, this also fails every unconfirmed publish
     *      with a lower sequence number
     */
    void onNack(long sequenceNumber, boolean multiple);
}
