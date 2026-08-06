/*
 * FTPClientDataConnectionCoordinator.java
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

package org.bluezoo.gumdrop.ftp.client;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TCPTransportFactory;

/**
 * Opens the FTP client's data connection (RFC 959 §3.2), the client-side
 * counterpart of the server's {@code FTPDataConnectionCoordinator}.
 *
 * <p>Unlike the server, which juggles both active and passive modes and
 * multiple concurrent listeners, the client side of this exchange is a
 * single outbound TCP connection to the address the server returned from
 * PASV/EPSV — so this class is a thin wrapper around {@link ClientEndpoint}
 * that shares the control connection's {@link
 * org.bluezoo.gumdrop.SelectorLoop}, rather than a full state machine.
 * {@link FTPClientProtocolHandler} owns the actual transfer coordination
 * (correlating the data connection's EOF with the control connection's
 * final reply code) — see its {@code *DataHandler} inner classes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc959">RFC 959</a> §3.2
 */
final class FTPClientDataConnectionCoordinator {

    private final Endpoint controlEndpoint;
    private TCPTransportFactory transportFactory;

    FTPClientDataConnectionCoordinator(Endpoint controlEndpoint) {
        this.controlEndpoint = controlEndpoint;
    }

    /**
     * Opens an outbound data connection to the given address (as returned
     * by PASV/EPSV), sharing the control connection's SelectorLoop, and
     * wires it to {@code dataHandler}.
     *
     * @param dataAddress the server's data connection address
     * @param dataHandler the handler for the data connection's lifecycle
     */
    void connect(InetSocketAddress dataAddress, ProtocolHandler dataHandler) {
        if (transportFactory == null) {
            transportFactory = new TCPTransportFactory();
            transportFactory.start();
        }
        ClientEndpoint dataEndpoint = new ClientEndpoint(transportFactory,
                controlEndpoint.getSelectorLoop(),
                dataAddress.getAddress(), dataAddress.getPort());
        try {
            dataEndpoint.connect(dataHandler);
        } catch (IOException e) {
            dataHandler.error(e);
        }
    }
}
