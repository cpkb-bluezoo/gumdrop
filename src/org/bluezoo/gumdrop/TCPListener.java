/*
 * TCPListener.java
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

package org.bluezoo.gumdrop;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import java.net.UnixDomainSocketAddress;

/**
 * Base class for TCP server connectors that listen on ports and accept
 * connections. Also supports UNIX domain sockets when a {@link #setPath
 * path} is configured instead of a port.
 *
 * <p>Extends {@link Listener} with TCP-specific
 * functionality: managing {@link ServerSocketChannel}s and creating
 * per-connection {@link TCPEndpoint} instances via
 * {@link #newEndpoint(SocketChannel, SelectorLoop)}.
 *
 * <p>A listener may be configured for either TCP (port-based) or UNIX
 * domain socket (path-based) binding, but not both. If both {@code path}
 * and {@code port} are specified, {@link #start()} throws
 * {@link IllegalStateException}.
 *
 * <p>For UDP-based servers, see {@link UDPListener}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Listener
 * @see UDPListener
 * @see ProtocolHandler
 */
public abstract class TCPListener extends Listener {

    private static final Logger LOGGER =
            Logger.getLogger(TCPListener.class.getName());

    // ── UNIX domain socket path ──

    private String path;

    // ── TCP server channel management ──

    private List<ServerSocketChannel> serverChannels =
            new ArrayList<ServerSocketChannel>();

    protected TCPListener() {
    }

    // ═══════════════════════════════════════════════════════════════════
    // UNIX domain socket path
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Returns the UNIX domain socket path, or {@code null} for TCP.
     *
     * @return the socket path, or null
     */
    @Override
    public String getPath() {
        return path;
    }

    /**
     * Sets the UNIX domain socket path. When set, this listener binds
     * to a filesystem path instead of a TCP port. Mutually exclusive
     * with {@code port}.
     *
     * @param path the socket path
     */
    public void setPath(String path) {
        this.path = path;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Abstract methods for protocol subclasses
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Creates a ProtocolHandler for a newly accepted TCP connection.
     * Protocol subclasses return their specific handler implementation.
     *
     * @return a new protocol handler
     */
    protected abstract ProtocolHandler createHandler();

    /**
     * Returns whether this listener should be registered with the
     * {@link AcceptSelectorLoop} for TCP accept events. Subclasses
     * that manage their own I/O (e.g. QUIC/UDP listeners) should
     * override this to return {@code false}.
     *
     * @return true if this listener needs TCP accept registration
     */
    public boolean requiresTcpAccept() {
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════
    // TCP accept path
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Creates a TCPEndpoint for a newly accepted socket channel and
     * wires it to a ProtocolHandler from {@link #createHandler()}.
     *
     * <p>Called by {@link AcceptSelectorLoop} after accepting a TCP
     * connection. Only applicable when the transport factory is a
     * {@link TCPTransportFactory}. QUIC subclasses set up their own
     * accept path in {@link #start()} and do not use this.
     *
     * @param sc the accepted socket channel
     * @param workerLoop the worker selector loop
     * @return the TCPEndpoint
     * @throws IOException if an I/O error occurs
     */
    public TCPEndpoint newEndpoint(SocketChannel sc, SelectorLoop workerLoop)
            throws IOException {
        ProtocolHandler handler = createHandler();
        TCPTransportFactory tcpFactory =
                (TCPTransportFactory) getTransportFactory();
        TCPEndpoint endpoint = tcpFactory.createServerEndpoint(sc, handler);
        return endpoint;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Validates configuration and starts this listener.
     *
     * @throws IllegalStateException if both {@code path} and
     *         {@code port} are configured
     */
    @Override
    public void start() {
        if (path != null && getPort() > 0) {
            throw new IllegalStateException(
                    "Listener cannot have both path and port configured");
        }
        super.start();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Server channel management
    // ═══════════════════════════════════════════════════════════════════

    void addServerChannel(ServerSocketChannel ssc) {
        serverChannels.add(ssc);
    }

    /**
     * Closes all server channels. For UNIX domain socket channels,
     * also deletes the socket file.
     */
    public void closeServerChannels() {
        for (Iterator<ServerSocketChannel> it = serverChannels.iterator();
             it.hasNext(); ) {
            ServerSocketChannel ssc = it.next();
            try {
                SocketAddress localAddr = ssc.getLocalAddress();
                ssc.close();
                if (localAddr instanceof UnixDomainSocketAddress) {
                    Path socketPath =
                            ((UnixDomainSocketAddress) localAddr).getPath();
                    Files.deleteIfExists(socketPath);
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error closing server channel", e);
            }
        }
        serverChannels.clear();
    }

}
