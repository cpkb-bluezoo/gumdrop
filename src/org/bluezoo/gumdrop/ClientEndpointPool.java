/*
 * ClientEndpointPool.java
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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A connection pool for {@link Endpoint} objects.
 *
 * <p>Maintains idle endpoints keyed by target (host, port, secure)
 * and supports automatic idle-timeout cleanup.
 *
 * <p>Because {@link Endpoint} is transport-agnostic, this pool works
 * equally well with {@link TCPEndpoint}s and
 * {@link org.bluezoo.gumdrop.quic.QuicStreamEndpoint}s.
 *
 * <h4>Usage</h4>
 * <pre>{@code
 * ClientEndpointPool pool = new ClientEndpointPool();
 * pool.setMaxEndpointsPerTarget(10);
 * pool.setIdleTimeoutMs(60000);
 *
 * PoolTarget target = new PoolTarget(host, 587, true);
 * PoolEntry entry = pool.tryAcquire(target);
 * if (entry != null) {
 *     // Reuse the existing endpoint
 *     Endpoint ep = entry.getEndpoint();
 * } else {
 *     // Create a new connection via ClientEndpoint or TransportFactory
 * }
 *
 * // When done, release back to pool
 * pool.release(entry);
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Endpoint
 * @see ClientEndpoint
 */
public class ClientEndpointPool {

    private static final Logger LOGGER =
            Logger.getLogger(ClientEndpointPool.class.getName());

    /** Default maximum endpoints per target. */
    public static final int DEFAULT_MAX_ENDPOINTS_PER_TARGET = 8;

    /** Default idle timeout in milliseconds (5 minutes). */
    public static final long DEFAULT_IDLE_TIMEOUT_MS = 5 * 60 * 1000;

    /** Minimum idle timeout (10 seconds). */
    public static final long MIN_IDLE_TIMEOUT_MS = 10 * 1000;

    private final Map<PoolTarget, EndpointList> pool;

    private int maxEndpointsPerTarget = DEFAULT_MAX_ENDPOINTS_PER_TARGET;
    private long idleTimeoutMs = DEFAULT_IDLE_TIMEOUT_MS;

    private final ScheduledExecutorService cleanupExecutor;
    private ScheduledFuture<?> cleanupFuture;

    /**
     * Creates a new endpoint connection pool with default settings.
     */
    public ClientEndpointPool() {
        this.pool = new ConcurrentHashMap<PoolTarget, EndpointList>();
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(
                new DaemonThreadFactory("EndpointPool-Cleanup"));
    }

    // ── Configuration ──

    /**
     * Returns the maximum number of endpoints per target.
     *
     * @return the max endpoints per target
     */
    public int getMaxEndpointsPerTarget() {
        return maxEndpointsPerTarget;
    }

    /**
     * Sets the maximum number of endpoints per target.
     *
     * @param max the maximum (must be at least 1)
     * @throws IllegalArgumentException if max is less than 1
     */
    public void setMaxEndpointsPerTarget(int max) {
        if (max < 1) {
            throw new IllegalArgumentException(
                    "maxEndpointsPerTarget must be at least 1");
        }
        this.maxEndpointsPerTarget = max;
    }

    /**
     * Returns the idle timeout in milliseconds.
     *
     * @return the idle timeout
     */
    public long getIdleTimeoutMs() {
        return idleTimeoutMs;
    }

    /**
     * Sets the idle timeout in milliseconds.
     *
     * <p>Endpoints idle for longer than this are closed and removed.
     *
     * @param timeout the idle timeout (minimum 10 seconds)
     * @throws IllegalArgumentException if timeout is too small
     */
    public void setIdleTimeoutMs(long timeout) {
        if (timeout < MIN_IDLE_TIMEOUT_MS) {
            throw new IllegalArgumentException(
                    "idleTimeoutMs must be at least " + MIN_IDLE_TIMEOUT_MS);
        }
        this.idleTimeoutMs = timeout;
    }

    // ── Pool operations ──

