/*
 * SMTPClient.java
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
import org.bluezoo.gumdrop.TCPTransportFactory;
import org.bluezoo.gumdrop.dns.DANETrustManager;
import org.bluezoo.gumdrop.dns.DNSMessage;
import org.bluezoo.gumdrop.dns.DNSResourceRecord;
import org.bluezoo.gumdrop.dns.DNSSECAwareQueryCallback;
import org.bluezoo.gumdrop.dns.DNSSECStatus;
import org.bluezoo.gumdrop.dns.DNSType;
import org.bluezoo.gumdrop.dns.client.DNSResolver;
import org.bluezoo.gumdrop.smtp.client.handler.ServerGreeting;
import org.bluezoo.gumdrop.tls.ServerCredentials;

/**
 * High-level SMTP client facade.
 *
 * <p>This class provides a simple, concrete API for connecting to SMTP
 * servers. It internally creates a {@link TCPTransportFactory},
 * {@link ClientEndpoint}, and {@link SMTPClientProtocolHandler}, wiring
 * them together and forwarding lifecycle events to the caller's
 * {@link ServerGreeting} handler.
 *
 * <h4>Plaintext with STARTTLS (submission)</h4>
 * <pre>{@code
 * SMTPClient client = new SMTPClient(selectorLoop, "smtp.example.com", 587);
 * client.setClientCredentials(clientCredentials);
 * client.connect(new ServerGreeting() {
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
 * SMTPClient client = new SMTPClient("smtp.example.com", 465);
 * client.setSecure(true);
 * client.setClientCredentials(clientCredentials);
 * client.connect(greetingHandler);
 * }</pre>
 *
 * <h4>Opportunistic DANE (RFC 7672)</h4>
 * <pre>{@code
 * SMTPClient client = new SMTPClient("mail.example.com", 25);
 * client.setDaneResolver(myResolver); // a DNSSEC-enabled DNSResolver
 * client.connect(greetingHandler);
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerGreeting
 * @see SMTPClientProtocolHandler
 * @see org.bluezoo.gumdrop.dns.DANETrustManager
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5321">RFC 5321</a> (SMTP)
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8314">RFC 8314</a> (Implicit TLS, SMTPS port 465)
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3207">RFC 3207</a> (STARTTLS)
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7672">RFC 7672</a> (SMTP DANE)
 */
public class SMTPClient {

    private final String host;
    private final InetAddress hostAddress;
    private final int port;
    private final String socketPath;
    private final SelectorLoop selectorLoop;

    private boolean secure;
    private ServerCredentials clientCredentials;
    private X509TrustManager trustManager;
    private Path keystoreFile;
    private String keystorePass;
    private String keystoreFormat;
    private DNSResolver daneResolver;

