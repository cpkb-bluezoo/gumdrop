/*
 * TransportFactory.java
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

import org.bluezoo.gumdrop.telemetry.TelemetryConfig;

import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Abstract base class for transport endpoint factories.
 *
 * <p>TransportFactory provides a shared configuration surface for all
 * transport types (TCP, UDP, QUIC):
 * <ul>
 * <li>Security configuration (keystore, certificates, cipher suites,
 *     named groups)</li>
 * <li>Telemetry configuration</li>
 * <li>Network buffer limits</li>
 * </ul>
 *
 * <p>Subclasses translate the shared configuration into the appropriate
 * backend:
 * <ul>
 * <li>TCPTransportFactory -- JSSE SSLContext / SSLEngine</li>
 * <li>UDPTransportFactory -- JSSE SSLContext / SSLEngine for DTLS</li>
 * <li>QuicTransportFactory -- quiche config / BoringSSL SSL_CTX via JNI</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Endpoint
 * @see ProtocolHandler
 */
public abstract class TransportFactory {

    private static final Logger LOGGER =
            Logger.getLogger(TransportFactory.class.getName());

    /** Default maximum network input buffer size: 1 MB */
    public static final int DEFAULT_MAX_NET_IN_SIZE = 1024 * 1024;

    // -- Security configuration --

    protected boolean secure;
    protected Path keystoreFile;
    protected String keystorePass;
    protected String keystoreFormat = "PKCS12";

    /**
     * PEM certificate chain file path (used by QUIC; also accepted for TCP
     * as an alternative to keystore).
     */
    protected Path certFile;

    /**
     * PEM private key file path (used by QUIC; also accepted for TCP
     * as an alternative to keystore).
     */
    protected Path keyFile;

    /**
     * Truststore file for validating peer certificates (e.g. client certs
     * in mutual TLS). When null, the JVM default truststore is used.
     */
    protected Path truststoreFile;

    /**
     * Truststore password.
     */
    protected String truststorePass;

    /**
     * Truststore format (default PKCS12).
     */
    protected String truststoreFormat = "PKCS12";

    /**
     * TLS 1.3 cipher suites (colon-separated IANA names).
     * Applies to both JSSE and BoringSSL backends.
     * Example: "TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256"
     */
    protected String cipherSuites;

    /**
     * Allowed key exchange groups / named curves (colon-separated).
     * For PQC: "X25519MLKEM768" (hybrid), "MLKEM768" (pure PQ).
     * For classical: "X25519", "secp256r1", "secp384r1".
     */
    protected String namedGroups;

    // -- Telemetry --

    protected TelemetryConfig telemetryConfig;

    // -- Buffer limits --

    private int maxNetInSize = DEFAULT_MAX_NET_IN_SIZE;

    protected TransportFactory() {
    }

    // -- Security setters --

    /**
     * Sets whether endpoints created by this factory are secured immediately.
     *
     * <p>For TCP, false means plaintext with optional STARTTLS.
     * For QUIC, this is always effectively true (QUIC mandates TLS 1.3).
     *
     * @param secure true for immediate security
     */
    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    /**
     * Returns whether endpoints created by this factory are secured.
     *
     * @return true if secure
     */
    public boolean isSecure() {
        return secure;
    }

    /**
     * Sets the Java keystore file path.
     * Used by TCP (JSSE) and UDP (JSSE DTLS) transports.
     *
     * @param file the keystore file path
     */
    public void setKeystoreFile(Path file) {
        this.keystoreFile = file;
    }

    /**
     * Sets the Java keystore file path from a string.
     *
     * @param file the keystore file path
     */
    public void setKeystoreFile(String file) {
        this.keystoreFile = Path.of(file);
    }

    /**
     * Sets the Java keystore password.
     *
     * @param pass the keystore password
     */
    public void setKeystorePass(String pass) {
        this.keystorePass = pass;
    }

    /**
     * Sets the Java keystore format.
     * Defaults to "PKCS12".
     *
     * @param format the keystore format (e.g., "PKCS12", "JKS")
     */
    public void setKeystoreFormat(String format) {
        this.keystoreFormat = format;
    }

    /**
     * Sets the truststore file path for validating peer certificates.
     *
     * <p>For server mode this is used to validate client certificates
     * when mutual TLS is enabled ({@code needClientAuth}).
     * When not set, the JVM default truststore is used.
     *
     * @param file the truststore file path
     */
    public void setTruststoreFile(Path file) {
        this.truststoreFile = file;
    }

    /**
     * Sets the truststore file path from a string.
     *
     * @param file the truststore file path
     */
    public void setTruststoreFile(String file) {
        this.truststoreFile = Path.of(file);
    }

