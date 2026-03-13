/*
 * FTPListener.java
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

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TCPListener;
import org.bluezoo.gumdrop.auth.Realm;

/**
 * TCP transport listener for FTP control connections.
 * RFC 959 section 5.2 specifies port 21 for the control connection;
 * implicit FTPS uses port 990.
 *
 * <p>Handler creation follows a dual-source pattern:
 * <ol>
 *   <li>When used within an {@link FTPService}, the service's
 *       {@code createHandler()} method is called to create handlers.
 *       This is the normal server deployment path.</li>
 *   <li>When used standalone (no service), a
 *       {@link FTPConnectionHandlerFactory} can be set directly via
 *       {@link #setHandlerFactory}. This enables standalone FTP data
 *       servers or embedded usage without a full service lifecycle.</li>
 * </ol>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FTPListener extends TCPListener {

    private static final Logger LOGGER =
            Logger.getLogger(FTPListener.class.getName());

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
    protected FTPConnectionHandlerFactory handlerFactory;
    private boolean requireTLSForData = false;
    private Realm realm;

    // Back-reference to the owning service (null when used standalone)
    private FTPService service;

    // Metrics for this endpoint (null if telemetry is not enabled)
    private FTPServerMetrics metrics;

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

    public void setPort(int port) {
        this.port = port;
        this.portExplicitlySet = true;
    }

    public void setHandlerFactory(FTPConnectionHandlerFactory factory) {
        this.handlerFactory = factory;
    }

    public FTPConnectionHandlerFactory getHandlerFactory() {
        return handlerFactory;
    }

    /**
     * Sets whether TLS is required for data connections.
     * When true, data transfers will fail unless PROT P has been issued.
     *
     * @param require true to require TLS for data connections
     */
    public void setRequireTLSForData(boolean require) {
        this.requireTLSForData = require;
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
     */
    public void setRealm(Realm realm) {
        this.realm = realm;
    }

    /**
     * Checks if SSL/TLS context is available for AUTH TLS.
     *
     * @return true if AUTH TLS is supported, false otherwise
     */
    public boolean isSTARTTLSAvailable() {
        return context != null;
    }

    /**
     * Creates a new SSLEngine for a data connection.
     *
     * @return a new SSLEngine configured for server mode, or null if
     *         TLS not available
     */
    public SSLEngine createDataSSLEngine() {
        if (context == null) {
            return null;
        }
        SSLEngine engine = context.createSSLEngine();
        engine.setUseClientMode(false);
        if (needClientAuth) {
            engine.setNeedClientAuth(true);
        }
        // Don't request client certificates unless explicitly configured
        return engine;
    }

    public void start() {
        super.start();
        if (isMetricsEnabled()) {
            metrics = new FTPServerMetrics(getTelemetryConfig());
        }
    }

    /**
     * Returns the metrics for this endpoint, or null if telemetry is
     * not enabled.
     *
     * @return the FTP server metrics
     */
    public FTPServerMetrics getMetrics() {
        return metrics;
    }

    /**
     * No-op: server channel cleanup is handled centrally by
     * {@link org.bluezoo.gumdrop.Gumdrop#closeServerChannels} during
     * unregister/shutdown, so individual listeners do not need to
     * close their own channels.
     */
    public void stop() {
        // Gumdrop.closeServerChannels() handles cleanup centrally
    }

    /**
     * Sets the owning service. Called by {@link FTPService} during
     * wiring.
     *
     * @param service the owning service
     */
    void setService(FTPService service) {
        this.service = service;
    }

    /**
     * Returns the owning service, or null if used standalone.
     *
     * @return the owning service
     */
    public FTPService getService() {
        return service;
    }

    @Override
    protected ProtocolHandler createHandler() {
        FTPConnectionHandler handler = null;
        if (service != null) {
            try {
                handler = service.createHandler(this);
            } catch (Exception e) {
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.log(Level.WARNING,
                            "Failed to create FTP handler from service,"
                                    + " using default behaviour", e);
                }
            }
        } else if (handlerFactory != null) {
            try {
                handler = handlerFactory.createHandler();
            } catch (Exception e) {
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.log(Level.WARNING,
                            "Failed to create FTP handler,"
                                    + " using default behaviour", e);
                }
            }
        }
        return new FTPProtocolHandler(this, handler);
    }

}
