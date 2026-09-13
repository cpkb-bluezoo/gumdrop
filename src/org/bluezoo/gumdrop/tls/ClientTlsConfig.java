/*
 * ClientTlsConfig.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.tls;

import java.nio.file.Path;
import java.util.logging.Logger;

import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.quic.QuicTransportFactory;
import org.bluezoo.gumdrop.util.EmptyX509TrustManager;

/**
 * Outbound TLS dial settings for client facades — transport identity, trust,
 * and implicit/explicit secure mode.
 *
 * <p>TLS is enabled only when this config (or a process default — see
 * {@link org.bluezoo.gumdrop.client.ClientDefaults}) includes explicit TLS
 * material via {@link #trustJvm()}, {@link #trustManager}, {@link
 * #clientCredentials}, keystore / PEM paths, etc. {@link #secure(boolean)} alone
 * does not enable TLS; without material the connection stays plaintext.
 *
 * @see TlsConfig
 * @see docs/COMPOSITION.md
 */
public final class ClientTlsConfig {

    private static final Logger LOGGER =
            Logger.getLogger(ClientTlsConfig.class.getName());

    private boolean secure;
    private boolean verifyPeer = true;
    private boolean configured;
    private ServerCredentials clientCredentials;
    private X509TrustManager trustManager;
    private Path keystoreFile;
    private String keystorePass;
    private String keystoreFormat;
    private Path certFile;
    private Path keyFile;

    public ClientTlsConfig secure(boolean secure) {
        this.secure = secure;
        return this;
    }

    public ClientTlsConfig verifyPeer(boolean verifyPeer) {
        this.verifyPeer = verifyPeer;
        markConfigured();
        return this;
    }

    /**
     * Explicitly trust the JVM default CA store for server certificate
     * verification (required before implicit TLS or STARTTLS can run).
     */
    public ClientTlsConfig trustJvm() {
        markConfigured();
        this.verifyPeer = true;
        this.trustManager = null;
        return this;
    }

    public ClientTlsConfig clientCredentials(ServerCredentials clientCredentials) {
        if (clientCredentials != null) {
            markConfigured();
        }
        this.clientCredentials = clientCredentials;
        return this;
    }

    public ClientTlsConfig trustManager(X509TrustManager trustManager) {
        if (trustManager != null) {
            markConfigured();
        }
        this.trustManager = trustManager;
        return this;
    }

    public ClientTlsConfig keystoreFile(Path keystoreFile) {
        if (keystoreFile != null) {
            markConfigured();
        }
        this.keystoreFile = keystoreFile;
        return this;
    }

    public ClientTlsConfig keystorePass(String keystorePass) {
        if (keystorePass != null) {
            markConfigured();
        }
        this.keystorePass = keystorePass;
        return this;
    }

    public ClientTlsConfig keystoreFormat(String keystoreFormat) {
        if (keystoreFormat != null) {
            markConfigured();
        }
        this.keystoreFormat = keystoreFormat;
        return this;
    }

    /** PEM client certificate chain (QUIC / HTTP/3). */
    public ClientTlsConfig certFile(Path certFile) {
        if (certFile != null) {
            markConfigured();
        }
        this.certFile = certFile;
        return this;
    }

    /** PEM client private key (QUIC / HTTP/3). */
    public ClientTlsConfig keyFile(Path keyFile) {
        if (keyFile != null) {
            markConfigured();
        }
        this.keyFile = keyFile;
        return this;
    }

    public boolean isSecure() {
        return secure;
    }

    public boolean isVerifyPeer() {
        return verifyPeer;
    }

    /** True when explicit TLS material was configured (client or process default). */
    public boolean isConfigured() {
        return configured;
    }

    /** Implicit TLS on the wire ({@code secure} and TLS material present). */
    public boolean useImplicitTls() {
        return configured && secure;
    }

    /** TLS upgrades (STARTTLS, STLS, AUTH TLS) are allowed when material is present. */
    public boolean allowsTlsUpgrade() {
        return configured;
    }

    public ServerCredentials getClientCredentials() {
        return clientCredentials;
    }

