/*
 * SMTPClient.java
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

package org.bluezoo.gumdrop.smtp.client;

import org.bluezoo.gumdrop.Client;
import org.bluezoo.gumdrop.ClientHandler;
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
     * Creates a new SMTP client connection with a handler.
     * This is the preferred method for creating client connections.
     *
     * @param channel the socket channel for the connection
     * @param engine optional SSL engine for secure connections
     * @param handler the client handler to receive SMTP events
     * @return a new SMTPClientConnection instance
     */
    @Override
    protected Connection newConnection(SocketChannel channel, javax.net.ssl.SSLEngine engine, ClientHandler handler) {
        return new SMTPClientConnection(this, channel, engine, secure, (SMTPClientHandler) handler);
    }

    @Override
    public String getDescription() {
        return "SMTP Client (" + host.getHostAddress() + ":" + port + ")";
    }
}
