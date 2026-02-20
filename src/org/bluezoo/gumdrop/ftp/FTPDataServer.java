/*
 * FTPDataServer.java
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

import org.bluezoo.gumdrop.AcceptSelectorLoop;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

/**
 * Accepts FTP data connections on a given port for passive mode.
 * This is created by the FTP control connection on demand for PASV/EPSV.
 * It cannot be instantiated by the server configuration.
 *
 * <p>Unlike protocol servers, FTP data connections use blocking I/O
 * for file transfers, so this class uses a {@link AcceptSelectorLoop.RawAcceptHandler}
 * instead of the endpoint infrastructure.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class FTPDataServer implements AcceptSelectorLoop.RawAcceptHandler {

    final FTPControlConnection controlConnection;
    final int requestedPort;
    final FTPDataConnectionCoordinator coordinator;
    private int actualPort = -1;
    private ServerSocketChannel serverChannel;

    FTPDataServer(FTPControlConnection controlConnection, int port, FTPDataConnectionCoordinator coordinator) {
        this.controlConnection = controlConnection;
        this.requestedPort = port;
        this.coordinator = coordinator;
    }

    /**
     * Gets the actual port being used (may differ from requested if 0 was requested).
     */
    public int getActualPort() {
        return actualPort;
    }

    /**
     * Called after binding to capture the actual port.
     */
    public void notifyBound(ServerSocketChannel channel) {
        this.serverChannel = channel;
        try {
            InetSocketAddress localAddress = (InetSocketAddress) channel.getLocalAddress();
            actualPort = localAddress.getPort();
        } catch (Exception e) {
            actualPort = requestedPort;
        }
    }

    /**
     * Called by AcceptSelectorLoop when a client connects to the passive data port.
     */
    @Override
    public void accepted(SocketChannel sc) throws IOException {
        FTPDataConnection dataConnection = new FTPDataConnection(sc, coordinator);
        coordinator.acceptDataConnection(dataConnection);
    }

    /**
     * Stops the data server and closes its channel.
     */
    public void stop() {
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException e) {
                // Ignore close errors
            }
            serverChannel = null;
        }
    }
}
