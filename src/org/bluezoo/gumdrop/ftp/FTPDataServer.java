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

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.Server;

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
class FTPDataServer extends Server {

    final FTPConnection controlConnection;
    final int requestedPort;
    final FTPDataConnectionCoordinator coordinator;
    private int actualPort = -1;

    FTPDataServer(FTPConnection controlConnection, int port, FTPDataConnectionCoordinator coordinator) {
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
     * This is a custom method for FTPDataServer since we can't override
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
        // Check if data protection (PROT P) is enabled
        SSLEngine dataEngine = engine;
        boolean secure = isSecure();
        
        if (coordinator.isDataProtectionEnabled() && !secure) {
            // PROT P is active but we're not in implicit TLS mode
            // Create SSL engine for data connection
            FTPServer ftpServer = controlConnection.getServer();
            if (ftpServer != null) {
                dataEngine = ftpServer.createDataSSLEngine();
                secure = (dataEngine != null);
            }
        }
        
        // Create data connection and coordinate with parent control connection
        FTPDataConnection dataConnection = new FTPDataConnection(sc, dataEngine, secure, coordinator);
        
        // Notify coordinator of new data connection
        coordinator.acceptDataConnection(dataConnection);
        
        return dataConnection;
    }

}
