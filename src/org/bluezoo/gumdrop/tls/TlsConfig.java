/*
 * TlsConfig.java
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

package org.bluezoo.gumdrop.tls;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.net.ssl.X509TrustManager;

/**
 * TLS identity and trust material — the same type for both a server-side
 * {@link org.bluezoo.gumdrop.Listener} (HTTPS, HTTP/3, SMTPS, DoT, …) and an outbound client
 * facade.
 *
 * <p>A {@code TlsConfig} holds <strong>material only</strong>: the identity
 * a side presents (certificate/key or keystore, or {@link ServerCredentials})
 * and the trust it extends to its peer ({@link #verifyPeer}, {@link
 * #trustManager}). It says nothing about <em>when</em> TLS starts — that is
 * a property of the consumer, not this config: {@link org.bluezoo.gumdrop.Listener#secure(boolean)}
 * on the server side, and each client facade's own {@code secure(boolean)}
 * on the client side. Supplying material here only makes TLS (and, on a
 * STARTTLS-capable protocol, an upgrade) <em>possible</em>; it never by
 * itself forces a connection to start encrypted.
 *
 * <p>Use the same {@code TlsConfig} on {@link
 * org.bluezoo.gumdrop.http.server.Http2Listener} and {@link
 * org.bluezoo.gumdrop.http.h3.Http3Listener}, or pass it to {@link
 * org.bluezoo.gumdrop.http.HttpServer.Composer#secureEndpoint(int, TlsConfig)}
 * to wire both HTTP transports on one port — exactly this kind of sharing
 * (one config, multiple consumers with different immediacy needs) is why
 * immediacy cannot live on this class. Build one with a static factory
 * ({@link #pem}, {@link #keystore}, {@link #credentials}) for identity
 * material up front, or construct it fluently and add trust/identity
 * incrementally — client facades hold one as a long-lived field and mutate
 * it before {@code connect()}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.Listener#tls(TlsConfig)
 * @see <a href="https://github.com/cpkb-bluezoo/gumdrop/blob/main/web/configuration.html">web/configuration.html</a>
 */
public final class TlsConfig {

    private Path certFile;
    private Path keyFile;
    private Path keystoreFile;
    private String keystorePass;
    private KeystoreFormat keystoreFormat;
    private ServerCredentials serverCredentials;
    private boolean verifyPeer = true;
    private boolean earlyData;
    private String cipherSuites;
    private String namedGroups;
    private TlsVersion tlsVersion;
    private DtlsVersion dtlsVersion;
    private final Map<String, String> sniHostnames = new LinkedHashMap<String, String>();
    private String sniDefaultAlias;
    private boolean requireClientAuth;
    private X509TrustManager trustManager;
    private Path echConfigListFile;
    private Path echPrivateKeyFile;
    private boolean echServerRequired;
    private Path clientEchConfigListFile;
    private boolean clientEchGreaseEnabled;
    private boolean clientEchDnsDiscovery;
    private boolean clientEchRequired;
    private boolean requireCookie;
    private byte[] cookieSecret;
    private int maxFragmentSize;

    /**
     * Creates an empty config for fluent configuration.
     */
    public TlsConfig() {
    }

    /**
     * TLS identity from PEM certificate chain and private key files.
     */
    public static TlsConfig pem(Path certFile, Path keyFile) {
        if (certFile == null || keyFile == null) {
            throw new NullPointerException("certFile and keyFile are required");
        }
        TlsConfig config = new TlsConfig();
        config.certFile = certFile;
        config.keyFile = keyFile;
        return config;
    }

    /**
     * TLS identity from a PKCS#12 or JKS keystore (default format PKCS12).
     */
    public static TlsConfig keystore(Path keystoreFile, String keystorePass) {
        return keystore(keystoreFile, keystorePass, KeystoreFormat.PKCS12);
    }

    /**
     * TLS identity from a keystore with an explicit format.
     */
    public static TlsConfig keystore(Path keystoreFile, String keystorePass,
                                     KeystoreFormat keystoreFormat) {
        if (keystoreFile == null || keystorePass == null) {
            throw new NullPointerException("keystoreFile and keystorePass are required");
        }
        TlsConfig config = new TlsConfig();
        config.keystoreFile = keystoreFile;
        config.keystorePass = keystorePass;
        config.keystoreFormat = keystoreFormat;
        return config;
    }

