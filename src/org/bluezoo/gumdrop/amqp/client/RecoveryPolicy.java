/*
 * RecoveryPolicy.java
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

package org.bluezoo.gumdrop.amqp.client;

/**
 * Reconnect backoff parameters for {@link AMQPClientRecovery}.
 *
 * <p>Defaults: 1s initial delay, doubling each attempt, capped at 30s,
 * unlimited attempts — a conventional exponential backoff that retries
 * quickly at first and settles into a steady 30s cadence for a
 * broker/network outage that outlasts a few attempts, without ever
 * giving up on its own (an application that wants to give up after N
 * attempts should set {@link #withMaxAttempts}).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class RecoveryPolicy {

    private long initialDelayMs = 1000L;
    private long maxDelayMs = 30000L;
    private double multiplier = 2.0;
    private int maxAttempts = 0; // 0 = unlimited

    public RecoveryPolicy withInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
        return this;
    }

    public RecoveryPolicy withMaxDelayMs(long maxDelayMs) {
        this.maxDelayMs = maxDelayMs;
        return this;
    }

    public RecoveryPolicy withMultiplier(double multiplier) {
        this.multiplier = multiplier;
        return this;
    }

    /** @param maxAttempts consecutive failed attempts before giving up; 0 = unlimited */
    public RecoveryPolicy withMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
        return this;
    }

    public long getInitialDelayMs() { return initialDelayMs; }
    public long getMaxDelayMs() { return maxDelayMs; }
    public double getMultiplier() { return multiplier; }
    public int getMaxAttempts() { return maxAttempts; }

    /** Delay before the given 1-based attempt number, per the configured backoff. */
    public long delayFor(int attempt) {
        double delay = initialDelayMs * Math.pow(multiplier, Math.max(0, attempt - 1));
        return (long) Math.min(delay, maxDelayMs);
    }
}
