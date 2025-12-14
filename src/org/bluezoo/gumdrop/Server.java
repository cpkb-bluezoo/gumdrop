/*
 * Server.java
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

import org.bluezoo.gumdrop.ratelimit.AuthenticationRateLimiter;
import org.bluezoo.gumdrop.ratelimit.ConnectionRateLimiter;
import org.bluezoo.gumdrop.util.CIDRNetwork;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.nio.channels.ServerSocketChannel;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Abstract base class for server connectors that listen on ports.
 * A server in Gumdrop terminology corresponds exactly to a server in 
 * common internet parlance: it is a service running and listening on 
 * a specific port for client connections.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class Server extends Connector {

    private static final Logger LOGGER = Logger.getLogger(Server.class.getName());

    /** Default connection idle timeout: 5 minutes */
    public static final long DEFAULT_IDLE_TIMEOUT_MS = 5 * 60 * 1000;

    /** Default read timeout: 30 seconds */
    public static final long DEFAULT_READ_TIMEOUT_MS = 30 * 1000;

    /** Default connection timeout for initial handshake: 60 seconds */
    public static final long DEFAULT_CONNECTION_TIMEOUT_MS = 60 * 1000;

    // Server-specific fields (moved from Connector)
    private List<ServerSocketChannel> serverChannels = new ArrayList<ServerSocketChannel>();
    private Set<InetAddress> addresses = null;
    protected boolean needClientAuth = false;

    // Connection lifecycle configuration
    private long idleTimeoutMs = DEFAULT_IDLE_TIMEOUT_MS;
    private long readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
    private long connectionTimeoutMs = DEFAULT_CONNECTION_TIMEOUT_MS;

    // Rate limiting (optional, created on demand)
    private ConnectionRateLimiter connectionRateLimiter;
    private AuthenticationRateLimiter authRateLimiter;

    // CIDR network filtering (parsed for efficient lookup)
    private final List<CIDRNetwork> allowedCIDRs = new ArrayList<CIDRNetwork>();
    private final List<CIDRNetwork> blockedCIDRs = new ArrayList<CIDRNetwork>();

    protected Server() {
        super();
    }

    /**
     * Configures the addresses this server should bind to.
     */
    public void setAddresses(String value) {
        if (value == null) {
            addresses = null;
            return;
        }
        addresses = new LinkedHashSet<>();
        StringTokenizer st = new StringTokenizer(value);
        while (st.hasMoreTokens()) {
            String host = st.nextToken();
            try {
                addresses.add(InetAddress.getByName(host));
            } catch (UnknownHostException e) {
                String message = Gumdrop.L10N.getString("err.unknown_host");
                message = MessageFormat.format(message, host);
                LOGGER.log(Level.SEVERE, message, e);
            }
        }
    }

    public List<ServerSocketChannel> getServerChannels() {
        return serverChannels;
    }

    void addServerChannel(ServerSocketChannel channel) {
        serverChannels.add(channel);
    }

    void closeServerChannels() throws IOException {
        for (Iterator<ServerSocketChannel> i = serverChannels.iterator(); i.hasNext(); ) {
            i.next().close();
        }
    }

    public void setNeedClientAuth(boolean flag) {
        needClientAuth = flag;
    }

    // -- Connection Lifecycle Configuration --

    /**
     * Returns the idle timeout in milliseconds.
     * Connections with no activity for this duration may be closed.
     *
     * @return the idle timeout in milliseconds
     */
    public long getIdleTimeoutMs() {
        return idleTimeoutMs;
    }

    /**
     * Sets the idle timeout in milliseconds.
     * Connections with no activity for this duration may be closed.
     *
     * @param idleTimeoutMs the idle timeout in milliseconds (0 to disable)
     */
    public void setIdleTimeoutMs(long idleTimeoutMs) {
        this.idleTimeoutMs = idleTimeoutMs;
    }

    /**
     * Sets the idle timeout using a string with optional time unit suffix.
     * Supported suffixes: ms, s, m, h (milliseconds, seconds, minutes, hours).
     * Examples: "30s", "5m", "300000ms"
     *
     * @param timeout the timeout string
     */
    public void setIdleTimeout(String timeout) {
        this.idleTimeoutMs = parseTimeout(timeout);
    }

    /**
     * Returns the read timeout in milliseconds.
     * If no data is received for this duration during an active read, the
     * connection may be closed.
     *
     * @return the read timeout in milliseconds
     */
    public long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    /**
     * Sets the read timeout in milliseconds.
     *
     * @param readTimeoutMs the read timeout in milliseconds (0 to disable)
     */
    public void setReadTimeoutMs(long readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    /**
     * Sets the read timeout using a string with optional time unit suffix.
     *
     * @param timeout the timeout string
     */
    public void setReadTimeout(String timeout) {
        this.readTimeoutMs = parseTimeout(timeout);
    }

    /**
     * Returns the connection timeout in milliseconds.
     * This is the maximum time allowed for initial connection establishment
     * including TLS handshake.
     *
     * @return the connection timeout in milliseconds
     */
    public long getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    /**
     * Sets the connection timeout in milliseconds.
     *
     * @param connectionTimeoutMs the connection timeout in milliseconds (0 to disable)
     */
    public void setConnectionTimeoutMs(long connectionTimeoutMs) {
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    /**
     * Sets the connection timeout using a string with optional time unit suffix.
     *
     * @param timeout the timeout string
     */
    public void setConnectionTimeout(String timeout) {
        this.connectionTimeoutMs = parseTimeout(timeout);
    }

    /**
     * Parses a timeout string with optional time unit suffix.
     *
     * @param timeout the timeout string (e.g., "30s", "5m", "1h", "5000ms")
     * @return the timeout in milliseconds
     */
    private long parseTimeout(String timeout) {
        if (timeout == null || timeout.isEmpty()) {
            return 0;
        }
        timeout = timeout.trim().toLowerCase();

        long multiplier = 1;
        String numPart = timeout;

        if (timeout.endsWith("ms")) {
            numPart = timeout.substring(0, timeout.length() - 2);
            multiplier = 1;
        } else if (timeout.endsWith("s")) {
            numPart = timeout.substring(0, timeout.length() - 1);
            multiplier = 1000;
        } else if (timeout.endsWith("m")) {
            numPart = timeout.substring(0, timeout.length() - 1);
            multiplier = 60 * 1000;
        } else if (timeout.endsWith("h")) {
            numPart = timeout.substring(0, timeout.length() - 1);
            multiplier = 60 * 60 * 1000;
        }

        try {
            return Long.parseLong(numPart.trim()) * multiplier;
        } catch (NumberFormatException e) {
            LOGGER.warning("Invalid timeout format: " + timeout);
            return 0;
        }
    }

    // ========== Rate Limiting Configuration ==========

    /**
     * Sets the maximum concurrent connections allowed per IP address.
     *
     * <p>When set to a positive value, connections from an IP address will be
     * rejected if that IP already has the maximum number of active connections.
     * Set to 0 to disable concurrent connection limiting.
     *
     * @param max the maximum concurrent connections per IP (0 to disable)
     */
    public void setMaxConnectionsPerIP(int max) {
        ensureConnectionRateLimiter().setMaxConcurrentPerIP(max);
    }

    /**
     * Sets the connection rate limit.
     *
     * <p>Format: {@code count/duration}, where duration supports suffixes:
     * <ul>
     * <li>{@code s} - seconds</li>
     * <li>{@code m} - minutes</li>
     * <li>{@code h} - hours</li>
     * </ul>
     *
     * <p>Examples: {@code 100/60s} (100 per minute), {@code 1000/1h} (1000 per hour)
     *
     * @param rateLimit the rate limit string
     */
    public void setRateLimit(String rateLimit) {
        ensureConnectionRateLimiter().setRateLimit(rateLimit);
    }

    /**
     * Sets the maximum failed authentication attempts before lockout.
     *
     * @param max the maximum failures before lockout
     */
    public void setMaxAuthFailures(int max) {
        ensureAuthRateLimiter().setMaxFailures(max);
    }

    /**
     * Sets the authentication lockout duration.
     *
     * <p>Format: duration with suffix: {@code s} (seconds), {@code m} (minutes),
     * {@code h} (hours). Example: {@code 5m} (5 minutes)
     *
     * @param duration the lockout duration
     */
    public void setAuthLockoutTime(String duration) {
        ensureAuthRateLimiter().setLockoutTime(duration);
    }

    /**
     * Returns the connection rate limiter, or null if not configured.
     *
     * @return the connection rate limiter
     */
    public ConnectionRateLimiter getConnectionRateLimiter() {
        return connectionRateLimiter;
    }

    /**
     * Returns the authentication rate limiter, or null if not configured.
     *
     * @return the authentication rate limiter
     */
    public AuthenticationRateLimiter getAuthRateLimiter() {
        return authRateLimiter;
    }

    /**
     * Ensures the connection rate limiter is created.
     */
    private synchronized ConnectionRateLimiter ensureConnectionRateLimiter() {
        if (connectionRateLimiter == null) {
            connectionRateLimiter = new ConnectionRateLimiter();
        }
        return connectionRateLimiter;
    }

    /**
     * Ensures the authentication rate limiter is created.
     */
    private synchronized AuthenticationRateLimiter ensureAuthRateLimiter() {
        if (authRateLimiter == null) {
            authRateLimiter = new AuthenticationRateLimiter();
        }
        return authRateLimiter;
    }

    // ========== CIDR Network Filtering ==========

    /**
     * Sets allowed networks in CIDR notation (comma-separated).
     * Supports both IPv4 and IPv6 networks.
     *
     * <p>When configured, only connections from these networks will be accepted.
     * If no allowed networks are configured, connections from any network are
     * accepted (unless blocked).
     *
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
            throw e;
        }
    }

    /**
     * Sets blocked networks in CIDR notation (comma-separated).
     * Supports both IPv4 and IPv6 networks.
     *
     * <p>Connections from blocked networks are rejected before any other
     * filtering rules are applied.
     *
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
            throw e;
        }
    }

    /**
     * Returns the list of allowed CIDR networks.
     *
     * @return unmodifiable list of allowed networks
     */
    protected List<CIDRNetwork> getAllowedCIDRs() {
        return java.util.Collections.unmodifiableList(allowedCIDRs);
    }

    /**
     * Returns the list of blocked CIDR networks.
     *
     * @return unmodifiable list of blocked networks
     */
    protected List<CIDRNetwork> getBlockedCIDRs() {
        return java.util.Collections.unmodifiableList(blockedCIDRs);
    }

    /**
     * Determines whether to accept a connection from the specified remote address.
     * This method is called for each incoming connection before resources are allocated.
     *
     * <p>The default implementation applies the following filters in order:
     * <ol>
     *   <li>Blocked CIDR networks (explicit deny)</li>
     *   <li>Allowed CIDR networks (if configured, explicit allow only)</li>
     *   <li>Connection rate limiting (if configured)</li>
     * </ol>
     *
     * <p>Subclasses can override to add additional filtering policies but should call
     * {@code super.acceptConnection(remoteAddress)} to apply standard filtering.
     * 
     * @param remoteAddress the remote socket address attempting to connect
     * @return true to accept the connection, false to reject it
     */
    public boolean acceptConnection(InetSocketAddress remoteAddress) {
        InetAddress clientIP = remoteAddress.getAddress();

        // 1. Check blocked networks first (explicit deny) - fast CIDR matching
        if (!blockedCIDRs.isEmpty() && CIDRNetwork.matchesAny(clientIP, blockedCIDRs)) {
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

        // 3. Check connection rate limiter if configured
        if (connectionRateLimiter != null) {
            if (!connectionRateLimiter.allowConnection(clientIP)) {
                return false;
            }
            connectionRateLimiter.connectionOpened(clientIP);
        }

        return true;
    }

    /**
     * Called when a connection is closed to update rate limiting tracking.
     *
     * <p>Subclasses should call this method when connections are closed to maintain
     * accurate concurrent connection counts.
     *
     * @param remoteAddress the remote address of the closed connection
     */
    public void connectionClosed(InetSocketAddress remoteAddress) {
        if (connectionRateLimiter != null && remoteAddress != null) {
            connectionRateLimiter.connectionClosed(remoteAddress.getAddress());
        }
    }

    /**
     * Checks if the specified IP address is locked out due to failed authentication.
     *
     * @param ip the IP address to check
     * @return true if the IP is locked out
     */
    public boolean isAuthLocked(InetAddress ip) {
        return authRateLimiter != null && authRateLimiter.isLocked(ip);
    }

    /**
     * Records a failed authentication attempt.
     *
     * @param ip the IP address of the failed attempt
     */
    public void recordAuthFailure(InetAddress ip) {
        if (authRateLimiter != null) {
            authRateLimiter.recordFailure(ip);
        }
    }

    /**
     * Records a failed authentication attempt for a specific user.
     *
     * @param ip the IP address of the failed attempt
     * @param username the username that was attempted
     */
    public void recordAuthFailure(InetAddress ip, String username) {
        if (authRateLimiter != null) {
            authRateLimiter.recordFailure(ip, username);
        }
    }

    /**
     * Records a successful authentication, clearing any failure tracking.
     *
     * @param ip the IP address of the successful authentication
     */
    public void recordAuthSuccess(InetAddress ip) {
        if (authRateLimiter != null) {
            authRateLimiter.recordSuccess(ip);
        }
    }

    /**
     * Returns the IP addresses this server should be bound to.
     */
    protected Set<InetAddress> getAddresses() throws IOException {
        if (addresses == null) {
            addresses = new LinkedHashSet<>();
            for (Enumeration<NetworkInterface> e1 = NetworkInterface.getNetworkInterfaces(); e1.hasMoreElements(); ) {
                NetworkInterface ni = e1.nextElement();
                for (Enumeration<InetAddress> e2 = ni.getInetAddresses(); e2.hasMoreElements(); ) {
                    addresses.add(e2.nextElement());
                }
            }
        }
        return addresses;
    }

    /**
     * Returns the port number this server should be bound to.
     */
    protected abstract int getPort();

    /**
     * Override to configure SSL engine for server-side use with client auth settings.
     */
    @Override
    protected void configureSSLEngine(javax.net.ssl.SSLEngine engine) {
        super.configureSSLEngine(engine); // Sets useClientMode(false)
        engine.setUseClientMode(false); // we are a server
        if (needClientAuth) {
            engine.setNeedClientAuth(true);
        } else {
            engine.setWantClientAuth(true);
        }
    }
}
