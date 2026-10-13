/*
 * GrpcCallOptions.java
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

package org.bluezoo.gumdrop.grpc.client;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.bluezoo.gumdrop.grpc.GrpcMetadata;

/**
 * What a client sets on a call before it starts: the request metadata and a
 * deadline. Pass it to the {@link GrpcClient#call} form that takes it.
 *
 * <p>A deadline is sent to the server as {@code grpc-timeout}, and the client
 * enforces it too: when it passes, the call fails locally with
 * {@code DEADLINE_EXCEEDED} (4) and the stream is cancelled.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class GrpcCallOptions {

    private final GrpcMetadata metadata = new GrpcMetadata();
    private long timeoutNanos = -1L;
    private Instant deadline;

    /**
     * Returns the metadata to send with the request, to be added to.
     *
     * @return the request metadata
     */
    public GrpcMetadata getMetadata() {
        return metadata;
    }

    /**
     * Sets the time the call may take, from when it starts.
     *
     * @param amount the time, not negative
     * @param unit the unit of {@code amount}
     * @return these options
     * @throws IllegalArgumentException if the time is negative
     */
    public GrpcCallOptions timeout(long amount, TimeUnit unit) {
        if (amount < 0) {
            throw new IllegalArgumentException("timeout must not be negative: " + amount);
        }
        timeoutNanos = unit.toNanos(amount);
        deadline = null;
        return this;
    }

    /**
     * Sets the time by which the call must be over. A time that has passed
     * already makes the call fail as soon as it starts.
     *
     * @param deadline the deadline
     * @return these options
     */
    public GrpcCallOptions deadline(Instant deadline) {
        this.deadline = deadline;
        timeoutNanos = -1L;
        return this;
    }

    /**
     * Returns the time the call has left, as the call starts.
     *
     * @return the time in nanoseconds, or -1 if no deadline was set
     */
    long remainingNanos() {
        if (deadline != null) {
            return Math.max(0L, Duration.between(Instant.now(), deadline).toNanos());
        }
        return timeoutNanos;
    }
}
