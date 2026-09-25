/*
 * Amqp1Receiver.java
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
import org.bluezoo.gumdrop.amqp1.codec.Attach;

/**
 * An attached receiving link. The broker sends messages only against
 * credit the receiver has granted.
 *
 * <p>Not thread-safe: use a receiver from the connection's event loop (any
 * handler callback), or through {@link Amqp1ClientRecovery#execute}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface Amqp1Receiver {

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
     * The credit outstanding: how many more deliveries the broker may
     * send.
     *
     * @return the link credit
     */
    long getLinkCredit();

    /**
     * Grants the broker permission to send {@code credit} more
     * deliveries, on top of any outstanding credit.
     *
     * @param credit the number of additional deliveries; positive
     * @throws IllegalStateException if the link is not attached
     */
    void addCredit(long credit);

    /**
     * Detaches the link. The broker's answering {@code detach} is
     * reported through {@link Amqp1ReceiverHandler#handleDetached}.
     *
     * @param error why, or {@code null}
     * @param close {@code true} to close (destroy) the link, {@code false}
     *      to leave it resumable
     * @throws IllegalStateException if the link is not attached
     */
    void detach(Amqp1Error error, boolean close);
}
