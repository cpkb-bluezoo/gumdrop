/*
 * FTPConnector.java
 * Copyright (C) 2006 Chris Burdess
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

import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;

/**
 * Connection factory for FTP control connections on a given port.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FTPConnector extends Connector {

    /**
     * The default FTP transmission control port.
     */
    protected static final int FTP_DEFAULT_PORT = 21;

    /**
     * The default FTP data port.
     */
    protected static final int FTP_DEFAULT_DATA_PORT = 20;

    protected int port = FTP_DEFAULT_PORT;

    public String getDescription() {
        return "FTP";
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void start() {
        // NOOP
    }

    public void stop() {
        // NOOP
    }

    public Connection newConnection(SocketChannel sc, SSLEngine engine) {
        // System.err.println("New connection from "+sc.socket().getInetAddress().getHostName());
        return new FTPConnection(sc, engine, isSecure());
    }

}
