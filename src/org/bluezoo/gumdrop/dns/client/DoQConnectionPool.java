/*
 * DoQConnectionPool.java
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

package org.bluezoo.gumdrop.dns.client;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;

/**
 * Connection pool for DNS-over-QUIC (DoQ) transports.
 * RFC 9250 section 5.5.1: clients SHOULD reuse existing QUIC connections
 * rather than opening new ones for every query batch. QUIC natively
 * multiplexes streams, so each query uses a new stream on a shared
 * connection.
 *
 * <p>The pool maintains at most one {@link DoQClientTransport} per
 * upstream server (identified by address and port). When a transport
 * for a server is requested, an existing connection is returned if
 * still open; otherwise a new one is created.
 *
 * <p>This class implements {@link DNSClientTransport} so it can be used
 * as a drop-in replacement for {@code DoQClientTransport} in
 * {@link DNSResolver}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DoQClientTransport
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9250#section-5.5.1">
 *      RFC 9250 section 5.5.1</a>
 */
public class DoQConnectionPool implements DNSClientTransport {

    private static final Logger LOGGER =
            Logger.getLogger(DoQConnectionPool.class.getName());

    private static final ConcurrentHashMap<String, PoolEntry> pool =
            new ConcurrentHashMap<>();

    private static long maxIdleTimeMs = 30_000;

    private PoolEntry entry;

    /**
     * Sets the maximum idle time for pooled connections.
     * Connections idle beyond this threshold are closed on next access.
     *
     * @param millis idle timeout in milliseconds
     */
    public static void setMaxIdleTimeMs(long millis) {
        maxIdleTimeMs = millis;
    }

    /**
     * Returns the maximum idle time for pooled connections.
     *
     * @return idle timeout in milliseconds
     */
    public static long getMaxIdleTimeMs() {
        return maxIdleTimeMs;
    }

    /**
     * Returns the number of connections currently in the pool.
     *
     * @return pool size
     */
    public static int poolSize() {
        return pool.size();
    }

    /**
     * Closes all pooled connections and clears the pool.
     */
    public static void closeAll() {
        for (PoolEntry pe : pool.values()) {
            pe.transport.close();
        }
        pool.clear();
    }

    @Override
    public void open(InetAddress server, int port, SelectorLoop loop,
                     DNSClientTransportHandler handler) throws IOException {
        String serverKey = server.getHostAddress() + ":" + port;
        entry = pool.compute(serverKey, (key, existing) -> {
            if (existing != null && existing.isUsable()) {
                existing.lastUsed = System.currentTimeMillis();
                existing.handler = handler;
                return existing;
            }
            if (existing != null) {
                existing.transport.close();
            }
            PoolEntry pe = new PoolEntry();
            pe.transport = new DoQClientTransport();
            pe.handler = handler;
            pe.loop = loop;
            pe.server = server;
            pe.port = port;
            pe.lastUsed = System.currentTimeMillis();
            pe.createdAt = System.currentTimeMillis();
            return pe;
        });
        if (!entry.opened) {
            entry.transport.open(server, port, loop, handler);
            entry.opened = true;
        }
    }

    // RFC 9250 section 4.2: each query opens a new stream
    @Override
    public void send(ByteBuffer data) {
        if (entry == null) {
            throw new IllegalStateException("Transport not opened");
        }
        entry.lastUsed = System.currentTimeMillis();
        entry.transport.send(data);
    }

    @Override
    public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
        if (entry == null) {
            throw new IllegalStateException("Transport not opened");
        }
        return entry.transport.scheduleTimer(delayMs, callback);
    }

    /**
     * Returns this transport's connection to the pool.
     * The underlying QUIC connection is kept alive for reuse.
     * Call {@link #closeAll()} to forcibly close all pooled connections.
     */
    @Override
    public void close() {
        if (entry != null) {
            entry.lastUsed = System.currentTimeMillis();
            evictStale();
        }
    }

    /**
     * Removes connections that have been idle longer than
     * {@link #maxIdleTimeMs}.
     */
    static void evictStale() {
        long now = System.currentTimeMillis();
        pool.entrySet().removeIf(e -> {
            PoolEntry pe = e.getValue();
            if (now - pe.lastUsed > maxIdleTimeMs) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Evicting idle DoQ connection: "
                            + e.getKey());
                }
                pe.transport.close();
                return true;
            }
            return false;
        });
    }

    static final class PoolEntry {
        DoQClientTransport transport;
        DNSClientTransportHandler handler;
        SelectorLoop loop;
        InetAddress server;
        int port;
        long lastUsed;
        long createdAt;
        boolean opened;

        boolean isUsable() {
            long now = System.currentTimeMillis();
            return opened && (now - lastUsed) <= maxIdleTimeMs;
        }
    }
}
