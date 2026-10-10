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

import org.bluezoo.gumdrop.tls.KeystoreFormat;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.text.MessageFormat;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.ratelimit.AuthenticationRateLimiter;
import org.bluezoo.gumdrop.ratelimit.ConnectionRateLimiter;
import org.bluezoo.gumdrop.quic.QuicTransportFactory;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.tls.ServerCredentials;
import org.bluezoo.gumdrop.tls.DtlsVersion;
import org.bluezoo.gumdrop.tls.TlsVersion;
import org.bluezoo.gumdrop.util.CidrNetwork;
import org.bluezoo.gumdrop.telemetry.EventLogger;
import java.util.ResourceBundle;
/**
 * Common base class for all server endpoint types (TCP and UDP).
 *
 * <p>Holds transport-agnostic configuration shared by both
 * {@link TcpListener} (TCP) and {@link UdpListener} (UDP):
 * port, addresses, TLS/DTLS settings, rate limiting, CIDR
 * allow/block lists, and timeouts.
 *
 * <p>Subclasses override {@link #createTransportFactory()} to select
 * the appropriate transport (TCP, UDP, or QUIC).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see TcpListener
 * @see UdpListener
 */
public abstract class Listener {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.L10N");

    private static final Logger LOGGER =
            Logger.getLogger(Listener.class.getName());

    /** Default maximum network input buffer size: 1 MB */
    public static final int DEFAULT_MAX_NET_IN_SIZE = 1024 * 1024;

    /** Default maximum network output buffer size: 4 MB */
    public static final int DEFAULT_MAX_NET_OUT_SIZE = 4 * 1024 * 1024;

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
    protected ServerCredentials serverCredentials;
    protected TlsVersion tlsVersion = TlsVersion.NEGOTIATE;
    protected DtlsVersion dtlsVersion = DtlsVersion.NEGOTIATE;
    private boolean dtlsRequireCookie;
    private byte[] dtlsCookieSecret;
    private int dtlsMaxFragmentSize;
    protected Path keystoreFile;
    protected String keystorePass;
    protected KeystoreFormat keystoreFormat = KeystoreFormat.PKCS12;
    protected Path certFile;
    protected Path keyFile;
    protected Path echConfigListFile;
    protected Path echPrivateKeyFile;
    protected boolean echServerRequired;
    private String cipherSuites;
    private String namedGroups;
    private Gumdrop gumdrop;
    private TelemetryConfig standaloneTelemetry;
    private Map<String, String> sniHostnameToAlias;
    private String sniDefaultAlias;
    private int maxNetInSize = DEFAULT_MAX_NET_IN_SIZE;
    private int maxNetOutSize = DEFAULT_MAX_NET_OUT_SIZE;

    // ── Server-level configuration ──

    private Set<InetAddress> addresses = null;
    private boolean wildcard = false;
    protected boolean needClientAuth = false;
    private X509TrustManager trustManager;
    private long idleTimeoutMs = DEFAULT_IDLE_TIMEOUT_MS;
    private long readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
    private long connectionTimeoutMs = DEFAULT_CONNECTION_TIMEOUT_MS;
    private ConnectionRateLimiter connectionRateLimiter;
    private AuthenticationRateLimiter authRateLimiter;
    private List<CidrNetwork> allowedNetworks;
    private List<CidrNetwork> blockedNetworks;

    /**
     * Global (per-listener) cap on the number of simultaneously accepted
     * connections. 0 disables the limit. This is the hard admission-control
     * backstop that protects a single instance from file-descriptor and
     * memory exhaustion regardless of source IP.
     */
    private int maxConnections = 0;

    /**
     * Maximum concurrent DTLS peers on one secure UDP listener socket.
     * {@code 0} means unlimited. See {@link UdpTransportFactory#getMaxDtlsPeers()}.
     */
    private int maxDtlsPeers = UdpTransportFactory.DEFAULT_MAX_DTLS_PEERS;

