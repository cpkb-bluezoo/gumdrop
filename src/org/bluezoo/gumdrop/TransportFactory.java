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

import org.bluezoo.gumdrop.crypto.NamedGroup;
import org.bluezoo.gumdrop.tls.KeystoreFormat;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.EventLogger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
 * <li>TcpTransportFactory -- the in-tree {@link org.bluezoo.gumdrop.tls}
 *     engine, TLS 1.3 or TLS 1.2 (a deployment-time choice, see
 *     {@code TcpTransportFactory#setTlsVersion})</li>
 * <li>UdpTransportFactory -- in-tree DTLS 1.2 engine (see {@code Dtls12RecordEngine})</li>
 * <li>{@link org.bluezoo.gumdrop.quic.QuicTransportFactory} -- the
 *     pure-Java {@link org.bluezoo.gumdrop.quic} engine (always TLS 1.3)</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Endpoint
 * @see ProtocolHandler
 */
public abstract class TransportFactory {


    /** Default maximum network input buffer size: 1 MB */
    public static final int DEFAULT_MAX_NET_IN_SIZE = 1024 * 1024;

    /** Default maximum network output buffer size: 4 MB */
    public static final int DEFAULT_MAX_NET_OUT_SIZE = 4 * 1024 * 1024;

    // -- Security configuration --

    protected boolean secure;
    protected Path keystoreFile;
    protected String keystorePass;
    protected KeystoreFormat keystoreFormat = KeystoreFormat.PKCS12;

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
    protected KeystoreFormat truststoreFormat = KeystoreFormat.PKCS12;

    /**
     * TLS 1.3 cipher suites (colon-separated IANA names).
     * Example: "TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256"
     * Consulted by all transports using the in-tree TLS stack. Unrecognised
     * names are logged and skipped; QUIC additionally filters against what
     * its AEAD layer implements (AES-128/256-GCM, ChaCha20-Poly1305).
     */
    protected String cipherSuites;

    /**
     * Allowed key exchange groups / named curves (colon-separated),
     * by IANA TLS Supported Groups registry name, e.g.
     * "X25519MLKEM768:x25519:secp256r1". Hybrid post-quantum groups
     * ({@code X25519MLKEM768}, {@code SecP256r1MLKEM768},
     * {@code SecP384r1MLKEM1024}) are supported by the in-tree
     * {@link org.bluezoo.gumdrop.tls.HandshakeEngine}. A client offers these
     * groups; a server negotiates the first of them that the client
     * also supports.
     */
    protected String namedGroups;

    /**
     * Optional pinned server certificate fingerprint for client-side
     * connections. When set, the client verifies that the server's
     * leaf certificate matches this SHA-256 fingerprint after the
     * normal chain validation. Format: colon-separated lowercase hex
     * (e.g. "ab:cd:ef:01:23:...") with optional "SHA-256:" prefix.
     */
    protected String pinnedCertFingerprint;

    /** Server: binary {@code ECHConfigList} file for TLS 1.3 ECH decryption. */
    protected Path echConfigListFile;
    /** Server: 32-byte X25519 ECH private key file (raw or hex). */
    protected Path echPrivateKeyFile;
    /** Server: require clients to offer outer ECH (RFC 9849 section 7.3). */
    protected boolean echServerRequired;

    // -- Telemetry --

    protected TelemetryConfig telemetryConfig;
    private TelemetryConfig standaloneTelemetry;

    /**
     * Returns the telemetry configuration this factory's events go to:
     * the one it was given, else one of its own, which prints events
     * through {@code java.util.logging}. Never null.
     *
     * @return the configuration
     */
    protected synchronized TelemetryConfig eventTelemetry() {
        if (telemetryConfig != null) {
            return telemetryConfig;
        }
        if (standaloneTelemetry == null) {
            standaloneTelemetry = new TelemetryConfig();
        }
        return standaloneTelemetry;
    }

    private EventLogger events() {
        return eventTelemetry().getLogger(TransportFactory.class, Gumdrop.L10N);
    }

    // -- Buffer limits --

    private int maxNetInSize = DEFAULT_MAX_NET_IN_SIZE;
    private int maxNetOutSize = DEFAULT_MAX_NET_OUT_SIZE;

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
     * Used by all transports (TCP/TLS, DTLS, QUIC) via
     * {@link org.bluezoo.gumdrop.util.TlsUtils}.
     *
     * @param file the keystore file path
     */
    public void setKeystoreFile(Path file) {
        this.keystoreFile = file;
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
    public void setKeystoreFormat(KeystoreFormat format) {
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
    public void setTruststoreFormat(KeystoreFormat format) {
        this.truststoreFormat = format;
    }

    /**
     * Sets the PEM certificate chain file path.
     *
     * <p>Alternative to {@link #setKeystoreFile} on any transport. Loaded
     * into {@link org.bluezoo.gumdrop.tls.ServerCredentials} via
     * {@link org.bluezoo.gumdrop.quic.tls.PemCredentials}.
     *
     * @param path the certificate chain PEM file path
     */
    public void setCertFile(Path path) {
        this.certFile = path;
    }

    /**
     * Sets the PEM private key file path.
     *
     * <p>Alternative to {@link #setKeystoreFile} on any transport. Must be
     * unencrypted PEM (PKCS#8 or traditional format).
     *
     * @param path the private key PEM file path
     */
    public void setKeyFile(Path path) {
        this.keyFile = path;
    }

    /**
     * Sets the allowed TLS 1.3 cipher suites.
     *
     * <p>Accepts a colon-separated list of cipher suite names in
     * canonical (IANA) form. Applied by the in-tree handshake engines on
     * TCP, DTLS, and QUIC client connections; QUIC server listeners filter
     * against the QUIC AEAD layer (see {@code QuicTransportFactory}).
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
     * <p>Accepts a colon-separated list of IANA TLS Supported Groups
     * registry names (case-insensitive), in preference order; an
     * unrecognised name is logged and skipped. Honoured by the in-tree
     * {@link org.bluezoo.gumdrop.tls.HandshakeEngine} on TCP, DTLS, and
     * QUIC, client and server.
     *
     * @param namedGroups the named group list
     */
    public void setNamedGroups(String namedGroups) {
        this.namedGroups = namedGroups;
    }

    /**
     * Sets an optional pinned server certificate fingerprint for
     * client-side connections.
     *
     * <p>When set, the client verifies that the server's leaf
     * certificate has this SHA-256 fingerprint after the normal chain
     * validation. If it doesn't match, the handshake fails.
     *
     * @param fingerprint colon-separated hex with optional
     *                    "SHA-256:" prefix
     */
    public void setPinnedCertFingerprint(String fingerprint) {
        if (fingerprint != null && fingerprint.startsWith("SHA-256:")) {
            fingerprint = fingerprint.substring(8);
        }
        this.pinnedCertFingerprint = fingerprint != null
                ? fingerprint.toLowerCase() : null;
    }

    /**
     * Returns the pinned server certificate fingerprint.
     *
     * @return the fingerprint, or null if not configured
     */
    public String getPinnedCertFingerprint() {
        return pinnedCertFingerprint;
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

    /**
     * Returns the maximum outbound network buffer size in bytes. When a
     * connection's pending write buffer would exceed this size (because the
     * peer is not draining), the connection is closed instead of buffering
     * unbounded data off-heap. 0 disables the limit.
     *
     * @return the maximum outbound buffer size in bytes, or 0 for unlimited
     */
    public int getMaxNetOutSize() {
        return maxNetOutSize;
    }

    /**
     * Sets the maximum outbound network buffer size in bytes.
     *
     * @param size the maximum buffer size in bytes, or 0 for unlimited
     */
    public void setMaxNetOutSize(int size) {
        this.maxNetOutSize = size;
    }

    public void setEchConfigListFile(Path echConfigListFile) {
        this.echConfigListFile = echConfigListFile;
    }

    public void setEchPrivateKeyFile(Path echPrivateKeyFile) {
        this.echPrivateKeyFile = echPrivateKeyFile;
    }

    public void setEchServerRequired(boolean echServerRequired) {
        this.echServerRequired = echServerRequired;
    }

    public Path getEchConfigListFile() {
        return echConfigListFile;
    }

    public Path getEchPrivateKeyFile() {
        return echPrivateKeyFile;
    }

    public boolean isEchServerRequired() {
        return echServerRequired;
    }

    // -- Lifecycle --

    /**
     * Starts this factory.
     *
     * <p>Subclasses must call {@code super.start()} and then initialise
     * their transport-specific security context (in-tree TLS engines,
     * QUIC {@code SSL_CTX}, etc.).
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
    /**
     * Resolves a colon-separated {@link #setNamedGroups} value against
     * {@link NamedGroup}, in configured order. Names are those of the
     * IANA TLS Supported Groups registry; an unrecognised name is logged
     * and skipped.
     *
     * @param raw the configured value, or null
     * @return the resolved groups, or null if none were configured or
     *         recognised (the engine's own default order then applies)
     */
    List<NamedGroup> resolveNamedGroups(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        List<NamedGroup> resolved = new ArrayList<NamedGroup>();
        String[] names = raw.split(":");
        for (int i = 0; i < names.length; i++) {
            String name = names[i].trim();
            if (name.isEmpty()) {
                continue;
            }
            NamedGroup group = NamedGroup.fromName(name);
            if (group != null) {
                resolved.add(group);
            } else {
                events().warn("warn.unrecognized_named_group").attr("group", name).emit();
            }
        }
        return resolved.isEmpty() ? null : resolved;
    }

    /**
     * Narrows a {@link #setNamedGroups} value to the classical groups the
     * TLS 1.2 / DTLS 1.2 engine implements: {@code x25519} and
     * {@code secp256r1}, in configured order. Hybrid and other groups are
     * TLS 1.3 only.
     *
     * @param raw the configured value, or null
     * @param warn whether to log the entries dropped, and a fallback to
     *        the default; false when the same value also drives a TLS 1.3
     *        engine that does use the dropped entries
     * @return the usable groups, or null for the engine's default
     *         (x25519 then secp256r1)
     */
    List<NamedGroup> resolveTls12NamedGroups(String raw, boolean warn) {
        List<NamedGroup> all = resolveNamedGroups(raw);
        if (all == null) {
            return null;
        }
        List<NamedGroup> usable = new ArrayList<NamedGroup>();
        for (int i = 0; i < all.size(); i++) {
            NamedGroup group = all.get(i);
            if (group == NamedGroup.X25519 || group == NamedGroup.SECP256R1) {
                if (!usable.contains(group)) {
                    usable.add(group);
                }
            } else if (warn) {
                events().warn("warn.tls12_named_groups_ignored").attr("group", group.getName()).emit();
            }
        }
        if (usable.isEmpty()) {
            if (warn) {
                events().warn("warn.tls12_named_groups_default").attr("named_groups", raw).emit();
            }
            return null;
        }
        return usable;
    }

}
