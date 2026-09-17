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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.auth.GssapiServer;
import org.bluezoo.gumdrop.auth.Realm;
import java.net.InetAddress;
import org.bluezoo.gumdrop.tls.TlsConfig;

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

    private static final Logger LOGGER =
            Logger.getLogger(SocksListener.class.getName());
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

    public void setPort(int port) {
        this.port = port;
    }
    /**
     * Sets the port. Returns {@code this} for fluent configuration.
     *
     * @param port the port number
     * @return this listener
     */
    public SocksListener port(int port) {
        setPort(port);
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
     */
    public void setRealm(Realm realm) {
        this.realm = realm;
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
     */
    public void setGSSAPIServer(GssapiServer gssapiServer) {
        this.gssapiServer = gssapiServer;
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
    public void configureGSSAPI(Path keytabPath, String servicePrincipal)
            throws IOException {
        this.gssapiServer = new GssapiServer(keytabPath, servicePrincipal);
    }

    public org.bluezoo.gumdrop.socks.server.SocksServer getServer() {
        return server;
    }

    public void setServer(org.bluezoo.gumdrop.socks.server.SocksServer server) {
        this.server = server;
    }

    private Gumdrop gumdrop;

    @Override
    public void start(Gumdrop gumdrop) {
        this.gumdrop = gumdrop;
        super.start(gumdrop);
    }

    /**
     * Returns the runtime this listener is running under, for relays
     * (UDP ASSOCIATE, BIND) created by accepted connections.
     *
     * @return the runtime, or null if not yet started
     */
    public Gumdrop getGumdrop() {
        return gumdrop;
    }

    @Override
    protected ProtocolHandler createHandler() {
        if (server != null) {
            try {
                return server.createProtocolHandler(this);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        L10N.getString("log.handler_create_failed"), e);
            }
        }
        throw new IllegalStateException(
                "SocksListener requires a SocksServer");
    }

}