    public X509TrustManager getTrustManager() {
        return trustManager;
    }

    public Path getCertFile() {
        return certFile;
    }

    public Path getKeyFile() {
        return keyFile;
    }

    /**
     * Merges per-client settings with an optional process default. Per-client
     * values win when set; otherwise defaults fill in. {@code secure} comes
     * from the client when {@code perClient} is configured, else from defaults.
     */
    public static ClientTlsConfig effective(ClientTlsConfig perClient,
                                            ClientTlsConfig processDefault) {
        ClientTlsConfig local = perClient != null ? perClient : new ClientTlsConfig();
        boolean localConfigured = local.configured;
        boolean defaultConfigured =
                processDefault != null && processDefault.configured;
        if (!localConfigured && !defaultConfigured) {
            return new ClientTlsConfig();
        }
        ClientTlsConfig out = new ClientTlsConfig();
        out.configured = true;
        out.secure = localConfigured ? local.secure
                : (defaultConfigured && processDefault.secure);
        out.verifyPeer = localConfigured ? local.verifyPeer
                : (defaultConfigured ? processDefault.verifyPeer : true);
        out.clientCredentials = coalesce(local.clientCredentials,
                defaultConfigured ? processDefault.clientCredentials : null);
        out.trustManager = coalesce(local.trustManager,
                defaultConfigured ? processDefault.trustManager : null);
        out.keystoreFile = coalesce(local.keystoreFile,
                defaultConfigured ? processDefault.keystoreFile : null);
        out.keystorePass = coalesce(local.keystorePass,
                defaultConfigured ? processDefault.keystorePass : null);
        out.keystoreFormat = coalesce(local.keystoreFormat,
                defaultConfigured ? processDefault.keystoreFormat : null);
        out.certFile = coalesce(local.certFile,
                defaultConfigured ? processDefault.certFile : null);
        out.keyFile = coalesce(local.keyFile,
                defaultConfigured ? processDefault.keyFile : null);
        return out;
    }

    public void applyTo(TcpTransportFactory factory) {
        if (factory == null) {
            throw new NullPointerException("factory");
        }
        if (!configured) {
            factory.setSecure(false);
            return;
        }
        factory.setSecure(secure);
        if (clientCredentials != null) {
            factory.setClientCredentials(clientCredentials);
        }
        if (trustManager != null) {
            factory.setTrustManager(trustManager);
        } else if (!verifyPeer) {
            LOGGER.warning("TLS peer verification disabled");
            factory.setTrustManager(new EmptyX509TrustManager());
        }
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
    }

    public void applyTo(QuicTransportFactory factory) {
        if (factory == null) {
            throw new NullPointerException("factory");
        }
        if (!configured) {
            factory.setVerifyPeer(false);
            return;
        }
        if (trustManager != null) {
            factory.setTrustManager(trustManager);
        } else if (!verifyPeer) {
            LOGGER.warning("TLS peer verification disabled");
            factory.setTrustManager(new EmptyX509TrustManager());
        }
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
        factory.setVerifyPeer(verifyPeer);
    }

    /**
     * Replaces this config with a copy of {@code source} (for delegating facades
     * such as {@link org.bluezoo.gumdrop.websocket.client.WebSocketClient} →
     * {@link org.bluezoo.gumdrop.http.HttpClient} on the HTTP/3 path).
     */
    public ClientTlsConfig copyFrom(ClientTlsConfig source) {
        if (source == null) {
            return this;
        }
        this.secure = source.secure;
        this.verifyPeer = source.verifyPeer;
        this.configured = source.configured;
        this.clientCredentials = source.clientCredentials;
        this.trustManager = source.trustManager;
        this.keystoreFile = source.keystoreFile;
        this.keystorePass = source.keystorePass;
        this.keystoreFormat = source.keystoreFormat;
        this.certFile = source.certFile;
        this.keyFile = source.keyFile;
        return this;
    }

    private void markConfigured() {
        this.configured = true;
    }

    private static <T> T coalesce(T local, T fallback) {
        return local != null ? local : fallback;
    }

}