    /**
     * TLS identity from loaded {@link ServerCredentials}.
     */
    public static TlsConfig credentials(ServerCredentials serverCredentials) {
        if (serverCredentials == null) {
            throw new NullPointerException("serverCredentials");
        }
        TlsConfig config = new TlsConfig();
        config.serverCredentials = serverCredentials;
        return config;
    }

    /**
     * Enables TLS 1.3 early data (0-RTT) where the transport supports it
     * (QUIC). Early data is replayable, so it is off by default.
     *
     * @param earlyData whether to send or accept early data
     * @return this config
     */
    public TlsConfig earlyData(boolean earlyData) {
        this.earlyData = earlyData;
        return this;
    }

    /**
     * Restricts the TLS 1.3 cipher suites (colon-separated IANA names).
     *
     * @param cipherSuites the suites, or null for the default set
     * @return this config
     */
    public TlsConfig cipherSuites(String cipherSuites) {
        this.cipherSuites = cipherSuites;
        return this;
    }

    /**
     * Restricts the key exchange groups (colon-separated names, for example
     * {@code X25519:SECP256R1}).
     *
     * @param namedGroups the groups, or null for the default set
     * @return this config
     */
    public TlsConfig namedGroups(String namedGroups) {
        this.namedGroups = namedGroups;
        return this;
    }

    /**
     * Pins the TLS protocol version of a TCP listener. Not negotiated per
     * connection: to serve both TLS 1.2 and TLS 1.3 peers use two listeners.
     * QUIC is always TLS 1.3.
     *
     * @param tlsVersion the version, or null to negotiate
     * @return this config
     */
    public TlsConfig tlsVersion(TlsVersion tlsVersion) {
        this.tlsVersion = tlsVersion;
        return this;
    }

    /**
     * Pins the DTLS protocol version of a UDP listener.
     *
     * @param dtlsVersion the version, or null to negotiate
     * @return this config
     */
    public TlsConfig dtlsVersion(DtlsVersion dtlsVersion) {
        this.dtlsVersion = dtlsVersion;
        return this;
    }

    /**
     * Presents a different server certificate for a host name, by Server Name
     * Indication. The alias names an entry in the keystore, so SNI needs a
     * keystore identity.
     *
     * @param hostname an exact host name or a {@code *.example.com} wildcard
     * @param alias the keystore alias to present
     * @return this config
     */
    public TlsConfig sni(String hostname, String alias) {
        if (hostname == null || alias == null) {
            throw new NullPointerException("hostname and alias are required");
        }
        sniHostnames.put(hostname, alias);
        return this;
    }

    /**
     * The keystore alias presented when no SNI host name matches.
     *
     * @param alias the alias, or null
     * @return this config
     */
    public TlsConfig sniDefaultAlias(String alias) {
        this.sniDefaultAlias = alias;
        return this;
    }

    /**
     * Requires connecting clients to present a certificate (mutual TLS),
     * verified against {@link #trustManager(X509TrustManager)}.
     *
     * @param required whether client certificates are required
     * @return this config
     */
    public TlsConfig requireClientAuth(boolean required) {
        this.requireClientAuth = required;
        return this;
    }

    /**
     * Makes a DTLS server demand a valid cookie (RFC 6347 section 4.2.1, and
     * the HelloRetryRequest cookie of RFC 9147) before it allocates any
     * handshake state for a peer, so that a spoofed source address cannot make
     * it do work or reflect traffic. Only meaningful on a UDP listener; TCP
     * and QUIC ignore it. If no {@link #cookieSecret(byte[])} is set, the
     * listener generates a random one when it starts.
     *
     * @param required whether to require the cookie exchange
     * @return this config
     */
    public TlsConfig requireCookie(boolean required) {
        this.requireCookie = required;
        return this;
    }

    /**
     * Sets the secret the DTLS cookies are computed from. Give every server
     * instance behind one address the same secret so that a cookie issued by
     * one is accepted by another; leave it unset to use a random secret per
     * listener.
     *
     * @param secret the secret (copied), or null for a random one
     * @return this config
     */
    public TlsConfig cookieSecret(byte[] secret) {
        this.cookieSecret = secret != null ? secret.clone() : null;
        return this;
    }

