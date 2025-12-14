/*
 * Client.java
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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.SocketChannel;
import javax.net.ssl.SSLEngine;

/**
 * Abstract base class for client-side connection factories.
 *
 * <p>This class extends {@link Connector} and adds client-specific
 * functionality such as target host and port configuration,
 * client-mode SSL engine setup, and asynchronous connection establishment.
 *
 * <p>Clients create outbound connections to remote servers using the
 * same event-driven, non-blocking architecture as server-side connections.
 * Protocol implementations provide a {@link ClientHandler} to receive
 * connection events and protocol-specific data.
 *
 * <h4>Standalone Usage (no SelectorLoop required)</h4>
 * <pre>{@code
 * // Simple - Gumdrop infrastructure is managed automatically
 * RedisClient client = new RedisClient("localhost", 6379);
 * client.connect(new RedisConnectionReady() {
 *     public void handleReady(RedisSession session) {
 *         session.ping(handler);
 *     }
 *     // ... other callbacks
 * });
 * // Infrastructure auto-starts on connect, auto-stops when done
 * }</pre>
 *
 * <h4>Server Integration (with SelectorLoop affinity)</h4>
 * <pre>{@code
 * // In a server connection handler - use same SelectorLoop for efficiency
 * public void handleRequest() {
 *     SelectorLoop myLoop = getSelectorLoop();
 *     RedisClient client = new RedisClient(myLoop, "localhost", 6379);
 *     client.connect(handler);
 * }
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class Client extends Connector {

    protected final InetAddress host;
    protected final int port;
    protected SelectorLoop selectorLoop;

    // ─────────────────────────────────────────────────────────────────────────
    // Constructors with explicit SelectorLoop (server integration)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a client that will connect to the specified host and port,
     * using the provided SelectorLoop for I/O.
     *
     * <p>Use this constructor for server integration where you want the
     * client to share a SelectorLoop with server connections.
     *
     * @param selectorLoop the SelectorLoop for connection I/O
     * @param host the target host as a String
     * @param port the target port number
     * @throws UnknownHostException if the host cannot be resolved
     */
    public Client(SelectorLoop selectorLoop, String host, int port) throws UnknownHostException {
        this(selectorLoop, InetAddress.getByName(host), port);
    }

    /**
     * Creates a client that will connect to the specified host and port,
     * using the provided SelectorLoop for I/O.
     *
     * @param selectorLoop the SelectorLoop for connection I/O
     * @param host the target host as an InetAddress
     * @param port the target port number
     */
    public Client(SelectorLoop selectorLoop, InetAddress host, int port) {
        super();
        this.selectorLoop = selectorLoop;
        this.host = host;
        this.port = port;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constructors without SelectorLoop (standalone usage)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a client that will connect to the specified host and port.
     *
     * <p>A SelectorLoop will be obtained automatically from the Gumdrop
     * infrastructure when {@link #connect} is called. The infrastructure
     * is started automatically if needed and will shut down when all
     * client connections close.
     *
     * @param host the target host as a String
     * @param port the target port number
     * @throws UnknownHostException if the host cannot be resolved
     */
    public Client(String host, int port) throws UnknownHostException {
        this(InetAddress.getByName(host), port);
    }

    /**
     * Creates a client that will connect to the specified host and port.
     *
     * <p>A SelectorLoop will be obtained automatically from the Gumdrop
     * infrastructure when {@link #connect} is called.
     *
     * @param host the target host as an InetAddress
     * @param port the target port number
     */
    public Client(InetAddress host, int port) {
        super();
        this.selectorLoop = null; // Set lazily in connect()
        this.host = host;
        this.port = port;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Properties
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the target host address.
     *
     * @return the host address
     */
    public InetAddress getHost() {
        return host;
    }

    /**
     * Returns the target port number.
     *
     * @return the port number
     */
    public int getPort() {
        return port;
    }

    /**
     * Returns the SelectorLoop this client uses for connections.
     *
     * <p>May return null before {@link #connect} is called if the client
     * was created without an explicit SelectorLoop.
     *
     * @return the SelectorLoop, or null if not yet assigned
     */
    public SelectorLoop getSelectorLoop() {
        return selectorLoop;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SSL configuration
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void configureSSLEngine(SSLEngine engine) {
        super.configureSSLEngine(engine);
        engine.setUseClientMode(true);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Connection creation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a new connection instance for this client.
     *
     * <p>This implementation throws UnsupportedOperationException because
     * client connections require a ClientHandler. Use {@link #connect}
     * instead.
     *
     * @param channel the socket channel for the connection
     * @param engine the SSL engine if this is a secure connection, or null
     * @return never returns
     * @throws UnsupportedOperationException always
     */
    @Override
    protected final Connection newConnection(SocketChannel channel, SSLEngine engine) {
        throw new UnsupportedOperationException(
                "Client connections require a ClientHandler - use connect(ClientHandler) instead");
    }

    /**
     * Creates a new connection instance for this client.
     *
     * <p>This method is called by {@link #connect(ClientHandler)} to create
     * the appropriate connection type for the protocol.
     *
     * @param channel the socket channel for the connection
     * @param engine the SSL engine if this is a secure connection, or null
     * @param handler the client handler that will receive connection events
     * @return a new connection instance configured for this protocol
     */
    protected abstract Connection newConnection(SocketChannel channel, SSLEngine engine, ClientHandler handler);

    // ─────────────────────────────────────────────────────────────────────────
    // Connection establishment
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Initiates an asynchronous connection to the remote server.
     *
     * <p>This method creates a non-blocking TCP connection to the configured
     * host and port. The provided handler will receive events as the connection
     * progresses through its lifecycle:
     * <ul>
     *   <li>{@link ClientHandler#onConnected(ConnectionInfo)} when TCP connected</li>
     *   <li>{@link ClientHandler#onTLSStarted(TLSInfo)} when TLS handshake completes</li>
     *   <li>{@link ClientHandler#onError(Exception)} if connection fails</li>
     *   <li>{@link ClientHandler#onDisconnected()} when the connection closes</li>
     * </ul>
     *
     * <p>This method returns immediately and does not block. All subsequent
     * interaction with the connection happens through the provided handler's
     * callback methods.
     *
     * <p>If no SelectorLoop was provided at construction time, this method
     * automatically obtains one from the Gumdrop infrastructure, starting
     * the infrastructure if necessary.
     *
     * @param handler the handler to receive connection events
     * @throws IOException if the connection cannot be initiated
     */
    public <T extends ClientHandler> void connect(T handler) throws IOException {
        // Get Gumdrop instance and register this client activity
        Gumdrop gumdrop = Gumdrop.getInstance();

        // If no SelectorLoop was provided, set up infrastructure
        if (selectorLoop == null) {
            gumdrop.start(); // No-op if already started
            selectorLoop = gumdrop.nextWorkerLoop();
        }

        // Create and configure the socket channel
        SocketChannel channel = SocketChannel.open();
        channel.configureBlocking(false);

        // Prepare SSL engine if this client uses encryption
        SSLEngine sslEngine = null;
        if (secure) {
            sslEngine = context.createSSLEngine(host.getHostAddress(), port);
            configureSSLEngine(sslEngine);
        }

        // Create the connection instance
        Connection connection = newConnection(channel, sslEngine, handler);
        connection.connector = this;
        connection.channel = channel;
        connection.setClientConnection(true); // Mark as client connection for lifecycle tracking
        connection.init();

        // Register with Gumdrop for lifecycle tracking
        gumdrop.addChannelHandler(connection);

        // Attempt to connect (non-blocking)
        InetSocketAddress remoteAddress = new InetSocketAddress(host, port);
        boolean connected = channel.connect(remoteAddress);

        if (connected) {
            // Connection completed immediately (rare but possible for localhost)
            selectorLoop.register(channel, connection);
        } else {
            // Connection is pending - register for OP_CONNECT
            selectorLoop.registerForConnect(channel, connection);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Description
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected String getDescription() {
        return getClass().getSimpleName() + "(" + host.getHostAddress() + ":" + port + ")";
    }

}
