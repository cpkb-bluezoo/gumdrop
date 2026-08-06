/*
 * FTPClientDataConnectionCoordinator.java
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

import javax.net.ssl.SSLContext;

import org.bluezoo.gumdrop.AcceptSelectorLoop;
import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TCPEndpoint;
import org.bluezoo.gumdrop.TCPTransportFactory;

/**
 * Opens the FTP client's data connection (RFC 959 §3.2), the client-side
 * counterpart of the server's {@code FTPDataConnectionCoordinator}.
 *
 * <p><strong>Passive mode</strong> (PASV/EPSV): a single outbound TCP
 * connection to the address the server returned — a thin wrapper around
 * {@link ClientEndpoint} that shares the control connection's {@link
 * org.bluezoo.gumdrop.SelectorLoop}.
 *
 * <p><strong>Active mode</strong> (PORT/EPRT): the client instead listens
 * and the server connects in. This mirrors the server-side coordinator's
 * own passive-mode acceptor ({@code FTPDataConnectionCoordinator}'s
 * {@code incomingDataConnections} queue / {@code waitingContinuation}
 * pattern, just with the roles reversed): the accept happens on {@link
 * Gumdrop#getAcceptLoop()}'s thread, so a connection that arrives before
 * {@link #acceptNext} is called is queued, and a call to {@link
 * #acceptNext} that arrives before the connection is parked — whichever
 * happens second completes the hand-off, on the control connection's own
 * loop thread via {@link Endpoint#execute(Runnable)}.
 *
 * <p>Either way, {@link FTPClientProtocolHandler} owns the actual transfer
 * coordination (correlating the data connection's EOF with the control
 * connection's final reply code) — see its {@code *DataHandler} inner
 * classes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc959">RFC 959</a> §3.2
 * @see <a href="https://www.rfc-editor.org/rfc/rfc2428">RFC 2428</a> (EPRT/EPSV)
 */
final class FTPClientDataConnectionCoordinator {

    private final Endpoint controlEndpoint;
    private TCPTransportFactory transportFactory;

    // Active-mode (PORT/EPRT) listener state, guarded by 'this'.
    private ServerSocketChannel activeListenerChannel;
    private final BlockingQueue<SocketChannel> incomingActiveConnections =
            new LinkedBlockingQueue<SocketChannel>();
    private ProtocolHandler pendingActiveHandler;

    // RFC 4217 §9 (PROT). A second, TLS-enabled transport factory is used
    // for passive-mode data connections once PROT P is active, built
    // lazily so plaintext transfers never pay for one. Active-mode
    // (PORT/EPRT) TLS protection is not yet supported — see acceptNext().
    private boolean dataProtectionEnabled;
    private SSLContext dataSslContext;
    private TCPTransportFactory secureTransportFactory;

    FTPClientDataConnectionCoordinator(Endpoint controlEndpoint) {
        this.controlEndpoint = controlEndpoint;
    }

    /**
     * Sets whether passive-mode data connections should be TLS-protected
     * (RFC 4217 §9, PROT P), and the SSL context to use for them.
     *
     * @param enabled true if PROT P is active
     * @param sslContext the SSL context to secure data connections with,
     *      or null to use the platform default
     */
    void setDataProtection(boolean enabled, SSLContext sslContext) {
        this.dataProtectionEnabled = enabled;
        this.dataSslContext = sslContext;
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
        TCPTransportFactory factory = dataProtectionEnabled
                ? secureTransportFactory() : plainTransportFactory();
        ClientEndpoint dataEndpoint = new ClientEndpoint(factory,
                controlEndpoint.getSelectorLoop(),
                dataAddress.getAddress(), dataAddress.getPort());
        try {
            dataEndpoint.connect(dataHandler);
        } catch (IOException e) {
            dataHandler.error(e);
        }
    }

    private TCPTransportFactory plainTransportFactory() {
        if (transportFactory == null) {
            transportFactory = new TCPTransportFactory();
            transportFactory.start();
        }
        return transportFactory;
    }

    private TCPTransportFactory secureTransportFactory() {
        if (secureTransportFactory == null) {
            secureTransportFactory = new TCPTransportFactory();
            secureTransportFactory.setSecure(true);
            if (dataSslContext != null) {
                secureTransportFactory.setSSLContext(dataSslContext);
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
    synchronized InetSocketAddress openActiveListener() throws IOException {
        closeActiveListener();

        InetAddress localAddress =
                ((InetSocketAddress) controlEndpoint.getLocalAddress()).getAddress();
        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.configureBlocking(false);
        ssc.bind(new InetSocketAddress(localAddress, 0));
        activeListenerChannel = ssc;

        Gumdrop gumdrop = Gumdrop.getInstance();
        gumdrop.ensureAcceptLoop();
        gumdrop.getAcceptLoop().registerRawAcceptor(ssc,
                new AcceptSelectorLoop.RawAcceptHandler() {
                    @Override
                    public void accepted(SocketChannel sc) throws IOException {
                        onActiveAccept(sc);
                    }
                });

        return (InetSocketAddress) ssc.getLocalAddress();
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
            // Only one connection is expected per PORT/EPRT.
            stopListening();
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
                    sc.configureBlocking(false);
                    TCPEndpoint dataEndpoint = new TCPEndpoint(dataHandler);
                    dataEndpoint.setChannel(sc);
                    dataEndpoint.init();
                    controlEndpoint.getSelectorLoop().registerTCP(sc, dataEndpoint);
                    dataHandler.connected(dataEndpoint);
                } catch (IOException e) {
                    dataHandler.error(e);
                }
            }
        });
    }

    /** Closes just the listening socket, not any already-accepted connection. */
    private void stopListening() {
        if (activeListenerChannel != null) {
            try {
                activeListenerChannel.close();
            } catch (IOException e) {
                // Ignore close errors
            }
            activeListenerChannel = null;
        }
    }

    /**
     * Closes the active-mode listener (if open) and discards any queued,
     * never-delivered connection. Used on PORT/EPRT rejection and transfer
     * failure cleanup.
     */
    synchronized void closeActiveListener() {
        stopListening();
        SocketChannel sc;
        while ((sc = incomingActiveConnections.poll()) != null) {
            try {
                sc.close();
            } catch (IOException e) {
                // Ignore close errors
            }
        }
        pendingActiveHandler = null;
    }
}
