/*
 * Amqp1OutgoingDelivery.java
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

/**
 * A message being sent on a sending link.
 *
 * <p>The body is written in chunks with {@link #write}; each chunk becomes
 * a {@code data} section. Octets are packed into transfer frames of the
 * negotiated size as they arrive, so memory use is bounded by the size of
 * one frame however large the message. When the session's transfer window
 * is exhausted, frames wait in a queue: {@link #write} then returns
 * {@code false} and the sender should stop until
 * {@link Amqp1SenderHandler#handleWritable}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface Amqp1OutgoingDelivery {

    /**
     * The delivery tag.
     *
     * @return the tag
     */
    byte[] getTag();

    /**
     * The session-level delivery-id, assigned when the first transfer is
     * sent; {@code null} before then.
     *
     * @return the delivery-id or {@code null}
     */
    Long getDeliveryId();

    /**
     * Whether the delivery has been settled: sent pre-settled, or settled
     * by the receiver or by {@link #settle()}.
     *
     * @return true if settled
     */
    boolean isSettled();

    /**
     * Appends body octets as a {@code data} section. The octets are
     * copied; the buffer may be reused on return.
     *
     * @param body the octets; consumed on return
     * @return {@code true} if the octets went out (or are queued within
     *      one frame's worth), {@code false} if the session window is
     *      exhausted and the sender should wait for
     *      {@link Amqp1SenderHandler#handleWritable}
     * @throws IllegalStateException if the delivery is finished or aborted
     */
    boolean write(ByteBuffer body);

    /**
     * Completes the delivery: sends the final transfer.
     *
     * @throws IllegalStateException if already finished or aborted
     */
    void finish();

    /**
     * Abandons the delivery. The receiver discards whatever it has
     * received of it.
     *
     * @throws IllegalStateException if already finished or aborted
     */
    void abort();

    /**
     * Settles an unsettled delivery, telling the receiver we have
     * finished with it. Needed only when the receiver reported an outcome
     * without settling ({@link Amqp1SenderHandler#handleOutcome} with
     * {@code settled} false).
     *
     * @throws IllegalStateException if the delivery is settled or not yet sent
     */
    void settle();
}
