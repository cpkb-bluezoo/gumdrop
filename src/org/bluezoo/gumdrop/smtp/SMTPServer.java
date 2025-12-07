/*
 * SMTPServer.java
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

package org.bluezoo.gumdrop.smtp;

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.Realm;
import org.bluezoo.gumdrop.util.CIDRNetwork;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;

/**
 * Connection factory for SMTP connections on a given port.
 * This connector supports both standard SMTP (port 25) and submission 
 * service (port 587), with transparent SSL/TLS support for SMTPS.
 *
 * <p>SMTP-specific features include:
 * <ul>
 * <li>CIDR-based network filtering (allow/block lists)</li>
 * <li>Connection rate limiting (inherited from Server)</li>
 * <li>Authentication rate limiting (inherited from Server)</li>
 * <li>Optional authentication requirement (MSA mode)</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5321">RFC 5321 - SMTP</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6409">RFC 6409 - Message Submission</a>
 */
public class SMTPServer extends Server {

    private static final Logger LOGGER = Logger.getLogger(SMTPServer.class.getName());

    /**
     * The default SMTP port (standard mail transfer).
     */
    protected static final int SMTP_DEFAULT_PORT = 25;

    /**
     * The default SMTP submission port (authenticated mail submission).
     */
    protected static final int SMTP_SUBMISSION_PORT = 587;

    /**
     * The default SMTPS port (SMTP over SSL/TLS).
     */
    protected static final int SMTPS_DEFAULT_PORT = 465;

    protected int port = -1;
    protected long maxMessageSize = 35882577; // ~35MB default, configurable
    protected Realm realm; // Authentication realm for SMTP AUTH
    protected SMTPConnectionHandlerFactory handlerFactory; // Factory for creating per-connection handlers

    // Connection filtering and policy settings
    protected boolean authRequired = false; // Force authentication (MSA mode)

    // Parsed CIDR networks for efficient lookup (SMTP-specific feature)
    private final List<CIDRNetwork> allowedCIDRs = new ArrayList<CIDRNetwork>();
    private final List<CIDRNetwork> blockedCIDRs = new ArrayList<CIDRNetwork>();

    /**
     * Returns a short description of this connector.
     */
    @Override
    public String getDescription() {
        return secure ? "smtps" : "smtp";
    }

    /**
     * Returns the port number this connector is bound to.
     */
    @Override
    public int getPort() {
        return port;
    }

    /**
     * Sets the port number this connector should bind to.
     * @param port the port number
     */
    public void setPort(int port) {
        this.port = port;
    }

    /**
     * Returns the maximum message size in bytes.
     * @return the maximum message size
     */
    public long getMaxMessageSize() {
        return maxMessageSize;
    }

    /**
     * Sets the maximum message size in bytes.
     * @param maxMessageSize the maximum message size
     */
    public void setMaxMessageSize(long maxMessageSize) {
        this.maxMessageSize = maxMessageSize;
    }

    /**
     * Returns the authentication realm.
     * @return the realm for SMTP authentication, or null if no authentication
     */
    public Realm getRealm() {
        return realm;
    }

    /**
     * Sets the authentication realm for SMTP AUTH.
     * @param realm the realm to use for authentication
     */
    public void setRealm(Realm realm) {
        this.realm = realm;
    }

    /**
     * Sets the factory for creating SMTP connection handlers.
     * Each new connection will get its own handler instance from this factory,
     * ensuring thread safety and state isolation between connections.
     * 
     * @param factory the factory to create handler instances, or null for default behavior
     */
    public void setHandlerFactory(SMTPConnectionHandlerFactory factory) {
        this.handlerFactory = factory;
    }

    /**
     * Returns the configured SMTP connection handler factory.
     * @return the factory or null if none configured
     */
    public SMTPConnectionHandlerFactory getHandlerFactory() {
        return handlerFactory;
    }

    /**
     * Returns whether authentication is required for this connector.
     * @return true if AUTH is mandatory, false if optional
     */
    public boolean isAuthRequired() {
        return authRequired;
    }

    /**
     * Sets whether authentication is required.
     * This should be true for Message Submission (port 587), false for MTA (port 25).
     * @param authRequired true to require AUTH command before accepting mail
     */
    public void setAuthRequired(boolean authRequired) {
        this.authRequired = authRequired;
    }

