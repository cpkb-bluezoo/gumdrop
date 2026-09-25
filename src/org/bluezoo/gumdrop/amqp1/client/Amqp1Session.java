/*
 * Amqp1Session.java
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
import org.bluezoo.gumdrop.amqp1.codec.Begin;

/**
 * An active AMQP 1.0 session: a bidirectional, sequential conversation
 * between two containers, multiplexed on one channel of the connection.
 * Links to nodes are attached within a session, and the session's
 * transfer windows control how many transfers may be in flight.
 *
 * <p>Node addresses in {@code source} and {@code target} are
 * broker-specific: consult the broker's documentation for the address
 * syntax of its queues and topics.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface Amqp1Session {

    /**
     * The channel number this client uses to send on this session.
     *
     * @return the local channel
     */
    int getLocalChannel();

    /**
     * The server's {@code begin} performative for this session.
     *
     * @return the peer's begin
     */
    Begin getPeerBegin();

    /**
     * Attaches a sending link that publishes to {@code address}.
     * Deliveries are sent unsettled, so the broker reports an outcome
     * for each (at-least-once).
     *
     * @param linkName a link name unique within this session
     * @param address the target node address
     * @param handler receives the link's events
     * @throws IllegalStateException if the session is not active, the
     *      link name is in use, or every handle is in use
     */
    void attachSender(String linkName, String address, Amqp1SenderHandler handler);

    /**
     * Attaches a sending link with full control of the {@code attach}
     * parameters: settle modes, target properties (durability, capabilities),
     * maximum message size and so on.
     *
     * <p>The client assigns the link handle, overwriting
     * {@link Attach#getHandle()}, and supplies an empty source and an
     * initial delivery-count of 0 if none was set.
     *
     * @param attach the parameters; must describe the sending end
     * @param handler receives the link's events
     * @throws IllegalArgumentException if {@code attach} is the receiving end
     * @throws IllegalStateException if the session is not active, the
     *      link name is in use, or every handle is in use
     */
    void attachSender(Attach attach, Amqp1SenderHandler handler);

    /**
     * Attaches a receiving link that consumes from {@code address}. No
     * credit is issued until {@link Amqp1Receiver#addCredit} is called.
     *
     * @param linkName a link name unique within this session
     * @param address the source node address
     * @param handler receives the link's events and the messages
     * @throws IllegalStateException if the session is not active, the
     *      link name is in use, or every handle is in use
     */
    void attachReceiver(String linkName, String address, Amqp1ReceiverHandler handler);

    /**
     * Attaches a receiving link with full control of the {@code attach}
     * parameters: settle modes, source filters and distribution mode,
     * durability and so on.
     *
     * <p>The client assigns the link handle, overwriting
     * {@link Attach#getHandle()}, and supplies an empty target if none
     * was set.
     *
     * @param attach the parameters; must describe the receiving end
     * @param handler receives the link's events and the messages
     * @throws IllegalArgumentException if {@code attach} is the sending end
     * @throws IllegalStateException if the session is not active, the
     *      link name is in use, or every handle is in use
     */
    void attachReceiver(Attach attach, Amqp1ReceiverHandler handler);

    /**
     * Ends the session. The peer's answering {@code end} is reported
     * through {@link Amqp1SessionHandler#handleEnded}. Links still
     * attached are reported detached.
     *
     * @param error why the session is being ended, or {@code null} for a
     *      normal end
     * @throws IllegalStateException if the session is already ending
     */
    void end(Amqp1Error error);
}
