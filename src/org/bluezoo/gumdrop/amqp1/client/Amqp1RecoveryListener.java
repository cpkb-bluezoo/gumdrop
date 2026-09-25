/*
 * Amqp1RecoveryListener.java
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
 * Optional observer of {@link Amqp1ClientRecovery}'s reconnection
 * progress. All methods do nothing by default. They are called from the
 * connection's event loop (or the retry timer), so must not block.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface Amqp1RecoveryListener {

    /**
     * The connection was lost, or an attempt to connect failed.
     *
     * @param cause the reason
     */
    default void onConnectionLost(Exception cause) {
    }

    /**
     * A reconnection attempt has been scheduled.
     *
     * @param attempt the attempt number, from 1
     * @param delayMs the delay before it is made
     */
    default void onReconnecting(int attempt, long delayMs) {
    }

    /**
     * The connection and session have been re-established and the
     * recorded links are being attached again. Each link's handler
     * receives its own {@code handleAttached} once the broker accepts it.
     */
    default void onRecovered() {
    }

    /**
     * Recovery has been abandoned: the policy's attempts are exhausted or
     * the failure is not one a retry can fix (such as rejected
     * credentials). The client makes no further attempts.
     *
     * @param cause the last failure
     */
    default void onRecoveryFailed(Exception cause) {
    }
}
