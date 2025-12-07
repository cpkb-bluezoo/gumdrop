/*
 * AuthenticationRateLimiter.java
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

import java.net.InetAddress;
import java.text.MessageFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Rate limiter for failed authentication attempts to prevent brute-force attacks.
 *
 * <p>This class tracks failed authentication attempts and temporarily locks out
 * clients that exceed the failure threshold. The lockout duration increases
 * with repeated failures (exponential backoff).
 *
 * <p>Supports tracking by:
 * <ul>
 * <li>IP address - protects against distributed attacks from a single source</li>
 * <li>Username - protects against credential stuffing attacks</li>
 * <li>Combined IP + username - most precise tracking</li>
 * </ul>
 *
 * <h4>Example Usage</h4>
 * <pre>
 * AuthenticationRateLimiter limiter = new AuthenticationRateLimiter();
 * limiter.setMaxFailures(5);
 * limiter.setLockoutDuration(300000); // 5 minutes
 * 
 * String key = clientIP.getHostAddress();
 * 
 * if (limiter.isLocked(key)) {
 *     // Reject immediately - account is locked
 *     return AuthResult.LOCKED;
 * }
 * 
 * if (authenticate(username, password)) {
 *     limiter.recordSuccess(key);
 *     return AuthResult.SUCCESS;
 * } else {
 *     limiter.recordFailure(key);
 *     return AuthResult.FAILURE;
 * }
 * </pre>
 *
 * <h4>Configuration</h4>
 * <pre>
 * &lt;property name="maxAuthFailures"&gt;5&lt;/property&gt;
 * &lt;property name="authLockoutTime"&gt;5m&lt;/property&gt;
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ConnectionRateLimiter
 */
public class AuthenticationRateLimiter {

    private static final Logger LOGGER = Logger.getLogger(AuthenticationRateLimiter.class.getName());
    private static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.ratelimit.L10N");

    /** Default maximum failed attempts before lockout */
    public static final int DEFAULT_MAX_FAILURES = 5;

    /** Default lockout duration in milliseconds (5 minutes) */
    public static final long DEFAULT_LOCKOUT_MS = 300000;

    /** Default maximum lockout duration (1 hour) */
    public static final long DEFAULT_MAX_LOCKOUT_MS = 3600000;

    /** Cleanup interval for expired entries (10 minutes) */
    private static final long CLEANUP_INTERVAL_MS = 600000;

    // Configuration
    private int maxFailures = DEFAULT_MAX_FAILURES;
    private long lockoutMs = DEFAULT_LOCKOUT_MS;
    private long maxLockoutMs = DEFAULT_MAX_LOCKOUT_MS;
    private boolean exponentialBackoff = true;

    // State tracking
    private final ConcurrentMap<String, FailureTracker> trackers;

    // Background cleanup
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> cleanupTask;

    /**
     * Tracks authentication failures for a single key (IP or username).
     */
    static class FailureTracker {
        private int failureCount;
        private long lastFailureTime;
        private long lockoutUntil;
        private int lockoutCount; // For exponential backoff

        synchronized void recordFailure(long now, int maxFailures, long lockoutMs, 
                                        long maxLockoutMs, boolean exponentialBackoff) {
            failureCount++;
            lastFailureTime = now;

            if (failureCount >= maxFailures) {
                lockoutCount++;
                // Exponential backoff: 1x, 2x, 4x, 8x... up to maxLockoutMs
                long duration = lockoutMs;
                if (exponentialBackoff && lockoutCount > 1) {
                    duration = Math.min(lockoutMs * (1L << (lockoutCount - 1)), maxLockoutMs);
                }
                lockoutUntil = now + duration;
            }
        }

        synchronized void recordSuccess() {
            failureCount = 0;
            lockoutUntil = 0;
            // Don't reset lockoutCount - maintain history for repeat offenders
        }

        synchronized boolean isLocked(long now) {
            if (lockoutUntil > 0 && now < lockoutUntil) {
                return true;
            }
            // Lockout expired
            if (lockoutUntil > 0 && now >= lockoutUntil) {
                // Reset failure count after lockout expires
                failureCount = 0;
                lockoutUntil = 0;
            }
            return false;
        }

        synchronized long getLockoutRemaining(long now) {
            if (lockoutUntil > now) {
                return lockoutUntil - now;
            }
            return 0;
        }

        synchronized int getFailureCount() {
            return failureCount;
        }

        synchronized long getLastFailureTime() {
            return lastFailureTime;
        }

        synchronized boolean isExpired(long now, long expiryPeriod) {
            return !isLocked(now) && (now - lastFailureTime) > expiryPeriod;
        }
    }

