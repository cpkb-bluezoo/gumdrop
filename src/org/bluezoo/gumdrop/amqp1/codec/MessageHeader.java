/*
 * MessageHeader.java
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

import java.util.List;

/**
 * The {@code header} message section (core specification 3.2.1):
 * transport delivery details of a message.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class MessageHeader {

    /** The default message priority. */
    public static final int DEFAULT_PRIORITY = 4;

    private boolean durable;
    private Integer priority;
    private Long ttl;
    private boolean firstAcquirer;
    private Long deliveryCount;

    /** Whether the message must survive a broker restart. */
    public boolean isDurable() {
        return durable;
    }

    public void setDurable(boolean durable) {
        this.durable = durable;
    }

    /** The priority, 0 to 9; {@link #DEFAULT_PRIORITY} if unset. */
    public int getPriority() {
        return priority == null ? DEFAULT_PRIORITY : priority.intValue();
    }

    public void setPriority(int priority) {
        this.priority = Integer.valueOf(priority);
    }

    /** The time to live in milliseconds, or 0 if unset. */
    public long getTtl() {
        return ttl == null ? 0L : ttl.longValue();
    }

    public void setTtl(long ttl) {
        this.ttl = Long.valueOf(ttl);
    }

    public boolean isFirstAcquirer() {
        return firstAcquirer;
    }

    public void setFirstAcquirer(boolean firstAcquirer) {
        this.firstAcquirer = firstAcquirer;
    }

    /** The number of prior unsuccessful delivery attempts. */
    public long getDeliveryCount() {
        return deliveryCount == null ? 0L : deliveryCount.longValue();
    }

    public void setDeliveryCount(long deliveryCount) {
        this.deliveryCount = Long.valueOf(deliveryCount);
    }

    void write(Amqp1Encoder out) {
        new Amqp1ListBuilder()
                .addBoolean(durable ? Boolean.TRUE : null)
                .addUbyte(priority)
                .addUint(ttl)
                .addBoolean(firstAcquirer ? Boolean.TRUE : null)
                .addUint(deliveryCount)
                .writeTo(out, MessageParser.SECTION_HEADER);
    }

    static MessageHeader fromFields(List<Object> f) throws Amqp1ProtocolException {
        MessageHeader h = new MessageHeader();
        Boolean durable = Fields.bool(f, 0, "durable");
        h.durable = durable != null && durable.booleanValue();
        Long priority = Fields.unsigned(f, 1, "priority");
        h.priority = priority == null ? null : Integer.valueOf(priority.intValue());
        h.ttl = Fields.unsigned(f, 2, "ttl");
        Boolean first = Fields.bool(f, 3, "first-acquirer");
        h.firstAcquirer = first != null && first.booleanValue();
        h.deliveryCount = Fields.unsigned(f, 4, "delivery-count");
        return h;
    }
}
