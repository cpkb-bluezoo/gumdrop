/*
 * FtpDataTransport.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.ftp;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;

import org.bluezoo.gumdrop.AcceptSelectorLoop;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.StorageExecutor;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.util.AsyncFile;

/**
 * The operating-system facing half of an FTP data transfer: opening the
 * passive-mode listener, connecting out in active mode, wrapping a data
 * socket in an endpoint, and opening the file being transferred.
 *
 * <p>{@link FtpDataConnectionCoordinator} owns the protocol logic (waiting for
 * the connection, timeouts, address checks, pacing, ASCII conversion) and
 * reaches the network and the file system only through this seam.
 * {@link SocketFtpDataTransport} is the default and the only production
 * implementation; unit tests substitute a mock that needs no socket, no
 * loop and no real file.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
interface FtpDataTransport {

    /** A bound passive-mode (PASV/EPSV) listener. */
    interface PassiveListener {
        /** The bound local port, announced to the client. */
        int port();

        /** Releases the listening socket. */
        void close();
    }

    /** A pending active-mode (PORT/EPRT) outbound connect. */
    interface ActiveConnection {
        /** Abandons the connect attempt. */
        void close();
    }

    /** Outcome of an active-mode outbound connect, on the control loop. */
    interface ActiveConnectCallback {
        /**
         * The data socket is established.
         *
         * @param channel the connected data socket
         */
        void connected(SocketChannel channel);

        /**
         * The connect attempt failed.
         *
         * @param cause why
         */
        void failed(Exception cause);
    }

    /**
     * Binds a passive-mode listener and arranges for accepted connections to
     * be handed to {@code acceptor}.
     *
     * @param server the listener owning the session (port range, may be null)
     * @param port the requested port, 0 for a system-assigned one
     * @param acceptor receives each accepted data connection
     * @return the bound listener
     * @throws IOException if it could not be opened
     */
    PassiveListener listenPassive(FtpListener server, int port,
            AcceptSelectorLoop.RawAcceptHandler acceptor) throws IOException;

    /**
     * Starts an active-mode outbound connect.
     *
     * @param gumdrop the runtime
     * @param address the client's data address
     * @param port the client's data port
     * @param loop the control connection's loop
     * @param callback told the outcome
     * @return a handle to abandon the attempt
     * @throws IOException if the connect could not be started
     */
    ActiveConnection connectActive(Gumdrop gumdrop, InetAddress address,
            int port, SelectorLoop loop, ActiveConnectCallback callback)
            throws IOException;

    /**
     * Wraps a data socket in an endpoint for {@code handler}, without yet
     * registering it for I/O.
     *
     * @param channel the data socket
     * @param handler the transfer handler
     * @param secureFactory the factory supplying TLS for PROT P, or null for
     *        a plaintext data connection
     * @return the endpoint
     * @throws IOException if it could not be created
     */
    Endpoint createDataEndpoint(SocketChannel channel, ProtocolHandler handler,
            TcpTransportFactory secureFactory) throws IOException;

    /**
     * Registers a created data endpoint for I/O on {@code loop}.
     *
     * @param loop the control connection's loop
     * @param channel the data socket
     * @param endpoint the endpoint from {@link #createDataEndpoint}
     */
    void registerDataEndpoint(SelectorLoop loop, SocketChannel channel,
            Endpoint endpoint);

    /**
     * Opens the file being transferred for asynchronous I/O.
     *
     * @param storage the storage executor (for file systems without
     *        asynchronous channels), or null to run on the calling thread
     * @param path the file
     * @param options the open options
     * @return the open channel
     * @throws IOException if it could not be opened
     */
    AsyncFile openFile(StorageExecutor storage, Path path,
            OpenOption... options) throws IOException;
}
