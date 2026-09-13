/*
 * Pop3Client.java
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

package org.bluezoo.gumdrop.pop3.client;

import java.io.IOException;
import java.util.function.Supplier;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.tls.ServerCredentials;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.pop3.client.handler.RemoteGreeting;

/**
 * High-level POP3 client facade (RFC 1939).
 *
 * <p>Supports both plaintext POP3 (port 110, RFC 1939) with optional
 * STLS upgrade (RFC 2595 section 4) and implicit TLS (POP3S, port 995,
 * RFC 8314 section 3.3).
 *
 * <p>This class provides a simple, concrete API for connecting to POP3
 * servers. It internally creates a {@link TcpTransportFactory},
 * {@link ClientEndpoint}, and {@link Pop3ClientProtocolHandler}, wiring
 * them together and forwarding lifecycle events to the caller's
 * {@link RemoteGreeting} handler.
 *
 * <h4>Plaintext with STLS</h4>
 * <pre>{@code
 * Pop3Client client = new Pop3Client(selectorLoop, "pop.example.com", 110);
 * client.setClientCredentials(clientCredentials); // Makes TLS available for STLS
 * client.connect(new RemoteGreeting() {
 *     public void handleGreeting(ClientAuthorizationState auth,
 *                                String message, String apopTimestamp) {
 *         auth.capa(new CapaReplyHandler() {
 *             public void handleCapabilities(ClientAuthorizationState auth,
 *                     boolean stls, List&lt;String&gt; saslMechanisms,
 *                     boolean top, boolean uidl, boolean user,
 *                     boolean pipelining, String implementation) {
 *                 if (stls) {
 *                     auth.stls(stlsHandler);
 *                 } else {
 *                     auth.user("alice", userHandler);
 *                 }
 *             }
 *             // ...
 *         });
 *     }
 *     public void handleServiceUnavailable(String message) { }
 *     public void onConnected(Endpoint endpoint) { }
 *     public void onSecurityEstablished(SecurityInfo info) { }
 *     public void onError(Exception cause) { cause.printStackTrace(); }
 *     public void onDisconnected() { }
 * });
 * }</pre>
 *
 * <h4>Implicit TLS (POP3S)</h4>
 * <pre>{@code
 * Pop3Client client = new Pop3Client("pop.example.com", 995);
 * client.setSecure(true);
 * client.setClientCredentials(clientCredentials);
 * client.connect(greetingHandler);
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see RemoteGreeting
 * @see Pop3ClientProtocolHandler
 * @see <a href="https://www.rfc-editor.org/rfc/rfc1939">RFC 1939 — POP3</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8314">RFC 8314 — Implicit TLS</a>
 */
public class Pop3Client {

    private static final Logger LOGGER =
            Logger.getLogger(Pop3Client.class.getName());

    private String host;
    private InetAddress hostAddress;
    private int port;
    private String socketPath;
    private SelectorLoop selectorLoop;

    private boolean secure;
    private ServerCredentials clientCredentials;
    private X509TrustManager trustManager;
    private Path keystoreFile;
    private String keystorePass;
    private String keystoreFormat;

    private TcpTransportFactory transportFactory;
    private ClientEndpoint clientEndpoint;
    private Pop3ClientProtocolHandler endpointHandler;

    private Pop3ClientSessionProvider sessionProvider;


    /**
     * Creates a client for fluent configuration before {@link #connect()}.
     */
    public Pop3Client() {
        this.selectorLoop = null;
        this.host = null;
        this.hostAddress = null;
        this.port = 110;
        this.socketPath = null;
    }

    /**
     * Creates a POP3 client for the given hostname and port.
     *
     * <p>Uses the next available worker loop from the global
     * {@link Gumdrop} instance. DNS resolution is deferred until
     * {@link #connect} is called.
     *
     * @param host the remote hostname or IP address
     * @param port the remote port
     */
    public Pop3Client(String host, int port) {
        this(null, host, port);
    }

    /**
     * Creates a POP3 client with an explicit selector loop.
     *
     * <p>DNS resolution is deferred until {@link #connect} is called.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop
     *                     worker
     * @param host the remote hostname or IP address
     * @param port the remote port
     */
    public Pop3Client(SelectorLoop selectorLoop, String host, int port) {
        this.selectorLoop = selectorLoop;
        this.host = host;
        this.hostAddress = null;
        this.port = port;
        this.socketPath = null;
    }

    /**
     * Creates a POP3 client for the given address and port.
     *
     * @param host the remote host address
     * @param port the remote port
     */
    public Pop3Client(InetAddress host, int port) {
        this(null, host, port);
    }

