/*
 * SocksListener.java
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

package org.bluezoo.gumdrop.socks;

import org.bluezoo.gumdrop.util.CidrNetwork;
import java.util.List;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ResourceBundle;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.auth.GssapiServer;
import org.bluezoo.gumdrop.auth.Realm;
import java.net.InetAddress;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.telemetry.EventLogger;

/**
 * TCP transport listener for SOCKS proxy connections.
 *
 * <p>Supports SOCKS on port 1080 (plaintext) and port 1081 (TLS).
 * Port 1080 is the IANA-assigned port for SOCKS (RFC 1928 uses this
 * implicitly).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.socks.server.SocksServer
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc1928">RFC 1928</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc1929">RFC 1929</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc1961">RFC 1961</a>
 */
public class SocksListener extends TcpListener {

    private EventLogger events() {
        return eventTelemetry().getLogger(SocksListener.class, L10N);
    }
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.socks.L10N");

    private int port = -1;
    private Realm realm;
    private GssapiServer gssapiServer;

    private org.bluezoo.gumdrop.socks.server.SocksServer server;
    private SocksServerMetrics metrics;

    @Override
    public void start() {
        super.start();
        if (port <= 0) {
            port = secure ? SocksConstants.SOCKSS_DEFAULT_PORT
                          : SocksConstants.SOCKS_DEFAULT_PORT;
        }
        if (isMetricsEnabled()) {
            metrics = new SocksServerMetrics(getTelemetryConfig());
        }
    }

    /**
     * Returns the metrics for this listener, or null if telemetry is
     * not enabled.
     *
     * @return the SOCKS server metrics
     */
    public SocksServerMetrics getMetrics() {
        return metrics;
    }

    @Override
    public String getDescription() {
        return secure ? "sockss" : "socks";
    }

    @Override
    public int getPort() {
        return port;
    }

    /**
     * Sets the port. Returns {@code this} for fluent configuration.
     *
     * @param port the port number
     * @return this listener
     */
    public SocksListener port(int port) {
        this.port = port;
        return this;
    }

    @Override
    public SocksListener bindWildcard() {
        super.bindWildcard();
        return this;
    }

    @Override
    public SocksListener addresses(InetAddress... addrs) {
        super.addresses(addrs);
        return this;
    }

    @Override
    public SocksListener secure(boolean flag) {
        super.secure(flag);
        return this;
    }

    @Override
    public SocksListener tls(TlsConfig tls) {
        super.tls(tls);
        return this;
    }

    @Override
    public SocksListener maxConnections(int max) {
        super.maxConnections(max);
        return this;
    }

    @Override
    public SocksListener maxConnectionsPerIP(int max) {
        super.maxConnectionsPerIP(max);
        return this;
    }

    @Override
    public SocksListener rateLimit(String rateLimit) {
        super.rateLimit(rateLimit);
        return this;
    }

    @Override
    public SocksListener maxAuthFailures(int max) {
        super.maxAuthFailures(max);
        return this;
    }

    @Override
    public SocksListener authLockoutTimeMs(long lockoutMs) {
        super.authLockoutTimeMs(lockoutMs);
        return this;
    }

    @Override
    public SocksListener allowedNetworks(List<CidrNetwork> allowedNetworks) {
        super.allowedNetworks(allowedNetworks);
        return this;
    }

    @Override
    public SocksListener blockedNetworks(List<CidrNetwork> blockedNetworks) {
        super.blockedNetworks(blockedNetworks);
        return this;
    }

    @Override
    public SocksListener name(String name) {
        super.name(name);
        return this;
    }

    @Override
    public SocksListener maxNetInSize(int size) {
        super.maxNetInSize(size);
        return this;
    }

    @Override
    public SocksListener maxNetOutSize(int size) {
        super.maxNetOutSize(size);
        return this;
    }

    @Override
    public SocksListener idleTimeoutMs(long idleTimeoutMs) {
        super.idleTimeoutMs(idleTimeoutMs);
        return this;
    }

    @Override
    public SocksListener readTimeoutMs(long readTimeoutMs) {
        super.readTimeoutMs(readTimeoutMs);
        return this;
    }

    @Override
    public SocksListener connectionTimeoutMs(long connectionTimeoutMs) {
        super.connectionTimeoutMs(connectionTimeoutMs);
        return this;
    }

    @Override
    public SocksListener maxDtlsPeers(int max) {
        super.maxDtlsPeers(max);
        return this;
    }

    /**
     * Returns the authentication realm for this listener.
     * Used for SOCKS5 authentication per RFC 1928 §3, RFC 1929, RFC 1961.
     *
     * @return the realm, or null if no authentication is configured
     */
    public Realm getRealm() {
        return realm;
    }

    /**
     * Sets the authentication realm for this listener.
     * Used for SOCKS5 authentication per RFC 1928 §3, RFC 1929, RFC 1961.
     *
     * @param realm the realm
     * @return this
     */
    public SocksListener realm(Realm realm) {
        this.realm = realm;
        return this;
    }

    /**
     * Returns the GSSAPI server for Kerberos authentication.
     * RFC 1961 §3–§4: GSS-API authentication for SOCKS5.
     *
     * @return the GSSAPI server, or null if GSSAPI is not configured
     */
    public GssapiServer getGSSAPIServer() {
        return gssapiServer;
    }

    /**
     * Sets the GSSAPI server for Kerberos authentication.
     * RFC 1961 §3–§4: GSS-API authentication for SOCKS5.
     *
     * @param gssapiServer the GSSAPI server
     * @return this
     */
    public SocksListener gssapiServer(GssapiServer gssapiServer) {
        this.gssapiServer = gssapiServer;
        return this;
    }

    /**
     * Configures GSSAPI/Kerberos authentication by creating a
     * {@link GssapiServer} from the specified keytab and service
     * principal. RFC 1961 §3–§4: GSS-API authentication for SOCKS5.
     *
     * @param keytabPath the path to the Kerberos keytab file
     * @param servicePrincipal the service principal name
     *        (e.g. "socks/proxy.example.com@EXAMPLE.COM")
     * @throws IOException if the keytab cannot be read or credentials
     *         cannot be acquired
     */
    public SocksListener configureGSSAPI(Path keytabPath, String servicePrincipal)
            throws IOException {
        this.gssapiServer = new GssapiServer(keytabPath, servicePrincipal);
        return this;
    }

    public org.bluezoo.gumdrop.socks.server.SocksServer getServer() {
        return server;
    }

    public SocksListener server(org.bluezoo.gumdrop.socks.server.SocksServer server) {
        this.server = server;
        return this;
    }

    @Override
    protected ProtocolHandler createHandler() {
        if (server != null) {
            try {
                return server.createProtocolHandler(this);
            } catch (Exception e) {
                events().warn("log.handler_create_failed").thrown(e).emit();
            }
        }
        throw new IllegalStateException(
                "SocksListener requires a SocksServer");
    }

}
