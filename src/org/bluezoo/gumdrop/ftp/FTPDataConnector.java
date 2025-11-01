/*
 * FTPDataConnector.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.bluezoo.gumdrop.ftp;

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.Connector;

import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;

/**
 * Connection factory for FTP data connections on a given port.
 * This is created by the FTPConnection on demand, it cannot be instantiated
 * by the server configuration.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class FTPDataConnector extends Connector {

    final FTPConnection controlConnection;
    final int requestedPort;
    final FTPDataConnectionCoordinator coordinator;
    private int actualPort = -1;

    FTPDataConnector(FTPConnection controlConnection, int port, FTPDataConnectionCoordinator coordinator) {
        this.controlConnection = controlConnection;
        this.requestedPort = port;
        this.coordinator = coordinator;
    }

    public String getDescription() {
        return "FTP";
    }

    public int getPort() {
        return requestedPort;
    }

    public void setPort(int port) {
        throw new UnsupportedOperationException();
    }

    /**
     * Gets the actual port being used (may differ from requested if 0 was requested).
     */
    public int getActualPort() {
        return actualPort;
    }

    /**
     * Sets the actual port after binding (called by Server).
     */
    public void setActualPort(int actualPort) {
        this.actualPort = actualPort;
    }

    public void start() {
        // NOOP
    }

    public void stop() {
        // NOOP - cleanup handled by coordinator
    }

    /**
     * Called by Server after binding to capture the actual port.
     * This is a custom method for FTPDataConnector since we can't override
     * the package-private addServerChannel method from another package.
     */
    public void notifyBound(ServerSocketChannel channel) {
        // Capture the actual bound port
        try {
            InetSocketAddress localAddress = (InetSocketAddress) channel.getLocalAddress();
            actualPort = localAddress.getPort();
        } catch (Exception e) {
            // Fallback - use requested port if we can't get actual port  
            actualPort = requestedPort;
        }
    }

    public Connection newConnection(SocketChannel sc, SSLEngine engine) {
        // Create data connection and coordinate with parent control connection
        FTPDataConnection dataConnection = new FTPDataConnection(sc, engine, isSecure(), coordinator);
        
        // Notify coordinator of new data connection
        coordinator.acceptDataConnection(dataConnection);
        
        return dataConnection;
    }

}
