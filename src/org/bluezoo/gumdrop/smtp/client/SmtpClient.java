/*
 * SmtpClient.java
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

package org.bluezoo.gumdrop.smtp.client;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.client.ClientConnect;
import org.bluezoo.gumdrop.client.ClientDial;
import org.bluezoo.gumdrop.dns.DaneTrustManager;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnssecAwareQueryCallback;
import org.bluezoo.gumdrop.dns.DnssecStatus;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.tls.ServerCredentials;

/**
 * High-level SMTP client facade.
 *
 * <p>This class provides a simple, concrete API for connecting to SMTP
 * servers. It internally creates a {@link TcpTransportFactory},
 * {@link ClientEndpoint}, and {@link SmtpClientProtocolHandler}, wiring
 * them together and forwarding lifecycle events to the caller's
 * {@link RemoteGreeting} handler, supplied directly to {@link
 * #connect(RemoteGreeting)}.
 *
 * <h4>Composition (recommended)</h4>
 * <pre>{@code
 * SmtpClient client = new SmtpClient()
 *         .host("smtp.example.com")
 *         .port(587);
 * client.connect(new MyRemoteGreeting());
 * }</pre>
 *
 * <h4>Plaintext with STARTTLS (submission)</h4>
 * <pre>{@code
 * SmtpClient client = new SmtpClient(selectorLoop, "smtp.example.com", 587);
 * client.setClientCredentials(clientCredentials);
 * client.connect(new RemoteGreeting() {
 *     public void handleGreeting(ClientHelloState hello,
 *                                String message, boolean esmtp) {
 *         hello.ehlo("myhostname", ehloHandler);
 *     }
 *     // ...
 * });
 * }</pre>
 *
 * <h4>Implicit TLS (SMTPS)</h4>
 * <pre>{@code
 * SmtpClient client = new SmtpClient("smtp.example.com", 465);
 * client.setSecure(true);
 * client.setClientCredentials(clientCredentials);
 * client.connect(greetingHandler);
 * }</pre>
 *
 * <h4>Opportunistic DANE (RFC 7672)</h4>
 * <pre>{@code
 * SmtpClient client = new SmtpClient("mail.example.com", 25);
 * client.setDaneResolver(myResolver); // a DNSSEC-enabled DnsResolver
 * client.connect(greetingHandler);
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see RemoteGreeting
 * @see SmtpClientProtocolHandler
 * @see org.bluezoo.gumdrop.dns.DaneTrustManager
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5321">RFC 5321</a> (SMTP)
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8314">RFC 8314</a> (Implicit TLS, SMTPS port 465)
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3207">RFC 3207</a> (STARTTLS)
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7672">RFC 7672</a> (SMTP DANE)
 */
public class SmtpClient {

    private final ClientDial dial = ClientDial.withDefaultPort(25);
    private final TlsConfig tls = new TlsConfig();
    private boolean secure;

    private DnsResolver daneResolver;

    private TcpTransportFactory transportFactory;
    private ClientEndpoint clientEndpoint;
    private SmtpClientProtocolHandler endpointHandler;
    private Gumdrop gumdrop;

    /**
     * Creates an SMTP client for fluent configuration before {@link #connect()}.
     */
    public SmtpClient() {
    }

    /**
     * Creates an SMTP client for the given hostname and port.
     *
     * <p>Uses the next available worker loop from the global
     * {@link Gumdrop} instance. DNS resolution is deferred until
     * {@link #connect} is called.
     *
     * @param host the remote hostname or IP address
     * @param port the remote port
     */
    public SmtpClient(String host, int port) {
        this(null, host, port);
    }

    /**
     * Creates an SMTP client with an explicit selector loop.
     *
     * <p>DNS resolution is deferred until {@link #connect} is called.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop
     *                     worker
     * @param host the remote hostname or IP address
     * @param port the remote port
     */
    public SmtpClient(SelectorLoop selectorLoop, String host,
                      int port) {
        dial.selectorLoop(selectorLoop).host(host).port(port);
    }

    /**
     * Creates an SMTP client for the given address and port.
     *
     * @param host the remote host address
     * @param port the remote port
     */
    public SmtpClient(InetAddress host, int port) {
        this(null, host, port);
    }

    /**
     * Creates an SMTP client with an explicit selector loop and address.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop
     *                     worker
     * @param host the remote host address
     * @param port the remote port
     */
    public SmtpClient(SelectorLoop selectorLoop, InetAddress host,
                      int port) {
        dial.selectorLoop(selectorLoop).host(host).port(port);
    }

