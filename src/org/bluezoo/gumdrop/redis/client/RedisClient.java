/*
 * RedisClient.java
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

package org.bluezoo.gumdrop.redis.client;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;

import org.bluezoo.gumdrop.Client;
import org.bluezoo.gumdrop.ClientHandler;
import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.SelectorLoop;

/**
 * Redis client implementation that creates and manages Redis connections.
 *
 * <p>This class extends {@link Client} to provide Redis-specific client
 * functionality for connecting to Redis servers and creating connection
 * instances.
 *
 * <h4>Standalone Usage (recommended for simple scripts)</h4>
 * <pre>{@code
 * // Simple - no Gumdrop setup needed
 * RedisClient client = new RedisClient("localhost", 6379);
 * client.connect(new RedisConnectionReady() {
 *     public void handleReady(RedisSession session) {
 *         session.ping(new StringResultHandler() {
 *             public void handleResult(String result, RedisSession s) {
 *                 System.out.println("Redis says: " + result);
 *                 s.quit();
 *             }
 *             public void handleError(String error, RedisSession s) {
 *                 System.err.println("Error: " + error);
 *                 s.close();
 *             }
 *         });
 *     }
 *     public void onConnected(ConnectionInfo info) { }
 *     public void onDisconnected() { }
 *     public void onTLSStarted(TLSInfo info) { }
 *     public void onError(Exception e) { e.printStackTrace(); }
 * });
 * // Infrastructure auto-starts and auto-stops
 * }</pre>
 *
 * <h4>Server Integration (with SelectorLoop affinity)</h4>
 * <pre>{@code
 * // Use the same SelectorLoop as your server connection
 * RedisClient client = new RedisClient(connection.getSelectorLoop(), "localhost", 6379);
 * client.connect(handler);
 * }</pre>
 *
 * <h4>TLS Connection</h4>
 * <pre>{@code
 * RedisClient client = new RedisClient("redis.example.com", 6379);
 * client.setSecure(true);
 * client.setKeystoreFile("/path/to/truststore.p12");
 * client.setKeystorePass("password");
 * client.connect(handler);
 * }</pre>
 *
 * <h4>With Authentication</h4>
 * <pre>{@code
 * client.connect(new RedisConnectionReady() {
 *     public void handleReady(RedisSession session) {
 *         // Redis 6+ ACL authentication
 *         session.auth("username", "password", new StringResultHandler() {
 *             public void handleResult(String result, RedisSession s) {
 *                 // Authenticated, now use Redis
 *                 s.set("key", "value", myHandler);
 *             }
 *             public void handleError(String error, RedisSession s) {
 *                 System.err.println("Auth failed: " + error);
 *                 s.close();
 *             }
 *         });
 *     }
 *     // ... other callbacks
 * });
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClient extends Client {

    /** The default Redis port. */
    public static final int DEFAULT_PORT = 6379;

    /** The default Redis TLS port. */
    public static final int DEFAULT_TLS_PORT = 6380;

    // ─────────────────────────────────────────────────────────────────────────
    // Constructors without SelectorLoop (standalone usage)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a Redis client that will connect to the specified host and port.
     *
     * <p>The Gumdrop infrastructure is managed automatically - it starts
     * when {@link #connect} is called and stops when all connections close.
     *
     * @param host the Redis server host as a String
     * @param port the Redis server port number (typically 6379)
     * @throws UnknownHostException if the host cannot be resolved
     */
    public RedisClient(String host, int port) throws UnknownHostException {
        super(host, port);
    }

    /**
     * Creates a Redis client that will connect to the default port (6379).
     *
     * @param host the Redis server host as a String
     * @throws UnknownHostException if the host cannot be resolved
     */
    public RedisClient(String host) throws UnknownHostException {
        super(host, DEFAULT_PORT);
    }

    /**
     * Creates a Redis client that will connect to the specified host and port.
     *
     * @param host the Redis server host as an InetAddress
     * @param port the Redis server port number (typically 6379)
     */
    public RedisClient(InetAddress host, int port) {
        super(host, port);
    }

    /**
     * Creates a Redis client that will connect to the default port (6379).
     *
     * @param host the Redis server host as an InetAddress
     */
    public RedisClient(InetAddress host) {
        super(host, DEFAULT_PORT);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constructors with SelectorLoop (server integration)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a Redis client that will connect to the specified host and port,
     * using the provided SelectorLoop for I/O.
     *
     * <p>Use this constructor for server integration where you want the
     * client to share a SelectorLoop with server connections for efficiency.
     *
     * @param selectorLoop the SelectorLoop for connection I/O
     * @param host the Redis server host as a String
     * @param port the Redis server port number (typically 6379)
     * @throws UnknownHostException if the host cannot be resolved
     */
    public RedisClient(SelectorLoop selectorLoop, String host, int port) throws UnknownHostException {
        super(selectorLoop, host, port);
    }

    /**
     * Creates a Redis client that will connect to the default port (6379),
     * using the provided SelectorLoop for I/O.
     *
     * @param selectorLoop the SelectorLoop for connection I/O
     * @param host the Redis server host as a String
     * @throws UnknownHostException if the host cannot be resolved
     */
    public RedisClient(SelectorLoop selectorLoop, String host) throws UnknownHostException {
        super(selectorLoop, host, DEFAULT_PORT);
    }

    /**
     * Creates a Redis client that will connect to the specified host and port,
     * using the provided SelectorLoop for I/O.
     *
     * @param selectorLoop the SelectorLoop for connection I/O
     * @param host the Redis server host as an InetAddress
     * @param port the Redis server port number (typically 6379)
     */
    public RedisClient(SelectorLoop selectorLoop, InetAddress host, int port) {
        super(selectorLoop, host, port);
    }

    /**
     * Creates a Redis client that will connect to the default port (6379),
     * using the provided SelectorLoop for I/O.
     *
     * @param selectorLoop the SelectorLoop for connection I/O
     * @param host the Redis server host as an InetAddress
     */
    public RedisClient(SelectorLoop selectorLoop, InetAddress host) {
        super(selectorLoop, host, DEFAULT_PORT);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Connection factory
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a new Redis client connection with a handler.
     *
     * @param channel the socket channel for the connection
     * @param engine optional SSL engine for secure connections
     * @param handler the client handler to receive Redis events (must be {@link RedisConnectionReady})
     * @return a new RedisClientConnection instance
     * @throws ClassCastException if handler is not a RedisConnectionReady
     */
    @Override
    protected Connection newConnection(SocketChannel channel, SSLEngine engine, ClientHandler handler) {
        return new RedisClientConnection(this, engine, secure, (RedisConnectionReady) handler);
    }

    @Override
    public String getDescription() {
        return "Redis Client (" + host.getHostAddress() + ":" + port + ")";
    }

}
