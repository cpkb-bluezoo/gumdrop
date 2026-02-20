/*
 * UDPListener.java
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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Base class for UDP server endpoints that bind a
 * {@link UDPEndpoint} and receive datagrams.
 *
 * <p>Unlike {@link TCPListener} (TCP), which creates a per-connection
 * {@link TCPEndpoint} and {@link ProtocolHandler}, a datagram endpoint
 * uses a single {@link ProtocolHandler} for all received datagrams.
 * The handler is provided by subclasses via
 * {@link #createProtocolHandler()}.
 *
 * <p>On {@link #start()}, the endpoint creates a
 * {@link UDPTransportFactory}, binds a {@link UDPEndpoint} to
 * the configured port, and self-registers with a {@link SelectorLoop}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see TCPListener
 * @see Listener
 */
public abstract class UDPListener extends Listener {

    private static final Logger LOGGER =
            Logger.getLogger(UDPListener.class.getName());

    private UDPEndpoint endpoint;

    protected UDPListener() {
    }

    /**
     * Creates the endpoint handler that receives all datagrams for
     * this endpoint. Called once during {@link #start()}.
     *
     * @return the endpoint handler
     */
    protected abstract ProtocolHandler createProtocolHandler();

    /**
     * Returns the transport factory type for UDP.
     *
     * @return a new {@link UDPTransportFactory}
     */
    @Override
    protected TransportFactory createTransportFactory() {
        return new UDPTransportFactory();
    }

    /**
     * Starts this datagram endpoint. Creates the transport factory,
     * binds a {@link UDPEndpoint} to the configured port, and
     * registers with a {@link SelectorLoop} for read events.
     */
    @Override
    public void start() {
        super.start();
        UDPTransportFactory udpFactory =
                (UDPTransportFactory) getTransportFactory();
        ProtocolHandler handler = createProtocolHandler();
        try {
            endpoint = udpFactory.createServerEndpoint(
                    null, getPort(), handler);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE,
                    "Failed to bind datagram endpoint on port "
                            + getPort(), e);
        }
    }

    /**
     * Stops this datagram endpoint. Closes the underlying
     * {@link UDPEndpoint}.
     */
    @Override
    public void stop() {
        if (endpoint != null) {
            endpoint.close();
            endpoint = null;
        }
    }

    /**
     * Returns the underlying datagram endpoint, or null if not started.
     *
     * @return the datagram endpoint
     */
    protected UDPEndpoint getEndpoint() {
        return endpoint;
    }

}
