/*
 * FtpClientDataConnectionCoordinator.java
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

package org.bluezoo.gumdrop.ftp.client;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.AcceptSelectorLoop;
import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TcpEndpoint;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.TransportFactory;
import org.bluezoo.gumdrop.tls.ServerCredentials;

/**
 * Opens the FTP client's data connection (RFC 959 §3.2), the client-side
 * counterpart of the server's {@code FtpDataConnectionCoordinator}.
 *
 * <p><strong>Passive mode</strong> (PASV/EPSV): a single outbound TCP
 * connection to the address the server returned — a thin wrapper around
 * {@link ClientEndpoint} that shares the control connection's {@link
 * org.bluezoo.gumdrop.SelectorLoop}.
 *
 * <p><strong>Active mode</strong> (PORT/EPRT): the client instead listens
 * and the server connects in. This mirrors the server-side coordinator's
 * own passive-mode acceptor ({@code FtpDataConnectionCoordinator}'s
 * {@code incomingDataConnections} queue / {@code waitingContinuation}
 * pattern, just with the roles reversed): the accept happens on {@link
 * Gumdrop#getAcceptLoop()}'s thread, so a connection that arrives before
 * {@link #acceptNext} is called is queued, and a call to {@link
 * #acceptNext} that arrives before the connection is parked — whichever
 * happens second completes the hand-off, on the control connection's own
 * loop thread via {@link Endpoint#execute(Runnable)}.
 *
 * <p>Either way, {@link FtpClientProtocolHandler} owns the actual transfer
 * coordination (correlating the data connection's EOF with the control
 * connection's final reply code) — see its {@code *DataHandler} inner
 * classes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc959">RFC 959</a> §3.2
 * @see <a href="https://www.rfc-editor.org/rfc/rfc2428">RFC 2428</a> (EPRT/EPSV)
 */
final class FtpClientDataConnectionCoordinator {

    private final Gumdrop gumdrop;
    private final Endpoint controlEndpoint;
    private TcpTransportFactory transportFactory;

    // Active-mode (PORT/EPRT) listener state, guarded by 'this'.
    private ActiveListener activeListener;
    private ActiveListenerOpener listenerOpener = new LoopListenerOpener();
    private DataConnector dataConnector = new LoopDataConnector();
    private final BlockingQueue<SocketChannel> incomingActiveConnections =
            new LinkedBlockingQueue<SocketChannel>();
    private ProtocolHandler pendingActiveHandler;

    // RFC 4217 §9 (PROT). A second, TLS-enabled transport factory is used
    // for passive-mode data connections once PROT P is active, built
    // lazily so plaintext transfers never pay for one. Active-mode
    // (PORT/EPRT) TLS protection is not yet supported — see acceptNext().
    private boolean dataProtectionEnabled;
    private ServerCredentials dataClientCredentials;
    private TcpTransportFactory secureTransportFactory;

    /** A bound active-mode (PORT/EPRT) listener. Package-private test seam. */
    interface ActiveListener {
        /** The bound local address, announced to the server. */
        InetSocketAddress address();

        /** Releases the listening socket. */
        void close();
    }

    /**
     * Opens active-mode listeners. The default binds a real
     * {@link ServerSocketChannel} on {@link Gumdrop}'s accept loop; tests
     * substitute a mock so no socket or loop is needed.
     */
    interface ActiveListenerOpener {
        /**
         * @param local the control connection's local address to bind
         * @param onAccept called with the server's incoming data connection
         * @return the bound listener
         * @throws IOException if it could not be opened
         */
        ActiveListener open(InetAddress local,
                AcceptSelectorLoop.RawAcceptHandler onAccept) throws IOException;
    }

    /** Default opener: a real listening channel registered on the accept loop. */
    private final class LoopListenerOpener implements ActiveListenerOpener {
        @Override
        public ActiveListener open(InetAddress local,
                AcceptSelectorLoop.RawAcceptHandler onAccept) throws IOException {
            final ServerSocketChannel ssc = ServerSocketChannel.open();
            ssc.configureBlocking(false);
            ssc.bind(new InetSocketAddress(local, 0));
            gumdrop.ensureAcceptLoop();
            gumdrop.getAcceptLoop().registerRawAcceptor(ssc, onAccept);
            final InetSocketAddress address = (InetSocketAddress) ssc.getLocalAddress();
            return new ActiveListener() {
                @Override
                public InetSocketAddress address() {
                    return address;
                }

                @Override
                public void close() {
                    AcceptSelectorLoop loop = gumdrop.getAcceptLoop();
                    if (loop != null) {
                        loop.closeRawAcceptor(ssc);
                        return;
                    }
                    try {
                        ssc.close();
                    } catch (IOException e) {
                        // Ignore close errors
                    }
                }
            };
        }
    }

