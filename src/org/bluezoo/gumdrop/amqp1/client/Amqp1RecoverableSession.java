/*
 * Amqp1RecoverableSession.java
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

import org.bluezoo.gumdrop.amqp1.codec.Attach;

/**
 * The session managed by {@link Amqp1ClientRecovery}. It outlives any one
 * connection: links attached through it are recorded, and attached again
 * on the new session each time the connection is re-established.
 *
 * <p>The {@link Amqp1Sender} and {@link Amqp1Receiver} a handler is given
 * are likewise stable across reconnects: they always refer to the link's
 * current attachment. Between a loss and the re-attachment their
 * operations throw {@link IllegalStateException}.
 *
 * <h2>What recovery does and does not preserve</h2>
 * <ul>
 *   <li>When the connection is lost each link's handler receives
 *       {@code handleDetached(error, false)}. When the link is attached
 *       again it receives {@code handleAttached} once more; a receiver
 *       must therefore grant credit there, on every attachment.</li>
 *   <li>Deliveries are not resumed. A delivery sent but not yet
 *       acknowledged when the connection was lost is <em>in doubt</em>: it
 *       may or may not have reached the broker, so a sender wanting
 *       at-least-once delivery should send it again. A message received
 *       but not yet disposed of is released by the broker on connection
 *       loss and delivered again, so receivers may see duplicates;
 *       disposing of a delivery from the lost connection throws
 *       {@link IllegalStateException}.</li>
 *   <li>A link the broker closes deliberately (a detach with
 *       {@code closed} true, for example because the node does not
 *       exist) is not attached again.</li>
 * </ul>
 *
 * <p>Attach links, and use them, from a handler callback, which runs on the
 * connection's event loop. From any other thread, submit the call with
 * {@link Amqp1ClientRecovery#execute}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface Amqp1RecoverableSession {

    /**
     * Attaches a recoverable sending link publishing to {@code address}.
     *
     * @param linkName a link name unique among this session's sending links
     * @param address the target node address
     * @param handler receives the link's events, across reconnects
     * @throws IllegalStateException if the name is already in use
     */
    void attachSender(String linkName, String address, Amqp1SenderHandler handler);

    /**
     * Attaches a recoverable sending link with full control of the
     * {@code attach} parameters. The {@code Attach} is copied.
     *
     * @param attach the parameters; must describe the sending end
     * @param handler receives the link's events, across reconnects
     * @throws IllegalArgumentException if {@code attach} is the receiving end
     * @throws IllegalStateException if the name is already in use
     */
    void attachSender(Attach attach, Amqp1SenderHandler handler);

    /**
     * Attaches a recoverable receiving link consuming from {@code address}.
     *
     * @param linkName a link name unique among this session's receiving links
     * @param address the source node address
     * @param handler receives the link's events and messages, across reconnects
     * @throws IllegalStateException if the name is already in use
     */
    void attachReceiver(String linkName, String address, Amqp1ReceiverHandler handler);

    /**
     * Attaches a recoverable receiving link with full control of the
     * {@code attach} parameters. The {@code Attach} is copied.
     *
     * @param attach the parameters; must describe the receiving end
     * @param handler receives the link's events and messages, across reconnects
     * @throws IllegalArgumentException if {@code attach} is the sending end
     * @throws IllegalStateException if the name is already in use
     */
    void attachReceiver(Attach attach, Amqp1ReceiverHandler handler);
}
