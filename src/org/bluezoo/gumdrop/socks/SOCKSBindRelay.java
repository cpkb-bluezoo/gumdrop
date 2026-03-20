/*
 * SOCKSBindRelay.java
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

package org.bluezoo.gumdrop.socks;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.AcceptSelectorLoop;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.TimerHandle;

/**
 * Manages a single SOCKS BIND session (RFC 1928 §4, SOCKS4 CD=2).
 *
 * <p>BIND is a two-reply command: the server binds a listening port
 * and sends the first reply with BND.ADDR:BND.PORT, then waits for
 * an incoming TCP connection. When a connection arrives, the server
 * sends a second reply and begins bidirectional data relay.
 *
 * <p>This class implements {@link AcceptSelectorLoop.RawAcceptHandler}
 * to receive the accepted connection. Since {@code accepted()} runs
 * on the AcceptSelectorLoop thread, all state mutations are bounced
 * to the control connection's SelectorLoop via
 * {@link Endpoint#execute(Runnable)}.
 *
 * <p>The {@code ServerSocketChannel} is single-use: it accepts
 * exactly one connection, then is closed.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc1928#section-4">
 *      RFC 1928 §4</a>
 */
class SOCKSBindRelay implements AcceptSelectorLoop.RawAcceptHandler {

    private static final Logger LOGGER =
            Logger.getLogger(SOCKSBindRelay.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.socks.L10N");

    /**
     * Callback interface for notifying the protocol handler of
     * BIND events. All methods are called on the control connection's
     * SelectorLoop thread.
     */
    interface Callback {
        void bindAccepted(SocketChannel sc,
                          InetSocketAddress peerAddress);

        void bindFailed(byte replyCode);
    }

    private final Endpoint controlEndpoint;
    private final SOCKSService service;
    private final long idleTimeoutMs;
    private final InetAddress expectedPeerAddress;
    private final Callback callback;

    private ServerSocketChannel serverChannel;
    private boolean closed;
    private TimerHandle idleTimer;

    /**
     * Creates a new BIND relay.
     *
     * @param controlEndpoint the TCP control connection endpoint
     * @param service the SOCKS service
     * @param idleTimeoutMs idle timeout for waiting for the
     *        incoming connection
     * @param expectedPeerAddress expected peer IP from the BIND
     *        request's DST.ADDR, or null if any peer is accepted
     * @param callback the protocol handler callback
     */
    SOCKSBindRelay(Endpoint controlEndpoint, SOCKSService service,
                   long idleTimeoutMs,
                   InetAddress expectedPeerAddress,
                   Callback callback) {
        this.controlEndpoint = controlEndpoint;
        this.service = service;
        this.idleTimeoutMs = idleTimeoutMs;
        this.expectedPeerAddress = expectedPeerAddress;
        this.callback = callback;
    }

    /**
     * Binds a listening port and registers with the
     * AcceptSelectorLoop.
     *
     * @return the bound address (BND.ADDR:BND.PORT for Reply 1)
     * @throws IOException if the server socket cannot be bound
     */
    InetSocketAddress start() throws IOException {
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(0));

        InetSocketAddress boundAddress =
                (InetSocketAddress) serverChannel.getLocalAddress();

        Gumdrop.getInstance().getAcceptLoop()
                .registerRawAcceptor(serverChannel, this);

        startIdleTimer();

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(
                    L10N.getString("log.bind_listening"),
                    boundAddress.getPort()));
        }

        return boundAddress;
    }

    /**
     * Called by AcceptSelectorLoop when an incoming connection
     * arrives on the bound port.
     *
     * <p>This method runs on the AcceptSelectorLoop thread.
     * It reconfigures the channel to non-blocking and bounces
     * to the control SelectorLoop for all state mutations.
     */
    @Override
    public void accepted(SocketChannel sc) throws IOException {
        sc.configureBlocking(false);

        final InetSocketAddress peerAddress =
                (InetSocketAddress) sc.getRemoteAddress();
        final SocketChannel accepted = sc;

        controlEndpoint.execute(new Runnable() {
            @Override
            public void run() {
                if (closed) {
                    try {
                        accepted.close();
                    } catch (IOException e) {
                        // ignore
                    }
                    return;
                }

                closeServerChannel();
                cancelIdleTimer();

                // RFC 1928 §4: validate incoming peer IP
                if (expectedPeerAddress != null
                        && !expectedPeerAddress.isAnyLocalAddress()
                        && !peerAddress.getAddress()
                                .equals(expectedPeerAddress)) {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.fine(MessageFormat.format(
                                L10N.getString(
                                        "log.bind_peer_rejected"),
                                peerAddress.getAddress(),
                                expectedPeerAddress));
                    }
                    try {
                        accepted.close();
                    } catch (IOException e) {
                        // ignore
                    }
                    callback.bindFailed(
                            SOCKSConstants.SOCKS5_REPLY_NOT_ALLOWED);
                    return;
                }

                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(MessageFormat.format(
                            L10N.getString("log.bind_accepted"),
                            peerAddress.getAddress()
                                    .getHostAddress(),
                            peerAddress.getPort()));
                }

                callback.bindAccepted(accepted, peerAddress);
            }
        });
    }

    /**
     * Closes the server channel and cancels the idle timer.
     * Called when the TCP control connection closes before
     * an incoming connection arrives.
     */
    void close() {
        if (closed) {
            return;
        }
        closed = true;
        cancelIdleTimer();
        closeServerChannel();
        service.releaseRelay();

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(L10N.getString("log.bind_closed"));
        }
    }

    private void closeServerChannel() {
        if (serverChannel != null && serverChannel.isOpen()) {
            try {
                serverChannel.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    private void startIdleTimer() {
        if (idleTimeoutMs <= 0) {
            return;
        }
        idleTimer = controlEndpoint.scheduleTimer(idleTimeoutMs,
                new Runnable() {
                    @Override
                    public void run() {
                        if (!closed) {
                            if (LOGGER.isLoggable(Level.FINE)) {
                                LOGGER.fine(L10N.getString(
                                        "log.bind_timeout"));
                            }
                            closeServerChannel();
                            callback.bindFailed(
                                    SOCKSConstants
                                            .SOCKS5_REPLY_TTL_EXPIRED);
                        }
                    }
                });
    }

    private void cancelIdleTimer() {
        if (idleTimer != null) {
            idleTimer.cancel();
            idleTimer = null;
        }
    }

}
