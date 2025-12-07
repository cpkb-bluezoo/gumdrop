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
 * This class extends {@link Connector} and adds client-specific
 * functionality such as target host and port configuration,
 * client-mode SSL engine setup, and asynchronous connection establishment.
 * 
 * <p>Clients create outbound connections to remote servers using the
 * same event-driven, non-blocking architecture as server-side connections.
 * Protocol implementations provide a {@link ClientHandler} to receive
 * connection events and protocol-specific data.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class Client extends Connector {

    protected InetAddress host;
    protected int port;

    /**
     * Creates a client that will connect to the specified host and port.
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
     * @param host the target host as an InetAddress
     * @param port the target port number
     */
    public Client(InetAddress host, int port) {
        super();
        this.host = host;
        this.port = port;
    }

    /**
     * Returns the target host address.
     */
    public InetAddress getHost() {
        return host;
    }

    /**
     * Returns the target port number.
     */
    public int getPort() {
        return port;
    }

    @Override
    protected void configureSSLEngine(SSLEngine engine) {
        super.configureSSLEngine(engine);
        engine.setUseClientMode(true);
    }

    /**
     * Creates a new connection instance for this client.
     * This implementation delegates to the 3-parameter version with a null handler.
     * 
     * @param channel the socket channel for the connection
     * @param engine the SSL engine if this is a secure connection, or null
     * @return a new connection instance configured for this protocol
     */
    @Override
    protected final Connection newConnection(SocketChannel channel, SSLEngine engine) {
        throw new UnsupportedOperationException(
                "Client connections require a ClientHandler - use connect(ClientHandler) instead");
    }

    /**
     * Creates a new connection instance for this client.
     * This method is called by {@link #connect(ClientHandler)} to create
     * the appropriate connection type for the protocol.
     * 
     * @param channel the socket channel for the connection
     * @param engine the SSL engine if this is a secure connection, or null
     * @param handler the client handler that will receive connection events
     * @return a new connection instance configured for this protocol
     */
    protected abstract Connection newConnection(SocketChannel channel, SSLEngine engine, ClientHandler handler);

    /**
     * Initiates an asynchronous connection to the remote server.
     * 
     * <p>This method creates a non-blocking TCP connection to the configured
     * host and port. The provided handler will receive events as the connection
     * progresses through its lifecycle:
     * <ul>
     * <li>{@link ClientHandler#onConnected()} when the connection is established</li>
     * <li>{@link ClientHandler#onError(Exception)} if connection fails</li>
     * <li>{@link ClientHandler#onDisconnected()} when the connection closes</li>
     * </ul>
     * 
     * <p>This method returns immediately and does not block. All subsequent
     * interaction with the connection happens through the provided handler's
     * callback methods.
     * 
     * @param handler the handler to receive connection events and drive protocol behavior
     * @throws IOException if the connection cannot be initiated
     */
    public <T extends ClientHandler> void connect(T handler) throws IOException {
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
        connection.init();
        
        // Attempt to connect (non-blocking)
        InetSocketAddress remoteAddress = new InetSocketAddress(host, port);
        boolean connected = channel.connect(remoteAddress);
        
        // Get a worker SelectorLoop from Gumdrop
        SelectorLoop workerLoop = Gumdrop.getInstance().nextWorkerLoop();
        
        if (connected) {
            // Connection completed immediately (rare but possible for localhost)
            workerLoop.register(channel, connection);
            // connected() will be called when registered
        } else {
            // Connection is pending - register for OP_CONNECT
            workerLoop.registerForConnect(channel, connection);
        }
    }

    @Override
    protected String getDescription() {
        return getClass().getSimpleName() + "(" + host.getHostAddress() + ":" + port + ")";
    }

}
