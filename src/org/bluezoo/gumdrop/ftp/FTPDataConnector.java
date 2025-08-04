/*
 * FTPDataConnector.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.ftp;

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.Connector;

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
    final int port;

    FTPDataConnector(FTPConnection controlConnection, int port) {
        this.controlConnection = controlConnection;
        this.port = port;
    }

    public String getDescription() {
        return "FTP";
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        throw new UnsupportedOperationException();
    }

    public void start() {
        // NOOP
    }

    public void stop() {
        // NOOP
    }

    public Connection newConnection(SocketChannel sc, SSLEngine engine) {
        return new FTPDataConnection(sc, engine);
    }

}
