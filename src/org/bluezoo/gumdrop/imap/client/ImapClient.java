/*
 * ImapClient.java
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

package org.bluezoo.gumdrop.imap.client;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.logging.Logger;

import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.client.ClientConnect;
import org.bluezoo.gumdrop.client.ClientDial;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.tls.ServerCredentials;

/**
 * High-level IMAP4rev2 client facade (RFC 9051).
 *
 * <p>This class provides a simple, concrete API for connecting to IMAP
 * servers. It internally creates a {@link TcpTransportFactory},
 * {@link ClientEndpoint}, and {@link ImapClientProtocolHandler}, wiring
 * them together and forwarding lifecycle events to the caller's
 * {@link RemoteGreeting} handler.
 *
 * <p>Supports plaintext (port 143) with STARTTLS upgrade
 * (RFC 9051 section 6.2.1) and implicit TLS/IMAPS (port 993,
 * RFC 8314 section 3.3).
 *
 * <h4>Plaintext with STARTTLS</h4>
 * <pre>{@code
 * ImapClient client = new ImapClient(selectorLoop, "imap.example.com", 143);
 * client.setClientCredentials(clientCredentials);
 * client.connect(new RemoteGreeting() {
 *     public void handleGreeting(ClientNotAuthenticatedState auth,
 *                                String greeting,
 *                                List<String> preAuthCapabilities) {
 *         auth.starttls(starttlsHandler);
 *     }
 *     // ...
 * });
 * }</pre>
 *
 * <h4>Implicit TLS (IMAPS)</h4>
 * <pre>{@code
 * ImapClient client = new ImapClient("imap.example.com", 993);
 * client.setSecure(true);
 * client.setClientCredentials(clientCredentials);
 * client.connect(greetingHandler);
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see RemoteGreeting
 * @see ImapClientProtocolHandler
 */
public class ImapClient {

    private static final Logger LOGGER =
            Logger.getLogger(ImapClient.class.getName());

    private final ClientDial dial = ClientDial.withDefaultPort(993);
    private final TlsConfig tls = new TlsConfig();
    private boolean secure;

    private MailboxEventListener mailboxEventListener;

    private TcpTransportFactory transportFactory;
    private ClientEndpoint clientEndpoint;
    private ImapClientProtocolHandler endpointHandler;

    /**
     * Creates a client for fluent configuration before {@link #connect(RemoteGreeting)}.
     */
    public ImapClient() {
    }

    /**
     * Creates an IMAP client for the given hostname and port.
     *
     * <p>Uses the next available worker loop from the global
     * {@link Gumdrop} instance. DNS resolution is deferred until
     * {@link #connect} is called.
     *
     * @param host the remote hostname or IP address
     * @param port the remote port
     */
    public ImapClient(String host, int port) {
        this(null, host, port);
    }

    /**
     * Creates an IMAP client with an explicit selector loop.
     *
     * <p>DNS resolution is deferred until {@link #connect} is called.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop
     *                     worker
     * @param host the remote hostname or IP address
     * @param port the remote port
     */
    public ImapClient(SelectorLoop selectorLoop, String host,
                      int port) {
        dial.selectorLoop(selectorLoop).host(host).port(port);
    }

    /**
     * Creates an IMAP client for the given address and port.
     *
     * @param host the remote host address
     * @param port the remote port
     */
    public ImapClient(InetAddress host, int port) {
        this(null, host, port);
    }

    /**
     * Creates an IMAP client with an explicit selector loop and address.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop
     *                     worker
     * @param host the remote host address
     * @param port the remote port
     */
    public ImapClient(SelectorLoop selectorLoop, InetAddress host,
                      int port) {
        dial.selectorLoop(selectorLoop).host(host).port(port);
    }

    /**
     * Creates an IMAP client for a UNIX domain socket, mirroring
     * {@link org.bluezoo.gumdrop.TcpListener#setPath} on the server side.
     *
     * <p>Uses the next available worker loop from the global {@link
     * Gumdrop} instance.
     *
     * @param socketPath the UNIX domain socket path
     */
    public ImapClient(String socketPath) {
        this(null, socketPath);
    }