    /**
     * Tries to acquire an idle endpoint for the given target.
     *
     * @param target the connection target
     * @return a pool entry, or null if no idle endpoint is available
     */
    public PoolEntry tryAcquire(PoolTarget target) {
        EndpointList list = pool.get(target);
        if (list == null) {
            return null;
        }

        PoolEntry entry = list.pollIdle();
        if (entry == null) {
            return null;
        }

        if (entry.endpoint == null || !entry.endpoint.isOpen()) {
            list.remove(entry);
            return tryAcquire(target);
        }

        if (System.currentTimeMillis() - entry.lastUsed > idleTimeoutMs) {
            entry.endpoint.close();
            list.remove(entry);
            return tryAcquire(target);
        }

        entry.markBusy();
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Acquired pooled endpoint to " + target);
        }
        return entry;
    }

    /**
     * Returns whether more endpoints can be created for the given target.
     *
     * @param target the connection target
     * @return true if the target has not reached its limit
     */
    public boolean canCreateEndpoint(PoolTarget target) {
        EndpointList list = pool.get(target);
        return list == null
                || list.totalCount() < maxEndpointsPerTarget;
    }

    /**
     * Registers a new endpoint with the pool.
     *
     * @param target the connection target
     * @param endpoint the endpoint to register
     * @return the pool entry
     */
    public PoolEntry register(PoolTarget target, Endpoint endpoint) {
        PoolEntry entry = new PoolEntry(endpoint, target);
        EndpointList list = pool.get(target);
        if (list == null) {
            list = new EndpointList();
            EndpointList existing = pool.putIfAbsent(target, list);
            if (existing != null) {
                list = existing;
            }
        }
        list.add(entry);
        scheduleCleanupIfNeeded();

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Registered endpoint in pool: " + target
                    + " (total: " + list.totalCount() + ")");
        }
        return entry;
    }

    /**
     * Releases an endpoint back to the pool for reuse.
     *
     * <p>If the endpoint is no longer open or the target has reached
     * its limit, the endpoint is closed and removed instead.
     *
     * @param entry the entry to release
     */
    public void release(PoolEntry entry) {
        if (entry == null) {
            return;
        }

        Endpoint endpoint = entry.endpoint;
        if (endpoint == null || !endpoint.isOpen()) {
            remove(entry);
            return;
        }

        EndpointList list = pool.get(entry.target);
        if (list == null
                || list.totalCount() > maxEndpointsPerTarget) {
            remove(entry);
            endpoint.close();
            return;
        }

        entry.markIdle();
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Released endpoint to pool: " + entry.target);
        }
    }

    /**
     * Removes and closes an endpoint from the pool.
     *
     * @param entry the entry to remove
     */
    public void remove(PoolEntry entry) {
        if (entry == null) {
            return;
        }

        EndpointList list = pool.get(entry.target);
        if (list != null) {
            list.remove(entry);
        }

        if (entry.endpoint != null && entry.endpoint.isOpen()) {
            entry.endpoint.close();
        }
    }

    /**
     * Closes all endpoints and clears the pool.
     */
    public void shutdown() {
        if (cleanupFuture != null) {
            cleanupFuture.cancel(false);
            cleanupFuture = null;
        }
        cleanupExecutor.shutdown();

        for (EndpointList list : pool.values()) {
            for (PoolEntry entry : list.all()) {
                if (entry.endpoint != null && entry.endpoint.isOpen()) {
                    entry.endpoint.close();
                }
            }
        }
        pool.clear();

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Endpoint pool shutdown complete");
        }
    }

    // ── Statistics ──

    /**
     * Returns the total number of endpoints in the pool.
     *
     * @return the total count (idle + busy)
     */
    public int getTotalEndpointCount() {
        int count = 0;
        for (EndpointList list : pool.values()) {
            count += list.totalCount();
        }
        return count;
    }

    /**
     * Returns the number of idle endpoints in the pool.
     *
     * @return the idle count
     */
    public int getIdleEndpointCount() {
        int count = 0;
        for (EndpointList list : pool.values()) {
            count += list.idleCount();
        }
        return count;
    }

    // ── Cleanup ──

    private synchronized void scheduleCleanupIfNeeded() {
        if (cleanupFuture == null || cleanupFuture.isDone()) {
            cleanupFuture = cleanupExecutor.schedule(
                    new CleanupTask(),
                    idleTimeoutMs / 2,
                    TimeUnit.MILLISECONDS);
        }
    }

    private void cleanupIdleEndpoints() {
        long now = System.currentTimeMillis();
        long threshold = now - idleTimeoutMs;
        int closedCount = 0;

        for (EndpointList list : pool.values()) {
            List<PoolEntry> expired = list.removeExpired(threshold);
            for (int i = 0; i < expired.size(); i++) {
                PoolEntry entry = expired.get(i);
                if (entry.endpoint != null && entry.endpoint.isOpen()) {
                    entry.endpoint.close();
                    closedCount++;
                }
            }
        }

        if (closedCount > 0 && LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Closed " + closedCount
                    + " expired idle endpoints");
        }

        if (getIdleEndpointCount() > 0) {
            scheduleCleanupIfNeeded();
        }
    }

    // ── Inner classes ──

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
         * @return the host
         */
        public InetAddress getHost() {
            return host;
        }

        /**
         * Returns the target port.
         *
         * @return the port
         */
        public int getPort() {
            return port;
        }

        /**
         * Returns whether this target uses TLS.
         *
         * @return true if secure
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
            return port == that.port
                    && secure == that.secure
                    && Objects.equals(host, that.host);
        }

        @Override
        public int hashCode() {
            return Objects.hash(host, port, secure);
        }

        @Override
        public String toString() {
            String scheme = secure ? "tls://" : "tcp://";
            return scheme + host.getHostAddress() + ":" + port;
        }
    }

    /**
     * An entry in the endpoint pool.
     *
     * <p>Tracks the endpoint, its target, state (busy/idle), and
     * last usage time.
     */
    public static class PoolEntry {

        final Endpoint endpoint;
        final PoolTarget target;
        volatile long lastUsed;
        volatile boolean busy;

        PoolEntry(Endpoint endpoint, PoolTarget target) {
            this.endpoint = endpoint;
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
         * Returns the pooled endpoint.
         *
         * @return the endpoint
         */
        public Endpoint getEndpoint() {
            return endpoint;
        }

        /**
         * Returns the target for this entry.
         *
         * @return the pool target
         */
        public PoolTarget getTarget() {
            return target;
        }

        /**
         * Returns whether this entry is currently busy.
         *
         * @return true if busy
         */
        public boolean isBusy() {
            return busy;
        }
    }

    /**
     * Thread-safe list of pooled endpoints for a target.
     */
    private static class EndpointList {

        private final Deque<PoolEntry> entries =
                new ConcurrentLinkedDeque<PoolEntry>();
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
            Iterator<PoolEntry> it = entries.iterator();
            while (it.hasNext()) {
                PoolEntry entry = it.next();
                if (!entry.busy) {
                    idleCount.decrementAndGet();
                    return entry;
                }
            }
            return null;
        }

        List<PoolEntry> removeExpired(long threshold) {
            List<PoolEntry> expired = new ArrayList<PoolEntry>();
            Iterator<PoolEntry> it = entries.iterator();
            while (it.hasNext()) {
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
            return new ArrayList<PoolEntry>(entries);
        }

        int totalCount() {
            return totalCount.get();
        }

        int idleCount() {
            return idleCount.get();
        }
    }

    /**
     * Cleanup task for removing expired endpoints.
     */
    private class CleanupTask implements Runnable {
        @Override
        public void run() {
            try {
                cleanupIdleEndpoints();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        "Error in endpoint pool cleanup", e);
            }
        }
    }

    /**
     * Daemon thread factory for cleanup executor.
     */
    private static class DaemonThreadFactory implements ThreadFactory {

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
}
