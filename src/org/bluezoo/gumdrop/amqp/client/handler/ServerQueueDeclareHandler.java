/*
 * ServerQueueDeclareHandler.java
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

/** Receives {@code queue.declare-ok}. */
public interface ServerQueueDeclareHandler {

    /**
     * @param queue the queue name (equal to what was requested, unless
     *      the server auto-generated one for an empty name)
     * @param messageCount number of messages currently in the queue
     * @param consumerCount number of active consumers
     */
    void handleQueueDeclareOk(String queue, long messageCount, long consumerCount);
}