    /** Test seam: substitutes how active-mode listeners are opened. */
    void setActiveListenerOpener(ActiveListenerOpener opener) {
        this.listenerOpener = opener;
    }

    /**
     * Creates the data endpoints themselves. The default opens a {@link
     * ClientEndpoint} for passive mode and registers an accepted channel on
     * the control connection's loop for active mode; tests substitute a mock
     * that hands the handler an in-memory endpoint.
     */
    interface DataConnector {
        /**
         * Opens the passive-mode connection to the server's data address.
         *
         * @param gumdrop the runtime
         * @param control the control connection (for its loop)
         * @param address the server's data address
         * @param handler told of the connection's lifecycle
         * @param factory the (plain or TLS) transport factory to use
         * @throws IOException if the connect could not be started
         */
        void connect(Gumdrop gumdrop, Endpoint control,
                InetSocketAddress address, ProtocolHandler handler,
                TcpTransportFactory factory) throws IOException;

        /**
         * Adopts the server's incoming active-mode connection, on the
         * control connection's loop thread.
         *
         * @param control the control connection (for its loop)
         * @param channel the accepted channel
         * @param handler told of the connection's lifecycle
         * @throws IOException if the channel could not be adopted
         */
        void adopt(Endpoint control, SocketChannel channel,
                ProtocolHandler handler) throws IOException;
    }

    /** Default connector: real client endpoints and loop registration. */
    private static final class LoopDataConnector implements DataConnector {
        @Override
        public void connect(Gumdrop gumdrop, Endpoint control,
                InetSocketAddress address, ProtocolHandler handler,
                TcpTransportFactory factory) throws IOException {
            ClientEndpoint dataEndpoint = new ClientEndpoint(factory,
                    control.getSelectorLoop(),
                    address.getAddress(), address.getPort());
            dataEndpoint.connect(gumdrop, handler);
        }

        @Override
        public void adopt(Endpoint control, SocketChannel sc,
                ProtocolHandler handler) throws IOException {
            sc.configureBlocking(false);
            TcpEndpoint dataEndpoint = new TcpEndpoint(handler);
            dataEndpoint.setChannel(sc);
            dataEndpoint.init();
            control.getSelectorLoop().registerTCP(sc, dataEndpoint);
            handler.connected(dataEndpoint);
        }
    }

    /** Test seam: substitutes how data endpoints are created. */
    void setDataConnector(DataConnector connector) {
        this.dataConnector = connector;
    }

    FtpClientDataConnectionCoordinator(Gumdrop gumdrop, Endpoint controlEndpoint) {
        this.gumdrop = gumdrop;
        this.controlEndpoint = controlEndpoint;
    }

    /**
     * Sets whether passive-mode data connections should be TLS-protected
     * (RFC 4217 §9, PROT P), and the client credentials to present on them.
     *
     * @param enabled true if PROT P is active
     * @param clientCredentials the client's own credentials to present on
     *      secured data connections, or null for none
     */
    void setDataProtection(boolean enabled, ServerCredentials clientCredentials) {
        this.dataProtectionEnabled = enabled;
        this.dataClientCredentials = clientCredentials;
    }

    /**
     * Opens an outbound data connection to the given address (as returned
     * by PASV/EPSV), sharing the control connection's SelectorLoop, and
     * wires it to {@code dataHandler}. TLS-protected (RFC 4217 §9) if
     * PROT P is active.
     *
     * @param dataAddress the server's data connection address
     * @param dataHandler the handler for the data connection's lifecycle
     */
    void connect(InetSocketAddress dataAddress, ProtocolHandler dataHandler) {
        TcpTransportFactory factory = dataProtectionEnabled
                ? secureTransportFactory() : plainTransportFactory();
        try {
            dataConnector.connect(gumdrop, controlEndpoint, dataAddress,
                    dataHandler, factory);
        } catch (IOException e) {
            dataHandler.error(e);
        }
    }

    /**
     * Substitutes the plaintext data-connection transport factory. Package-private
     * so tests can inject a factory whose connect fails deterministically.
     */
    void setPlainTransportFactory(TcpTransportFactory factory) {
        this.transportFactory = factory;
    }

    /** The current active-mode listener, or null. Package-private for tests. */
    synchronized ActiveListener activeListener() {
        return activeListener;
    }

    private TcpTransportFactory plainTransportFactory() {
        if (transportFactory == null) {
            transportFactory = new TcpTransportFactory();
            transportFactory.start();
        }
        return transportFactory;
    }

    private TcpTransportFactory secureTransportFactory() {
        if (secureTransportFactory == null) {
            secureTransportFactory = new TcpTransportFactory();
            secureTransportFactory.setSecure(true);
            if (dataClientCredentials != null) {
                secureTransportFactory.setClientCredentials(dataClientCredentials);
            }
            if (controlEndpoint instanceof TcpEndpoint) {
                TransportFactory controlFactory =
                        ((TcpEndpoint) controlEndpoint).getTransportFactory();
                if (controlFactory instanceof TcpTransportFactory) {
                    X509TrustManager trust =
                            ((TcpTransportFactory) controlFactory).getTrustManager();
                    if (trust != null) {
                        secureTransportFactory.setTrustManager(trust);
                    }
                }
            }
            secureTransportFactory.start();
        }
        return secureTransportFactory;
    }