    /**
     * Sets the largest handshake message fragment a DTLS server or client
     * sends in one datagram, which bounds memory and keeps flights under the
     * path MTU.
     *
     * @param bytes the size in bytes, or 0 for the default (1024)
     * @return this config
     */
    public TlsConfig maxFragmentSize(int bytes) {
        this.maxFragmentSize = bytes;
        return this;
    }

    public boolean isRequireCookie() {
        return requireCookie;
    }

    public byte[] getCookieSecret() {
        return cookieSecret != null ? cookieSecret.clone() : null;
    }

    public int getMaxFragmentSize() {
        return maxFragmentSize;
    }

    public String getCipherSuites() {
        return cipherSuites;
    }

    public String getNamedGroups() {
        return namedGroups;
    }

    public TlsVersion getTlsVersion() {
        return tlsVersion;
    }

    public DtlsVersion getDtlsVersion() {
        return dtlsVersion;
    }

    public Map<String, String> getSniHostnames() {
        return Collections.unmodifiableMap(sniHostnames);
    }

    public String getSniDefaultAlias() {
        return sniDefaultAlias;
    }

    public boolean isClientAuthRequired() {
        return requireClientAuth;
    }

    public boolean isEarlyDataEnabled() {
        return earlyData;
    }

    public TlsConfig verifyPeer(boolean verifyPeer) {
        this.verifyPeer = verifyPeer;
        return this;
    }

    /**
     * Explicitly trust the JVM default CA store for peer certificate
     * verification, clearing any custom trust manager.
     */
    public TlsConfig trustJvm() {
        this.verifyPeer = true;
        this.trustManager = null;
        return this;
    }

    /**
     * Sets this side's own TLS identity — the certificate chain and key a
     * server presents to a client, or a client presents to a server that
     * requests mTLS.
     *
     * @param serverCredentials the identity to present
     * @return this config
     */
    public TlsConfig serverCredentials(ServerCredentials serverCredentials) {
        this.serverCredentials = serverCredentials;
        return this;
    }

    /**
     * Sets the trust manager used to verify the peer's certificate — the
     * server's certificate on a client, or a client certificate under mTLS
     * on a server.
     *
     * @param trustManager the trust manager
     * @return this config
     */
    public TlsConfig trustManager(X509TrustManager trustManager) {
        this.trustManager = trustManager;
        return this;
    }

    public TlsConfig keystoreFile(Path keystoreFile) {
        this.keystoreFile = keystoreFile;
        return this;
    }

    public TlsConfig keystorePass(String keystorePass) {
        this.keystorePass = keystorePass;
        return this;
    }

    public TlsConfig keystoreFormat(KeystoreFormat keystoreFormat) {
        this.keystoreFormat = keystoreFormat;
        return this;
    }

    /** PEM certificate chain (QUIC / HTTP/3 client identity). */
    public TlsConfig certFile(Path certFile) {
        this.certFile = certFile;
        return this;
    }

    /** PEM private key (QUIC / HTTP/3 client identity). */
    public TlsConfig keyFile(Path keyFile) {
        this.keyFile = keyFile;
        return this;
    }

    /**
     * Server-only: binary {@code ECHConfigList} file for QUIC / HTTP/3 ECH decryption.
     */
    public TlsConfig echConfigListFile(Path echConfigListFile) {
        this.echConfigListFile = echConfigListFile;
        return this;
    }

    /**
     * Server-only: 32-byte X25519 ECH private key file (raw or hex).
     */
    public TlsConfig echPrivateKeyFile(Path echPrivateKeyFile) {
        this.echPrivateKeyFile = echPrivateKeyFile;
        return this;
    }

    /**
     * Server-only: require clients to offer ECH (RFC 9849 section 7.3).
     */
    public TlsConfig echServerRequired(boolean echServerRequired) {
        this.echServerRequired = echServerRequired;
        return this;
    }

    /**
     * Client-only: binary {@code ECHConfigList} file when DNS HTTPS does not supply {@code ech}.
     */
    public TlsConfig clientEchConfigListFile(Path clientEchConfigListFile) {
        this.clientEchConfigListFile = clientEchConfigListFile;
        return this;
    }