    /**
     * Creates a new authentication rate limiter with default settings.
     */
    public AuthenticationRateLimiter() {
        this.trackers = new ConcurrentHashMap<String, FailureTracker>();
    }

    /**
     * Sets the maximum failed authentication attempts before lockout.
     *
     * @param max the maximum failures (must be at least 1)
     */
    public void setMaxFailures(int max) {
        if (max < 1) {
            throw new IllegalArgumentException("maxFailures must be at least 1");
        }
        this.maxFailures = max;
    }

    /**
     * Returns the maximum failed attempts before lockout.
     *
     * @return the maximum failures
     */
    public int getMaxFailures() {
        return maxFailures;
    }

    /**
     * Sets the lockout duration in milliseconds.
     *
     * @param lockoutMs the lockout duration
     */
    public void setLockoutDuration(long lockoutMs) {
        this.lockoutMs = lockoutMs;
    }

    /**
     * Sets the lockout duration from a string specification.
     *
     * <p>Supports suffixes: {@code s} (seconds), {@code m} (minutes), {@code h} (hours)
     *
     * @param duration the duration string (e.g., "5m", "300s", "1h")
     */
    public void setLockoutTime(String duration) {
        this.lockoutMs = parseDuration(duration);
    }

    /**
     * Returns the lockout duration in milliseconds.
     *
     * @return the lockout duration
     */
    public long getLockoutDuration() {
        return lockoutMs;
    }

    /**
     * Sets the maximum lockout duration for exponential backoff.
     *
     * @param maxLockoutMs the maximum lockout duration
     */
    public void setMaxLockoutDuration(long maxLockoutMs) {
        this.maxLockoutMs = maxLockoutMs;
    }

    /**
     * Sets whether to use exponential backoff for repeated lockouts.
     *
     * @param exponentialBackoff true to enable exponential backoff
     */
    public void setExponentialBackoff(boolean exponentialBackoff) {
        this.exponentialBackoff = exponentialBackoff;
    }

    /**
     * Parses a duration string with time unit suffix.
     */
    private long parseDuration(String duration) {
        if (duration == null || duration.isEmpty()) {
            return lockoutMs;
        }

        duration = duration.trim().toLowerCase();
        long multiplier = 1;
        String numPart = duration;

        if (duration.endsWith("ms")) {
            numPart = duration.substring(0, duration.length() - 2);
            multiplier = 1;
        } else if (duration.endsWith("s")) {
            numPart = duration.substring(0, duration.length() - 1);
            multiplier = 1000;
        } else if (duration.endsWith("m")) {
            numPart = duration.substring(0, duration.length() - 1);
            multiplier = 60 * 1000;
        } else if (duration.endsWith("h")) {
            numPart = duration.substring(0, duration.length() - 1);
            multiplier = 60 * 60 * 1000;
        }

        try {
            return Long.parseLong(numPart.trim()) * multiplier;
        } catch (NumberFormatException e) {
            LOGGER.warning(MessageFormat.format(L10N.getString("ratelimit.err.invalid_duration"), duration));
            return lockoutMs;
        }
    }

    /**
     * Checks if the specified key is currently locked out.
     *
     * @param key the tracking key (IP address, username, or combination)
     * @return {@code true} if the key is locked out
     */
    public boolean isLocked(String key) {
        if (key == null) {
            return false;
        }

        FailureTracker tracker = trackers.get(key);
        return tracker != null && tracker.isLocked(System.currentTimeMillis());
    }

    /**
     * Checks if the specified IP address is currently locked out.
     *
     * @param ip the IP address
     * @return {@code true} if the IP is locked out
     */
    public boolean isLocked(InetAddress ip) {
        return isLocked(ip != null ? ip.getHostAddress() : null);
    }

    /**
     * Records a failed authentication attempt.
     *
     * @param key the tracking key (IP address, username, or combination)
     */
    public void recordFailure(String key) {
        if (key == null) {
            return;
        }

        FailureTracker tracker = getOrCreateTracker(key);
        tracker.recordFailure(System.currentTimeMillis(), maxFailures, lockoutMs, 
                             maxLockoutMs, exponentialBackoff);

        if (LOGGER.isLoggable(Level.FINE)) {
            if (tracker.isLocked(System.currentTimeMillis())) {
                LOGGER.fine(MessageFormat.format(L10N.getString("ratelimit.auth_locked"),
                    key, tracker.getLockoutRemaining(System.currentTimeMillis()) / 1000));
            } else {
                LOGGER.fine(MessageFormat.format(L10N.getString("ratelimit.auth_failure"),
                    key, tracker.getFailureCount(), maxFailures));
            }
        }
    }