    /**
     * Opens a local listener on an ephemeral port, bound to the same
     * local address as the control connection, for active mode
     * (PORT/EPRT). Any previously-open listener is closed first.
     *
     * @return the bound local address, to announce to the server via
     *      PORT/EPRT
     * @throws IOException if the listener could not be opened
     */
    InetSocketAddress openActiveListener() throws IOException {
        closeActiveListener();

        InetAddress localAddress =
                ((InetSocketAddress) controlEndpoint.getLocalAddress()).getAddress();
        ActiveListener opened = listenerOpener.open(localAddress,
                new AcceptSelectorLoop.RawAcceptHandler() {
                    @Override
                    public void accepted(SocketChannel sc) throws IOException {
                        onActiveAccept(sc);
                    }
                });
        synchronized (this) {
            activeListener = opened;
        }
        return opened.address();
    }

    /**
     * Called (on the accept loop's thread) when the server connects to
     * the active-mode listener. Delivers immediately to a handler already
     * waiting via {@link #acceptNext}, or queues the connection if none is
     * waiting yet — the PORT/EPRT reply and the server's connect race, and
     * either order is legal.
     */
    private void onActiveAccept(SocketChannel sc) {
        ProtocolHandler handler;
        synchronized (this) {
            // Only one connection is expected per PORT/EPRT. We are on
            // the accept thread, so this closes in place without waiting.
            releaseListener(takeListener());
            if (pendingActiveHandler == null) {
                incomingActiveConnections.offer(sc);
                return;
            }
            handler = pendingActiveHandler;
            pendingActiveHandler = null;
        }
        wireActiveConnection(sc, handler);
    }

    /**
     * Delivers the next active-mode connection to {@code dataHandler},
     * either immediately (if the server already connected) or once it
     * does.
     *
     * @param dataHandler the handler for the data connection's lifecycle
     */
    void acceptNext(ProtocolHandler dataHandler) {
        if (dataProtectionEnabled) {
            // RFC 4217 §9 PROT P is not yet implemented for active-mode
            // data connections: the accepting side would need to perform
            // a server-role TLS handshake on an already-open channel,
            // which this coordinator does not yet set up.
            dataHandler.error(new IOException(
                    "PROT P is not supported for active-mode (PORT/EPRT) "
                            + "data connections"));
            return;
        }
        SocketChannel sc;
        synchronized (this) {
            sc = incomingActiveConnections.poll();
            if (sc == null) {
                pendingActiveHandler = dataHandler;
                return;
            }
        }
        wireActiveConnection(sc, dataHandler);
    }

    /**
     * Registers the accepted channel with the control connection's
     * SelectorLoop and delivers it to {@code dataHandler}, on the control
     * connection's loop thread (accepts arrive on the accept loop's
     * thread instead).
     */
    private void wireActiveConnection(final SocketChannel sc, final ProtocolHandler dataHandler) {
        controlEndpoint.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    dataConnector.adopt(controlEndpoint, sc, dataHandler);
                } catch (IOException e) {
                    dataHandler.error(e);
                }
            }
        });
    }

    /**
     * Detaches the listening socket from this coordinator, for {@link
     * #releaseListener}. Caller holds the lock.
     */
    private ActiveListener takeListener() {
        ActiveListener l = activeListener;
        activeListener = null;
        return l;
    }

    /**
     * Closes just the listening socket, not any already-accepted
     * connection. The close goes through the accept loop, which owns the
     * channel's selector registration, so the port is really released when
     * this returns (a plain close from another thread would leave it
     * accepting until the loop next selects). Must not be called holding
     * this coordinator's lock from any thread but the accept thread, which
     * the loop's own thread may be waiting on.
     */
    private void releaseListener(ActiveListener listener) {
        if (listener != null) {
            listener.close();
        }
    }

    /**
     * Closes the active-mode listener (if open) and discards any queued,
     * never-delivered connection. Used on PORT/EPRT rejection and transfer
     * failure cleanup.
     */
    void closeActiveListener() {
        ActiveListener ssc;
        synchronized (this) {
            ssc = takeListener();
            pendingActiveHandler = null;
        }
        // Outside the lock: waits for the accept thread, which may itself
        // be blocked on this lock in onActiveAccept().
        releaseListener(ssc);
        SocketChannel sc;
        while ((sc = incomingActiveConnections.poll()) != null) {
            try {
                sc.close();
            } catch (IOException e) {
                // Ignore close errors
            }
        }
    }
}
