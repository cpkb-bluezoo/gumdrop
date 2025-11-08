/*
 * FTPServer.java
 * Copyright (C) 2006 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
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
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.ftp;

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.Server;

import java.nio.channels.SocketChannel;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;

/**
 * Connection factory for FTP control connections on a given port.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FTPServer extends Server {

    private static final Logger LOGGER = Logger.getLogger(FTPServer.class.getName());

    /**
     * The default FTP transmission control port.
     */
    protected static final int FTP_DEFAULT_PORT = 21;

    /**
     * The default FTP data port.
     */
    protected static final int FTP_DEFAULT_DATA_PORT = 20;

    protected int port = FTP_DEFAULT_PORT;
    protected FTPConnectionHandlerFactory handlerFactory;

    public String getDescription() {
        return "FTP";
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setHandlerFactory(FTPConnectionHandlerFactory factory) {
        this.handlerFactory = factory;
    }

    public FTPConnectionHandlerFactory getHandlerFactory() {
        return handlerFactory;
    }

    public void start() {
        // NOOP
    }

    public void stop() {
        // NOOP
    }

    public Connection newConnection(SocketChannel sc, SSLEngine engine) {
        // Create a new handler instance for this connection (thread safety)
        FTPConnectionHandler handler = null;
        if (handlerFactory != null) {
            try {
                handler = handlerFactory.createHandler();
            } catch (Exception e) {
                // Log error but don't fail connection creation
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.log(Level.WARNING, "Failed to create FTP handler, using default behavior", e);
                }
            }
        }
        
        return new FTPConnection(sc, engine, isSecure(), handler);
    }

}
