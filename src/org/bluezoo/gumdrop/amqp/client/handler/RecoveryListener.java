/*
 * RecoveryListener.java
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
 * Notified of {@link org.bluezoo.gumdrop.amqp.client.AMQPClientRecovery}'s
 * reconnect state transitions — for logging/metrics, not for re-running
 * setup: topology (exchanges, queues, bindings, consumers) declared via
 * {@link RecoveryHandler#onFirstConnect} is replayed automatically and
 * does not require any action from these callbacks.
 *
 * <p>All methods have empty default implementations — implement only
 * what you need.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface RecoveryListener {

    /** The connection was lost (or a connect attempt failed). */
    default void onConnectionLost(Exception cause) { }

    /**
     * A reconnect attempt is about to be made after waiting {@code delayMs}.
     *
     * @param attempt the 1-based attempt number since the last successful connection
     */
    default void onReconnecting(int attempt, long delayMs) { }

    /**
     * Reconnected and finished replaying recorded topology (exchange/queue
     * declarations, bindings, consumers). Channels the application is
     * holding references to are usable again.
     */
    default void onRecovered() { }

    /**
     * Gave up after exhausting {@link org.bluezoo.gumdrop.amqp.client.RecoveryPolicy#withMaxAttempts}.
     * No further reconnect attempts will be made.
     */
    default void onRecoveryFailed(Exception cause) { }
}
