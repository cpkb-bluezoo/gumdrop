/*
 * UDPDNSClientTransport.java
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

package org.bluezoo.gumdrop.dns.client;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.UDPEndpoint;
import org.bluezoo.gumdrop.UDPTransportFactory;

/**
 * Plain UDP transport for DNS client queries.
 *
 * <p>Wraps {@link UDPTransportFactory} to send and receive DNS messages
 * as single UDP datagrams on port 53 (or a configured port).
 *
 * <p>This is the default transport used by {@link DNSResolver}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DNSClientTransport
 */
public class UDPDNSClientTransport implements DNSClientTransport {

    private UDPEndpoint endpoint;

    @Override
    public void open(InetAddress server, int port, SelectorLoop loop,
                     DNSClientTransportHandler handler) throws IOException {
        UDPTransportFactory factory = new UDPTransportFactory();
        factory.start();
        this.endpoint = factory.connect(server, port,
                new UDPProtocolHandler(handler), loop);
    }

    @Override
    public void send(ByteBuffer data) {
        endpoint.send(data);
    }

    @Override
    public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
        return endpoint.scheduleTimer(delayMs, callback);
    }

    @Override
    public void close() {
        if (endpoint != null) {
            endpoint.close();
        }
    }

    /**
     * Adapts the Gumdrop {@link ProtocolHandler} callbacks to
     * {@link DNSClientTransportHandler}.
     */
    private static class UDPProtocolHandler implements ProtocolHandler {

        private final DNSClientTransportHandler handler;

        UDPProtocolHandler(DNSClientTransportHandler handler) {
            this.handler = handler;
        }

        @Override
        public void connected(Endpoint ep) {
        }

        @Override
        public void receive(ByteBuffer data) {
            handler.onReceive(data);
        }

        @Override
        public void disconnected() {
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
        }

        @Override
        public void error(Exception cause) {
            handler.onError(cause);
        }
    }

}
