/*
 * LDAPClient.java
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

package org.bluezoo.gumdrop.ldap.client;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;

import org.bluezoo.gumdrop.Client;
import org.bluezoo.gumdrop.ClientHandler;
import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.SelectorLoop;

/**
 * LDAP client implementation that creates and manages LDAP client connections.
 *
 * <p>This class extends {@link Client} to provide LDAP-specific client
 * functionality for connecting to LDAP servers and creating connection
 * instances.
 *
 * <h4>Standalone Usage</h4>
 * <pre>{@code
 * // Simple - no Gumdrop setup needed
 * LDAPClient client = new LDAPClient("ldap.example.com", 389);
 * client.connect(new LDAPConnectionReady() {
 *     public void handleReady(LDAPConnected connection) {
 *         connection.bind("cn=admin,dc=example,dc=com", "secret",
 *             new BindResultHandler() {
 *                 public void handleBindSuccess(LDAPSession session) {
 *                     // Perform searches, modifications, etc.
 *                     session.search(request, new MySearchHandler());
 *                 }
 *                 public void handleBindFailure(LDAPResult result, LDAPConnected conn) {
 *                     conn.unbind();
 *                 }
 *             });
 *     }
 *     // ... other callbacks
 * });
 * }</pre>
 *
 * <h4>Server Integration (with SelectorLoop affinity)</h4>
 * <pre>{@code
 * // Use the same SelectorLoop as your server connection
 * LDAPClient client = new LDAPClient(connection.getSelectorLoop(), "ldap.example.com", 389);
 * client.connect(handler);
 * }</pre>
 *
 * <h4>LDAPS (implicit TLS)</h4>
 * <pre>{@code
 * LDAPClient client = new LDAPClient("ldap.example.com", 636);
 * client.setSecure(true);
 * client.setKeystoreFile("/path/to/truststore.p12");
 * client.connect(handler);
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class LDAPClient extends Client {

    // ─────────────────────────────────────────────────────────────────────────
    // Constructors without SelectorLoop (standalone usage)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates an LDAP client that will connect to the specified host and port.
     *
     * <p>The Gumdrop infrastructure is managed automatically - it starts
     * when {@link #connect} is called and stops when all connections close.
     *
     * @param host the LDAP server host as a String
     * @param port the LDAP server port number (typically 389 or 636)
     * @throws UnknownHostException if the host cannot be resolved
     */
    public LDAPClient(String host, int port) throws UnknownHostException {
        super(host, port);
    }

    /**
     * Creates an LDAP client that will connect to the default LDAP port (389).
     *
     * @param host the LDAP server host as a String
     * @throws UnknownHostException if the host cannot be resolved
     */
    public LDAPClient(String host) throws UnknownHostException {
        super(host, LDAPConstants.DEFAULT_PORT);
    }

    /**
     * Creates an LDAP client that will connect to the specified host and port.
     *
     * @param host the LDAP server host as an InetAddress
     * @param port the LDAP server port number (typically 389 or 636)
     */
    public LDAPClient(InetAddress host, int port) {
        super(host, port);
    }

    /**
     * Creates an LDAP client that will connect to the default LDAP port (389).
     *
     * @param host the LDAP server host as an InetAddress
     */
    public LDAPClient(InetAddress host) {
        super(host, LDAPConstants.DEFAULT_PORT);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constructors with SelectorLoop (server integration)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates an LDAP client that will connect to the specified host and port,
     * using the provided SelectorLoop for I/O.
     *
     * <p>Use this constructor for server integration where you want the
     * client to share a SelectorLoop with server connections for efficiency.
     *
     * @param selectorLoop the SelectorLoop for connection I/O
     * @param host the LDAP server host as a String
     * @param port the LDAP server port number (typically 389 or 636)
     * @throws UnknownHostException if the host cannot be resolved
     */
    public LDAPClient(SelectorLoop selectorLoop, String host, int port) throws UnknownHostException {
        super(selectorLoop, host, port);
    }

    /**
     * Creates an LDAP client that will connect to the default LDAP port (389),
     * using the provided SelectorLoop for I/O.
     *
     * @param selectorLoop the SelectorLoop for connection I/O
     * @param host the LDAP server host as a String
     * @throws UnknownHostException if the host cannot be resolved
     */
    public LDAPClient(SelectorLoop selectorLoop, String host) throws UnknownHostException {
        super(selectorLoop, host, LDAPConstants.DEFAULT_PORT);
    }

    /**
     * Creates an LDAP client that will connect to the specified host and port,
     * using the provided SelectorLoop for I/O.
     *
     * @param selectorLoop the SelectorLoop for connection I/O
     * @param host the LDAP server host as an InetAddress
     * @param port the LDAP server port number (typically 389 or 636)
     */
    public LDAPClient(SelectorLoop selectorLoop, InetAddress host, int port) {
        super(selectorLoop, host, port);
    }

    /**
     * Creates an LDAP client that will connect to the default LDAP port (389),
     * using the provided SelectorLoop for I/O.
     *
     * @param selectorLoop the SelectorLoop for connection I/O
     * @param host the LDAP server host as an InetAddress
     */
    public LDAPClient(SelectorLoop selectorLoop, InetAddress host) {
        super(selectorLoop, host, LDAPConstants.DEFAULT_PORT);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Connection factory
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a new LDAP client connection with a handler.
     *
     * @param channel the socket channel for the connection
     * @param engine optional SSL engine for secure connections
     * @param handler the client handler to receive LDAP events (must be {@link LDAPConnectionReady})
     * @return a new LDAPClientConnection instance
     * @throws ClassCastException if handler is not an LDAPConnectionReady
     */
    @Override
    protected Connection newConnection(SocketChannel channel, SSLEngine engine, ClientHandler handler) {
        return new LDAPClientConnection(this, engine, secure, (LDAPConnectionReady) handler);
    }

    @Override
    public String getDescription() {
        return "LDAP Client (" + host.getHostAddress() + ":" + port + ")";
    }

}
