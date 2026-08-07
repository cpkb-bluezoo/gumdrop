/*
 * RecoveryHandler.java
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
 * Entry point for {@link org.bluezoo.gumdrop.amqp.client.AMQPClientRecovery}.
 *
 * <p>Unlike {@link ConnectionReady} (the raw, non-recovering protocol
 * handler's entry point), this is called exactly <strong>once</strong>,
 * on the first successful connection — not again after a reconnect.
 * Declare exchanges/queues/bindings and register consumers here; the
 * recovery layer records those calls and automatically replays them
 * against each new connection after a reconnect, so the application
 * never needs to repeat its setup.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.amqp.client.AMQPClientRecovery
 */
public interface RecoveryHandler {

    /**
     * @param connection a {@link ClientConnection} whose channels
     *      transparently survive reconnects — keep using the {@link
     *      ClientChannel} instances it hands back as normal; recovery
     *      happens underneath them
     */
    void onFirstConnect(ClientConnection connection);
}