    /**
     * Creates an IMAP client for a UNIX domain socket with an
     * explicit selector loop.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop worker
     * @param socketPath the UNIX domain socket path
     */
    public ImapClient(SelectorLoop selectorLoop, String socketPath) {
        dial.selectorLoop(selectorLoop).socketPath(socketPath);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Configuration (before connect)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Sets whether this client uses implicit TLS (IMAPS).
     *
     * <p>When true, the connection starts with TLS immediately (port 993).
     * When false, the connection starts plaintext and STARTTLS can be
     * used to upgrade if client credentials are configured.
     *
     * @param secure true for implicit TLS
     */
    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    /**
     * Sets client certificate credentials for TLS connections.
     *
     * <p>Required for both implicit TLS ({@code setSecure(true)}) and
     * explicit TLS via STARTTLS. When set without {@code setSecure(true)},
     * the in-tree TLS engine is configured but not started until the
     * handler calls {@code endpoint.startTLS()}.
     *
     * @param clientCredentials the client certificate credentials, if any
     */
    public void setClientCredentials(ServerCredentials clientCredentials) {
        tls.serverCredentials(clientCredentials);
    }

    /**
     * Sets a custom trust manager for TLS certificate verification.
     *
     * <p>When set, the trust manager is injected into the transport
     * factory's SSL context. Useful for certificate pinning
     * ({@link org.bluezoo.gumdrop.util.PinnedCertTrustManager}) or
     * disabling verification in dev/test
     * ({@link org.bluezoo.gumdrop.util.EmptyX509TrustManager}).
     *
     * @param trustManager the trust manager, or null to use defaults
     */
    public void setTrustManager(X509TrustManager trustManager) {
        tls.trustManager(trustManager);
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
     * Sets the listener for unsolicited mailbox events.
     *
     * <p>Can be called before or after {@link #connect}. If called after,
     * the listener is set on the existing protocol handler.
     *
     * @param listener the event listener, or null to clear
     */
    public void setMailboxEventListener(MailboxEventListener listener) {
        this.mailboxEventListener = listener;
        if (endpointHandler != null) {
            endpointHandler.setMailboxEventListener(listener);
        }
    }


    /** @return this client */
    public ImapClient secure(boolean secure) {
        this.secure = secure;
        return this;
    }

    public ImapClient trustJvm() {
        tls.trustJvm();
        return this;
    }

    /** @return this client */
    public ImapClient clientCredentials(ServerCredentials clientCredentials) {
        tls.serverCredentials(clientCredentials);
        return this;
    }

    /** @return this client */
    public ImapClient trustManager(X509TrustManager trustManager) {
        tls.trustManager(trustManager);
        return this;
    }

    /** @return this client */
    public ImapClient keystoreFile(Path path) {
        tls.keystoreFile(path);
        return this;
    }

    /** @return this client */
    public ImapClient keystorePass(String password) {
        tls.keystorePass(password);
        return this;
    }

    /** @return this client */
    public ImapClient keystoreFormat(String format) {
        tls.keystoreFormat(format);
        return this;
    }


    public ImapClient host(String host) {
        dial.host(host);
        return this;
    }

    public ImapClient host(InetAddress hostAddress) {
        dial.host(hostAddress);
        return this;
    }

    public ImapClient port(int port) {
        dial.port(port);
        return this;
    }

    public ImapClient socketPath(String socketPath) {
        dial.socketPath(socketPath);
        return this;
    }

    public ImapClient selectorLoop(SelectorLoop selectorLoop) {
        dial.selectorLoop(selectorLoop);
        return this;
    }

    public ImapClient dnsResolver(DnsResolver dnsResolver) {
        dial.dnsResolver(dnsResolver);
        return this;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Connects to the remote IMAP server.
     *
     * <p>Creates the transport factory, endpoint handler, and client
     * endpoint, then initiates the connection. Lifecycle events are
     * forwarded to the given handler.
     *
     * @param gumdrop the runtime this connection is made under
     * @param handler the handler to receive the server greeting and
     *                lifecycle events
     */
    public void connect(Gumdrop gumdrop, RemoteGreeting handler) {
        dial.requireTarget();
        transportFactory = new TcpTransportFactory();
        endpointHandler = new ImapClientProtocolHandler(handler);
        if (mailboxEventListener != null) {
            endpointHandler.setMailboxEventListener(mailboxEventListener);
        }
        ClientConnect.discoverEch(gumdrop, secure, dial, tls, new ClientConnect.EchDiscoveryCallback() {
            @Override
            public void discovered(byte[] echConfigList) {
                try {
                    ClientConnect.prepareTls(secure, tls, transportFactory, echConfigList);
                    endpointHandler.setSecure(secure);
                    clientEndpoint = ClientConnect.openAndConnect(
                            gumdrop, dial, transportFactory, endpointHandler);
                } catch (IOException e) {
                    handler.onError(e);
                }
            }
        });
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
