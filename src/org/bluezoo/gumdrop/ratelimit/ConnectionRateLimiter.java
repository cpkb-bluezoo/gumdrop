/*
 * ConnectionRateLimiter.java
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * IP-based connection rate limiter for server connections.
 *
 * <p>This class provides two types of rate limiting:
 * <ul>
 * <li><b>Concurrent connection limiting:</b> Maximum simultaneous connections per IP</li>
 * <li><b>Connection rate limiting:</b> Maximum connection attempts per IP per time window</li>
 * </ul>
 *
 * <p>Thread-safe: designed for concurrent access from multiple threads.
 *
 * <h4>Example Usage</h4>
 * <pre>
 * ConnectionRateLimiter limiter = new ConnectionRateLimiter();
 * limiter.setMaxConcurrentPerIP(10);
 * limiter.setConnectionRate(100, 60000); // 100 connections per minute
 * 
 * // In acceptConnection():
 * if (limiter.allowConnection(clientIP)) {
 *     limiter.connectionOpened(clientIP);
 *     // Accept connection
 * } else {
 *     // Reject connection
 * }
 * 
 * // When connection closes:
 * limiter.connectionClosed(clientIP);
 * </pre>
 *
 * <h4>Configuration</h4>
 * <pre>
 * &lt;property name="maxConnectionsPerIP"&gt;10&lt;/property&gt;
 * &lt;property name="rateLimit"&gt;100/60s&lt;/property&gt;
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see RateLimiter
 * @see AuthenticationRateLimiter
 */
public class ConnectionRateLimiter {

    private static final Logger LOGGER = Logger.getLogger(ConnectionRateLimiter.class.getName());
    private static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.ratelimit.L10N");

    /** Default maximum concurrent connections per IP */
    public static final int DEFAULT_MAX_CONCURRENT = 10;

    /** Default maximum connections per window */
    public static final int DEFAULT_MAX_PER_WINDOW = 100;

    /** Default window size in milliseconds (1 minute) */
    public static final long DEFAULT_WINDOW_MS = 60000;

    /** Cleanup interval for expired entries (5 minutes) */
    private static final long CLEANUP_INTERVAL_MS = 300000;

    // Configuration
    private int maxConcurrentPerIP = DEFAULT_MAX_CONCURRENT;
    private int maxConnectionsPerWindow = DEFAULT_MAX_PER_WINDOW;
    private long windowMs = DEFAULT_WINDOW_MS;

    // State tracking
    private final ConcurrentMap<InetAddress, AtomicInteger> activeConnections;
    private final ConcurrentMap<InetAddress, RateLimiter> connectionRates;

    // Background cleanup
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> cleanupTask;

    /**
     * Creates a new connection rate limiter with default settings.
     */
    public ConnectionRateLimiter() {
        this.activeConnections = new ConcurrentHashMap<InetAddress, AtomicInteger>();
        this.connectionRates = new ConcurrentHashMap<InetAddress, RateLimiter>();
    }

    /**
     * Sets the maximum concurrent connections allowed per IP address.
     *
     * @param max the maximum concurrent connections (0 to disable limit)
     */
    public void setMaxConcurrentPerIP(int max) {
        this.maxConcurrentPerIP = max;
    }

    /**
     * Returns the maximum concurrent connections allowed per IP address.
     *
     * @return the maximum concurrent connections
     */
    public int getMaxConcurrentPerIP() {
        return maxConcurrentPerIP;
    }

    /**
     * Sets the connection rate limit.
     *
     * @param maxConnections the maximum connections per window
     * @param windowMs the window duration in milliseconds
     */
    public void setConnectionRate(int maxConnections, long windowMs) {
        this.maxConnectionsPerWindow = maxConnections;
        this.windowMs = windowMs;
        // Clear existing limiters to apply new settings
        connectionRates.clear();
    }

