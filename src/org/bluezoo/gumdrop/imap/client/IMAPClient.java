/*
 * IMAPClient.java
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
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TCPTransportFactory;
import org.bluezoo.gumdrop.imap.client.handler.MailboxEventListener;
import org.bluezoo.gumdrop.imap.client.handler.ServerGreeting;

/**
 * High-level IMAP client facade.
 *
 * <p>This class provides a simple, concrete API for connecting to IMAP
 * servers. It internally creates a {@link TCPTransportFactory},
 * {@link ClientEndpoint}, and {@link IMAPClientProtocolHandler}, wiring
 * them together and forwarding lifecycle events to the caller's
 * {@link ServerGreeting} handler.
 *
 * <h4>Plaintext with STARTTLS</h4>
 * <pre>{@code
 * IMAPClient client = new IMAPClient(selectorLoop, "imap.example.com", 143);
 * client.setSSLContext(sslContext);
 * client.connect(new ServerGreeting() {
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
 * IMAPClient client = new IMAPClient("imap.example.com", 993);
 * client.setSecure(true);
 * client.setSSLContext(sslContext);
 * client.connect(greetingHandler);
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerGreeting
 * @see IMAPClientProtocolHandler
 */
public class IMAPClient {

    private static final Logger LOGGER =
            Logger.getLogger(IMAPClient.class.getName());

    private final String host;
    private final InetAddress hostAddress;
    private final int port;
    private final SelectorLoop selectorLoop;

    private boolean secure;
    private SSLContext sslContext;
    private MailboxEventListener mailboxEventListener;

    private TCPTransportFactory transportFactory;
    private ClientEndpoint clientEndpoint;
    private IMAPClientProtocolHandler endpointHandler;

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
    public IMAPClient(String host, int port) {
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
    public IMAPClient(SelectorLoop selectorLoop, String host,
                      int port) {
        this.selectorLoop = selectorLoop;
        this.host = host;
        this.hostAddress = null;
        this.port = port;
    }

    /**
     * Creates an IMAP client for the given address and port.
     *
     * @param host the remote host address
     * @param port the remote port
     */
    public IMAPClient(InetAddress host, int port) {
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
    public IMAPClient(SelectorLoop selectorLoop, InetAddress host,
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
     * Sets whether this client uses implicit TLS (IMAPS).
     *
     * <p>When true, the connection starts with TLS immediately (port 993).
     * When false, the connection starts plaintext and STARTTLS can be
     * used to upgrade if an SSLContext is configured.
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
     * @param handler the handler to receive the server greeting and
     *                lifecycle events
     */
    public void connect(ServerGreeting handler) {
        transportFactory = new TCPTransportFactory();
        transportFactory.setSecure(secure);
        if (sslContext != null) {
            transportFactory.setSSLContext(sslContext);
        }
        transportFactory.start();

        endpointHandler = new IMAPClientProtocolHandler(handler);
        endpointHandler.setSecure(secure);
        if (mailboxEventListener != null) {
            endpointHandler.setMailboxEventListener(
                    mailboxEventListener);
        }

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
