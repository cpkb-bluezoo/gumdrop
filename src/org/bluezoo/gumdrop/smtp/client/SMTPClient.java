/*
 * SMTPClient.java
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

package org.bluezoo.gumdrop.smtp.client;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;

import org.bluezoo.gumdrop.Client;
import org.bluezoo.gumdrop.ClientHandler;
import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.smtp.client.handler.ServerGreeting;

/**
 * SMTP client implementation that creates and manages SMTP client connections.
 *
 * <p>This class extends {@link Client} to provide SMTP-specific client functionality
 * for connecting to SMTP servers and creating connection instances.
 *
 * <h4>Standalone Usage</h4>
 * <pre>{@code
 * SMTPClient client = new SMTPClient("smtp.example.com", 587);
 * client.connect(new ServerGreeting() {
 *     public void handleGreeting(SMTPSession session) {
 *         session.ehlo("myhostname", new EhloHandler());
 *     }
 *     // ... other callbacks
 * });
 * }</pre>
 *
 * <h4>Server Integration (with SelectorLoop affinity)</h4>
 * <pre>{@code
 * // Use the same SelectorLoop as your server connection
 * SMTPClient client = new SMTPClient(connection.getSelectorLoop(), "smtp.example.com", 587);
 * client.connect(handler);
 * }</pre>
 *
 * <p>The handler passed to {@code connect()} must implement {@link ServerGreeting}
 * to receive the server's initial greeting and begin the SMTP session.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerGreeting
 */
public class SMTPClient extends Client {

    /** Default SMTP submission port. */
    public static final int DEFAULT_PORT = 25;

    /** Default SMTP submission port (with STARTTLS). */
    public static final int SUBMISSION_PORT = 587;

    /** Default SMTPS port (implicit TLS). */
    public static final int SMTPS_PORT = 465;

    /** Whether to use BDAT (CHUNKING) when available. */
    private boolean chunkingEnabled = true;

    // ─────────────────────────────────────────────────────────────────────────
    // Constructors without SelectorLoop (standalone usage)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates an SMTP client that will connect to the specified host and port.
     *
     * <p>The Gumdrop infrastructure is managed automatically - it starts
     * when {@link #connect} is called and stops when all connections close.
     *
     * @param host the SMTP server host as a String
     * @param port the SMTP server port number (typically 25, 465, or 587)
     * @throws UnknownHostException if the host cannot be resolved
     */
    public SMTPClient(String host, int port) throws UnknownHostException {
        super(host, port);
    }

    /**
     * Creates an SMTP client that will connect to the default port (25).
     *
     * @param host the SMTP server host as a String
     * @throws UnknownHostException if the host cannot be resolved
     */
    public SMTPClient(String host) throws UnknownHostException {
        super(host, DEFAULT_PORT);
    }

    /**
     * Creates an SMTP client that will connect to the specified host and port.
     *
     * @param host the SMTP server host as an InetAddress
     * @param port the SMTP server port number (typically 25, 465, or 587)
     */
    public SMTPClient(InetAddress host, int port) {
        super(host, port);
    }

    /**
     * Creates an SMTP client that will connect to the default port (25).
     *
     * @param host the SMTP server host as an InetAddress
     */
    public SMTPClient(InetAddress host) {
        super(host, DEFAULT_PORT);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constructors with SelectorLoop (server integration)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates an SMTP client that will connect to the specified host and port,
     * using the provided SelectorLoop for I/O.
     *
     * <p>Use this constructor for server integration where you want the
     * client to share a SelectorLoop with server connections for efficiency.
     *
     * @param selectorLoop the SelectorLoop for connection I/O
     * @param host the SMTP server host as a String
     * @param port the SMTP server port number (typically 25, 465, or 587)
     * @throws UnknownHostException if the host cannot be resolved
     */
    public SMTPClient(SelectorLoop selectorLoop, String host, int port) throws UnknownHostException {
        super(selectorLoop, host, port);
    }

    /**
     * Creates an SMTP client that will connect to the default port (25),
     * using the provided SelectorLoop for I/O.
     *
     * @param selectorLoop the SelectorLoop for connection I/O
     * @param host the SMTP server host as a String
     * @throws UnknownHostException if the host cannot be resolved
     */
    public SMTPClient(SelectorLoop selectorLoop, String host) throws UnknownHostException {
        super(selectorLoop, host, DEFAULT_PORT);
    }

    /**
     * Creates an SMTP client that will connect to the specified host and port,
     * using the provided SelectorLoop for I/O.
     *
     * @param selectorLoop the SelectorLoop for connection I/O
     * @param host the SMTP server host as an InetAddress
     * @param port the SMTP server port number (typically 25, 465, or 587)
     */
    public SMTPClient(SelectorLoop selectorLoop, InetAddress host, int port) {
        super(selectorLoop, host, port);
    }

    /**
     * Creates an SMTP client that will connect to the default port (25),
     * using the provided SelectorLoop for I/O.
     *
     * @param selectorLoop the SelectorLoop for connection I/O
     * @param host the SMTP server host as an InetAddress
     */
    public SMTPClient(SelectorLoop selectorLoop, InetAddress host) {
        super(selectorLoop, host, DEFAULT_PORT);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Configuration
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns whether BDAT (CHUNKING) is enabled.
     *
     * <p>When enabled (default), the client will use the BDAT command instead of
     * DATA if the server advertises CHUNKING support. BDAT is more efficient as
     * it doesn't require dot-stuffing and allows the server to process chunks
     * as they arrive.
     *
     * @return true if CHUNKING is enabled
     */
    public boolean isChunkingEnabled() {
        return chunkingEnabled;
    }

    /**
     * Sets whether to use BDAT (CHUNKING) when the server supports it.
     *
     * <p>When enabled (default), the client will use the BDAT command instead of
     * DATA if the server advertises CHUNKING support. Disable this for testing
     * or when connecting to servers with buggy CHUNKING implementations.
     *
     * @param enabled true to enable CHUNKING, false to always use DATA
     */
    public void setChunkingEnabled(boolean enabled) {
        this.chunkingEnabled = enabled;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Connection factory
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a new SMTP client connection with a handler.
     * 
     * <p>The handler must implement {@link ServerGreeting} to receive the
     * server's initial greeting and begin the SMTP session.
     *
     * @param channel the socket channel for the connection
     * @param engine optional SSL engine for secure connections
     * @param handler the client handler (must be ServerGreeting)
     * @return a new SMTPClientConnection instance
     * @throws ClassCastException if handler is not a ServerGreeting
     */
    @Override
    protected Connection newConnection(SocketChannel channel, SSLEngine engine, ClientHandler handler) {
        SMTPClientConnection conn = new SMTPClientConnection(this, channel, engine, secure, (ServerGreeting) handler);
        conn.setChunkingEnabled(chunkingEnabled);
        return conn;
    }

    @Override
    public String getDescription() {
        return "SMTP Client (" + host.getHostAddress() + ":" + port + ")";
    }
}