    /**
     * Creates a POP3 client with an explicit selector loop and address.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop
     *                     worker
     * @param host the remote host address
     * @param port the remote port
     */
    public Pop3Client(SelectorLoop selectorLoop, InetAddress host,
                      int port) {
        this.selectorLoop = selectorLoop;
        this.host = null;
        this.hostAddress = host;
        this.port = port;
        this.socketPath = null;
    }

    /**
     * Creates a POP3 client for a UNIX domain socket, mirroring {@link
     * org.bluezoo.gumdrop.TcpListener#setPath} on the server side.
     *
     * <p>Uses the next available worker loop from the global {@link
     * Gumdrop} instance.
     *
     * @param socketPath the UNIX domain socket path
     */
    public Pop3Client(String socketPath) {
        this(null, socketPath);
    }

    /**
     * Creates a POP3 client for a UNIX domain socket with an explicit
     * selector loop.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop worker
     * @param socketPath the UNIX domain socket path
     */
    public Pop3Client(SelectorLoop selectorLoop, String socketPath) {
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
     * Sets whether this client uses implicit TLS (POP3S).
     *
     * <p>When true, the connection starts with TLS immediately (port 995).
     * When false, the connection starts plaintext and STLS can be used
     * to upgrade if client credentials are configured.
     *
     * @param secure true for implicit TLS
     */
    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    /**
     * Sets the SSL context for TLS connections.
     *
     * <p>Required for both implicit TLS ({@code setSecure(true)}) and
     * explicit TLS via STLS. When set without {@code setSecure(true)},
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


    /** @return this client */
    public Pop3Client secure(boolean secure) {
        setSecure(secure);
        return this;
    }

    /** @return this client */
    public Pop3Client clientCredentials(ServerCredentials clientCredentials) {
        setClientCredentials(clientCredentials);
        return this;
    }

    /** @return this client */
    public Pop3Client trustManager(X509TrustManager trustManager) {
        setTrustManager(trustManager);
        return this;
    }

    /** @return this client */
    public Pop3Client keystoreFile(Path path) {
        setKeystoreFile(path);
        return this;
    }

    /** @return this client */
    public Pop3Client keystorePass(String password) {
        setKeystorePass(password);
        return this;
    }

    /** @return this client */
    public Pop3Client keystoreFormat(String format) {
        setKeystoreFormat(format);
        return this;
    }


    public Pop3Client host(String host) {
        this.host = host;
        this.hostAddress = null;
        this.socketPath = null;
        return this;
    }

    public Pop3Client host(InetAddress hostAddress) {
        if (hostAddress == null) {
            throw new NullPointerException("hostAddress");
        }
        this.hostAddress = hostAddress;
        this.host = null;
        this.socketPath = null;
        return this;
    }

    public Pop3Client port(int port) {
        this.port = port;
        return this;
    }

    public Pop3Client socketPath(String socketPath) {
        if (socketPath == null) {
            throw new NullPointerException("socketPath");
        }
        this.socketPath = socketPath;
        this.host = null;
        this.hostAddress = null;
        return this;
    }

    public Pop3Client selectorLoop(SelectorLoop selectorLoop) {
        this.selectorLoop = selectorLoop;
        return this;
    }

    public Pop3Client sessionProvider(Pop3ClientSessionProvider provider) {
        setSessionProvider(provider);
        return this;
    }

    public Pop3Client sessionPerConnection(Supplier<RemoteGreeting> supplier) {
        return sessionProvider(Pop3ClientSessionProviders.perSession(supplier));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Connects to the remote POP3 server.
     *
     * <p>Creates the transport factory, endpoint handler, and client
     * endpoint, then initiates the connection. Lifecycle events are
     * forwarded to the given handler.
     *
     * @param handler the handler to receive the server greeting and
     *                lifecycle events
     */
    public void connect(RemoteGreeting handler) {
        if (socketPath == null && host == null && hostAddress == null) {
            throw new IllegalStateException(
                    "host, host address, or socketPath is required");
        }
        transportFactory = new TcpTransportFactory();
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

        endpointHandler = new Pop3ClientProtocolHandler(handler);
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
     * Connects using a {@link Pop3ClientSessionProvider}.
     */
    public void connect(Pop3ClientSessionProvider provider) {
        connect(provider.openSession());
    }

    /**
     * Connects using the configured session provider.
     */
    public void connect() {
        if (sessionProvider == null) {
            throw new IllegalStateException(
                    "sessionProvider is required; use .sessionProvider(...)"
                            + " or connect(RemoteGreeting)");
        }
        connect(sessionProvider);
    }

    public Pop3ClientSessionProvider getSessionProvider() {
        return sessionProvider;
    }

    public void setSessionProvider(Pop3ClientSessionProvider sessionProvider) {
        this.sessionProvider = sessionProvider;
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
     * Closes the connection and deregisters from Gumdrop's lifecycle
     * tracking.
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