    /**
     * Number of currently open connections accepted by this listener.
     * Incremented on the accept thread and decremented (from worker threads)
     * when an endpoint closes, so it must be atomic.
     */
    private final AtomicInteger activeConnectionCount = new AtomicInteger();

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
     * @return this
     */
    public Listener name(String name) {
        this.name = name;
        return this;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Connector-level setters
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Returns the runtime this listener started under.
     *
     * @return the runtime, or null before {@link #start(Gumdrop)}
     */
    public Gumdrop getGumdrop() {
        return gumdrop;
    }

    /**
     * Returns the telemetry configuration of the runtime this listener
     * started under.
     *
     * @return the configuration, or null before {@link #start(Gumdrop)}
     */
    public TelemetryConfig getTelemetryConfig() {
        Gumdrop runtime = gumdrop;
        return runtime != null ? runtime.getTelemetryConfig() : null;
    }

    /**
     * Returns the telemetry configuration this listener's events go to:
     * its runtime's, else one of its own for a listener that has not
     * started under a runtime, which prints events through
     * {@code java.util.logging}. Never null.
     *
     * @return the configuration
     */
    protected synchronized TelemetryConfig eventTelemetry() {
        TelemetryConfig telemetry = getTelemetryConfig();
        if (telemetry != null) {
            return telemetry;
        }
        if (standaloneTelemetry == null) {
            standaloneTelemetry = new TelemetryConfig();
        }
        return standaloneTelemetry;
    }

    public int getMaxNetInSize() {
        return maxNetInSize;
    }

    public Listener maxNetInSize(int size) {
        this.maxNetInSize = size;
        return this;
    }

    public int getMaxNetOutSize() {
        return maxNetOutSize;
    }

    public Listener maxNetOutSize(int size) {
        this.maxNetOutSize = size;
        return this;
    }

    public boolean isSecure() {
        return secure;
    }

    /**
     * Applies server TLS identity from a {@link TlsConfig} before
     * {@link #start()}. Returns {@code this} for fluent configuration.
     *
     * @param tls the TLS identity configuration
     * @return this listener
     */
    public Listener tls(TlsConfig tls) {
        if (tls == null) {
            throw new NullPointerException("tls");
        }
        TlsConfigSupport.apply(tls, this);
        return this;
    }

    protected void setKeystoreFile(Path file) {
        keystoreFile = file;
    }

    protected void setKeystorePass(String pass) {
        keystorePass = pass;
    }

    protected void setKeystoreFormat(KeystoreFormat format) {
        keystoreFormat = format;
    }

    /**
     * Sets the {@code ECHConfigList} file for server-side ECH (HTTP/3 / QUIC).
     */
    protected void setEchConfigListFile(Path echConfigListFile) {
        this.echConfigListFile = echConfigListFile;
    }

    /**
     * Sets the X25519 ECH private key file (32 raw bytes or hex).
     */
    protected void setEchPrivateKeyFile(Path echPrivateKeyFile) {
        this.echPrivateKeyFile = echPrivateKeyFile;
    }

    /**
     * Requires clients to offer ECH on this listener.
     */
    protected void setEchServerRequired(boolean echServerRequired) {
        this.echServerRequired = echServerRequired;
    }

    /**
     * Sets the PEM certificate chain file for TLS server identity.
     *
     * @param file the certificate chain PEM file path
     */
    protected void setCertFile(Path file) {
        certFile = file;
    }

    /**
     * Sets the PEM private key file for TLS server identity.
     *
     * @param file the private key PEM file path
     */
    protected void setKeyFile(Path file) {
        keyFile = file;
    }

    /**
     * Sets the TLS 1.3 cipher suites (colon-separated IANA names).
     *
     * @param cipherSuites colon-separated cipher suite names, or null
     *                     to use the default set
     */
    protected void setCipherSuites(String cipherSuites) {
        this.cipherSuites = cipherSuites;
    }

    /**
     * Sets the allowed key exchange groups / named curves
     * (colon-separated).
     *
     * @param namedGroups colon-separated group names, or null to use
     *                    the default set
     */
    protected void setNamedGroups(String namedGroups) {
        this.namedGroups = namedGroups;
    }

    /**
     * Sets this server's identity (certificate chain and private key)
     * directly, bypassing keystore-file loading.
     *
     * @param serverCredentials the server credentials
     */
    protected void setServerCredentials(ServerCredentials serverCredentials) {
        this.serverCredentials = serverCredentials;
    }

    /**
     * Returns this server's identity.
     *
     * @return the server credentials, or null if not set
     */
    public ServerCredentials getServerCredentials() {
        return serverCredentials;
    }

    /**
     * Returns the TLS protocol version this listener's secure connections
     * speak.
     *
     * @return the TLS version, defaulting to {@link TlsVersion#TLS_1_3}
     */
    public TlsVersion getTlsVersion() {
        return tlsVersion;
    }

    /**
     * Sets the TLS protocol version this listener's secure connections
     * speak -- a deployment-time choice, not negotiated per-connection.
     * See {@link TlsVersion}'s own doc comment for why a deployment
     * wanting to serve both TLS 1.3 and TLS 1.2 peers configures two
     * listeners rather than one that detects the version at runtime.
     *
     * @param tlsVersion the TLS version
     */
    protected void setTlsVersion(TlsVersion tlsVersion) {
        this.tlsVersion = (tlsVersion != null) ? tlsVersion : TlsVersion.NEGOTIATE;
    }

    /**
     * Returns the DTLS protocol version UDP listeners are pinned to.
     *
     * @return the DTLS version
     */
    public DtlsVersion getDtlsVersion() {
        return dtlsVersion;
    }

    /**
     * Sets the DTLS protocol version UDP listeners speak.
     *
     * @param dtlsVersion the DTLS version
     */
    protected void setDtlsRequireCookie(boolean require) {
        this.dtlsRequireCookie = require;
    }

    protected void setDtlsCookieSecret(byte[] secret) {
        this.dtlsCookieSecret = secret != null ? secret.clone() : null;
    }

    protected void setDtlsMaxFragmentSize(int bytes) {
        this.dtlsMaxFragmentSize = bytes;
    }

    protected void setDtlsVersion(DtlsVersion dtlsVersion) {
        this.dtlsVersion = (dtlsVersion != null) ? dtlsVersion : DtlsVersion.NEGOTIATE;
    }

    /**
     * Indicates whether this listener has TLS material configured, either
     * explicitly injected {@link ServerCredentials} or a keystore from
     * which the transport factory builds them at start time.
     *
     * <p>This is the correct basis for STARTTLS/STLS availability on a
     * cleartext listener. Credentials for an in-band TLS upgrade are
     * built by the transport factory from the configured keystore, so
     * checking {@link #serverCredentials} alone (which is populated only
     * via {@link #setServerCredentials}) would miss the common
     * keystore-configured case.
     *
     * @return true if this listener can provide TLS
     */
    protected boolean isTLSConfigured() {
        return serverCredentials != null
                || (keystoreFile != null && keystorePass != null)
                || (certFile != null && keyFile != null);
    }

    protected void setSniHostnames(Map<String, String> hostnames) {
        this.sniHostnameToAlias = hostnames != null
                ? new LinkedHashMap<String, String>(hostnames)
                : null;
    }

    protected void setSniDefaultAlias(String alias) {
        this.sniDefaultAlias = alias;
    }

    public boolean isSNIEnabled() {
        return sniHostnameToAlias != null && !sniHostnameToAlias.isEmpty();
    }

    protected boolean isMetricsEnabled() {
        TelemetryConfig telemetry = getTelemetryConfig();
        return telemetry != null && telemetry.isMetricsEnabled();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Server-level setters
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Binds the wildcard address ({@code 0.0.0.0} / {@code ::}, dual-stack
     * where the platform supports it). Clears any explicit address list.
     *
     * @return this listener
     */
    public Listener bindWildcard() {
        wildcard = true;
        addresses = null;
        return this;
    }

    /**
     * Replaces the bind address set with the given literals. Clears wildcard
     * mode. Pass no addresses to revert to the default (all local NICs).
     *
     * <p>Use {@link InetAddress#ofLiteral(String)} for configured literals;
     * do not pass hostnames here.
     *
     * @param addrs the addresses to bind
     * @return this listener
     */
    public Listener addresses(InetAddress... addrs) {
        wildcard = false;
        if (addrs == null || addrs.length == 0) {
            addresses = null;
            return this;
        }
        LinkedHashSet<InetAddress> set = new LinkedHashSet<InetAddress>();
        for (int i = 0; i < addrs.length; i++) {
            if (addrs[i] == null) {
                throw new NullPointerException("address");
            }
            set.add(addrs[i]);
        }
        addresses = set;
        return this;
    }

    /**
     * Enables or disables TLS for this listener. Returns {@code this} for
     * fluent configuration.
     *
     * @param flag true for TLS
     * @return this listener
     */
    public Listener secure(boolean flag) {
        secure = flag;
        return this;
    }

    /**
     * Returns whether this listener binds the wildcard address.
     *
     * @return true if wildcard binding is enabled
     */
    public boolean isWildcard() {
        return wildcard;
    }

    protected void setNeedClientAuth(boolean flag) {
        needClientAuth = flag;
    }

    /**
     * Sets the trust manager used to verify client certificates under mTLS
     * (see {@link #setNeedClientAuth(boolean)}). Applied via {@link
     * TlsConfigSupport#apply(TlsConfig, Listener)} from {@link
     * TlsConfig#getTrustManager()} when set.
     *
     * @param trustManager the trust manager, or null to use JVM defaults
     */
    protected void setTrustManager(X509TrustManager trustManager) {
        this.trustManager = trustManager;
    }

    public long getIdleTimeoutMs() {
        return idleTimeoutMs;
    }

    public Listener idleTimeoutMs(long idleTimeoutMs) {
        this.idleTimeoutMs = idleTimeoutMs;
        return this;
    }

    public long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public Listener readTimeoutMs(long readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
        return this;
    }

    public long getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public Listener connectionTimeoutMs(long connectionTimeoutMs) {
        this.connectionTimeoutMs = connectionTimeoutMs;
        return this;
    }

    /**
     * Limits the simultaneous connections accepted from one address. Turns on
     * per-address limiting (with the defaults for
     * {@link #rateLimit(String)} if it is not set).
     *
     * @param max the maximum concurrent connections per address (0 to disable)
     * @return this listener
     */
    public Listener maxConnectionsPerIP(int max) {
        ensureConnectionRateLimiter();
        connectionRateLimiter.setMaxConcurrentPerIP(max);
        return this;
    }

    /**
     * Returns the global maximum number of simultaneous connections
     * accepted by this listener, or 0 if unlimited.
     *
     * @return the connection cap, or 0 for unlimited
     */
    public int getMaxConnections() {
        return maxConnections;
    }

    /**
     * Sets a global cap on the number of simultaneously accepted connections
     * for this listener. When the cap is reached, new connections are
     * rejected at accept time. This is the hard backstop against
     * file-descriptor and memory exhaustion; per-IP limits still apply
     * independently.
     *
     * @param max the maximum concurrent connections (0 to disable)
     * @return this listener
     */
    public Listener maxConnections(int max) {
        this.maxConnections = max;
        return this;
    }

    /**
     * Returns the maximum number of concurrent DTLS peers tracked on one
     * secure UDP socket for this listener, or {@code 0} if unlimited.
     *
     * @return the DTLS peer cap
     */
    public int getMaxDtlsPeers() {
        return maxDtlsPeers;
    }

    /**
     * Sets the maximum number of concurrent DTLS peers on one secure UDP
     * listener socket. {@code 0} disables the limit.
     *
     * @param max the DTLS peer cap
     * @return this
     */
    public Listener maxDtlsPeers(int max) {
        this.maxDtlsPeers = max;
        return this;
    }

    /**
     * Returns the number of connections currently open on this listener.
     *
     * @return the active connection count
     */
    public int getActiveConnectionCount() {
        return activeConnectionCount.get();
    }

    /**
     * Records that a connection has been admitted and is now open. Called on
     * the accept path once {@link #acceptConnection} has returned {@code true}
     * and the endpoint has been created. Increments the global connection
     * counter and consumes a rate-limit token, and (for IP connections)
     * updates per-IP concurrency tracking.
     *
     * @param remoteAddress the remote address of the accepted connection
     */
    public void connectionOpened(SocketAddress remoteAddress) {
        activeConnectionCount.incrementAndGet();
        if (connectionRateLimiter != null
                && remoteAddress instanceof InetSocketAddress) {
            connectionRateLimiter.connectionOpened(
                    ((InetSocketAddress) remoteAddress).getAddress());
        }
    }

    /**
     * Notifies the listener that a connection has closed, releasing its
     * admission accounting. Decrements the global connection counter and
     * (for IP connections) the per-IP concurrency tracking. Must be called
     * exactly once per {@link #connectionOpened} call.
     *
     * @param remoteAddress the remote address of the closed connection
     */
    public void connectionClosed(SocketAddress remoteAddress) {
        int n;
        do {
            n = activeConnectionCount.get();
        } while (n > 0 && !activeConnectionCount.compareAndSet(n, n - 1));
        if (connectionRateLimiter != null
                && remoteAddress instanceof InetSocketAddress) {
            connectionRateLimiter.connectionClosed(
                    ((InetSocketAddress) remoteAddress).getAddress());
        }
    }

    /**
     * Convenience overload for callers holding an {@link InetSocketAddress}.
     *
     * @param remoteAddress the remote address of the closed connection
     */
    public void connectionClosed(InetSocketAddress remoteAddress) {
        connectionClosed((SocketAddress) remoteAddress);
    }

    /**
     * Limits new connections from one address per time window. Turns on
     * per-address limiting (with the default concurrent-connection cap if
     * {@link #maxConnectionsPerIP(int)} is not set).
     *
     * @param rateLimit {@code count/duration}, for example {@code 100/60s};
     *        the units are {@code ms}, {@code s}, {@code m} and {@code h}
     * @return this listener
     * @throws IllegalArgumentException if the format is invalid
     */
    public Listener rateLimit(String rateLimit) {
        ensureConnectionRateLimiter();
        connectionRateLimiter.setRateLimit(rateLimit);
        return this;
    }

    /**
     * Sets how many failed authentications from one client address are
     * allowed before it is locked out. Setting this (or
     * {@link #authLockoutTimeMs(long)}) turns lockout on; the protocol
     * handlers then refuse a locked-out client without consulting the realm.
     *
     * @param max the failure threshold
     * @return this listener
     */
    public Listener maxAuthFailures(int max) {
        ensureAuthRateLimiter();
        authRateLimiter.setMaxFailures(max);
        return this;
    }

    /**
     * Sets the base lockout duration after too many failed authentications.
     *
     * @param lockoutMs the duration in milliseconds
     * @return this listener
     */
    public Listener authLockoutTimeMs(long lockoutMs) {
        ensureAuthRateLimiter();
        authRateLimiter.setLockoutDuration(lockoutMs);
        return this;
    }

    /**
     * Restricts connections to clients in these networks. An empty list or
     * {@code null} allows every client that is not blocked.
     *
     * @param allowedNetworks the networks clients must be in, or null
     * @return this listener
     */
    public Listener allowedNetworks(List<CidrNetwork> allowedNetworks) {
        this.allowedNetworks = allowedNetworks == null || allowedNetworks.isEmpty()
                ? null : new ArrayList<CidrNetwork>(allowedNetworks);
        return this;
    }

    /**
     * Refuses connections from clients in these networks, before the allowed
     * networks are consulted.
     *
     * @param blockedNetworks the networks to refuse, or null
     * @return this listener
     */
    public Listener blockedNetworks(List<CidrNetwork> blockedNetworks) {
        this.blockedNetworks = blockedNetworks == null || blockedNetworks.isEmpty()
                ? null : new ArrayList<CidrNetwork>(blockedNetworks);
        return this;
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
    public Path getPath() {
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
     * Starts this endpoint with a {@link Gumdrop} runtime available.
     *
     * <p>The default implementation ignores {@code gumdrop} and delegates
     * to {@link #start()} — most listener types don't need it (they don't
     * pick a worker loop of their own; that happens per-accepted-connection
     * instead). Subclasses that do need it (e.g. QUIC-based listeners
     * choosing a worker loop for the connection) override this instead of
     * {@link #start()}, and call this: it is how a listener learns its
     * runtime, and through it the telemetry configuration.
     *
     * @param gumdrop the runtime this listener is starting under
     */
    public void start(Gumdrop gumdrop) {
        this.gumdrop = gumdrop;
        attachRateLimiters();
        start();
    }

    /**
     * Creates the transport factory for this endpoint.
     *
     * <p>The default implementation returns a {@link TcpTransportFactory}.
     * Subclasses override this to select a different transport.
     *
     * @return the transport factory
     */
    protected TransportFactory createTransportFactory() {
        return new TcpTransportFactory();
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
        if (certFile != null) {
            factory.setCertFile(certFile);
        }
        if (keyFile != null) {
            factory.setKeyFile(keyFile);
        }
        TelemetryConfig telemetry = getTelemetryConfig();
        if (telemetry != null) {
            factory.setTelemetryConfig(telemetry);
        }
        if (cipherSuites != null) {
            factory.setCipherSuites(cipherSuites);
        }
        if (namedGroups != null) {
            factory.setNamedGroups(namedGroups);
        }
        factory.setMaxNetInSize(maxNetInSize);
        factory.setMaxNetOutSize(maxNetOutSize);

        if (factory instanceof TcpTransportFactory) {
            TcpTransportFactory tcpFactory = (TcpTransportFactory) factory;
            if (serverCredentials != null) {
                tcpFactory.setServerCredentials(serverCredentials);
            }
            tcpFactory.setTlsVersion(tlsVersion);
            if (needClientAuth) {
                tcpFactory.setNeedClientAuth(true);
            }
            if (trustManager != null) {
                tcpFactory.setTrustManager(trustManager);
            }
            if (sniHostnameToAlias != null) {
                tcpFactory.setSniHostnames(sniHostnameToAlias);
            }
            if (sniDefaultAlias != null) {
                tcpFactory.setSniDefaultAlias(sniDefaultAlias);
            }
        }
        if (factory instanceof UdpTransportFactory) {
            UdpTransportFactory udpFactory = (UdpTransportFactory) factory;
            if (serverCredentials != null) {
                udpFactory.setServerCredentials(serverCredentials);
            }
            udpFactory.setDtlsVersion(dtlsVersion);
            udpFactory.setMaxDtlsPeers(maxDtlsPeers);
            if (dtlsRequireCookie) {
                udpFactory.setRequireCookie(true);
            }
            if (dtlsCookieSecret != null) {
                udpFactory.setCookieSecret(dtlsCookieSecret);
            }
            if (dtlsMaxFragmentSize > 0) {
                udpFactory.setMaxFragmentSize(dtlsMaxFragmentSize);
            }
            if (needClientAuth) {
                udpFactory.setNeedClientAuth(true);
            }
            if (trustManager != null) {
                udpFactory.setTrustManager(trustManager);
            }
            if (sniHostnameToAlias != null) {
                udpFactory.setSniHostnames(sniHostnameToAlias);
            }
            if (sniDefaultAlias != null) {
                udpFactory.setSniDefaultAlias(sniDefaultAlias);
            }
        }
        if (factory instanceof QuicTransportFactory) {
            QuicTransportFactory quicFactory = (QuicTransportFactory) factory;
            if (serverCredentials != null) {
                quicFactory.setServerCredentials(serverCredentials);
            }
            if (needClientAuth) {
                quicFactory.setNeedClientAuth(true);
            }
            if (trustManager != null) {
                quicFactory.setTrustManager(trustManager);
            }
            if (sniHostnameToAlias != null) {
                quicFactory.setSniHostnames(sniHostnameToAlias);
            }
            if (sniDefaultAlias != null) {
                quicFactory.setSniDefaultAlias(sniDefaultAlias);
            }
        }
        if (echConfigListFile != null) {
            factory.setEchConfigListFile(echConfigListFile);
        }
        if (echPrivateKeyFile != null) {
            factory.setEchPrivateKeyFile(echPrivateKeyFile);
        }
        factory.setEchServerRequired(echServerRequired);
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
        if (wildcard) {
            // Any-local address; the accept path binds this as a single
            // (dual-stack where supported) wildcard socket.
            Set<InetAddress> wild = new LinkedHashSet<InetAddress>();
            wild.add(new InetSocketAddress(0).getAddress());
            return wild;
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
            eventTelemetry().getLogger(Listener.class, L10N)
                    .warn("log.failed_to_enumerate_network_interfaces").thrown(e).emit();
        }
        return all;
    }

    /**
     * Returns whether this listener is on the default address set (every
     * local address) rather than a wildcard or an explicit list.
     */
    boolean hasDefaultAddresses() {
        return getPath() == null && !wildcard && addresses == null;
    }

    /**
     * Checks whether a connection from the given remote address should
     * be accepted, based on CIDR allow/block lists and rate limits.
     *
     * @param remoteAddress the remote address
     * @return true if the connection should be accepted
     */
    public boolean acceptConnection(SocketAddress remoteAddress) {
        // Global admission backstop: reject once the hard connection cap is
        // reached, before any per-IP checks. Safe to check-then-admit without
        // locking because accepts are serialised on the single accept thread.
        if (maxConnections > 0
                && activeConnectionCount.get() >= maxConnections) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(L10N.getString("log.connection_cap_reached_0_rejecting_1"), maxConnections, remoteAddress));
            }
            return false;
        }

        if (!(remoteAddress instanceof InetSocketAddress)) {
            return true;
        }
        InetAddress addr =
                ((InetSocketAddress) remoteAddress).getAddress();

        if (blockedNetworks != null) {
            for (Iterator<CidrNetwork> it = blockedNetworks.iterator();
                 it.hasNext(); ) {
                if (it.next().matches(addr)) {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.fine(MessageFormat.format(L10N.getString("log.blocked_connection_from_0"), addr));
                    }
                    return false;
                }
            }
        }

        if (allowedNetworks != null) {
            boolean allowed = false;
            for (Iterator<CidrNetwork> it = allowedNetworks.iterator();
                 it.hasNext(); ) {
                if (it.next().matches(addr)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(MessageFormat.format(L10N.getString("log.connection_not_in_allowed_networks_0"), addr));
                }
                return false;
            }
        }

        if (connectionRateLimiter != null) {
            if (!connectionRateLimiter.allowConnection(addr)) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(MessageFormat.format(L10N.getString("log.rate_limit_exceeded_for_0"), addr));
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
     * Whether authentication attempts from this client are locked out because
     * of too many recent failures. A protocol handler asks before it starts to
     * check credentials, and refuses the attempt (without consulting the
     * realm) when this is true. Always false unless
     * {@link #maxAuthFailures(int)} or {@link #authLockoutTimeMs(long)} was set.
     *
     * @param remoteAddress the client's address
     * @return true if the client may not try to authenticate now
     */
    public boolean isAuthLockedOut(SocketAddress remoteAddress) {
        AuthenticationRateLimiter limiter = authRateLimiter;
        InetAddress ip = ipOf(remoteAddress);
        return limiter != null && ip != null && limiter.isLocked(ip);
    }

    /**
     * Records a failed authentication attempt by this client, towards its
     * lockout. Does nothing unless authentication lockout is configured.
     *
     * @param remoteAddress the client's address
     * @param username the user name tried, or null if not known
     */
    public void recordAuthFailure(SocketAddress remoteAddress, String username) {
        AuthenticationRateLimiter limiter = authRateLimiter;
        InetAddress ip = ipOf(remoteAddress);
        if (limiter != null && ip != null) {
            limiter.recordFailure(ip, username);
        }
    }

    /**
     * Records a successful authentication by this client, clearing its
     * failure count.
     *
     * @param remoteAddress the client's address
     * @param username the authenticated user, or null if not known
     */
    public void recordAuthSuccess(SocketAddress remoteAddress, String username) {
        AuthenticationRateLimiter limiter = authRateLimiter;
        InetAddress ip = ipOf(remoteAddress);
        if (limiter != null && ip != null) {
            limiter.recordSuccess(ip, username);
        }
    }

    private static InetAddress ipOf(SocketAddress remoteAddress) {
        return remoteAddress instanceof InetSocketAddress
                ? ((InetSocketAddress) remoteAddress).getAddress() : null;
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

    private void ensureConnectionRateLimiter() {
        if (connectionRateLimiter == null) {
            connectionRateLimiter = new ConnectionRateLimiter();
            attachRateLimiters();
        }
    }

    private void ensureAuthRateLimiter() {
        if (authRateLimiter == null) {
            authRateLimiter = new AuthenticationRateLimiter();
            attachRateLimiters();
        }
    }

    // Gives the limiters the configuration their events go to, once there is a runtime
    private void attachRateLimiters() {
        TelemetryConfig telemetry = getTelemetryConfig();
        if (telemetry == null) {
            return;
        }
        if (connectionRateLimiter != null) {
            connectionRateLimiter.setTelemetryConfig(telemetry);
        }
        if (authRateLimiter != null) {
            authRateLimiter.setTelemetryConfig(telemetry);
        }
    }

}