    /**
     * Creates an SMTP client for a UNIX domain socket, mirroring
     * {@link org.bluezoo.gumdrop.TcpListener#setPath} on the server side.
     *
     * <p>Uses the next available worker loop from the global {@link
     * Gumdrop} instance.
     *
     * @param socketPath the UNIX domain socket path
     */
    public SmtpClient(String socketPath) {
        this(null, socketPath);
    }

    /**
     * Creates an SMTP client for a UNIX domain socket with an
     * explicit selector loop.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop worker
     * @param socketPath the UNIX domain socket path
     */
    public SmtpClient(SelectorLoop selectorLoop, String socketPath) {
        dial.selectorLoop(selectorLoop).socketPath(socketPath);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Configuration (before connect)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Sets whether this client uses implicit TLS (SMTPS).
     *
     * <p>When true, the connection starts with TLS immediately (port 465).
     * When false, the connection starts plaintext and STARTTLS can be
     * used to upgrade if client credentials are configured.
     *
     * @param secure true for implicit TLS
     * @see <a href="https://www.rfc-editor.org/rfc/rfc8314">RFC 8314</a> — implicit TLS (port 465)
     */
    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    /**
     * Sets the SSL context for TLS connections.
     *
     * <p>Required for both implicit TLS ({@code setSecure(true)}) and
     * explicit TLS via STARTTLS. When set without {@code setSecure(true)},
     * an SSLEngine is created but not activated until the protocol
     * handler calls {@code endpoint.startTLS()}.
     *
     * @param context the SSL context
     */
    public void setClientCredentials(ServerCredentials clientCredentials) {
        tls.serverCredentials(clientCredentials);
    }

    /**
     * Sets a custom trust manager for TLS certificate verification.
     *
     * @param trustManager the trust manager, or null to use defaults
     * @see org.bluezoo.gumdrop.util.PinnedCertTrustManager
     * @see org.bluezoo.gumdrop.util.EmptyX509TrustManager
     */
    public void setTrustManager(X509TrustManager trustManager) {
        tls.trustManager(trustManager);
    }

    /**
     * Enables opportunistic DANE authentication (RFC 7672) of the
     * destination's certificate, using the given resolver to look up
     * TLSA records for {@code _<port>._tcp.<host>} before connecting.
     *
     * <p>DANE only takes effect when the lookup itself comes back
     * DNSSEC-secure (RFC 7672 section 3.1.3) and returns at least one
     * TLSA record -- otherwise {@code connect} proceeds exactly as it
     * would without this call. When it does take effect, any trust
     * manager set via {@link #setTrustManager} is used as the DANE
     * PKIX-TA/PKIX-EE delegate rather than being replaced outright.
     * Requires a hostname (not an address or UNIX socket) target.
     *
     * @param resolver the resolver to use for the TLSA lookup, or
     *                 null to disable DANE
     */
    public void setDaneResolver(DnsResolver resolver) {
        this.daneResolver = resolver;
    }

    /**
     * Sets the DNS resolver used for hostname lookup at connect. When
     * unset, {@link ClientEndpoint} uses {@link DnsResolver#forLoop}.
     */
    public void setDnsResolver(DnsResolver resolver) {
        dial.dnsResolver(resolver);
    }

    /**
     * Sets the keystore file for client certificate authentication.
     *
     * @param path the keystore file path
     */
    public void setKeystoreFile(Path path) {
        tls.keystoreFile(path);
    }

    public void setKeystoreFile(String path) {
        tls.keystoreFile(Path.of(path));
    }

    /**
     * Sets the keystore password.
     *
     * @param password the keystore password
     */
    public void setKeystorePass(String password) {
        tls.keystorePass(password);
    }

    /**
     * Sets the keystore format (e.g. JKS, PKCS12).
     *
     * @param format the keystore format
     */
    public void setKeystoreFormat(String format) {
        tls.keystoreFormat(format);
    }

    /**
     * Sets the remote hostname. Resolved via {@link DnsResolver} at
     * {@link #connect()}.
     *
     * @param host the remote hostname
     * @return this client
     */
    public SmtpClient host(String host) {
        dial.host(host);
        return this;
    }

    /**
     * Sets the remote host address (no DNS lookup at connect).
     *
     * @param hostAddress the remote address
     * @return this client
     */
    public SmtpClient host(InetAddress hostAddress) {
        dial.host(hostAddress);
        return this;
    }

    /**
     * Sets the remote port.
     *
     * @param port the port number
     * @return this client
     */
    public SmtpClient port(int port) {
        dial.port(port);
        return this;
    }

    /**
     * Sets the UNIX domain socket path (mutually exclusive with host).
     *
     * @param socketPath the socket path
     * @return this client
     */
    public SmtpClient socketPath(String socketPath) {
        dial.socketPath(socketPath);
        return this;
    }

    /**
     * Sets the selector loop for this client.
     *
     * @param selectorLoop the loop, or null for a Gumdrop worker
     * @return this client
     */
    public SmtpClient selectorLoop(SelectorLoop selectorLoop) {
        dial.selectorLoop(selectorLoop);
        return this;
    }

    /**
     * Sets whether this client uses implicit TLS (SMTPS).
     *
     * @param secure true for implicit TLS
     * @return this client
     */
    public SmtpClient secure(boolean secure) {
        this.secure = secure;
        return this;
    }

    public SmtpClient trustJvm() {
        tls.trustJvm();
        return this;
    }

    /**
     * Sets client TLS credentials.
     *
     * @param clientCredentials the credentials
     * @return this client
     */
    public SmtpClient clientCredentials(ServerCredentials clientCredentials) {
        tls.serverCredentials(clientCredentials);
        return this;
    }

    /**
     * Sets a custom trust manager.
     *
     * @param trustManager the trust manager
     * @return this client
     */
    public SmtpClient trustManager(X509TrustManager trustManager) {
        tls.trustManager(trustManager);
        return this;
    }

    /**
     * Sets the keystore file for client certificate authentication.
     *
     * @param path the keystore path
     * @return this client
     */
    public SmtpClient keystoreFile(Path path) {
        tls.keystoreFile(path);
        return this;
    }

    /**
     * Sets the keystore password.
     *
     * @param password the password
     * @return this client
     */
    public SmtpClient keystorePass(String password) {
        tls.keystorePass(password);
        return this;
    }

    /**
     * Sets the keystore format.
     *
     * @param format the format
     * @return this client
     */
    public SmtpClient keystoreFormat(String format) {
        tls.keystoreFormat(format);
        return this;
    }

    /**
     * Enables opportunistic DANE authentication via the given resolver.
     *
     * @param resolver the resolver, or null to disable
     * @return this client
     */
    public SmtpClient daneResolver(DnsResolver resolver) {
        setDaneResolver(resolver);
        return this;
    }

    public SmtpClient dnsResolver(DnsResolver resolver) {
        dial.dnsResolver(resolver);
        return this;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Connects to the remote SMTP server.
     *
     * <p>If a DANE resolver was configured via {@link
     * #setDaneResolver}, first looks up TLSA records for this
     * client's host and port; otherwise connects immediately.
     *
     * @param gumdrop the runtime this connection is made under
     * @param handler the handler to receive the server greeting and
     *                lifecycle events
     */
    public void connect(Gumdrop gumdrop, final RemoteGreeting handler) {
        this.gumdrop = gumdrop;
        if (daneResolver != null && dial.getHost() != null) {
            lookupDane(handler);
        } else {
            doConnect(handler);
        }
    }

    /**
     * Looks up TLSA records for this client's host/port and, if the
     * lookup is DNSSEC-secure and non-empty, installs a {@link
     * DaneTrustManager} before proceeding to {@link #doConnect}.
     * RFC 7672 section 3.1.3: an insecure or empty lookup is not an
     * error -- it just means DANE does not apply, so the connection
     * proceeds with whatever trust manager was already configured.
     */
    private void lookupDane(final RemoteGreeting handler) {
        String tlsaName = "_" + dial.getPort() + "._tcp." + dial.getHost();
        daneResolver.queryTLSA(tlsaName, new DnssecAwareQueryCallback() {
            @Override
            public void onResponse(DnsMessage response, DnssecStatus status) {
                if (status == DnssecStatus.SECURE) {
                    List<DnsResourceRecord> tlsaRecords = new ArrayList<>();
                    for (DnsResourceRecord rr : response.getAnswers()) {
                        if (rr.getType() == DnsType.TLSA) {
                            tlsaRecords.add(rr);
                        }
                    }
                    if (!tlsaRecords.isEmpty()) {
                        tls.trustManager(new DaneTrustManager(
                                tls.getTrustManager(), tlsaRecords));
                    }
                }
                doConnect(handler);
            }

            @Override
            public void onError(String error) {
                doConnect(handler);
            }
        });
    }

    /**
     * Creates the transport factory, endpoint handler, and client
     * endpoint, then initiates the connection. Lifecycle events are
     * forwarded to the given handler.
     *
     * @param handler the handler to receive the server greeting and
     *                lifecycle events
     */
    private void doConnect(RemoteGreeting handler) {
        dial.requireTarget();
        transportFactory = new TcpTransportFactory();
        endpointHandler = new SmtpClientProtocolHandler(handler);
        try {
            ClientConnect.prepareTls(secure, tls, transportFactory);
            endpointHandler.setSecure(secure);
            clientEndpoint = ClientConnect.openAndConnect(
                    gumdrop, dial, transportFactory, endpointHandler);
        } catch (IOException e) {
            handler.onError(e);
        }
    }

    /**
     * Returns whether the connection is open.
     *
     * @return true if connected and open
     */
    public boolean isOpen() {
        return endpointHandler != null && endpointHandler.isOpen();
    }

    /**
     * Closes the connection.
     */
    public void close() {
        if (endpointHandler != null) {
            endpointHandler.close();
        }
        if (clientEndpoint != null) {
            clientEndpoint.close();
        }
    }

    /**
     * @deprecated use {@code new SmtpClient().host(...).port(...)} fluent
     * configuration instead.
     */
    @Deprecated
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @deprecated use fluent methods on {@link SmtpClient} instead.
     */
    @Deprecated
    public static final class Builder {

        private SelectorLoop selectorLoop;
        private String host;
        private InetAddress hostAddress;
        private int port = 25;
        private String socketPath;
        private boolean secure;
        private ServerCredentials clientCredentials;
        private X509TrustManager trustManager;
        private Path keystoreFile;
        private String keystorePass;
        private String keystoreFormat;
        private DnsResolver daneResolver;

        private Builder() {
        }

        public Builder selectorLoop(SelectorLoop selectorLoop) {
            this.selectorLoop = selectorLoop;
            return this;
        }

        public Builder host(String host) {
            this.host = host;
            this.hostAddress = null;
            this.socketPath = null;
            return this;
        }

        public Builder host(InetAddress hostAddress) {
            this.hostAddress = hostAddress;
            this.host = null;
            this.socketPath = null;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder socketPath(String socketPath) {
            this.socketPath = socketPath;
            this.host = null;
            this.hostAddress = null;
            return this;
        }

        public Builder secure(boolean secure) {
            this.secure = secure;
            return this;
        }

        public Builder clientCredentials(ServerCredentials clientCredentials) {
            this.clientCredentials = clientCredentials;
            return this;
        }

        public Builder trustManager(X509TrustManager trustManager) {
            this.trustManager = trustManager;
            return this;
        }

        public Builder keystoreFile(Path keystoreFile) {
            this.keystoreFile = keystoreFile;
            return this;
        }

        public Builder keystorePass(String keystorePass) {
            this.keystorePass = keystorePass;
            return this;
        }

        public Builder keystoreFormat(String keystoreFormat) {
            this.keystoreFormat = keystoreFormat;
            return this;
        }

        public Builder daneResolver(DnsResolver daneResolver) {
            this.daneResolver = daneResolver;
            return this;
        }

        /**
         * Builds the client. A host (or socket path) is required; call
         * {@link SmtpClient#connect(RemoteGreeting)} on the result to
         * connect.
         */
        public SmtpClient build() {
            final SmtpClient client;
            if (socketPath != null) {
                client = (selectorLoop != null)
                        ? new SmtpClient(selectorLoop, socketPath)
                        : new SmtpClient(socketPath);
            } else if (host != null) {
                client = (selectorLoop != null)
                        ? new SmtpClient(selectorLoop, host, port)
                        : new SmtpClient(host, port);
            } else if (hostAddress != null) {
                client = (selectorLoop != null)
                        ? new SmtpClient(selectorLoop, hostAddress, port)
                        : new SmtpClient(hostAddress, port);
            } else {
                throw new IllegalStateException(
                        "host, host address, or socketPath is required");
            }
            client.setSecure(secure);
            if (clientCredentials != null) {
                client.setClientCredentials(clientCredentials);
            }
            if (trustManager != null) {
                client.setTrustManager(trustManager);
            }
            if (keystoreFile != null) {
                client.setKeystoreFile(keystoreFile);
            }
            if (keystorePass != null) {
                client.setKeystorePass(keystorePass);
            }
            if (keystoreFormat != null) {
                client.setKeystoreFormat(keystoreFormat);
            }
            if (daneResolver != null) {
                client.setDaneResolver(daneResolver);
            }
            return client;
        }
    }

}