    /**
     * Sets the connection rate limit from a string specification.
     *
     * <p>Format: {@code count/duration}, where duration supports suffixes:
     * <ul>
     * <li>{@code s} - seconds</li>
     * <li>{@code m} - minutes</li>
     * <li>{@code h} - hours</li>
     * </ul>
     *
     * <p>Examples: {@code 100/60s}, {@code 1000/1h}, {@code 50/30s}
     *
     * @param rateLimit the rate limit string
     * @throws IllegalArgumentException if the format is invalid
     */
    public void setRateLimit(String rateLimit) {
        if (rateLimit == null || rateLimit.isEmpty()) {
            return;
        }

        int slashIndex = rateLimit.indexOf('/');
        if (slashIndex == -1) {
            throw new IllegalArgumentException(
                MessageFormat.format(L10N.getString("ratelimit.err.invalid_format"), rateLimit));
        }

        try {
            int count = Integer.parseInt(rateLimit.substring(0, slashIndex).trim());
            String durationStr = rateLimit.substring(slashIndex + 1).trim().toLowerCase();
            long durationMs = parseDuration(durationStr);
            setConnectionRate(count, durationMs);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                MessageFormat.format(L10N.getString("ratelimit.err.invalid_format"), rateLimit));
        }
    }

    /**
     * Parses a duration string with time unit suffix.
     */
    private long parseDuration(String duration) {
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

        return Long.parseLong(numPart.trim()) * multiplier;
    }

    /**
     * Returns the maximum connections per window.
     *
     * @return the maximum connections
     */
    public int getMaxConnectionsPerWindow() {
        return maxConnectionsPerWindow;
    }

    /**
     * Returns the rate limit window in milliseconds.
     *
     * @return the window duration
     */
    public long getWindowMs() {
        return windowMs;
    }

    /**
     * Checks whether a connection from the specified IP address should be allowed.
     *
     * <p>This method checks both concurrent connection limits and rate limits.
     * It does not modify state; call {@link #connectionOpened(InetAddress)} after
     * accepting the connection.
     *
     * @param ip the client IP address
     * @return {@code true} if the connection should be allowed
     */
    public boolean allowConnection(InetAddress ip) {
        if (ip == null) {
            return true;
        }

        // Check concurrent connection limit
        if (maxConcurrentPerIP > 0) {
            AtomicInteger active = activeConnections.get(ip);
            if (active != null && active.get() >= maxConcurrentPerIP) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(MessageFormat.format(L10N.getString("ratelimit.concurrent_exceeded"),
                        ip.getHostAddress(), active.get(), maxConcurrentPerIP));
                }
                return false;
            }
        }

        // Check rate limit
        if (maxConnectionsPerWindow > 0) {
            RateLimiter limiter = getOrCreateLimiter(ip);
            if (!limiter.canAcquire()) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(MessageFormat.format(L10N.getString("ratelimit.rate_exceeded"),
                        ip.getHostAddress(), maxConnectionsPerWindow, windowMs / 1000));
                }
                return false;
            }
        }

        return true;
    }

    /**
     * Records that a connection was opened from the specified IP address.
     *
     * <p>Call this after accepting a connection to update tracking state.
     *
     * @param ip the client IP address
     */
    public void connectionOpened(InetAddress ip) {
        if (ip == null) {
            return;
        }

        // Increment active connection count
        AtomicInteger active = activeConnections.get(ip);
        if (active == null) {
            active = new AtomicInteger(0);
            AtomicInteger existing = activeConnections.putIfAbsent(ip, active);
            if (existing != null) {
                active = existing;
            }
        }
        active.incrementAndGet();

        // Record in rate limiter
        if (maxConnectionsPerWindow > 0) {
            RateLimiter limiter = getOrCreateLimiter(ip);
            limiter.tryAcquire();
        }

        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest(MessageFormat.format(L10N.getString("ratelimit.connection_opened"),
                ip.getHostAddress(), active.get()));
        }
    }

    /**
     * Records that a connection was closed from the specified IP address.
     *
     * @param ip the client IP address
     */
    public void connectionClosed(InetAddress ip) {
        if (ip == null) {
            return;
        }

        AtomicInteger active = activeConnections.get(ip);
        if (active != null) {
            int newCount = active.decrementAndGet();
            if (newCount <= 0) {
                activeConnections.remove(ip, active);
            }

            if (LOGGER.isLoggable(Level.FINEST)) {
                LOGGER.finest(MessageFormat.format(L10N.getString("ratelimit.connection_closed"),
                    ip.getHostAddress(), Math.max(0, newCount)));
            }
        }
    }

    /**
     * Returns the number of active connections from the specified IP address.
     *
     * @param ip the IP address
     * @return the active connection count
     */
    public int getActiveConnections(InetAddress ip) {
        AtomicInteger active = activeConnections.get(ip);
        return active != null ? active.get() : 0;
    }

    /**
     * Returns the remaining connection allowance for the specified IP address.
     *
     * @param ip the IP address
     * @return the remaining connections in the current window
     */
    public int getRemainingConnections(InetAddress ip) {
        RateLimiter limiter = connectionRates.get(ip);
        return limiter != null ? limiter.getRemaining() : maxConnectionsPerWindow;
    }

    /**
     * Returns the time in milliseconds until more connections will be available.
     *
     * @param ip the IP address
     * @return milliseconds until a connection is available, or 0 if available now
     */
    public long getTimeUntilAvailable(InetAddress ip) {
        RateLimiter limiter = connectionRates.get(ip);
        return limiter != null ? limiter.getTimeUntilAvailable() : 0;
    }

    /**
     * Gets or creates a rate limiter for the specified IP address.
     */
    private RateLimiter getOrCreateLimiter(InetAddress ip) {
        RateLimiter limiter = connectionRates.get(ip);
        if (limiter == null) {
            limiter = new RateLimiter(maxConnectionsPerWindow, windowMs);
            RateLimiter existing = connectionRates.putIfAbsent(ip, limiter);
            if (existing != null) {
                limiter = existing;
            }
        }
        return limiter;
    }

    /**
     * Sets the scheduler for background cleanup tasks.
     *
     * <p>When a scheduler is provided, expired rate limiter entries will be
     * periodically cleaned up to prevent memory leaks from clients that
     * connected once and never returned.
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
     * Removes expired rate limiters that have no recent activity.
     */
    public void cleanup() {
        long now = System.currentTimeMillis();
        int removed = 0;

        Iterator<Map.Entry<InetAddress, RateLimiter>> it = connectionRates.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<InetAddress, RateLimiter> entry = it.next();
            RateLimiter limiter = entry.getValue();

            // If the limiter has no events in the window, remove it
            if (limiter.getCount() == 0) {
                it.remove();
                removed++;
            }
        }

        // Also clean up active connections with zero count (shouldn't happen, but be safe)
        Iterator<Map.Entry<InetAddress, AtomicInteger>> activeIt = activeConnections.entrySet().iterator();
        while (activeIt.hasNext()) {
            Map.Entry<InetAddress, AtomicInteger> entry = activeIt.next();
            if (entry.getValue().get() <= 0) {
                activeIt.remove();
            }
        }

        if (removed > 0 && LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(L10N.getString("ratelimit.cleanup_complete"), removed));
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
        activeConnections.clear();
        connectionRates.clear();
    }
}

