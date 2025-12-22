/*
 * ClientConnectionPool.java
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

package org.bluezoo.gumdrop;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A connection pool for Client instances with SelectorLoop affinity.
 *
 * <p>This pool maintains idle connections keyed by target (host:port:secure)
 * and SelectorLoop. When a connection is requested, the pool preferentially
 * returns an existing idle connection, ensuring I/O operations remain efficient.
 *
 * <p><strong>Key Features:</strong>
 * <ul>
 * <li><strong>SelectorLoop Affinity:</strong> Connections are bound to their
 *     assigned SelectorLoop, ensuring all I/O for a connection occurs on the
 *     same thread.</li>
 * <li><strong>Connection Reuse:</strong> Idle connections are pooled for reuse,
 *     avoiding the overhead of establishing new TCP connections and TLS
 *     handshakes.</li>
 * <li><strong>Idle Timeout:</strong> Connections that are idle for too long
 *     are automatically closed and removed from the pool.</li>
 * <li><strong>Maximum Connections:</strong> Configurable limit on the number
 *     of connections to each target.</li>
 * </ul>
 *
 * <p><strong>Usage Pattern:</strong>
 * <pre>
 * // Create pool with custom settings
 * ClientConnectionPool pool = new ClientConnectionPool();
 * pool.setMaxConnectionsPerTarget(10);
 * pool.setIdleTimeoutMs(60000);
 *
 * // Use with HTTPClientConnectionPool for HTTP-specific pooling
 * HTTPClientConnectionPool httpPool = new HTTPClientConnectionPool(pool);
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Client
 * @see SelectorLoop
 */
public class ClientConnectionPool {

    private static final Logger LOGGER = Logger.getLogger(ClientConnectionPool.class.getName());

    /** Default maximum connections per target. */
    public static final int DEFAULT_MAX_CONNECTIONS_PER_TARGET = 8;

    /** Default idle timeout in milliseconds (5 minutes). */
    public static final long DEFAULT_IDLE_TIMEOUT_MS = 5 * 60 * 1000;

    /** Minimum idle timeout (10 seconds). */
    public static final long MIN_IDLE_TIMEOUT_MS = 10 * 1000;

    // Pool structure: Target -> PooledConnections
    private final Map<PoolTarget, ConnectionList> pool;

    // Configuration
    private int maxConnectionsPerTarget = DEFAULT_MAX_CONNECTIONS_PER_TARGET;
    private long idleTimeoutMs = DEFAULT_IDLE_TIMEOUT_MS;

    // Cleanup executor
    private final ScheduledExecutorService cleanupExecutor;
    private ScheduledFuture<?> cleanupFuture;

