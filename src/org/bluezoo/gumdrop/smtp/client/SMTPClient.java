/*
 * SMTPClient.java
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

package org.bluezoo.gumdrop.smtp.client;

import org.bluezoo.gumdrop.Client;
import org.bluezoo.gumdrop.Connection;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.channels.SocketChannel;

/**
 * SMTP client implementation that creates and manages SMTP client connections.
 * This class extends {@link Client} to provide SMTP-specific client functionality
 * for connecting to SMTP servers and creating connection instances.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SMTPClient extends Client {

    /**
     * Creates an SMTP client that will connect to the specified host and port.
     *
     * @param host the SMTP server host as a String
     * @param port the SMTP server port number (typically 25, 465, or 587)
     * @throws UnknownHostException if the host cannot be resolved
     */
    public SMTPClient(String host, int port) throws UnknownHostException {
        super(host, port);
    }

    /**
     * Creates an SMTP client that will connect to the specified host and port.
     *
     * @param host the SMTP server host as an InetAddress
     * @param port the SMTP server port number (typically 25, 465, or 587)
     */
    public SMTPClient(InetAddress host, int port) {
        super(host, port);
    }

    /**
     * Creates a new SMTP client connection.
     *
     * @param channel the socket channel for the connection
     * @param engine optional SSL engine for secure connections
     * @return a new SMTPClientConnection instance
     */
    @Override
    public Connection newConnection(SocketChannel channel, javax.net.ssl.SSLEngine engine) {
        return new SMTPClientConnection();
    }

    @Override
    public String getDescription() {
        return "SMTP Client (" + host.getHostAddress() + ":" + port + ")";
    }
}
