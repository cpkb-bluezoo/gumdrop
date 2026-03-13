/*
 * TCPDNSConnectionPool.java
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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

import org.bluezoo.gumdrop.dns.DNSFormatException;
import org.bluezoo.gumdrop.dns.DNSMessage;

/**
 * TCP connection pool for DNS queries.
 * RFC 7766 sections 6-7: DNS implementations SHOULD reuse TCP
 * connections for multiple queries. Connection reuse amortises the
 * cost of TCP (and TLS for DoT) handshakes.
 *
 * <p>This pool maintains a small set of persistent TCP connections
 * per upstream server, keyed by address. Connections have an idle
 * timeout and maximum lifetime.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7766">RFC 7766</a>
 */
public class TCPDNSConnectionPool {

    /** Default maximum connections per server. */
    public static final int DEFAULT_MAX_CONNECTIONS_PER_SERVER = 2;

    /** Default idle timeout in milliseconds (30 seconds). */
    public static final long DEFAULT_IDLE_TIMEOUT_MS = 30_000;

    /** Default maximum connection lifetime in milliseconds (5 minutes). */
    public static final long DEFAULT_MAX_LIFETIME_MS = 300_000;

    /** Default socket timeout for reads (5 seconds). */
    public static final int DEFAULT_SOCKET_TIMEOUT_MS = 5000;

    private int maxConnectionsPerServer = DEFAULT_MAX_CONNECTIONS_PER_SERVER;
    private long idleTimeoutMs = DEFAULT_IDLE_TIMEOUT_MS;
    private long maxLifetimeMs = DEFAULT_MAX_LIFETIME_MS;
    private int socketTimeoutMs = DEFAULT_SOCKET_TIMEOUT_MS;

    private final ConcurrentHashMap<InetSocketAddress, Deque<PooledConnection>>
            pool = new ConcurrentHashMap<>();

    /**
     * Sets the maximum number of connections per server.
     *
     * @param max the maximum number of connections
     */
    public void setMaxConnectionsPerServer(int max) {
        this.maxConnectionsPerServer = max;
    }

    /**
     * Sets the idle timeout after which unused connections are closed.
     *
     * @param millis the timeout in milliseconds
     */
    public void setIdleTimeoutMs(long millis) {
        this.idleTimeoutMs = millis;
    }

    /**
     * Sets the maximum lifetime of a connection regardless of activity.
     *
     * @param millis the maximum lifetime in milliseconds
     */
    public void setMaxLifetimeMs(long millis) {
        this.maxLifetimeMs = millis;
    }

    /**
     * Sets the socket read timeout.
     *
     * @param millis the timeout in milliseconds
     */
    public void setSocketTimeoutMs(int millis) {
        this.socketTimeoutMs = millis;
    }

    /**
     * Acquires a connection to the specified server. Returns an existing
     * pooled connection if available, otherwise opens a new one.
     * RFC 7766 section 6.2.1: clients SHOULD pipeline queries on
     * existing connections rather than opening new ones.
     *
     * @param server the upstream server address
     * @return the pooled connection
     * @throws IOException if a new connection cannot be opened
     */
    public PooledConnection acquire(InetSocketAddress server)
            throws IOException {
        Deque<PooledConnection> connections = pool.get(server);
        if (connections != null) {
            synchronized (connections) {
                evictExpired(connections);
                PooledConnection conn = connections.pollFirst();
                if (conn != null && conn.isUsable()) {
                    conn.lastUsed = System.currentTimeMillis();
                    return conn;
                }
            }
        }
        return openNew(server);
    }

    /**
     * Releases a connection back to the pool. If the pool is full or
     * the connection is expired, the connection is closed instead.
     *
     * @param conn the connection to release
     */
    public void release(PooledConnection conn) {
        if (!conn.isUsable()) {
            conn.close();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - conn.created > maxLifetimeMs) {
            conn.close();
            return;
        }
        conn.lastUsed = now;

        Deque<PooledConnection> connections = pool.computeIfAbsent(
                conn.server,
                k -> new ArrayDeque<>(maxConnectionsPerServer));
        synchronized (connections) {
            evictExpired(connections);
            if (connections.size() >= maxConnectionsPerServer) {
                conn.close();
            } else {
                connections.addLast(conn);
            }
        }
    }

    /**
     * Sends a DNS query and reads the response using a pooled connection.
     * RFC 7766 section 6.2.1: uses 2-byte length-prefixed framing.
     *
     * @param server the upstream server
     * @param queryData the serialised query bytes
     * @return the parsed response
     * @throws IOException on connection or I/O error
     * @throws DNSFormatException if the response is malformed
     */
    public DNSMessage sendAndReceive(InetSocketAddress server,
                                      byte[] queryData)
            throws IOException, DNSFormatException {
        PooledConnection conn = acquire(server);
        try {
            OutputStream out = conn.socket.getOutputStream();
            out.write((queryData.length >> 8) & 0xFF);
            out.write(queryData.length & 0xFF);
            out.write(queryData);
            out.flush();

            InputStream in = conn.socket.getInputStream();
            int hi = in.read();
            int lo = in.read();
            if (hi < 0 || lo < 0) {
                throw new IOException("Connection closed by peer");
            }
            int respLen = (hi << 8) | lo;
            byte[] respData = new byte[respLen];
            int offset = 0;
            while (offset < respLen) {
                int n = in.read(respData, offset, respLen - offset);
                if (n < 0) {
                    throw new IOException("Unexpected EOF");
                }
                offset += n;
            }

            DNSMessage response = DNSMessage.parse(
                    ByteBuffer.wrap(respData));
            release(conn);
            return response;
        } catch (IOException | DNSFormatException e) {
            conn.close();
            throw e;
        }
    }

    /**
     * Closes all pooled connections and clears the pool.
     */
    public void close() {
        for (Deque<PooledConnection> connections : pool.values()) {
            synchronized (connections) {
                for (PooledConnection conn : connections) {
                    conn.close();
                }
                connections.clear();
            }
        }
        pool.clear();
    }

    /**
     * Returns the number of pooled connections for a given server.
     *
     * @param server the server address
     * @return the number of idle connections in the pool
     */
    public int pooledConnectionCount(InetSocketAddress server) {
        Deque<PooledConnection> connections = pool.get(server);
        return connections != null ? connections.size() : 0;
    }

    private PooledConnection openNew(InetSocketAddress server)
            throws IOException {
        Socket socket = new Socket();
        socket.connect(server, socketTimeoutMs);
        socket.setSoTimeout(socketTimeoutMs);
        socket.setTcpNoDelay(true);
        return new PooledConnection(server, socket);
    }

    private void evictExpired(Deque<PooledConnection> connections) {
        long now = System.currentTimeMillis();
        Iterator<PooledConnection> it = connections.iterator();
        while (it.hasNext()) {
            PooledConnection conn = it.next();
            if (!conn.isUsable()
                    || now - conn.lastUsed > idleTimeoutMs
                    || now - conn.created > maxLifetimeMs) {
                conn.close();
                it.remove();
            }
        }
    }

    /**
     * A pooled TCP connection with tracking metadata.
     */
    public static class PooledConnection {
        final InetSocketAddress server;
        final Socket socket;
        final long created;
        volatile long lastUsed;

        PooledConnection(InetSocketAddress server, Socket socket) {
            this.server = server;
            this.socket = socket;
            this.created = System.currentTimeMillis();
            this.lastUsed = this.created;
        }

        boolean isUsable() {
            return !socket.isClosed() && socket.isConnected();
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
