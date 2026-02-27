/*
 * RedisClient.java
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

package org.bluezoo.gumdrop.redis.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TCPTransportFactory;

/**
 * High-level Redis client facade.
 *
 * <p>This class provides a simple, concrete API for connecting to Redis servers.
 * It internally creates a {@link TCPTransportFactory},
 * {@link ClientEndpoint}, and {@link RedisClientProtocolHandler}, wiring
 * them together and forwarding lifecycle events to the caller's
 * {@link RedisConnectionReady} handler.
 *
 * <h4>Basic Usage</h4>
 * <pre>{@code
 * RedisClient client = new RedisClient(selectorLoop, "localhost", 6379);
 * client.connect(new RedisConnectionReady() {
 *     public void handleReady(RedisSession session) {
 *         session.set("key", "value", handler);
 *     }
 *     public void onConnected(Endpoint endpoint) { }
 *     public void onSecurityEstablished(SecurityInfo info) { }
 *     public void onError(Exception cause) { cause.printStackTrace(); }
 *     public void onDisconnected() { }
 * });
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see RedisConnectionReady
 * @see RedisClientProtocolHandler
 */
public class RedisClient {

    private static final Logger LOGGER =
            Logger.getLogger(RedisClient.class.getName());

    private final String host;
    private final int port;
    private final SelectorLoop selectorLoop;

    // Configuration (set before connect)
    private boolean secure;
    private SSLContext sslContext;
    private Path keystoreFile;
    private String keystorePass;
    private String keystoreFormat;

    // Internal transport components (created at connect time)
    private TCPTransportFactory transportFactory;
    private ClientEndpoint clientEndpoint;
    private RedisClientProtocolHandler endpointHandler;
    private boolean connected;

    /**
     * Creates a Redis client for the given host and port.
     *
     * <p>Uses the next available worker loop from the global
     * {@link Gumdrop} instance. DNS resolution is deferred until
     * {@link #connect} is called.
     *
     * @param host the remote hostname or IP address
     * @param port the remote port
     */
    public RedisClient(String host, int port) {
        this(null, host, port);
    }

    /**
     * Creates a Redis client with an explicit selector loop.
     *
     * <p>DNS resolution is deferred until {@link #connect} is called.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop worker
     * @param host the remote hostname or IP address
     * @param port the remote port
     */
    public RedisClient(SelectorLoop selectorLoop, String host, int port) {
        this.selectorLoop = selectorLoop;
        this.host = host;
        this.port = port;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Configuration (before connect)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Sets whether this client uses TLS.
     *
     * @param secure true for TLS
     */
    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    /**
     * Sets an externally-configured SSL context.
     *
     * @param context the SSL context
     */
    public void setSSLContext(SSLContext context) {
        this.sslContext = context;
    }

    /**
     * Sets the keystore file for client certificate authentication.
     *
     * @param path the keystore file path
     */
    public void setKeystoreFile(Path path) {
        this.keystoreFile = path;
    }

    public void setKeystoreFile(String path) {
        this.keystoreFile = Path.of(path);
    }

    /**
     * Sets the keystore password.
     *
     * @param password the keystore password
     */
    public void setKeystorePass(String password) {
        this.keystorePass = password;
    }

    /**
     * Sets the keystore format (e.g. JKS, PKCS12).
     *
     * @param format the keystore format
     */
    public void setKeystoreFormat(String format) {
        this.keystoreFormat = format;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Connects to the remote Redis server.
     *
     * <p>Creates the transport factory, endpoint handler, and client
     * endpoint, then initiates the connection. Lifecycle events are
     * forwarded to the given handler.
     *
     * @param handler the handler to receive connection lifecycle events
     */
    public void connect(RedisConnectionReady handler) {
        transportFactory = new TCPTransportFactory();
        transportFactory.setSecure(secure);
        if (sslContext != null) {
            transportFactory.setSSLContext(sslContext);
        }
        if (keystoreFile != null) {
            transportFactory.setKeystoreFile(keystoreFile);
        }
        if (keystorePass != null) {
            transportFactory.setKeystorePass(keystorePass);
        }
        if (keystoreFormat != null) {
            transportFactory.setKeystoreFormat(keystoreFormat);
        }
        transportFactory.start();

        endpointHandler = new RedisClientProtocolHandler(handler);

        try {
            if (selectorLoop != null) {
                clientEndpoint = new ClientEndpoint(
                        transportFactory, selectorLoop,
                        host, port);
            } else {
                clientEndpoint = new ClientEndpoint(
                        transportFactory, host, port);
            }
            clientEndpoint.connect(endpointHandler);
            connected = true;
        } catch (IOException e) {
            handler.onError(e);
        }
    }

    /**
     * Returns whether the connection is open.
     *
     * @return true if connected and open
     */
    public boolean isOpen() {
        return connected && endpointHandler != null;
    }

    /**
     * Closes the connection and deregisters from Gumdrop's lifecycle
     * tracking.
     */
    public void close() {
        if (endpointHandler != null) {
            endpointHandler.close();
        }
        if (clientEndpoint != null) {
            clientEndpoint.close();
        }
    }
}