    /**
     * Client-only: send GREASE ECH when no real config is used (RFC 9849 section 6.2).
     */
    public TlsConfig clientEchGreaseEnabled(boolean clientEchGreaseEnabled) {
        this.clientEchGreaseEnabled = clientEchGreaseEnabled;
        return this;
    }

    /**
     * Client-only: look up the target host's DNS HTTPS record for an
     * {@code ech} SvcParam before dialling (RFC 9848, RFC 9460). Off by
     * default for the protocol clients that use {@code ClientConnect}
     * (SMTP, IMAP, POP3, FTP, Redis, LDAP, MQTT); the HTTP-family clients
     * do this on their own. A list found in DNS takes precedence over
     * {@link #clientEchConfigListFile(Path)}, which is then only the fallback.
     */
    public TlsConfig clientEchDnsDiscovery(boolean clientEchDnsDiscovery) {
        this.clientEchDnsDiscovery = clientEchDnsDiscovery;
        return this;
    }

    /**
     * Client-only: refuse to talk to a server that rejects Encrypted Client
     * Hello (RFC 9849 section 6.1.7). When the server rejects the offer the
     * handshake is not used: the client checks the server against the offered
     * config's {@code public_name} (which also authenticates any
     * {@code retry_configs}), then aborts with {@code ech_required} before
     * sending anything further, and never retries on its own. The
     * {@code retry_configs} are kept for later connections made through the
     * same transport factory. Has no effect unless a real ECH config is in
     * use, from DNS or {@link #clientEchConfigListFile(Path)}; GREASE
     * ({@link #clientEchGreaseEnabled(boolean)}) is never required.
     */
    public TlsConfig clientEchRequired(boolean clientEchRequired) {
        this.clientEchRequired = clientEchRequired;
        return this;
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

    public Path getClientEchConfigListFile() {
        return clientEchConfigListFile;
    }

    public boolean isClientEchGreaseEnabled() {
        return clientEchGreaseEnabled;
    }

    public boolean isClientEchRequired() {
        return clientEchRequired;
    }

    public boolean isClientEchDnsDiscoveryEnabled() {
        return clientEchDnsDiscovery;
    }

    public boolean isVerifyPeer() {
        return verifyPeer;
    }

    public Path getCertFile() {
        return certFile;
    }

    public Path getKeyFile() {
        return keyFile;
    }

    public Path getKeystoreFile() {
        return keystoreFile;
    }

    public String getKeystorePass() {
        return keystorePass;
    }

    public KeystoreFormat getKeystoreFormat() {
        return keystoreFormat;
    }

    public ServerCredentials getServerCredentials() {
        return serverCredentials;
    }

    public X509TrustManager getTrustManager() {
        return trustManager;
    }

    /**
     * True when this config carries any identity or custom trust material,
     * or has explicitly opted out of peer verification -- {@code
     * verifyPeer} defaults to {@code true}, so {@code false} here can only
     * come from an explicit {@link #verifyPeer} call and is itself a
     * deliberate choice {@link #effective} must not silently discard.
     */
    private boolean hasMaterial() {
        return serverCredentials != null
                || (certFile != null && keyFile != null)
                || (keystoreFile != null && keystorePass != null)
                || trustManager != null
                || !verifyPeer;
    }

    /**
     * Merges per-client settings with an optional process default. Per-client
     * material wins field-by-field when set; otherwise the default fills in.
     * {@code verifyPeer} follows whichever side actually supplied material,
     * since it has no meaningful "unset" state of its own.
     */
    public static TlsConfig effective(TlsConfig perClient, TlsConfig processDefault) {
        TlsConfig local = perClient != null ? perClient : new TlsConfig();
        TlsConfig fallback = processDefault != null ? processDefault : new TlsConfig();
        TlsConfig out = new TlsConfig();
        out.verifyPeer = local.hasMaterial() ? local.verifyPeer
                : (fallback.hasMaterial() ? fallback.verifyPeer : true);
        out.earlyData = local.earlyData || fallback.earlyData;
        out.cipherSuites = coalesce(local.cipherSuites, fallback.cipherSuites);
        out.namedGroups = coalesce(local.namedGroups, fallback.namedGroups);
        out.tlsVersion = coalesce(local.tlsVersion, fallback.tlsVersion);
        out.dtlsVersion = coalesce(local.dtlsVersion, fallback.dtlsVersion);
        out.sniHostnames.putAll(fallback.sniHostnames);
        out.sniHostnames.putAll(local.sniHostnames);
        out.sniDefaultAlias = coalesce(local.sniDefaultAlias, fallback.sniDefaultAlias);
        out.requireClientAuth = local.requireClientAuth || fallback.requireClientAuth;
        out.serverCredentials = coalesce(local.serverCredentials, fallback.serverCredentials);
        out.trustManager = coalesce(local.trustManager, fallback.trustManager);
        out.keystoreFile = coalesce(local.keystoreFile, fallback.keystoreFile);
        out.keystorePass = coalesce(local.keystorePass, fallback.keystorePass);
        out.keystoreFormat = coalesce(local.keystoreFormat, fallback.keystoreFormat);
        out.certFile = coalesce(local.certFile, fallback.certFile);
        out.keyFile = coalesce(local.keyFile, fallback.keyFile);
        out.echConfigListFile = coalesce(local.echConfigListFile, fallback.echConfigListFile);
        out.echPrivateKeyFile = coalesce(local.echPrivateKeyFile, fallback.echPrivateKeyFile);
        out.echServerRequired = local.hasEchListenerSettings()
                ? local.echServerRequired
                : fallback.echServerRequired;
        out.clientEchConfigListFile = coalesce(local.clientEchConfigListFile, fallback.clientEchConfigListFile);
        out.clientEchGreaseEnabled = local.hasClientEchSettings()
                ? local.clientEchGreaseEnabled
                : fallback.clientEchGreaseEnabled;
        out.clientEchDnsDiscovery = local.clientEchDnsDiscovery || fallback.clientEchDnsDiscovery;
        out.clientEchRequired = local.clientEchRequired || fallback.clientEchRequired;
        out.requireCookie = local.requireCookie || fallback.requireCookie;
        out.cookieSecret = coalesce(local.cookieSecret, fallback.cookieSecret);
        out.maxFragmentSize = local.maxFragmentSize != 0 ? local.maxFragmentSize : fallback.maxFragmentSize;
        return out;
    }

    private boolean hasEchListenerSettings() {
        return echConfigListFile != null || echPrivateKeyFile != null || echServerRequired;
    }

    private boolean hasClientEchSettings() {
        return clientEchConfigListFile != null || clientEchGreaseEnabled;
    }

    /**
     * Replaces this config with a copy of {@code source} (for delegating facades
     * such as {@link org.bluezoo.gumdrop.websocket.client.WebSocketClient} →
     * {@link org.bluezoo.gumdrop.http.HttpClient} on the HTTP/3 path).
     */
    public TlsConfig copyFrom(TlsConfig source) {
        if (source == null) {
            return this;
        }
        this.verifyPeer = source.verifyPeer;
        this.earlyData = source.earlyData;
        this.cipherSuites = source.cipherSuites;
        this.namedGroups = source.namedGroups;
        this.tlsVersion = source.tlsVersion;
        this.dtlsVersion = source.dtlsVersion;
        this.sniHostnames.clear();
        this.sniHostnames.putAll(source.sniHostnames);
        this.sniDefaultAlias = source.sniDefaultAlias;
        this.requireClientAuth = source.requireClientAuth;
        this.serverCredentials = source.serverCredentials;
        this.trustManager = source.trustManager;
        this.keystoreFile = source.keystoreFile;
        this.keystorePass = source.keystorePass;
        this.keystoreFormat = source.keystoreFormat;
        this.certFile = source.certFile;
        this.keyFile = source.keyFile;
        this.echConfigListFile = source.echConfigListFile;
        this.echPrivateKeyFile = source.echPrivateKeyFile;
        this.echServerRequired = source.echServerRequired;
        this.clientEchConfigListFile = source.clientEchConfigListFile;
        this.clientEchGreaseEnabled = source.clientEchGreaseEnabled;
        this.clientEchDnsDiscovery = source.clientEchDnsDiscovery;
        this.clientEchRequired = source.clientEchRequired;
        this.requireCookie = source.requireCookie;
        this.cookieSecret = source.cookieSecret != null ? source.cookieSecret.clone() : null;
        this.maxFragmentSize = source.maxFragmentSize;
        return this;
    }

    private static <T> T coalesce(T local, T fallback) {
        return local != null ? local : fallback;
    }

}
