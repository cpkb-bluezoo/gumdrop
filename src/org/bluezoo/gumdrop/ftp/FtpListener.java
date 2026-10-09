/*
 * FtpListener.java
 * Copyright (C) 2006, 2026 Chris Burdess
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

package org.bluezoo.gumdrop.ftp;

import org.bluezoo.gumdrop.util.CidrNetwork;
import java.util.List;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.auth.Realm;
import java.net.InetAddress;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.telemetry.EventLogger;

/**
 * TCP transport listener for FTP control connections.
 * RFC 959 section 5.2 specifies port 21 for the control connection;
 * implicit FTPS uses port 990.
 *
 * <p>Handler creation follows a dual-source pattern:
 * <ol>
 *   <li>When used within an {@link org.bluezoo.gumdrop.ftp.server.FtpServer}, the server's
 *       {@code createHandler()} method is called to create handlers.
 *       This is the normal server deployment path.</li>
 *   <li>When used standalone (no server), a
 *       {@link FtpConnectionHandlerFactory} can be set directly via
 *       {@link #setHandlerFactory}. This enables standalone FTP data
 *       servers or embedded usage without a full server lifecycle.</li>
 * </ol>
 *
 * <h2>Configuration</h2>
 *
 * <p>Listener properties (XML {@code name} attributes map to
 * {@code setXxx} bean setters):
 * <ul>
 *   <li>{@code port} &ndash; control port (default 21, or 990 for
 *       implicit FTPS)</li>
 *   <li>{@code secure} &ndash; implicit TLS (FTPS)</li>
 *   <li>{@code require-tls-for-data} &ndash; reject data transfers
 *       unless PROT P has been issued</li>
 *   <li>{@code allow-active-mode-bounce} &ndash; when {@code true},
 *       permit PORT/EPRT to specify a data address other than the
 *       control client's IP (default {@code false}; see RFC 4217
 *       section 10)</li>
 *   <li>{@code pasv-min-port} / {@code pasv-max-port} &ndash; restrict
 *       passive-mode (PASV/EPSV) data listeners to this port range,
 *       for deployments behind a firewall that only forwards a fixed
 *       range (default: unrestricted, OS-assigned)</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FtpListener extends TcpListener {

    private EventLogger events() {
        return eventTelemetry().getLogger(FtpListener.class, FtpProtocolHandler.L10N);
    }

    /**
     * The default FTP transmission control port.
     */
    protected static final int FTP_DEFAULT_PORT = 21;

    /**
     * The default FTPS transmission control port.
     */
    protected static final int FTPS_DEFAULT_PORT = 990;

    /**
     * The default FTP data port.
     */
    protected static final int FTP_DEFAULT_DATA_PORT = 20;

    protected int port = FTP_DEFAULT_PORT;
    private boolean portExplicitlySet = false;
    protected FtpConnectionHandlerFactory handlerFactory;
    private org.bluezoo.gumdrop.ftp.server.FtpServerSessionProvider sessionProvider;
    private boolean requireTLSForData = false;
    private boolean allowActiveModeBounce = false;
    private int pasvMinPort = 0;
    private int pasvMaxPort = 0;
    private Realm realm;

    // Back-reference to the owning server (null when used standalone)
    private org.bluezoo.gumdrop.ftp.server.FtpServer server;

    // Metrics for this endpoint (null if telemetry is not enabled)
    private FtpServerMetrics metrics;

    @Override
    public String getDescription() {
        return secure ? "ftps" : "ftp";
    }

    /**
     * Returns the control port. If no port has been explicitly set and
     * the listener is configured for TLS ({@link #isSecure()}), the
     * implicit FTPS port 990 is returned per RFC 4217.
     */
    public int getPort() {
        // RFC 4217: implicit FTPS uses port 990
        if (!portExplicitlySet && secure) {
            return FTPS_DEFAULT_PORT;
        }
        return port;
    }

    /**
     * Sets the port. Returns {@code this} for fluent configuration.
     *
     * @param port the port number
     * @return this listener
     */
    public FtpListener port(int port) {
        this.port = port;
        this.portExplicitlySet = true;
        return this;
    }

    @Override
    public FtpListener bindWildcard() {
        super.bindWildcard();
        return this;
    }

    @Override
    public FtpListener addresses(InetAddress... addrs) {
        super.addresses(addrs);
        return this;
    }

    @Override
    public FtpListener secure(boolean flag) {
        super.secure(flag);
        return this;
    }

    @Override
    public FtpListener tls(TlsConfig tls) {
        super.tls(tls);
        return this;
    }

    @Override
    public FtpListener maxConnections(int max) {
        super.maxConnections(max);
        return this;
    }

    @Override
    public FtpListener maxConnectionsPerIP(int max) {
        super.maxConnectionsPerIP(max);
        return this;
    }

    @Override
    public FtpListener rateLimit(String rateLimit) {
        super.rateLimit(rateLimit);
        return this;
    }

    @Override
    public FtpListener maxAuthFailures(int max) {
        super.maxAuthFailures(max);
        return this;
    }

    @Override
    public FtpListener authLockoutTimeMs(long lockoutMs) {
        super.authLockoutTimeMs(lockoutMs);
        return this;
    }

    @Override
    public FtpListener allowedNetworks(List<CidrNetwork> allowedNetworks) {
        super.allowedNetworks(allowedNetworks);
        return this;
    }

    @Override
    public FtpListener blockedNetworks(List<CidrNetwork> blockedNetworks) {
        super.blockedNetworks(blockedNetworks);
        return this;
    }

    @Override
    public FtpListener name(String name) {
        super.name(name);
        return this;
    }

    @Override
    public FtpListener maxNetInSize(int size) {
        super.maxNetInSize(size);
        return this;
    }

    @Override
    public FtpListener maxNetOutSize(int size) {
        super.maxNetOutSize(size);
        return this;
    }

    @Override
    public FtpListener idleTimeoutMs(long idleTimeoutMs) {
        super.idleTimeoutMs(idleTimeoutMs);
        return this;
    }

    @Override
    public FtpListener readTimeoutMs(long readTimeoutMs) {
        super.readTimeoutMs(readTimeoutMs);
        return this;
    }

    @Override
    public FtpListener connectionTimeoutMs(long connectionTimeoutMs) {
        super.connectionTimeoutMs(connectionTimeoutMs);
        return this;
    }

    @Override
    public FtpListener maxDtlsPeers(int max) {
        super.maxDtlsPeers(max);
        return this;
    }

    public FtpListener handlerFactory(FtpConnectionHandlerFactory factory) {
        this.handlerFactory = factory;
        return this;
    }

    public FtpConnectionHandlerFactory getHandlerFactory() {
        return handlerFactory;
    }

    /**
     * Sets whether TLS is required for data connections.
     * When true, data transfers will fail unless PROT P has been issued.
     *
     * @param require true to require TLS for data connections
     * @return this
     */
    public FtpListener requireTLSForData(boolean require) {
        this.requireTLSForData = require;
        return this;
    }

    /**
     * Returns whether TLS is required for data connections.
     *
     * @return true if TLS is required for data connections
     */
    public boolean isRequireTLSForData() {
        return requireTLSForData;
    }

    /**
     * Sets whether active-mode (PORT/EPRT) may connect to an address
     * other than the control connection's client IP.
     *
     * <p>When false (the default), RFC 4217 section 10 data-connection
     * verification is applied to active mode as well as passive mode,
     * preventing FTP bounce and port-scanning abuse. Set to true only
     * for explicitly trusted deployments.
     *
     * @param allow true to permit client-supplied foreign data addresses
     * @return this
     */
    public FtpListener allowActiveModeBounce(boolean allow) {
        this.allowActiveModeBounce = allow;
        return this;
    }

    /**
     * Returns whether active-mode data connections may target addresses
     * other than the control client's IP.
     *
     * @return true if FTP bounce is permitted
     */
    public boolean isAllowActiveModeBounce() {
        return allowActiveModeBounce;
    }

    /**
     * Sets the lowest port number to use for passive-mode (PASV/EPSV)
     * data listeners. Used together with {@link #setPasvMaxPort} to
     * restrict passive data connections to a fixed range, for
     * deployments behind a firewall that only forwards a limited set of
     * ports. 0 (the default) means unrestricted, OS-assigned.
     *
     * @param port the lowest passive-mode port, or 0 for unrestricted
     * @return this
     */
    public FtpListener pasvMinPort(int port) {
        this.pasvMinPort = port;
        return this;
    }

    /**
     * Returns the lowest port number used for passive-mode data
     * listeners, or 0 if unrestricted.
     *
     * @return the lowest passive-mode port, or 0
     */
    public int getPasvMinPort() {
        return pasvMinPort;
    }

    /**
     * Sets the highest port number to use for passive-mode (PASV/EPSV)
     * data listeners. See {@link #setPasvMinPort}.
     *
     * @param port the highest passive-mode port, or 0 for unrestricted
     * @return this
     */
    public FtpListener pasvMaxPort(int port) {
        this.pasvMaxPort = port;
        return this;
    }

    /**
     * Returns the highest port number used for passive-mode data
     * listeners, or 0 if unrestricted.
     *
     * @return the highest passive-mode port, or 0
     */
    public int getPasvMaxPort() {
        return pasvMaxPort;
    }

    /**
     * Returns the authentication realm for this listener.
     *
     * @return the realm, or null
     */
    public Realm getRealm() {
        return realm;
    }

    /**
     * Sets the authentication realm for this listener.
     *
     * @param realm the realm
     * @return this
     */
    public FtpListener realm(Realm realm) {
        this.realm = realm;
        return this;
    }

    /**
     * Checks if SSL/TLS context is available for AUTH TLS.
     *
     * @return true if AUTH TLS is supported, false otherwise
     */
    public boolean isSTARTTLSAvailable() {
        return isTLSConfigured();
    }

    public void start() {
        super.start();
        if (isMetricsEnabled()) {
            metrics = new FtpServerMetrics(getTelemetryConfig());
        }
    }

    /**
     * Returns the metrics for this endpoint, or null if telemetry is
     * not enabled.
     *
     * @return the FTP server metrics
     */
    public FtpServerMetrics getMetrics() {
        return metrics;
    }

    /**
     * No-op: server channel cleanup is handled centrally by
     * {@link org.bluezoo.gumdrop.TcpListener#closeServerChannels} during
     * unregister/shutdown, so individual listeners do not need to
     * close their own channels.
     */
    public void stop() {
        // Gumdrop.closeServerChannels() handles cleanup centrally
    }

    /**
     * Sets the owning server. Called by {@link org.bluezoo.gumdrop.ftp.server.FtpServer} during
     * wiring.
     *
     * @param server the owning server
     * @return this
     */
    public FtpListener server(org.bluezoo.gumdrop.ftp.server.FtpServer server) {
        this.server = server;
        return this;
    }

    /**
     * Returns the owning server, or null if used standalone.
     *
     * @return the owning server
     */
    public org.bluezoo.gumdrop.ftp.server.FtpServer getServer() {
        return server;
    }

    public org.bluezoo.gumdrop.ftp.server.FtpServerSessionProvider getSessionProvider() {
        return sessionProvider;
    }

    public FtpListener sessionProvider(
            org.bluezoo.gumdrop.ftp.server.FtpServerSessionProvider sessionProvider) {
        this.sessionProvider = sessionProvider;
        return this;
    }

    /**
     * Opens the application handler pipeline for a new connection.
     */
    public org.bluezoo.gumdrop.ftp.server.ClientConnected openApplicationSession() {
        if (sessionProvider != null) {
            try {
                return sessionProvider.openSession(this);
            } catch (Exception e) {
                events().warn("warn.ftp_handler_session_provider_failed").thrown(e).emit();
            }
        }
        org.bluezoo.gumdrop.ftp.server.FtpServer srv = getServer();
        if (srv != null) {
            try {
                return srv.openSession(this);
            } catch (Exception e) {
                events().warn("warn.ftp_handler_server_failed").thrown(e).emit();
            }
        }
        return null;
    }

    @Override
    protected ProtocolHandler createHandler() {
        org.bluezoo.gumdrop.ftp.server.ClientConnected session =
                openApplicationSession();
        org.bluezoo.gumdrop.ftp.FtpConnectionHandler legacy =
                org.bluezoo.gumdrop.ftp.server.LegacyConnectionHandlerAdapter
                        .unwrap(session);
        if (legacy != null) {
            return new FtpProtocolHandler(this, legacy);
        }
        if (session != null) {
            return new FtpProtocolHandler(this, session);
        }
        org.bluezoo.gumdrop.ftp.server.FtpServer srv = getServer();
        if (srv != null) {
            try {
                legacy = srv.createHandler(this);
                if (legacy != null) {
                    return new FtpProtocolHandler(this, legacy);
                }
            } catch (Exception e) {
                events().warn("warn.ftp_handler_server_default").thrown(e).emit();
            }
        } else if (handlerFactory != null) {
            try {
                legacy = handlerFactory.createHandler();
            } catch (Exception e) {
                events().warn("warn.ftp_handler_factory_default").thrown(e).emit();
            }
        }
        return new FtpProtocolHandler(this, legacy);
    }

}