    /**
     * Sets the truststore password.
     *
     * @param pass the truststore password
     */
    public void setTruststorePass(String pass) {
        this.truststorePass = pass;
    }

    /**
     * Sets the truststore format.
     * Defaults to "PKCS12".
     *
     * @param format the truststore format (e.g., "PKCS12", "JKS")
     */
    public void setTruststoreFormat(String format) {
        this.truststoreFormat = format;
    }

    /**
     * Sets the PEM certificate chain file path.
     *
     * <p>Required for QUIC (BoringSSL loads PEM directly).
     * Also accepted for TCP/UDP as an alternative to keystore
     * configuration.
     *
     * @param path the certificate chain PEM file path
     */
    public void setCertFile(Path path) {
        this.certFile = path;
    }

    /**
     * Sets the PEM certificate chain file path from a string.
     *
     * @param path the certificate chain PEM file path
     */
    public void setCertFile(String path) {
        this.certFile = Path.of(path);
    }

    /**
     * Sets the PEM private key file path.
     *
     * <p>Required for QUIC (BoringSSL loads PEM directly).
     * Also accepted for TCP/UDP as an alternative to keystore
     * configuration.
     *
     * @param path the private key PEM file path
     */
    public void setKeyFile(Path path) {
        this.keyFile = path;
    }

    /**
     * Sets the PEM private key file path from a string.
     *
     * @param path the private key PEM file path
     */
    public void setKeyFile(String path) {
        this.keyFile = Path.of(path);
    }

    /**
     * Sets the allowed TLS 1.3 cipher suites.
     *
     * <p>Accepts a colon-separated list of cipher suite names in
     * canonical (IANA) form. The transport subclass maps these to
     * the appropriate backend (JSSE or BoringSSL).
     *
     * <p>Example: "TLS_AES_256_GCM_SHA384:TLS_AES_128_GCM_SHA256"
     *
     * @param cipherSuites the cipher suite list
     */
    public void setCipherSuites(String cipherSuites) {
        this.cipherSuites = cipherSuites;
    }

    /**
     * Sets the allowed key exchange groups / named curves.
     *
     * <p>Accepts a colon-separated list of group names in canonical form.
     * The transport subclass maps these to the appropriate backend:
     * <ul>
     * <li>JSSE: {@code SSLParameters.setNamedGroups()}</li>
     * <li>BoringSSL: {@code SSL_CTX_set1_groups_list()}</li>
     * </ul>
     *
     * <p>For PQC enforcement, set this to "X25519MLKEM768" to require
     * hybrid post-quantum key exchange.
     *
     * @param namedGroups the named group list
     */
    public void setNamedGroups(String namedGroups) {
        this.namedGroups = namedGroups;
    }

    // -- Telemetry --

    /**
     * Returns the telemetry configuration for this factory.
     *
     * @return the telemetry configuration, or null if not configured
     */
    public TelemetryConfig getTelemetryConfig() {
        return telemetryConfig;
    }

    /**
     * Sets the telemetry configuration for this factory.
     *
     * @param telemetryConfig the telemetry configuration
     */
    public void setTelemetryConfig(TelemetryConfig telemetryConfig) {
        this.telemetryConfig = telemetryConfig;
    }

    /**
     * Returns true if telemetry is enabled.
     *
     * @return true if a TelemetryConfig has been set
     */
    public boolean isTelemetryEnabled() {
        return telemetryConfig != null;
    }

    /**
     * Returns true if metrics collection is enabled.
     *
     * @return true if telemetry is configured with metrics enabled
     */
    protected boolean isMetricsEnabled() {
        return telemetryConfig != null && telemetryConfig.isMetricsEnabled();
    }

    // -- Buffer limits --

    /**
     * Returns the maximum allowed size for the network input buffer.
     *
     * @return the maximum buffer size in bytes
     */
    public int getMaxNetInSize() {
        return maxNetInSize;
    }

    /**
     * Sets the maximum allowed size for the network input buffer.
     *
     * <p>If an endpoint's input buffer exceeds this limit, the endpoint
     * will be closed with an error. Set to 0 to disable the limit
     * (not recommended).
     *
     * @param size the maximum buffer size in bytes
     */
    public void setMaxNetInSize(int size) {
        this.maxNetInSize = size;
    }

    // -- Lifecycle --

    /**
     * Starts this factory.
     *
     * <p>Subclasses must call {@code super.start()} and then initialise
     * their transport-specific security context (JSSE SSLContext,
     * BoringSSL SSL_CTX, etc.).
     */
    public void start() {
    }

    /**
     * Stops this factory and releases resources.
     */
    protected void stop() {
    }

    /**
     * Returns a short description of this factory for logging.
     *
     * @return the description
     */
    protected abstract String getDescription();
}
