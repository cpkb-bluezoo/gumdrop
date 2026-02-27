/*
 * Listener.java
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

package org.bluezoo.gumdrop;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.text.MessageFormat;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;

import org.bluezoo.gumdrop.ratelimit.AuthenticationRateLimiter;
import org.bluezoo.gumdrop.ratelimit.ConnectionRateLimiter;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.util.CIDRNetwork;

/**
 * Common base class for all server endpoint types (TCP and UDP).
 *
 * <p>Holds transport-agnostic configuration shared by both
 * {@link TCPListener} (TCP) and {@link UDPListener} (UDP):
 * port, addresses, TLS/DTLS settings, rate limiting, CIDR
 * allow/block lists, and timeouts.
 *
 * <p>Subclasses override {@link #createTransportFactory()} to select
 * the appropriate transport (TCP, UDP, or QUIC).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see TCPListener
 * @see UDPListener
 */
public abstract class Listener {

    private static final Logger LOGGER =
            Logger.getLogger(Listener.class.getName());

    /** Default maximum network input buffer size: 1 MB */
    public static final int DEFAULT_MAX_NET_IN_SIZE = 1024 * 1024;

    /** Default connection idle timeout: 5 minutes */
    public static final long DEFAULT_IDLE_TIMEOUT_MS = 5 * 60 * 1000;

    /** Default read timeout: 30 seconds */
    public static final long DEFAULT_READ_TIMEOUT_MS = 30 * 1000;

    /** Default connection timeout for initial handshake: 60 seconds */
    public static final long DEFAULT_CONNECTION_TIMEOUT_MS = 60 * 1000;

    // ── Listener identity ──

    private String name;

    // ── Connector-level configuration ──

    protected boolean secure = false;
    protected SSLContext context;
    protected Path keystoreFile;
    protected String keystorePass;
    protected String keystoreFormat = "PKCS12";
    private String cipherSuites;
    private String namedGroups;
    protected TelemetryConfig telemetryConfig;
    private Map<String, String> sniHostnameToAlias;
    private String sniDefaultAlias;
    private int maxNetInSize = DEFAULT_MAX_NET_IN_SIZE;

    // ── Server-level configuration ──

    private Set<InetAddress> addresses = null;
    protected boolean needClientAuth = false;
    private long idleTimeoutMs = DEFAULT_IDLE_TIMEOUT_MS;
    private long readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
    private long connectionTimeoutMs = DEFAULT_CONNECTION_TIMEOUT_MS;
    private ConnectionRateLimiter connectionRateLimiter;
    private AuthenticationRateLimiter authRateLimiter;
    private List<CIDRNetwork> allowedNetworks;
    private List<CIDRNetwork> blockedNetworks;

    // ── Transport factory (created at start) ──

    private TransportFactory transportFactory;

    protected Listener() {
    }

