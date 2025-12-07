/*
 * RateLimiter.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.ratelimit;

/**
 * A sliding window rate limiter that tracks events over a configurable time window.
 *
 * <p>This implementation uses a circular buffer to efficiently track timestamps
 * of recent events. Events older than the window duration are automatically
 * expired when checking the limit.
 *
 * <p>Thread-safe: all public methods are synchronized.
 *
 * <h4>Example Usage</h4>
 * <pre>
 * // Allow 100 requests per minute
 * RateLimiter limiter = new RateLimiter(100, 60000);
 * 
 * if (limiter.tryAcquire()) {
 *     // Request allowed
 *     processRequest();
 * } else {
 *     // Rate limit exceeded
 *     sendRateLimitResponse();
 * }
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ConnectionRateLimiter
 * @see AuthenticationRateLimiter
 */
public class RateLimiter {

    private final long[] timestamps;
    private final long windowMs;
    private int index;
    private int count;

    /**
     * Creates a new rate limiter.
     *
     * @param maxEvents the maximum number of events allowed within the window
     * @param windowMs the time window in milliseconds
     * @throws IllegalArgumentException if maxEvents is less than 1 or windowMs is less than 1
     */
    public RateLimiter(int maxEvents, long windowMs) {
        if (maxEvents < 1) {
            throw new IllegalArgumentException("maxEvents must be at least 1");
        }
        if (windowMs < 1) {
            throw new IllegalArgumentException("windowMs must be at least 1");
        }
        this.timestamps = new long[maxEvents];
        this.windowMs = windowMs;
        this.index = 0;
        this.count = 0;
    }

    /**
     * Attempts to acquire a permit from this rate limiter.
     *
     * <p>If the rate limit has not been exceeded, the event is recorded and
     * {@code true} is returned. Otherwise, {@code false} is returned and
     * no state is changed.
     *
     * @return {@code true} if the event is allowed, {@code false} if rate limited
     */
    public synchronized boolean tryAcquire() {
        return tryAcquire(System.currentTimeMillis());
    }

    /**
     * Attempts to acquire a permit at the specified time.
     *
     * <p>This method is primarily for testing, allowing control over the
     * timestamp used for rate limiting calculations.
     *
     * @param now the current time in milliseconds
     * @return {@code true} if the event is allowed, {@code false} if rate limited
     */
    synchronized boolean tryAcquire(long now) {
        expireOldEntries(now);

        if (count >= timestamps.length) {
            return false; // Rate limit exceeded
        }

        // Record this event
        timestamps[index] = now;
        index = (index + 1) % timestamps.length;
        count++;
        return true;
    }

    /**
     * Checks if a permit would be available without acquiring it.
     *
     * @return {@code true} if an event would be allowed, {@code false} if rate limited
     */
    public synchronized boolean canAcquire() {
        return canAcquire(System.currentTimeMillis());
    }

    /**
     * Checks if a permit would be available at the specified time.
     *
     * @param now the current time in milliseconds
     * @return {@code true} if an event would be allowed, {@code false} if rate limited
     */
    synchronized boolean canAcquire(long now) {
        expireOldEntries(now);
        return count < timestamps.length;
    }

    /**
     * Returns the number of events recorded in the current window.
     *
     * @return the current event count
     */
    public synchronized int getCount() {
        expireOldEntries(System.currentTimeMillis());
        return count;
    }

    /**
     * Returns the number of permits remaining in the current window.
     *
     * @return the remaining permits
     */
    public synchronized int getRemaining() {
        expireOldEntries(System.currentTimeMillis());
        return timestamps.length - count;
    }

    /**
     * Returns the time in milliseconds until the next permit will be available.
     *
     * @return milliseconds until a permit is available, or 0 if one is available now
     */
    public synchronized long getTimeUntilAvailable() {
        long now = System.currentTimeMillis();
        expireOldEntries(now);

        if (count < timestamps.length) {
            return 0; // Permit available now
        }

        // Find the oldest timestamp
        int oldestIdx = (index - count + timestamps.length) % timestamps.length;
        long oldestTime = timestamps[oldestIdx];
        long expiryTime = oldestTime + windowMs;

        return Math.max(0, expiryTime - now);
    }

    /**
     * Resets this rate limiter, clearing all recorded events.
     */
    public synchronized void reset() {
        index = 0;
        count = 0;
    }

    /**
     * Expires entries older than the window.
     */
    private void expireOldEntries(long now) {
        long cutoff = now - windowMs;
        while (count > 0) {
            int oldestIdx = (index - count + timestamps.length) % timestamps.length;
            if (timestamps[oldestIdx] < cutoff) {
                count--;
            } else {
                break;
            }
        }
    }

    /**
     * Returns the maximum events allowed per window.
     *
     * @return the maximum events
     */
    public int getMaxEvents() {
        return timestamps.length;
    }

    /**
     * Returns the window duration in milliseconds.
     *
     * @return the window duration
     */
    public long getWindowMs() {
        return windowMs;
    }
}