    /**
     * Records a failed authentication attempt for an IP address.
     *
     * @param ip the IP address
     */
    public void recordFailure(InetAddress ip) {
        recordFailure(ip != null ? ip.getHostAddress() : null);
    }

    /**
     * Records a failed authentication attempt for an IP + username combination.
     *
     * @param ip the IP address
     * @param username the username
     */
    public void recordFailure(InetAddress ip, String username) {
        // Record for IP alone
        recordFailure(ip);
        // Record for combined key
        if (ip != null && username != null) {
            recordFailure(ip.getHostAddress() + ":" + username);
        }
    }

    /**
     * Records a successful authentication, clearing failure tracking.
     *
     * @param key the tracking key
     */
    public void recordSuccess(String key) {
        if (key == null) {
            return;
        }

        FailureTracker tracker = trackers.get(key);
        if (tracker != null) {
            tracker.recordSuccess();
        }

        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest(MessageFormat.format(L10N.getString("ratelimit.auth_success"), key));
        }
    }

    /**
     * Records a successful authentication for an IP address.
     *
     * @param ip the IP address
     */
    public void recordSuccess(InetAddress ip) {
        recordSuccess(ip != null ? ip.getHostAddress() : null);
    }

    /**
     * Records a successful authentication for an IP + username combination.
     *
     * @param ip the IP address
     * @param username the username
     */
    public void recordSuccess(InetAddress ip, String username) {
        recordSuccess(ip);
        if (ip != null && username != null) {
            recordSuccess(ip.getHostAddress() + ":" + username);
        }
    }

    /**
     * Returns the remaining lockout time in milliseconds.
     *
     * @param key the tracking key
     * @return the remaining lockout time, or 0 if not locked
     */
    public long getLockoutRemaining(String key) {
        if (key == null) {
            return 0;
        }

        FailureTracker tracker = trackers.get(key);
        return tracker != null ? tracker.getLockoutRemaining(System.currentTimeMillis()) : 0;
    }

    /**
     * Returns the current failure count for a key.
     *
     * @param key the tracking key
     * @return the failure count
     */
    public int getFailureCount(String key) {
        if (key == null) {
            return 0;
        }

        FailureTracker tracker = trackers.get(key);
        return tracker != null ? tracker.getFailureCount() : 0;
    }

    /**
     * Gets or creates a failure tracker for the specified key.
     */
    private FailureTracker getOrCreateTracker(String key) {
        FailureTracker tracker = trackers.get(key);
        if (tracker == null) {
            tracker = new FailureTracker();
            FailureTracker existing = trackers.putIfAbsent(key, tracker);
            if (existing != null) {
                tracker = existing;
            }
        }
        return tracker;
    }

    /**
     * Sets the scheduler for background cleanup tasks.
     *
     * @param scheduler the scheduler to use
     */
    public void setScheduler(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
        scheduleCleanup();
    }

    /**
     * Schedules the cleanup task.
     */
    private void scheduleCleanup() {
        if (scheduler != null && cleanupTask == null) {
            cleanupTask = scheduler.scheduleAtFixedRate(
                new CleanupTask(),
                CLEANUP_INTERVAL_MS,
                CLEANUP_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            );
        }
    }

    /**
     * Cleans up expired entries.
     */
    private class CleanupTask implements Runnable {
        @Override
        public void run() {
            try {
                cleanup();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, L10N.getString("ratelimit.err.cleanup_failed"), e);
            }
        }
    }

    /**
     * Removes expired trackers that have no recent activity.
     */
    public void cleanup() {
        long now = System.currentTimeMillis();
        // Expire entries after max lockout duration + window
        long expiryPeriod = maxLockoutMs * 2;
        int removed = 0;

        Iterator<Map.Entry<String, FailureTracker>> it = trackers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, FailureTracker> entry = it.next();
            FailureTracker tracker = entry.getValue();

            if (tracker.isExpired(now, expiryPeriod)) {
                it.remove();
                removed++;
            }
        }

        if (removed > 0 && LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(L10N.getString("ratelimit.auth_cleanup_complete"), removed));
        }
    }

    /**
     * Shuts down the rate limiter, cancelling any scheduled cleanup tasks.
     */
    public void shutdown() {
        if (cleanupTask != null) {
            cleanupTask.cancel(false);
            cleanupTask = null;
        }
    }

    /**
     * Resets all rate limiting state.
     */
    public void reset() {
        trackers.clear();
    }

    /**
     * Clears the lockout for a specific key (administrative unlock).
     *
     * @param key the tracking key to unlock
     */
    public void unlock(String key) {
        if (key != null) {
            trackers.remove(key);
            LOGGER.info(MessageFormat.format(L10N.getString("ratelimit.auth_unlocked"), key));
        }
    }
}

