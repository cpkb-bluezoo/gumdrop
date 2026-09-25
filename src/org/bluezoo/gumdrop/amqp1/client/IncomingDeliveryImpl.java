/*
 * IncomingDeliveryImpl.java
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

import org.bluezoo.gumdrop.amqp1.codec.Amqp1Error;
import org.bluezoo.gumdrop.amqp1.codec.DeliveryState;

/**
 * A delivery received on a receiving link.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class IncomingDeliveryImpl implements Amqp1IncomingDelivery {

    final ReceiverImpl link;
    final long deliveryId;
    private final byte[] tag;
    private boolean settled;

    IncomingDeliveryImpl(ReceiverImpl link, long deliveryId, byte[] tag, boolean settled) {
        this.link = link;
        this.deliveryId = deliveryId;
        this.tag = tag;
        this.settled = settled;
    }

    @Override
    public long getDeliveryId() {
        return deliveryId;
    }

    @Override
    public byte[] getTag() {
        return tag.clone();
    }

    @Override
    public boolean isSettled() {
        return settled;
    }

    @Override
    public void dispose(DeliveryState state, boolean settleNow) {
        if (settled) {
            throw new IllegalStateException("delivery is already settled");
        }
        link.requireAttached();
        link.session.sendIncomingDisposition(this, state, settleNow);
        if (settleNow) {
            settled = true;
        }
    }

    @Override
    public void accept() {
        dispose(DeliveryState.accepted(), true);
    }

    @Override
    public void release() {
        dispose(DeliveryState.released(), true);
    }

    @Override
    public void reject(Amqp1Error error) {
        dispose(DeliveryState.rejected(error), true);
    }
}
