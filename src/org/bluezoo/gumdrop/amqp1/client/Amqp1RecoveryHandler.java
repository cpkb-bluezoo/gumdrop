/*
 * Amqp1RecoveryHandler.java
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

/**
 * Entry point for a connection made through {@link Amqp1ClientRecovery}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface Amqp1RecoveryHandler {

    /**
     * The first connection succeeded: it is authenticated, open, and a
     * session has begun. Attach links here. If the connection is later
     * lost, it is re-established and these links attached again without
     * this method being called a second time.
     *
     * @param session the recoverable session, stable across reconnects
     */
    void onFirstConnect(Amqp1RecoverableSession session);
}
