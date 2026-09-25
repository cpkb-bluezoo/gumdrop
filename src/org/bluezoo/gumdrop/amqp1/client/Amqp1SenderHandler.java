/*
 * Amqp1SenderHandler.java
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
import org.bluezoo.gumdrop.amqp1.codec.DeliveryState;

/**
 * Receives the events of one sending link.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface Amqp1SenderHandler {

    /**
     * The broker accepted the link. No message can be sent until it
     * grants credit: see {@link #handleCredit}.
     *
     * @param sender the attached link
     * @param peerAttach the broker's {@code attach}, showing the terminus
     *      it resolved and its settle modes
     */
    void handleAttached(Amqp1Sender sender, Attach peerAttach);

    /**
     * The broker granted link credit, so
     * {@link Amqp1Sender#startDelivery} and {@link Amqp1Sender#send} may be
     * called; see {@link Amqp1Sender#getLinkCredit()} for how many
     * deliveries may be started.
     *
     * @param sender the link
     */
    void handleCredit(Amqp1Sender sender);

    /**
     * Writing may resume. Called after {@link Amqp1OutgoingDelivery#write}
     * returned {@code false}, once the session's transfer window has
     * reopened and everything queued has been sent.
     *
     * @param delivery the delivery that was being written
     */
    void handleWritable(Amqp1OutgoingDelivery delivery);

    /**
     * The receiver reported the state of an unsettled delivery.
     *
     * <p>If {@code settled} is {@code false} the broker has not settled
     * it yet (receiver settle mode {@code second}): call
     * {@link Amqp1OutgoingDelivery#settle()} to complete it.
     *
     * @param delivery the delivery
     * @param state the outcome, for example accepted or rejected; may be
     *      {@code null} if the receiver only settled it
     * @param settled whether the receiver settled the delivery
     */
    void handleOutcome(Amqp1OutgoingDelivery delivery, DeliveryState state, boolean settled);

    /**
     * The link is detached: the broker detached it (possibly refusing
     * the attach), acknowledged a detach requested with
     * {@link Amqp1Sender#detach}, or the session or connection ended.
     *
     * @param error the error reported, or {@code null} for a normal detach
     * @param closed whether the link was closed (destroyed) rather than
     *      merely detached. A session or connection ending under the
     *      link is reported as {@code false}: the link can be attached
     *      again on a new session
     */
    void handleDetached(Amqp1Error error, boolean closed);
}
