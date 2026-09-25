/*
 * Amqp1RecoveryPolicy.java
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
 * How {@link Amqp1ClientRecovery} retries after losing its connection:
 * exponential backoff from an initial delay up to a ceiling, optionally
 * giving up after a number of consecutive failed attempts.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Amqp1RecoveryPolicy {

    private long initialDelayMs = 1000L;
    private long maxDelayMs = 30000L;
    private double multiplier = 2.0;
    private int maxAttempts = 0; // 0 = unlimited

    /** @param initialDelayMs the delay before the first retry (default 1000) */
    public Amqp1RecoveryPolicy withInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
        return this;
    }

    /** @param maxDelayMs the longest delay between retries (default 30000) */
    public Amqp1RecoveryPolicy withMaxDelayMs(long maxDelayMs) {
        this.maxDelayMs = maxDelayMs;
        return this;
    }

    /** @param multiplier the factor the delay grows by each attempt (default 2.0) */
    public Amqp1RecoveryPolicy withMultiplier(double multiplier) {
        this.multiplier = multiplier;
        return this;
    }

    /** @param maxAttempts consecutive failed attempts before giving up; 0 for unlimited (the default) */
    public Amqp1RecoveryPolicy withMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
        return this;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public long getMaxDelayMs() {
        return maxDelayMs;
    }

    public double getMultiplier() {
        return multiplier;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * The delay before the given attempt.
     *
     * @param attempt the attempt number, from 1
     * @return the delay in milliseconds
     */
    public long delayFor(int attempt) {
        double delay = initialDelayMs * Math.pow(multiplier, Math.max(0, attempt - 1));
        return (long) Math.min(delay, maxDelayMs);
    }
}
