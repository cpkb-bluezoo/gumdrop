/*
 * DnsListener.java
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
import java.net.InetAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.UdpListener;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.tls.TlsConfig;

/**
 * UDP transport listener for DNS queries.
 * RFC 1035 section 4.2.1: DNS queries over UDP use port 53. Messages
 * carried by UDP are restricted to 512 octets (not counting the IP or
 * UDP headers). Longer messages must be truncated (TC bit set) and the
 * client should retry over TCP.
 *
 * <p>This endpoint binds a UDP socket on the configured port and
 * dispatches incoming DNS datagrams to its owning
 * {@link org.bluezoo.gumdrop.dns.server.DnsServer} for processing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.dns.server.DnsServer
 * @see UdpListener
 */
public class DnsListener extends UdpListener {

    private static final Logger LOGGER =
            Logger.getLogger(DnsListener.class.getName());

    private static final int DEFAULT_PORT = 53;

    private int port = DEFAULT_PORT;
    private org.bluezoo.gumdrop.dns.server.DnsServer server;

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
     * Sets the owning DNS server. Called by {@link org.bluezoo.gumdrop.dns.server.DnsServer}
     * during wiring.
     *
     * @param server the owning server
     */
    public void setServer(org.bluezoo.gumdrop.dns.server.DnsServer server) {
        this.server = server;
    }

    /**
     * Returns the owning server, or null if used standalone.
     *
     * @return the owning server
     */
    public org.bluezoo.gumdrop.dns.server.DnsServer getServer() {
        return server;
    }

    /**
     * Sends a DNS response datagram to the given destination.
     *
     * @param data        the serialised response
     * @param destination the target address
     */
    public void sendTo(ByteBuffer data, InetSocketAddress destination) {
        getEndpoint().sendTo(data, destination);
    }

    /**
     * Returns the SelectorLoop this listener's endpoint is registered
     * with, or null if the listener has not been started (e.g. a test
     * double that never calls {@link #start()}).
     *
     * @return the SelectorLoop, or null if unbound
     */
    public SelectorLoop getSelectorLoop() {
        Endpoint ep = getEndpoint();
        return (ep != null) ? ep.getSelectorLoop() : null;
    }

    @Override
    protected ProtocolHandler createProtocolHandler() {
        return new DnsDatagramHandler();
    }

    /**
     * Inner handler that dispatches received datagrams to the
     * owning {@link org.bluezoo.gumdrop.dns.server.DnsServer}.
     */
    private class DnsDatagramHandler implements ProtocolHandler {

        @Override
        public void connected(Endpoint ep) {
            // endpoint is already bound via UdpListener
        }

        @Override
        public void receive(ByteBuffer data) {
            if (server == null) {
                LOGGER.warning(DnsMessage.L10N.getString("warn.dns_no_service_set"));
                return;
            }
            InetSocketAddress source =
                    (InetSocketAddress) getEndpoint().getRemoteAddress();
            if (!acceptConnection(source)) {
                logRejection(source);
                return;
            }
            connectionOpened(source);
            server.handleDatagram(DnsListener.this, data, source,
                    new Runnable() {
                        @Override
                        public void run() {
                            connectionClosed(source);
                        }
                    });
        }

        private void logRejection(SocketAddress remoteAddress) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Connection rejected from " + remoteAddress);
            }
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

    /**
     * Sets the UDP port. Returns {@code this} for fluent configuration.
     */
    public DnsListener port(int port) {
        setPort(port);
        return this;
    }

    @Override
    public DnsListener bindWildcard() {
        super.bindWildcard();
        return this;
    }

    @Override
    public DnsListener addresses(InetAddress... addrs) {
        super.addresses(addrs);
        return this;
    }

    @Override
    public DnsListener secure(boolean flag) {
        super.secure(flag);
        return this;
    }

    @Override
    public DnsListener tls(TlsConfig tls) {
        super.tls(tls);
        return this;
    }


    /**
     * @deprecated use {@code new DnsListener().port(...)} fluent configuration.
     */
    @Deprecated
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @deprecated use fluent methods on {@link DnsListener} instead.
     */
    @Deprecated
    public static final class Builder {
        private int port = DEFAULT_PORT;

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public DnsListener build() {
            DnsListener listener = new DnsListener();
            listener.setPort(port);
            return listener;
        }
    }

}