    // ═══════════════════════════════════════════════════════════════════
    // Listener identity
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Returns the name of this listener endpoint. The name is an
     * optional identifier (e.g., "mx", "submission") that the owning
     * service can use to vary behaviour per listener.
     *
     * @return the name, or null if not set
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the name of this listener endpoint.
     *
     * @param name the listener name
     */
    public void setName(String name) {
        this.name = name;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Connector-level setters (gumdroprc compatible)
    // ═══════════════════════════════════════════════════════════════════

    public TelemetryConfig getTelemetryConfig() {
        return telemetryConfig;
    }

    public void setTelemetryConfig(TelemetryConfig telemetryConfig) {
        this.telemetryConfig = telemetryConfig;
    }

    public boolean isTelemetryEnabled() {
        return telemetryConfig != null;
    }

    public int getMaxNetInSize() {
        return maxNetInSize;
    }

    public void setMaxNetInSize(int size) {
        this.maxNetInSize = size;
    }

    public boolean isSecure() {
        return secure;
    }

    public void setSecure(boolean flag) {
        secure = flag;
    }

    public void setKeystoreFile(Path file) {
        keystoreFile = file;
    }

    public void setKeystoreFile(String file) {
        keystoreFile = Path.of(file);
    }

    public void setKeystorePass(String pass) {
        keystorePass = pass;
    }

    public void setKeystoreFormat(String format) {
        keystoreFormat = format;
    }

    /**
     * Sets the TLS 1.3 cipher suites (colon-separated IANA names).
     *
     * @param cipherSuites colon-separated cipher suite names, or null
     *                     to use the default set
     */
    public void setCipherSuites(String cipherSuites) {
        this.cipherSuites = cipherSuites;
    }

    /**
     * Sets the allowed key exchange groups / named curves
     * (colon-separated).
     *
     * @param namedGroups colon-separated group names, or null to use
     *                    the default set
     */
    public void setNamedGroups(String namedGroups) {
        this.namedGroups = namedGroups;
    }

    public void setSSLContext(SSLContext context) {
        this.context = context;
    }

    public SSLContext getSSLContext() {
        return context;
    }

    public void setSniHostnames(Map<String, String> hostnames) {
        this.sniHostnameToAlias = hostnames != null
                ? new LinkedHashMap<String, String>(hostnames)
                : null;
    }

    public void setSniDefaultAlias(String alias) {
        this.sniDefaultAlias = alias;
    }

    public boolean isSNIEnabled() {
        return sniHostnameToAlias != null && !sniHostnameToAlias.isEmpty();
    }

    protected boolean isMetricsEnabled() {
        return telemetryConfig != null && telemetryConfig.isMetricsEnabled();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Server-level setters (gumdroprc compatible)
    // ═══════════════════════════════════════════════════════════════════

    public void setAddresses(String value) {
        if (value != null && !value.isEmpty()) {
            addresses = new LinkedHashSet<InetAddress>();
            StringTokenizer st = new StringTokenizer(value, ", ");
            while (st.hasMoreTokens()) {
                String token = st.nextToken().trim();
                if (!token.isEmpty()) {
                    try {
                        addresses.add(InetAddress.getByName(token));
                    } catch (UnknownHostException e) {
                        LOGGER.warning(MessageFormat.format(
                                Gumdrop.L10N.getString("err.unknown_host"),
                                token));
                    }
                }
            }
        }
    }

    public void setNeedClientAuth(boolean flag) {
        needClientAuth = flag;
    }

    public long getIdleTimeoutMs() {
        return idleTimeoutMs;
    }

    public void setIdleTimeoutMs(long idleTimeoutMs) {
        this.idleTimeoutMs = idleTimeoutMs;
    }

    public void setIdleTimeout(String timeout) {
        this.idleTimeoutMs = parseDuration(timeout);
    }

    public long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(long readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public void setReadTimeout(String timeout) {
        this.readTimeoutMs = parseDuration(timeout);
    }

    public long getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public void setConnectionTimeoutMs(long connectionTimeoutMs) {
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    public void setConnectionTimeout(String timeout) {
        this.connectionTimeoutMs = parseDuration(timeout);
    }

    public void setMaxConnectionsPerIP(int max) {
        ensureConnectionRateLimiter();
        connectionRateLimiter.setMaxConcurrentPerIP(max);
    }

    /**
     * Notifies the connection rate limiter that a connection has closed.
     *
     * @param remoteAddress the remote address of the closed connection
     */
    public void connectionClosed(InetSocketAddress remoteAddress) {
        if (connectionRateLimiter != null && remoteAddress != null) {
            connectionRateLimiter.connectionClosed(remoteAddress.getAddress());
        }
    }

    /**
     * Notifies the connection rate limiter that a connection has closed.
     * Delegates to {@link #connectionClosed(InetSocketAddress)} for TCP
     * connections; no-op for UNIX domain socket connections (no IP to
     * track).
     *
     * @param remoteAddress the remote address of the closed connection
     */
    public void connectionClosed(SocketAddress remoteAddress) {
        if (remoteAddress instanceof InetSocketAddress) {
            connectionClosed((InetSocketAddress) remoteAddress);
        }
    }

    public void setRateLimit(String rateLimit) {
        ensureConnectionRateLimiter();
        connectionRateLimiter.setRateLimit(rateLimit);
    }

    public void setMaxAuthFailures(int max) {
        ensureAuthRateLimiter();
        authRateLimiter.setMaxFailures(max);
    }

    public void setAuthLockoutTime(String duration) {
        ensureAuthRateLimiter();
        authRateLimiter.setLockoutTime(duration);
    }

    public void setAllowedNetworks(String allowedNetworks) {
        if (allowedNetworks != null && !allowedNetworks.isEmpty()) {
            this.allowedNetworks = CIDRNetwork.parseList(allowedNetworks);
        }
    }

    public void setBlockedNetworks(String blockedNetworks) {
        if (blockedNetworks != null && !blockedNetworks.isEmpty()) {
            this.blockedNetworks = CIDRNetwork.parseList(blockedNetworks);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Abstract methods for protocol subclasses
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Returns the port for this listener, or {@code -1} if no port is
     * configured (e.g. for UNIX domain sockets).
     *
     * <p>Concrete protocol listeners override this to return their
     * configured port.
     *
     * @return the port number, or -1
     */
    public int getPort() {
        return -1;
    }

    /**
     * Returns the UNIX domain socket path for this listener, or
     * {@code null} if this listener uses TCP (port-based) binding.
     *
     * @return the socket path, or null
     */
    public String getPath() {
        return null;
    }

    /**
     * Returns a short description of this endpoint type
     * (e.g., "SMTP", "HTTP", "dns").
     *
     * @return the description
     */
    public abstract String getDescription();

    // ═══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Starts this endpoint. Creates the transport factory via
     * {@link #createTransportFactory()}, pushes configuration into it,
     * and calls {@link TransportFactory#start()} to initialise security.
     */
    public void start() {
        transportFactory = createTransportFactory();
        configureTransportFactory(transportFactory);
        transportFactory.start();
    }

    /**
     * Creates the transport factory for this endpoint.
     *
     * <p>The default implementation returns a {@link TCPTransportFactory}.
     * Subclasses override this to select a different transport.
     *
     * @return the transport factory
     */
    protected TransportFactory createTransportFactory() {
        return new TCPTransportFactory();
    }

    /**
     * Pushes this endpoint's configuration into the given transport
     * factory. Called by {@link #start()} before
     * {@link TransportFactory#start()}.
     *
     * @param factory the factory to configure
     */
    protected void configureTransportFactory(TransportFactory factory) {
        factory.setSecure(secure);
        if (keystoreFile != null) {
            factory.setKeystoreFile(keystoreFile);
        }
        if (keystorePass != null) {
            factory.setKeystorePass(keystorePass);
        }
        if (keystoreFormat != null) {
            factory.setKeystoreFormat(keystoreFormat);
        }
        if (telemetryConfig != null) {
            factory.setTelemetryConfig(telemetryConfig);
        }
        if (cipherSuites != null) {
            factory.setCipherSuites(cipherSuites);
        }
        if (namedGroups != null) {
            factory.setNamedGroups(namedGroups);
        }
        factory.setMaxNetInSize(maxNetInSize);

        if (factory instanceof TCPTransportFactory) {
            TCPTransportFactory tcpFactory = (TCPTransportFactory) factory;
            if (context != null) {
                tcpFactory.setSSLContext(context);
            }
            if (needClientAuth) {
                tcpFactory.setNeedClientAuth(true);
            }
            if (sniHostnameToAlias != null) {
                tcpFactory.setSniHostnames(sniHostnameToAlias);
            }
            if (sniDefaultAlias != null) {
                tcpFactory.setSniDefaultAlias(sniDefaultAlias);
            }
        }
    }

    /**
     * Stops this endpoint.
     */
    public void stop() {
        // Subclasses can override
    }

    // ═══════════════════════════════════════════════════════════════════
    // Accept path
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Returns the addresses this endpoint should listen on.
     * If none configured, returns all local addresses.
     * Returns an empty set for UNIX domain socket listeners.
     *
     * @return the set of addresses
     */
    public Set<InetAddress> getAddresses() {
        if (getPath() != null) {
            return Collections.emptySet();
        }
        if (addresses != null) {
            return addresses;
        }
        Set<InetAddress> all = new LinkedHashSet<InetAddress>();
        try {
            Enumeration<NetworkInterface> nics =
                    NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    all.add(addrs.nextElement());
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to enumerate network interfaces", e);
        }
        return all;
    }

    /**
     * Checks whether a connection from the given remote address should
     * be accepted, based on CIDR allow/block lists and rate limits.
     *
     * @param remoteAddress the remote address
     * @return true if the connection should be accepted
     */
    public boolean acceptConnection(SocketAddress remoteAddress) {
        if (!(remoteAddress instanceof InetSocketAddress)) {
            return true;
        }
        InetAddress addr =
                ((InetSocketAddress) remoteAddress).getAddress();

        if (blockedNetworks != null) {
            for (Iterator<CIDRNetwork> it = blockedNetworks.iterator();
                 it.hasNext(); ) {
                if (it.next().matches(addr)) {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.fine("Blocked connection from " + addr);
                    }
                    return false;
                }
            }
        }

        if (allowedNetworks != null) {
            boolean allowed = false;
            for (Iterator<CIDRNetwork> it = allowedNetworks.iterator();
                 it.hasNext(); ) {
                if (it.next().matches(addr)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Connection not in allowed networks: "
                            + addr);
                }
                return false;
            }
        }

        if (connectionRateLimiter != null) {
            if (!connectionRateLimiter.allowConnection(addr)) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Rate limit exceeded for " + addr);
                }
                return false;
            }
        }

        return true;
    }

    /**
     * Returns the transport factory used to create endpoints.
     *
     * @return the transport factory
     */
    public TransportFactory getTransportFactory() {
        return transportFactory;
    }

    /**
     * Returns the authentication rate limiter, or null if not configured.
     *
     * @return the authentication rate limiter
     */
    public AuthenticationRateLimiter getAuthRateLimiter() {
        return authRateLimiter;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Internal helpers
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Parses a duration string with optional time unit suffix.
     *
     * @param duration the duration string (e.g., "30s", "5m", "1h",
     *                 "5000ms")
     * @return the duration in milliseconds
     */
    protected static long parseDuration(String duration) {
        if (duration == null || duration.isEmpty()) {
            return 0;
        }
        duration = duration.trim().toLowerCase();

        long multiplier = 1;
        String numPart = duration;

        if (duration.endsWith("ms")) {
            numPart = duration.substring(0, duration.length() - 2);
            multiplier = 1;
        } else if (duration.endsWith("s")) {
            numPart = duration.substring(0, duration.length() - 1);
            multiplier = 1000;
        } else if (duration.endsWith("m")) {
            numPart = duration.substring(0, duration.length() - 1);
            multiplier = 60 * 1000;
        } else if (duration.endsWith("h")) {
            numPart = duration.substring(0, duration.length() - 1);
            multiplier = 60 * 60 * 1000;
        }

        try {
            return Long.parseLong(numPart.trim()) * multiplier;
        } catch (NumberFormatException e) {
            LOGGER.warning("Invalid duration format: " + duration);
            return 0;
        }
    }

    private void ensureConnectionRateLimiter() {
        if (connectionRateLimiter == null) {
            connectionRateLimiter = new ConnectionRateLimiter();
        }
    }

    private void ensureAuthRateLimiter() {
        if (authRateLimiter == null) {
            authRateLimiter = new AuthenticationRateLimiter();
        }
    }

}
