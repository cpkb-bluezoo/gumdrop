/*
 * DNSListener.java
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

package org.bluezoo.gumdrop.dns;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.UDPListener;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;

/**
 * UDP transport listener for DNS queries.
 *
 * <p>This endpoint binds a UDP socket on the configured port and
 * dispatches incoming DNS datagrams to its owning
 * {@link DNSService} for processing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DNSService
 * @see UDPListener
 */
public class DNSListener extends UDPListener {

    private static final Logger LOGGER =
            Logger.getLogger(DNSListener.class.getName());

    private static final int DEFAULT_PORT = 53;

    private int port = DEFAULT_PORT;
    private DNSService service;

    @Override
    public int getPort() {
        return port;
    }

    /**
     * Sets the port number this endpoint should bind to.
     *
     * @param port the port number (default 53)
     */
    public void setPort(int port) {
        this.port = port;
    }

    @Override
    public String getDescription() {
        return "dns";
    }

    /**
     * Sets the owning DNS service. Called by {@link DNSService}
     * during wiring.
     *
     * @param service the owning service
     */
    void setService(DNSService service) {
        this.service = service;
    }

    /**
     * Returns the owning service, or null if used standalone.
     *
     * @return the owning service
     */
    public DNSService getService() {
        return service;
    }

    /**
     * Sends a DNS response datagram to the given destination.
     *
     * @param data        the serialised response
     * @param destination the target address
     */
    void sendTo(ByteBuffer data, InetSocketAddress destination) {
        getEndpoint().sendTo(data, destination);
    }

    @Override
    protected ProtocolHandler createProtocolHandler() {
        return new DNSDatagramHandler();
    }

    /**
     * Inner handler that dispatches received datagrams to the
     * owning {@link DNSService}.
     */
    private class DNSDatagramHandler implements ProtocolHandler {

        @Override
        public void connected(Endpoint ep) {
            // endpoint is already bound via UDPListener
        }

        @Override
        public void receive(ByteBuffer data) {
            if (service == null) {
                LOGGER.warning("DNS datagram received but no service set");
                return;
            }
            InetSocketAddress source =
                    (InetSocketAddress) getEndpoint().getRemoteAddress();
            service.handleDatagram(DNSListener.this, data, source);
        }

        @Override
        public void disconnected() {
            // Server endpoint closed
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
            // no-op for plain UDP
        }

        @Override
        public void error(Exception cause) {
            LOGGER.log(Level.WARNING, "DNS endpoint error", cause);
        }
    }

}
