/*
 * Connector.java
 * Copyright (C) 2005, 2025 Chris Burdess
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
import org.bluezoo.gumdrop.util.SNIKeyManager;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIMatcher;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.StandardConstants;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;

/**
 * Abstract base class for connection factories.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class Connector {

    private static final Logger LOGGER = Logger.getLogger(Connector.class.getName());

    protected boolean secure = false;
    protected SSLContext context;
    protected String keystoreFile, keystorePass, keystoreFormat = "PKCS12";
    protected TelemetryConfig telemetryConfig;
    
    // SNI (Server Name Indication) configuration
    private Map<String, String> sniHostnameToAlias;
    private String sniDefaultAlias;

    protected Connector() {
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
     * When set, connections created by this connector will have telemetry enabled.
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

    final Connection newConnection(SocketChannel sc, SelectionKey key) throws IOException {
        SSLEngine engine = null;
        // Always create SSLEngine if SSL context is available (regardless of secure flag)
        // This enables STARTTLS capability even on secure=false connectors
        if (context != null) {
            InetSocketAddress peerAddress = (InetSocketAddress) sc.getRemoteAddress();
            // Use getHostString() instead of getHostName() to avoid DNS reverse lookup
            String peerHost = peerAddress.getHostString();
            int peerPort = peerAddress.getPort();
            engine = context.createSSLEngine(peerHost, peerPort);
            configureSSLEngine(engine);
        }
        Connection connection = newConnection(sc, engine);
        connection.connector = this;
        connection.channel = sc;
        connection.init();
        return connection;
    }

    /**
     * Returns a new connection for the given channel.
     *
     * @param channel the socket channel
     * @param engine if this connector is secure, the SSL engine to use
     */
    protected abstract Connection newConnection(SocketChannel channel, SSLEngine engine);

    /**
     * Configures SSL engine settings.
     * Default implementation sets useClientMode to false and configures SNI if enabled.
     * Subclasses can override to add additional SSL configuration.
     *
     * @param engine the SSL engine to configure
     */
    protected void configureSSLEngine(SSLEngine engine) {
        engine.setUseClientMode(false);
        
        // Configure SNI matchers if SNI is enabled
        if (isSNIEnabled()) {
            SSLParameters params = engine.getSSLParameters();
            params.setSNIMatchers(java.util.Collections.singleton(new SNIMatcher(StandardConstants.SNI_HOST_NAME) {
                @Override
                public boolean matches(javax.net.ssl.SNIServerName serverName) {
                    // Accept all SNI hostnames - the SNIKeyManager will handle selection
                    // This matcher just enables SNI processing
                    if (serverName instanceof SNIHostName) {
                        String hostname = ((SNIHostName) serverName).getAsciiName();
                        if (LOGGER.isLoggable(Level.FINE)) {
                            LOGGER.fine("SNI hostname received: " + hostname);
                        }
                    }
                    return true;
                }
            }));
            engine.setSSLParameters(params);
        }
    }

    /**
     * Indicates whether connections created by this connector should use
     * transport layer security immediately.
     */
    public boolean isSecure() {
        return secure;
    }

    /**
     * Sets whether connections created by this connector should use
     * transport layer security immediately.
     * If true, you should also specify a key store for the server certificate.
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
     * Sets the SNI (Server Name Indication) hostname to certificate alias mapping.
     * 
     * <p>This allows the server to present different certificates based on the
     * hostname requested by the client during TLS handshake. The keystore must
     * contain certificates with the specified aliases.
     * 
     * <p>Hostname keys can be:
     * <ul>
     * <li>Exact hostnames: "example.com"</li>
     * <li>Wildcard patterns: "*.example.com" (matches any subdomain)</li>
     * </ul>
     * 
     * <p>Example configuration in gumdroprc:
     * <pre>
     * &lt;property name="sniHostnames"&gt;
     *     &lt;map&gt;
     *         &lt;entry key="example.com" value="example-cert"/&gt;
     *         &lt;entry key="*.example.com" value="example-wildcard"/&gt;
     *         &lt;entry key="other.com" value="other-cert"/&gt;
     *     &lt;/map&gt;
     * &lt;/property&gt;
     * </pre>
     *
     * @param hostnames map of hostnames to certificate aliases
     */
    public void setSniHostnames(Map<String, String> hostnames) {
        this.sniHostnameToAlias = hostnames != null ? new LinkedHashMap<>(hostnames) : null;
    }
    
    /**
     * Sets the default certificate alias to use when SNI hostname is not matched
     * or when the client doesn't provide SNI.
     *
     * @param alias the default certificate alias in the keystore
     */
    public void setSniDefaultAlias(String alias) {
        this.sniDefaultAlias = alias;
    }
    
    /**
     * Returns true if SNI (Server Name Indication) is configured for this connector.
     *
     * @return true if SNI hostname mappings have been configured
     */
    public boolean isSNIEnabled() {
        return sniHostnameToAlias != null && !sniHostnameToAlias.isEmpty();
    }

    /**
     * Starts this connector.
     * Initializes SSL context if SSL configuration is provided.
     */
    protected void start() {
        // Validation: secure=true REQUIRES SSL configuration
        if (secure && (keystoreFile == null || keystorePass == null)) {
            String message = Gumdrop.L10N.getString("err.no_keystore");
            throw new RuntimeException("Secure connector requires keystore configuration: " + message);
        }

        // Create SSL context if SSL configuration is provided
        if (keystoreFile != null && keystorePass != null) {
            try {
                KeyStore ks = KeyStore.getInstance(keystoreFormat);
                InputStream in = new FileInputStream(keystoreFile);
                char[] pass = keystorePass.toCharArray();
                ks.load(in, pass);
                in.close();
                KeyManagerFactory f = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                f.init(ks, pass);
                context = SSLContext.getInstance("TLS");
                KeyManager[] km = f.getKeyManagers();
                
                // Wrap with SNI-aware key manager if SNI is configured
                if (isSNIEnabled()) {
                    km = wrapWithSNIKeyManager(km);
                    if (LOGGER.isLoggable(Level.INFO)) {
                        LOGGER.info("SNI enabled with " + sniHostnameToAlias.size() + 
                                   " hostname mapping(s)");
                    }
                }
                
                TrustManager[] tm = new TrustManager[1];
                tm[0] = new EmptyX509TrustManager();
                SecureRandom random = new SecureRandom();
                context.init(km, tm, random);
            } catch (Exception e) {
                RuntimeException e2 = new RuntimeException("Failed to initialize SSL context");
                e2.initCause(e);
                throw e2;
            }
        }
    }
    
    /**
     * Wraps the key managers with SNI-aware key managers.
     */
    private KeyManager[] wrapWithSNIKeyManager(KeyManager[] keyManagers) {
        KeyManager[] wrapped = new KeyManager[keyManagers.length];
        for (int i = 0; i < keyManagers.length; i++) {
            if (keyManagers[i] instanceof X509KeyManager) {
                wrapped[i] = new SNIKeyManager(
                    (X509KeyManager) keyManagers[i],
                    sniHostnameToAlias,
                    sniDefaultAlias
                );
            } else {
                wrapped[i] = keyManagers[i];
            }
        }
        return wrapped;
    }

    /**
     * Stops this connector.
     */
    protected void stop() {
        // Nothing to clean up now that thread pool is removed
    }

    /**
     * Returns a short (one word) description of this connector.
     */
    protected abstract String getDescription();
}
