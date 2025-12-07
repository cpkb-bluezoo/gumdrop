/*
 * DatagramConnector.java
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

import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.util.EmptyX509TrustManager;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;

/**
 * Abstract base class for datagram (UDP) connection factories.
 * Provides DTLS context management similar to how {@link Connector}
 * manages TLS for TCP connections.
 *
 * <p>Subclasses include:
 * <ul>
 * <li>{@link DatagramServer} - for receiving datagrams on a bound port</li>
 * <li>{@link DatagramClient} - for sending datagrams to a remote address</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class DatagramConnector {

    private static final Logger LOGGER = Logger.getLogger(DatagramConnector.class.getName());

    protected boolean secure = false;
    protected SSLContext dtlsContext;
    protected String keystoreFile, keystorePass, keystoreFormat = "PKCS12";
    protected TelemetryConfig telemetryConfig;

    protected DatagramConnector() {
    }

    /**
     * Returns the telemetry configuration for this connector.
     *
     * @return the telemetry configuration, or null if not configured
     */
    public TelemetryConfig getTelemetryConfig() {
        return telemetryConfig;
    }

    /**
     * Sets the telemetry configuration for this connector.
     *
     * @param telemetryConfig the telemetry configuration
     */
    public void setTelemetryConfig(TelemetryConfig telemetryConfig) {
        this.telemetryConfig = telemetryConfig;
    }

    /**
     * Returns true if telemetry is enabled for this connector.
     * Telemetry is enabled when a TelemetryConfig has been set.
     *
     * @return true if telemetry is enabled
     */
    public boolean isTelemetryEnabled() {
        return telemetryConfig != null;
    }

    /**
     * Indicates whether this connector uses DTLS for encryption.
     *
     * @return true if DTLS is enabled
     */
    public boolean isSecure() {
        return secure;
    }

    /**
     * Sets whether this connector should use DTLS for encryption.
     * If true, you should also specify a key store for the certificate.
     *
     * @param flag whether this connector should be secure
     */
    public void setSecure(boolean flag) {
        secure = flag;
    }

    public void setKeystoreFile(String file) {
        keystoreFile = file;
    }

    public void setKeystorePass(String pass) {
        keystorePass = pass;
    }

    public void setKeystoreFormat(String format) {
        keystoreFormat = format;
    }

    /**
     * Creates a DTLS engine for the given remote address.
     * DTLS engines are created per-remote-address for session management.
     *
     * @param remoteAddress the remote address for the DTLS session
     * @return the configured SSLEngine, or null if DTLS is not enabled
     */
    protected SSLEngine createDTLSEngine(InetSocketAddress remoteAddress) {
        if (dtlsContext == null) {
            return null;
        }
        SSLEngine engine = dtlsContext.createSSLEngine(
                remoteAddress.getHostString(),
                remoteAddress.getPort()
        );
        configureDTLSEngine(engine);
        return engine;
    }

    /**
     * Configures DTLS engine settings.
     * Default implementation sets useClientMode to false (server mode).
     * Subclasses can override to add additional DTLS configuration.
     *
     * @param engine the DTLS engine to configure
     */
    protected void configureDTLSEngine(SSLEngine engine) {
        engine.setUseClientMode(false);
    }

    /**
     * Starts this connector.
     * Initializes DTLS context if SSL configuration is provided.
     */
    protected void start() {
        // Validation: secure=true REQUIRES SSL configuration
        if (secure && (keystoreFile == null || keystorePass == null)) {
            String message = Gumdrop.L10N.getString("err.no_keystore");
            throw new RuntimeException("Secure datagram connector requires keystore configuration: " + message);
        }

        // Create DTLS context if SSL configuration is provided
        if (keystoreFile != null && keystorePass != null) {
            try {
                KeyStore ks = KeyStore.getInstance(keystoreFormat);
                InputStream in = new FileInputStream(keystoreFile);
                char[] pass = keystorePass.toCharArray();
                ks.load(in, pass);
                in.close();
                KeyManagerFactory f = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                f.init(ks, pass);
                dtlsContext = SSLContext.getInstance("DTLSv1.2");
                KeyManager[] km = f.getKeyManagers();
                TrustManager[] tm = new TrustManager[1];
                tm[0] = new EmptyX509TrustManager();
                SecureRandom random = new SecureRandom();
                dtlsContext.init(km, tm, random);
            } catch (Exception e) {
                RuntimeException e2 = new RuntimeException("Failed to initialize DTLS context");
                e2.initCause(e);
                throw e2;
            }
        }
    }

    /**
     * Stops this connector.
     */
    protected void stop() {
        // Subclasses can override for cleanup
    }

    /**
     * Returns a short (one word) description of this connector.
     *
     * @return the description
     */
    protected abstract String getDescription();

    /**
     * Handles a read error on this connector.
     * Default implementation logs the error.
     *
     * @param e the exception that occurred
     */
    void handleReadError(IOException e) {
        LOGGER.log(Level.WARNING, "Datagram read error on " + getDescription(), e);
    }

    /**
     * Handles a write error on this connector.
     * Default implementation logs the error.
     *
     * @param e the exception that occurred
     */
    void handleWriteError(IOException e) {
        LOGGER.log(Level.WARNING, "Datagram write error on " + getDescription(), e);
    }

}

