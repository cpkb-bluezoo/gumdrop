/*
 * SMTPServer.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.bluezoo.gumdrop.smtp;

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.Realm;
import org.bluezoo.gumdrop.util.CIDRNetwork;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;

/**
 * Connection factory for SMTP connections on a given port.
 * This connector supports both standard SMTP (port 25) and submission 
 * service (port 587), with transparent SSL/TLS support for SMTPS.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see https://www.rfc-editor.org/rfc/rfc5321 (SMTP)
 * @see https://www.rfc-editor.org/rfc/rfc6409 (Message Submission)
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
    protected boolean authRequired = false;                    // Force authentication (MSA mode)
    protected int maxConnectionsPerIP = 10;                   // Maximum concurrent connections per IP
    protected long connectionWindowMillis = 60000;           // Time window for rate limiting (1 minute)
    protected int maxConnectionsPerWindow = 100;             // Maximum connections per IP per window

    // Parsed CIDR networks for efficient lookup
    private final List<CIDRNetwork> allowedCIDRs = new ArrayList<>();
    private final List<CIDRNetwork> blockedCIDRs = new ArrayList<>();

    // Runtime state for connection tracking
    private final ConcurrentMap<InetAddress, Integer> activeConnections = new ConcurrentHashMap<>();
    private final ConcurrentMap<InetAddress, ConnectionWindow> connectionWindows = new ConcurrentHashMap<>();


    /**
     * Tracks connection rate limiting for an IP address.
     */
    private static class ConnectionWindow {
        private final long[] connectionTimes;
        private int index = 0;
        private int count = 0;

        ConnectionWindow(int maxConnections) {
            this.connectionTimes = new long[maxConnections];
        }

        synchronized boolean allowConnection(long now, long windowMillis) {
            // Remove old connections outside the window
            long cutoff = now - windowMillis;
            while (count > 0 && connectionTimes[(index - count + connectionTimes.length) % connectionTimes.length] < cutoff) {
                count--;
            }

            if (count >= connectionTimes.length) {
                return false; // Rate limit exceeded
            }

            // Add this connection
            connectionTimes[index] = now;
            index = (index + 1) % connectionTimes.length;
            count++;
            return true;
        }
    }

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
     * Sets the maximum concurrent connections per IP address.
     * @param maxConnectionsPerIP maximum concurrent connections (default 10)
     */
    public void setMaxConnectionsPerIP(int maxConnectionsPerIP) {
        this.maxConnectionsPerIP = maxConnectionsPerIP;
    }

    /**
     * Sets the rate limiting parameters.
     * @param maxConnections maximum connections per window
     * @param windowSeconds time window in seconds
     */
    public void setRateLimit(int maxConnections, int windowSeconds) {
        this.maxConnectionsPerWindow = maxConnections;
        this.connectionWindowMillis = windowSeconds * 1000L;
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
     * - Network allow/block lists (pre-parsed CIDR blocks for fast matching)
     * - Concurrent connection limits per IP
     * - Rate limiting per IP address
     * - Different policies for MTA vs MSA ports
     *
     * @param remoteAddress the remote socket address attempting to connect
     * @return true to accept the connection, false to reject it
     */
    @Override
    public boolean acceptConnection(InetSocketAddress remoteAddress) {
        InetAddress clientIP = remoteAddress.getAddress();
        
        // 1. Check blocked networks first (explicit deny) - fast CIDR matching
        if (isInCIDRList(clientIP, blockedCIDRs)) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Connection rejected from " + clientIP + ": blocked network");
            }
            return false;
        }
        
        // 2. Check allowed networks (if configured, explicit allow only) - fast CIDR matching
        if (!allowedCIDRs.isEmpty() && !isInCIDRList(clientIP, allowedCIDRs)) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Connection rejected from " + clientIP + ": not in allowed networks");
            }
            return false;
        }
        
        // 3. Check concurrent connection limit per IP
        Integer currentConnections = activeConnections.get(clientIP);
        if (currentConnections != null && currentConnections >= maxConnectionsPerIP) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Connection rejected from " + clientIP + ": too many concurrent connections (" + 
                           currentConnections + "/" + maxConnectionsPerIP + ")");
            }
            return false;
        }
        
        // 4. Check rate limiting
        long now = System.currentTimeMillis();
        ConnectionWindow window = connectionWindows.computeIfAbsent(clientIP, 
            k -> new ConnectionWindow(maxConnectionsPerWindow));
        
        if (!window.allowConnection(now, connectionWindowMillis)) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Connection rejected from " + clientIP + ": rate limit exceeded");
            }
            return false;
        }
        
        // 5. Connection accepted - increment active connection count
        activeConnections.merge(clientIP, 1, Integer::sum);
        
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest("Connection accepted from " + clientIP + 
                         " (active: " + activeConnections.get(clientIP) + "/" + maxConnectionsPerIP + ")");
        }
        
        return true;
    }

    /**
     * Called when a connection is closed to update connection tracking.
     * @param remoteAddress the address of the closed connection
     */
    public void connectionClosed(InetSocketAddress remoteAddress) {
        InetAddress clientIP = remoteAddress.getAddress();
        activeConnections.computeIfPresent(clientIP, (k, v) -> {
            int newCount = v - 1;
            return newCount > 0 ? newCount : null; // Remove if zero
        });
        
        if (LOGGER.isLoggable(Level.FINEST)) {
            Integer remaining = activeConnections.get(clientIP);
            LOGGER.finest("Connection closed from " + clientIP + 
                         " (remaining: " + (remaining != null ? remaining : 0) + ")");
        }
    }

    /**
     * Fast check if an IP address matches any CIDR network in the given list.
     * Uses pre-parsed CIDRNetwork objects for optimal performance.
     * 
     * @param ip the IP address to check
     * @param cidrList the list of pre-parsed CIDR networks
     * @return true if the IP matches any network in the list
     */
    private boolean isInCIDRList(InetAddress ip, List<CIDRNetwork> cidrList) {
        // Use the optimized utility method from CIDRNetwork
        return CIDRNetwork.matchesAny(ip, cidrList);
    }


}
