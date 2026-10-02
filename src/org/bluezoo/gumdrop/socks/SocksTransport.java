/*
 * SocksTransport.java
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
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import org.bluezoo.gumdrop.AcceptSelectorLoop;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.client.ResolveCallback;

/**
 * The operating-system facing half of the SOCKS server: resolving names,
 * connecting upstream, listening for a BIND peer and exchanging UDP
 * datagrams.
 *
 * <p>{@link SocksProtocolHandler}, {@link SocksBindRelay} and {@link
 * SocksUdpRelay} own the protocol logic (request parsing, destination
 * policy, relaying, timeouts) and reach the network only through this seam.
 * {@link SocketSocksTransport} is the default and the only production
 * implementation; unit tests substitute a mock that needs no socket and no
 * loop.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
interface SocksTransport {

    /** A bound BIND listener. */
    interface BindListener {
        /** The bound address, announced in the first BIND reply. */
        InetSocketAddress address();

        /** Releases the listening socket; safe to call repeatedly. */
        void close();
    }

    /** One UDP port of a UDP ASSOCIATE relay. */
    interface UdpPort {
        /** The bound local address. */
        InetSocketAddress localAddress();

        /** The source of the datagram being delivered to the handler. */
        InetSocketAddress remoteAddress();

        /**
         * Sends a datagram.
         *
         * @param data the payload
         * @param destination where to send it
         */
        void sendTo(ByteBuffer data, InetSocketAddress destination);

        /** Whether the port is still open. */
        boolean isOpen();

        /** Closes the port. */
        void close();
    }

    /**
     * Resolves a host name.
     *
     * @param loop the loop the callback runs on
     * @param host the name to resolve
     * @param callback told the addresses or the failure
     */
    void resolve(SelectorLoop loop, String host, ResolveCallback callback);

    /**
     * Connects to an upstream TCP destination.
     *
     * @param gumdrop the runtime
     * @param loop the control connection's loop
     * @param address the destination address
     * @param port the destination port
     * @param handler told of the connection's lifecycle
     * @throws IOException if the connect could not be started
     */
    void connect(Gumdrop gumdrop, SelectorLoop loop, InetAddress address,
            int port, ProtocolHandler handler) throws IOException;

    /**
     * Binds a BIND listener on the loopback address.
     *
     * @param gumdrop the runtime
     * @param acceptor receives the accepted peer connection
     * @return the bound listener
     * @throws IOException if it could not be bound
     */
    BindListener listenBind(Gumdrop gumdrop,
            AcceptSelectorLoop.RawAcceptHandler acceptor) throws IOException;

    /**
     * Wraps the accepted BIND peer socket in an endpoint and registers it
     * for I/O.
     *
     * @param loop the control connection's loop
     * @param channel the accepted socket
     * @param handler told of the peer connection's lifecycle
     * @return the peer endpoint
     * @throws IOException if it could not be wrapped
     */
    Endpoint adoptBindPeer(SelectorLoop loop, SocketChannel channel,
            ProtocolHandler handler) throws IOException;

    /**
     * Opens a UDP port bound to an ephemeral local port.
     *
     * @param gumdrop the runtime
     * @param loop the control connection's loop
     * @param handler receives each datagram
     * @return the port
     * @throws IOException if it could not be bound
     */
    UdpPort openUdp(Gumdrop gumdrop, SelectorLoop loop,
            ProtocolHandler handler) throws IOException;
}
