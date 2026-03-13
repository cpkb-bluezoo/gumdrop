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

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TCPTransportFactory;
import org.bluezoo.gumdrop.smtp.client.handler.ServerGreeting;

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
 * client.setSSLContext(sslContext);
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
 * client.setSSLContext(sslContext);
 * client.connect(greetingHandler);
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerGreeting
 * @see SMTPClientProtocolHandler
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5321">RFC 5321</a> (SMTP)
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8314">RFC 8314</a> (Implicit TLS, SMTPS port 465)
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3207">RFC 3207</a> (STARTTLS)
 */
public class SMTPClient {

    private final String host;
    private final InetAddress hostAddress;
    private final int port;
    private final SelectorLoop selectorLoop;

    private boolean secure;
    private SSLContext sslContext;
    private X509TrustManager trustManager;
    private Path keystoreFile;
    private String keystorePass;
    private String keystoreFormat;

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
    }

    // ═══════════════════════════════════════════════════════════════════
    // Configuration (before connect)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Sets whether this client uses implicit TLS (SMTPS).
     *
     * <p>When true, the connection starts with TLS immediately (port 465).
     * When false, the connection starts plaintext and STARTTLS can be
     * used to upgrade if an SSLContext is configured.
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
    public void setSSLContext(SSLContext context) {
        this.sslContext = context;
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
     * <p>Creates the transport factory, endpoint handler, and client
     * endpoint, then initiates the connection. Lifecycle events are
     * forwarded to the given handler.
     *
     * @param handler the handler to receive the server greeting and
     *                lifecycle events
     */
    public void connect(ServerGreeting handler) {
        transportFactory = new TCPTransportFactory();
        transportFactory.setSecure(secure);
        if (sslContext != null) {
            transportFactory.setSSLContext(sslContext);
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
            if (host != null) {
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
