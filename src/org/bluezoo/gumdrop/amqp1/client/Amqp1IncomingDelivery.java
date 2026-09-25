/*
 * Amqp1IncomingDelivery.java
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
 * A message received on a receiving link, awaiting the receiver's verdict.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface Amqp1IncomingDelivery {

    /**
     * The session-level delivery-id.
     *
     * @return the delivery-id
     */
    long getDeliveryId();

    /**
     * The delivery tag chosen by the sender.
     *
     * @return the tag
     */
    byte[] getTag();

    /**
     * Whether the sender sent this delivery already settled, in which
     * case there is nothing to dispose of.
     *
     * @return true if settled
     */
    boolean isSettled();

    /**
     * Reports the state of the delivery to the sender.
     *
     * @param state the state or outcome
     * @param settled {@code true} to settle the delivery as well
     * @throws IllegalStateException if the delivery is already settled
     */
    void dispose(DeliveryState state, boolean settled);

    /** Accepts and settles: the message was processed successfully. */
    void accept();

    /** Releases and settles: the message was not processed and may be redelivered. */
    void release();

    /**
     * Rejects and settles: the message was invalid and will never be
     * acceptable.
     *
     * @param error why, or {@code null}
     */
    void reject(Amqp1Error error);
}
