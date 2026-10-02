/*
 * SocketSocksTransport.java
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

package org.bluezoo.gumdrop.socks;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import org.bluezoo.gumdrop.AcceptSelectorLoop;
import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TcpEndpoint;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.UdpEndpoint;
import org.bluezoo.gumdrop.UdpTransportFactory;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.dns.client.ResolveCallback;

/**
 * The production {@link SocksTransport}: the loop's DNS resolver, real
 * client and listening sockets, and UDP endpoints on the control
 * connection's loop.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class SocketSocksTransport implements SocksTransport {

    @Override
    public void resolve(SelectorLoop loop, String host,
            ResolveCallback callback) {
        DnsResolver resolver = DnsResolver.forLoop(loop);
        resolver.resolve(host, callback);
    }

    @Override
    public void connect(Gumdrop gumdrop, SelectorLoop loop,
            InetAddress address, int port, ProtocolHandler handler)
            throws IOException {
        TcpTransportFactory factory = new TcpTransportFactory();
        factory.start();
        ClientEndpoint client = new ClientEndpoint(factory, loop, address, port);
        client.connect(gumdrop, handler);
    }

    @Override
    public BindListener listenBind(Gumdrop gumdrop,
            AcceptSelectorLoop.RawAcceptHandler acceptor) throws IOException {
        final ServerSocketChannel channel = ServerSocketChannel.open();
        channel.configureBlocking(false);
        // Bind to loopback only - BIND is a server-assisted relay, not a
        // general inbound listener, so exposing it on all interfaces is
        // unnecessary and widens the attack surface.
        channel.bind(new InetSocketAddress(
                InetAddress.getLoopbackAddress(), 0));
        final InetSocketAddress bound =
                (InetSocketAddress) channel.getLocalAddress();
        gumdrop.getAcceptLoop().registerRawAcceptor(channel, acceptor);
        return new BindListener() {
            @Override
            public InetSocketAddress address() {
                return bound;
            }

            @Override
            public void close() {
                if (channel.isOpen()) {
                    try {
                        channel.close();
                    } catch (IOException e) {
                        // ignore
                    }
                }
            }
        };
    }

    @Override
    public Endpoint adoptBindPeer(SelectorLoop loop, SocketChannel channel,
            ProtocolHandler handler) throws IOException {
        TcpTransportFactory factory = new TcpTransportFactory();
        factory.start();
        TcpEndpoint peer = factory.createServerEndpoint(channel, handler);
        loop.registerTCP(channel, peer);
        return peer;
    }

    @Override
    public UdpPort openUdp(Gumdrop gumdrop, SelectorLoop loop,
            ProtocolHandler handler) throws IOException {
        UdpTransportFactory factory = new UdpTransportFactory();
        factory.start();
        final UdpEndpoint endpoint = factory.createServerEndpoint(
                gumdrop, null, 0, handler, loop);
        return new UdpPort() {
            @Override
            public InetSocketAddress localAddress() {
                return (InetSocketAddress) endpoint.getLocalAddress();
            }

            @Override
            public InetSocketAddress remoteAddress() {
                return (InetSocketAddress) endpoint.getRemoteAddress();
            }

            @Override
            public void sendTo(ByteBuffer data, InetSocketAddress destination) {
                endpoint.sendTo(data, destination);
            }

            @Override
            public boolean isOpen() {
                return endpoint.isOpen();
            }

            @Override
            public void close() {
                endpoint.close();
            }
        };
    }
}