    /**
     * Creates a new connection pool with default settings.
     */
    public ClientConnectionPool() {
        this.pool = new ConcurrentHashMap<>();
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory("ConnectionPool-Cleanup"));
    }

    /**
     * Returns the maximum number of connections per target.
     *
     * @return the maximum connections per target
     */
    public int getMaxConnectionsPerTarget() {
        return maxConnectionsPerTarget;
    }

    /**
     * Sets the maximum number of connections per target.
     *
     * @param max the maximum connections per target (must be at least 1)
     * @throws IllegalArgumentException if max is less than 1
     */
    public void setMaxConnectionsPerTarget(int max) {
        if (max < 1) {
            throw new IllegalArgumentException("maxConnectionsPerTarget must be at least 1");
        }
        this.maxConnectionsPerTarget = max;
    }

    /**
     * Returns the idle timeout in milliseconds.
     *
     * @return the idle timeout in milliseconds
     */
    public long getIdleTimeoutMs() {
        return idleTimeoutMs;
    }

    /**
     * Sets the idle timeout in milliseconds.
     *
     * <p>Connections that have been idle for longer than this will be
     * closed and removed from the pool.
     *
     * @param timeout the idle timeout in milliseconds (minimum 10 seconds)
     * @throws IllegalArgumentException if timeout is less than minimum
     */
    public void setIdleTimeoutMs(long timeout) {
        if (timeout < MIN_IDLE_TIMEOUT_MS) {
            throw new IllegalArgumentException("idleTimeoutMs must be at least " + MIN_IDLE_TIMEOUT_MS);
        }
        this.idleTimeoutMs = timeout;
    }

    /**
     * Tries to acquire an idle connection for the given target.
     *
     * @param target the connection target
     * @return an idle pool entry, or null if none available
     */
    public PoolEntry tryAcquire(PoolTarget target) {
        ConnectionList connList = pool.get(target);
        if (connList == null) {
            return null;
        }

        PoolEntry entry = connList.pollIdle();
        if (entry == null) {
            return null;
        }

        // Validate the connection
        if (entry.connection == null || !entry.connection.isOpen()) {
            connList.remove(entry);
            return tryAcquire(target); // Try again
        }

        // Check if expired
        if (System.currentTimeMillis() - entry.lastUsed > idleTimeoutMs) {
            entry.connection.close();
            connList.remove(entry);
            return tryAcquire(target); // Try again
        }

        entry.markBusy();
        LOGGER.fine("Acquired pooled connection to " + target);
        return entry;
    }

    /**
     * Checks if the pool can accept more connections for the given target.
     *
     * @param target the connection target
     * @return true if more connections can be created
     */
    public boolean canCreateConnection(PoolTarget target) {
        ConnectionList connList = pool.get(target);
        return connList == null || connList.totalCount() < maxConnectionsPerTarget;
    }

    /**
     * Registers a new connection with the pool.
     *
     * @param target the connection target
     * @param connection the connection to register
     * @return the pool entry for this connection
     */
    public PoolEntry register(PoolTarget target, Connection connection) {
        PoolEntry entry = new PoolEntry(connection, target);
        ConnectionList connList = pool.get(target);
        if (connList == null) {
            connList = new ConnectionList();
            ConnectionList existing = pool.putIfAbsent(target, connList);
            if (existing != null) {
                connList = existing;
            }
        }
        connList.add(entry);

        scheduleCleanupIfNeeded();

        LOGGER.fine("Registered connection to pool: " + target + " (total: " + connList.totalCount() + ")");
        return entry;
    }

    /**
     * Releases a connection back to the pool for reuse.
     *
     * <p>The connection will be made available for reuse if:
     * <ul>
     * <li>The connection is still open</li>
     * <li>The target has not exceeded its maximum connections</li>
     * </ul>
     *
     * @param entry the pool entry to release
     */
    public void release(PoolEntry entry) {
        if (entry == null) {
            return;
        }

        Connection connection = entry.connection;
        if (connection == null || !connection.isOpen()) {
            remove(entry);
            return;
        }

        ConnectionList connList = pool.get(entry.target);
        if (connList == null || connList.totalCount() > maxConnectionsPerTarget) {
            remove(entry);
            connection.close();
            return;
        }

        entry.markIdle();
        LOGGER.fine("Released connection to pool: " + entry.target);
    }

    /**
     * Removes and closes a connection from the pool.
     *
     * <p>Use this method when a connection cannot be reused (e.g., after
     * an error or when the server closed the connection).
     *
     * @param entry the pool entry to remove
     */
    public void remove(PoolEntry entry) {
        if (entry == null) {
            return;
        }

        ConnectionList connList = pool.get(entry.target);
        if (connList != null) {
            connList.remove(entry);
        }

        if (entry.connection != null && entry.connection.isOpen()) {
            entry.connection.close();
        }
    }

    /**
     * Closes all connections and clears the pool.
     */
    public void shutdown() {
        // Cancel cleanup timer
        if (cleanupFuture != null) {
            cleanupFuture.cancel(false);
            cleanupFuture = null;
        }
        cleanupExecutor.shutdown();

        // Close all connections
        for (ConnectionList connList : pool.values()) {
            for (PoolEntry entry : connList.all()) {
                if (entry.connection != null && entry.connection.isOpen()) {
                    entry.connection.close();
                }
            }
        }
        pool.clear();

        LOGGER.fine("Connection pool shutdown complete");
    }

    /**
     * Returns the total number of connections in the pool (idle + busy).
     *
     * @return the total connection count
     */
    public int getTotalConnectionCount() {
        int count = 0;
        for (ConnectionList connList : pool.values()) {
            count += connList.totalCount();
        }
        return count;
    }

    /**
     * Returns the number of idle connections in the pool.
     *
     * @return the idle connection count
     */
    public int getIdleConnectionCount() {
        int count = 0;
        for (ConnectionList connList : pool.values()) {
            count += connList.idleCount();
        }
        return count;
    }

    /**
     * Schedules cleanup task if not already scheduled.
     */
    private synchronized void scheduleCleanupIfNeeded() {
        if (cleanupFuture == null || cleanupFuture.isDone()) {
            cleanupFuture = cleanupExecutor.schedule(
                    new CleanupTask(),
                    idleTimeoutMs / 2,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    /**
     * Removes and closes connections that have been idle too long.
     */
    private void cleanupIdleConnections() {
        long now = System.currentTimeMillis();
        long threshold = now - idleTimeoutMs;
        int closedCount = 0;

        for (ConnectionList connList : pool.values()) {
            List<PoolEntry> expired = connList.removeExpired(threshold);
            for (PoolEntry entry : expired) {
                if (entry.connection != null && entry.connection.isOpen()) {
                    entry.connection.close();
                    closedCount++;
                }
            }
        }

        if (closedCount > 0) {
            LOGGER.fine("Closed " + closedCount + " expired idle connections");
        }

        // Reschedule if there are still idle connections
        if (getIdleConnectionCount() > 0) {
            scheduleCleanupIfNeeded();
        }
    }

    // -- Inner classes --

    /**
     * Cleanup task for removing expired connections.
     */
    private class CleanupTask implements Runnable {
        @Override
        public void run() {
            try {
                cleanupIdleConnections();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in connection pool cleanup", e);
            }
        }
    }

    /**
     * Daemon thread factory for cleanup executor.
     */
    private static class DaemonThreadFactory implements java.util.concurrent.ThreadFactory {
        private final String name;

        DaemonThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        }
    }

    /**
     * Identifies a connection target (host, port, secure).
     */
    public static class PoolTarget {
        private final InetAddress host;
        private final int port;
        private final boolean secure;

        /**
         * Creates a new pool target.
         *
         * @param host the target host
         * @param port the target port
         * @param secure whether the connection uses TLS
         */
        public PoolTarget(InetAddress host, int port, boolean secure) {
            this.host = host;
            this.port = port;
            this.secure = secure;
        }

        /**
         * Returns the target host.
         *
         * @return the host address
         */
        public InetAddress getHost() {
            return host;
        }

        /**
         * Returns the target port.
         *
         * @return the port number
         */
        public int getPort() {
            return port;
        }

        /**
         * Returns whether this target uses TLS.
         *
         * @return true if TLS is used
         */
        public boolean isSecure() {
            return secure;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            PoolTarget that = (PoolTarget) o;
            return port == that.port && secure == that.secure && Objects.equals(host, that.host);
        }

        @Override
        public int hashCode() {
            return Objects.hash(host, port, secure);
        }

        @Override
        public String toString() {
            return (secure ? "https://" : "http://") + host.getHostAddress() + ":" + port;
        }
    }

    /**
     * An entry in the connection pool.
     *
     * <p>Pool entries track the connection state (busy/idle) and last usage time.
     * Handlers receive pool entries to release connections back to the pool.
     */
    public static class PoolEntry {
        final Connection connection;
        final PoolTarget target;
        volatile long lastUsed;
        volatile boolean busy;

        PoolEntry(Connection connection, PoolTarget target) {
            this.connection = connection;
            this.target = target;
            this.lastUsed = System.currentTimeMillis();
            this.busy = true;
        }

        void markBusy() {
            busy = true;
            lastUsed = System.currentTimeMillis();
        }

        void markIdle() {
            busy = false;
            lastUsed = System.currentTimeMillis();
        }

        /**
         * Returns the pooled connection.
         *
         * @return the connection
         */
        public Connection getConnection() {
            return connection;
        }

        /**
         * Returns the target for this connection.
         *
         * @return the pool target
         */
        public PoolTarget getTarget() {
            return target;
        }

        /**
         * Returns whether this connection is currently busy.
         *
         * @return true if busy, false if idle
         */
        public boolean isBusy() {
            return busy;
        }
    }

    /**
     * Thread-safe list of pooled connections for a target.
     */
    private static class ConnectionList {
        private final Deque<PoolEntry> entries = new ConcurrentLinkedDeque<>();
        private final AtomicInteger totalCount = new AtomicInteger(0);
        private final AtomicInteger idleCount = new AtomicInteger(0);

        void add(PoolEntry entry) {
            entries.add(entry);
            totalCount.incrementAndGet();
            if (!entry.busy) {
                idleCount.incrementAndGet();
            }
        }

        void remove(PoolEntry entry) {
            if (entries.remove(entry)) {
                totalCount.decrementAndGet();
                if (!entry.busy) {
                    idleCount.decrementAndGet();
                }
            }
        }

        PoolEntry pollIdle() {
            for (Iterator<PoolEntry> it = entries.iterator(); it.hasNext(); ) {
                PoolEntry entry = it.next();
                if (!entry.busy) {
                    idleCount.decrementAndGet();
                    return entry;
                }
            }
            return null;
        }

        List<PoolEntry> removeExpired(long threshold) {
            List<PoolEntry> expired = new ArrayList<>();
            for (Iterator<PoolEntry> it = entries.iterator(); it.hasNext(); ) {
                PoolEntry entry = it.next();
                if (!entry.busy && entry.lastUsed < threshold) {
                    it.remove();
                    totalCount.decrementAndGet();
                    idleCount.decrementAndGet();
                    expired.add(entry);
                }
            }
            return expired;
        }

        List<PoolEntry> all() {
            return new ArrayList<>(entries);
        }

        int totalCount() {
            return totalCount.get();
        }

        int idleCount() {
            return idleCount.get();
        }
    }
}