    private TCPTransportFactory transportFactory;
    private ClientEndpoint clientEndpoint;
    private SMTPClientProtocolHandler endpointHandler;

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
    public SMTPClient(String host, int port) {
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
    public SMTPClient(SelectorLoop selectorLoop, String host,
                      int port) {
        this.selectorLoop = selectorLoop;
        this.host = host;
        this.hostAddress = null;
        this.port = port;
        this.socketPath = null;
    }

    /**
     * Creates an SMTP client for the given address and port.
     *
     * @param host the remote host address
     * @param port the remote port
     */
    public SMTPClient(InetAddress host, int port) {
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
    public SMTPClient(SelectorLoop selectorLoop, InetAddress host,
                      int port) {
        this.selectorLoop = selectorLoop;
        this.host = null;
        this.hostAddress = host;
        this.port = port;
        this.socketPath = null;
    }

    /**
     * Creates an SMTP client for a UNIX domain socket, mirroring
     * {@link org.bluezoo.gumdrop.TCPListener#setPath} on the server side.
     *
     * <p>Uses the next available worker loop from the global {@link
     * Gumdrop} instance.
     *
     * @param socketPath the UNIX domain socket path
     */
    public SMTPClient(String socketPath) {
        this(null, socketPath);
    }

    /**
     * Creates an SMTP client for a UNIX domain socket with an
     * explicit selector loop.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop worker
     * @param socketPath the UNIX domain socket path
     */
    public SMTPClient(SelectorLoop selectorLoop, String socketPath) {
        if (socketPath == null) {
            throw new NullPointerException("socketPath");
        }
        this.selectorLoop = selectorLoop;
        this.host = null;
        this.hostAddress = null;
        this.port = -1;
        this.socketPath = socketPath;
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
        this.clientCredentials = clientCredentials;
    }

    /**
     * Sets a custom trust manager for TLS certificate verification.
     *
     * @param trustManager the trust manager, or null to use defaults
     * @see org.bluezoo.gumdrop.util.PinnedCertTrustManager
     * @see org.bluezoo.gumdrop.util.EmptyX509TrustManager
     */
    public void setTrustManager(X509TrustManager trustManager) {
        this.trustManager = trustManager;
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
    public void setDaneResolver(DNSResolver resolver) {
        this.daneResolver = resolver;
    }

    /**
     * Sets the keystore file for client certificate authentication.
     *
     * @param path the keystore file path
     */
    public void setKeystoreFile(Path path) {
        this.keystoreFile = path;
    }

    public void setKeystoreFile(String path) {
        this.keystoreFile = Path.of(path);
    }

    /**
     * Sets the keystore password.
     *
     * @param password the keystore password
     */
    public void setKeystorePass(String password) {
        this.keystorePass = password;
    }

    /**
     * Sets the keystore format (e.g. JKS, PKCS12).
     *
     * @param format the keystore format
     */
    public void setKeystoreFormat(String format) {
        this.keystoreFormat = format;
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
     * @param handler the handler to receive the server greeting and
     *                lifecycle events
     */
    public void connect(final ServerGreeting handler) {
        if (daneResolver != null && host != null) {
            lookupDane(handler);
        } else {
            doConnect(handler);
        }
    }

    /**
     * Looks up TLSA records for this client's host/port and, if the
     * lookup is DNSSEC-secure and non-empty, installs a {@link
     * DANETrustManager} before proceeding to {@link #doConnect}.
     * RFC 7672 section 3.1.3: an insecure or empty lookup is not an
     * error -- it just means DANE does not apply, so the connection
     * proceeds with whatever trust manager was already configured.
     */
    private void lookupDane(final ServerGreeting handler) {
        String tlsaName = "_" + port + "._tcp." + host;
        daneResolver.queryTLSA(tlsaName, new DNSSECAwareQueryCallback() {
            @Override
            public void onResponse(DNSMessage response, DNSSECStatus status) {
                if (status == DNSSECStatus.SECURE) {
                    List<DNSResourceRecord> tlsaRecords = new ArrayList<>();
                    for (DNSResourceRecord rr : response.getAnswers()) {
                        if (rr.getType() == DNSType.TLSA) {
                            tlsaRecords.add(rr);
                        }
                    }
                    if (!tlsaRecords.isEmpty()) {
                        trustManager = new DANETrustManager(
                                trustManager, tlsaRecords);
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
    private void doConnect(ServerGreeting handler) {
        transportFactory = new TCPTransportFactory();
        transportFactory.setSecure(secure);
        if (clientCredentials != null) {
            transportFactory.setClientCredentials(clientCredentials);
        }
        if (trustManager != null) {
            transportFactory.setTrustManager(trustManager);
        }
        if (keystoreFile != null) {
            transportFactory.setKeystoreFile(keystoreFile);
        }
        if (keystorePass != null) {
            transportFactory.setKeystorePass(keystorePass);
        }
        if (keystoreFormat != null) {
            transportFactory.setKeystoreFormat(keystoreFormat);
        }
        transportFactory.start();

        endpointHandler = new SMTPClientProtocolHandler(handler);
        endpointHandler.setSecure(secure);

        try {
            if (socketPath != null) {
                clientEndpoint = (selectorLoop != null)
                        ? new ClientEndpoint(transportFactory, selectorLoop, socketPath)
                        : new ClientEndpoint(transportFactory, socketPath);
            } else if (host != null) {
                if (selectorLoop != null) {
                    clientEndpoint = new ClientEndpoint(
                            transportFactory, selectorLoop,
                            host, port);
                } else {
                    clientEndpoint = new ClientEndpoint(
                            transportFactory, host, port);
                }
            } else {
                if (selectorLoop != null) {
                    clientEndpoint = new ClientEndpoint(
                            transportFactory, selectorLoop,
                            hostAddress, port);
                } else {
                    clientEndpoint = new ClientEndpoint(
                            transportFactory, hostAddress, port);
                }
            }
            clientEndpoint.connect(endpointHandler);
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
}