    /**
     * Sets allowed networks in CIDR notation (comma-separated).
     * Supports both IPv4 and IPv6 networks.
     * @param allowedNetworks networks to allow (e.g., "192.168.1.0/24,2001:db8::/32,10.0.0.0/8")
     * @throws IllegalArgumentException if any CIDR format is invalid
     */
    public void setAllowedNetworks(String allowedNetworks) {
        allowedCIDRs.clear();
        try {
            allowedCIDRs.addAll(CIDRNetwork.parseList(allowedNetworks));
            if (LOGGER.isLoggable(Level.INFO)) {
                LOGGER.info("Configured " + allowedCIDRs.size() + " allowed CIDR networks: " + allowedCIDRs);
            }
        } catch (IllegalArgumentException e) {
            LOGGER.log(Level.SEVERE, "Invalid allowed networks configuration: " + allowedNetworks, e);
            throw e; // Re-throw to fail configuration
        }
    }

    /**
     * Sets blocked networks in CIDR notation (comma-separated).
     * Supports both IPv4 and IPv6 networks.
     * @param blockedNetworks networks to block (e.g., "192.168.100.0/24,2001:db8:bad::/48")
     * @throws IllegalArgumentException if any CIDR format is invalid
     */
    public void setBlockedNetworks(String blockedNetworks) {
        blockedCIDRs.clear();
        try {
            blockedCIDRs.addAll(CIDRNetwork.parseList(blockedNetworks));
            if (LOGGER.isLoggable(Level.INFO)) {
                LOGGER.info("Configured " + blockedCIDRs.size() + " blocked CIDR networks: " + blockedCIDRs);
            }
        } catch (IllegalArgumentException e) {
            LOGGER.log(Level.SEVERE, "Invalid blocked networks configuration: " + blockedNetworks, e);
            throw e; // Re-throw to fail configuration
        }
    }

    /**
     * Starts this connector, setting default port if not specified.
     */
    @Override
    protected void start() {
        super.start();
        if (port <= 0) {
            // Use standard SMTP port (25) for non-secure, SMTPS port (465) for secure
            // Note: Port 587 (submission) is typically used for authenticated clients
            // and may or may not use TLS (STARTTLS), so we default to 25/465
            port = secure ? SMTPS_DEFAULT_PORT : SMTP_DEFAULT_PORT;
        }
    }

    /**
     * Stops this connector.
     */
    @Override
    protected void stop() {
        super.stop();
    }

    /**
     * Creates a new SMTP connection for the given socket channel.
     * For STARTTLS support: if secure=false but engine!=null, the connection 
     * starts as plaintext but can be upgraded with STARTTLS.
     * @param channel the socket channel for the client connection
     * @param engine the SSL engine (null if no SSL context, non-null if STARTTLS-capable)
     * @return a new SMTPConnection instance
     */
    @Override
    protected Connection newConnection(SocketChannel channel, SSLEngine engine) {
        // Create a new handler instance for this connection (thread safety)
        SMTPConnectionHandler handler = null;
        if (handlerFactory != null) {
            try {
                handler = handlerFactory.createHandler();
            } catch (Exception e) {
                // Log error but don't fail connection creation
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.log(Level.WARNING, "Failed to create SMTP handler, using default behavior", e);
                }
            }
        }
        
        return new SMTPConnection(this, channel, engine, isSecure(), handler);
    }

    /**
     * Checks if SSL/TLS context is available for STARTTLS.
     * @return true if STARTTLS is supported, false otherwise
     */
    protected boolean isSTARTTLSAvailable() {
        return context != null;
    }

    /**
     * Determines whether to accept a connection from the specified remote address.
     * Implements SMTP-specific filtering policies including:
     * <ul>
     * <li>Network allow/block lists (pre-parsed CIDR blocks for fast matching)</li>
     * <li>Rate limiting (via base Server class)</li>
     * </ul>
     *
     * @param remoteAddress the remote socket address attempting to connect
     * @return true to accept the connection, false to reject it
     */
    @Override
    public boolean acceptConnection(InetSocketAddress remoteAddress) {
        InetAddress clientIP = remoteAddress.getAddress();
        
        // 1. Check blocked networks first (explicit deny) - fast CIDR matching
        if (CIDRNetwork.matchesAny(clientIP, blockedCIDRs)) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Connection rejected from " + clientIP + ": blocked network");
            }
            return false;
        }
        
        // 2. Check allowed networks (if configured, explicit allow only) - fast CIDR matching
        if (!allowedCIDRs.isEmpty() && !CIDRNetwork.matchesAny(clientIP, allowedCIDRs)) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Connection rejected from " + clientIP + ": not in allowed networks");
            }
            return false;
        }
        
        // 3. Delegate to base class for rate limiting
        return super.acceptConnection(remoteAddress);
    }
}
