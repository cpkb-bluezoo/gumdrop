/*
 * Disposition.java
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
 * The {@code disposition} performative (core specification 2.7.6):
 * informs the peer of the state of deliveries, and settles them. It
 * covers a range of delivery-ids on the session.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Disposition extends Performative {

    private final boolean receiver;
    private final long first;
    private final Long last;
    private boolean settled;
    private DeliveryState state;
    private boolean batchable;

    /**
     * @param receiver {@code true} if sent by the receiving end of the deliveries
     * @param first the first delivery-id covered
     * @param last the last delivery-id covered, or {@code null} for just {@code first}
     */
    public Disposition(boolean receiver, long first, Long last) {
        this.receiver = receiver;
        this.first = first;
        this.last = last;
    }

    @Override
    public long getDescriptor() {
        return DESCRIPTOR_DISPOSITION;
    }

    /** True if sent by the receiving end of the deliveries it covers. */
    public boolean isReceiver() {
        return receiver;
    }

    public long getFirst() {
        return first;
    }

    /** The last delivery-id covered; equal to {@link #getFirst()} if unspecified. */
    public long getLast() {
        return last == null ? first : last.longValue();
    }

    public boolean isSettled() {
        return settled;
    }

    public void setSettled(boolean settled) {
        this.settled = settled;
    }

    public DeliveryState getState() {
        return state;
    }

    public void setState(DeliveryState state) {
        this.state = state;
    }

    public boolean isBatchable() {
        return batchable;
    }

    @Override
    public void write(Amqp1Encoder out) {
        new Amqp1ListBuilder()
                .addBoolean(Boolean.valueOf(receiver))
                .addUint(Long.valueOf(first))
                .addUint(last)
                .addBoolean(settled ? Boolean.TRUE : null)
                .addObjectEncoded(state)
                .addBoolean(batchable ? Boolean.TRUE : null)
                .writeTo(out, DESCRIPTOR_DISPOSITION);
    }

    static Disposition fromFields(List<Object> f) throws Amqp1ProtocolException {
        Boolean role = Fields.required(Fields.bool(f, 0, "role"), "disposition", "role");
        long first = Fields.required(Fields.unsigned(f, 1, "first"), "disposition", "first")
                .longValue();
        Disposition d = new Disposition(role.booleanValue(), first,
                Fields.unsigned(f, 2, "last"));
        Boolean settled = Fields.bool(f, 3, "settled");
        d.settled = settled != null && settled.booleanValue();
        d.state = DeliveryState.fromDescribed(Fields.get(f, 4));
        Boolean batchable = Fields.bool(f, 5, "batchable");
        d.batchable = batchable != null && batchable.booleanValue();
        return d;
    }
}
