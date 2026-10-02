/*
 * MockSocksTransport.java
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
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.AcceptSelectorLoop;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.client.ResolveCallback;

/**
 * Hand-written mock of the SOCKS server's network seam: resolving names,
 * connecting upstream, listening for a BIND peer and UDP ports are all
 * recorded in memory and completed by the test, so no socket, resolver or
 * loop is involved.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class MockSocksTransport implements SocksTransport {

    /** A recorded name resolution. */
    static final class Resolve {
        final String host;
        final ResolveCallback callback;

        Resolve(String host, ResolveCallback callback) {
            this.host = host;
            this.callback = callback;
        }
    }

    /** A recorded upstream connect. */
    static final class Connect {
        final InetAddress address;
        final int port;
        final ProtocolHandler handler;

        Connect(InetAddress address, int port, ProtocolHandler handler) {
            this.address = address;
            this.port = port;
            this.handler = handler;
        }
    }

    /** A recorded BIND listener. */
    static final class MockBindListener implements BindListener {
        final InetSocketAddress address;
        final AcceptSelectorLoop.RawAcceptHandler acceptor;
        boolean closed;

        MockBindListener(InetSocketAddress address,
                AcceptSelectorLoop.RawAcceptHandler acceptor) {
            this.address = address;
            this.acceptor = acceptor;
        }

        @Override
        public InetSocketAddress address() {
            return address;
        }

        @Override
        public void close() {
            closed = true;
        }

        /** Delivers the peer connection as the accept loop would. */
        void accept(SocketChannel channel) throws IOException {
            acceptor.accepted(channel);
        }
    }

    /** One datagram handed to {@link MockUdpPort#sendTo}. */
    static final class Datagram {
        final byte[] bytes;
        final InetSocketAddress destination;

        Datagram(byte[] bytes, InetSocketAddress destination) {
            this.bytes = bytes;
            this.destination = destination;
        }
    }

    /** An in-memory UDP port the test feeds and drains. */
    static final class MockUdpPort implements UdpPort {
        final InetSocketAddress local;
        final ProtocolHandler handler;
        final List<Datagram> sent = new ArrayList<Datagram>();
        InetSocketAddress remote;
        boolean open = true;

        MockUdpPort(InetSocketAddress local, ProtocolHandler handler) {
            this.local = local;
            this.handler = handler;
        }

        /** Delivers a datagram from {@code source} to the handler. */
        void deliver(byte[] data, InetSocketAddress source) {
            remote = source;
            handler.receive(ByteBuffer.wrap(data));
        }

        @Override
        public InetSocketAddress localAddress() {
            return local;
        }

        @Override
        public InetSocketAddress remoteAddress() {
            return remote;
        }

        @Override
        public void sendTo(ByteBuffer data, InetSocketAddress destination) {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            sent.add(new Datagram(b, destination));
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }

    final List<Resolve> resolves = new ArrayList<Resolve>();
    final List<Connect> connects = new ArrayList<Connect>();
    final List<MockBindListener> listeners = new ArrayList<MockBindListener>();
    final List<StubEndpoint> peers = new ArrayList<StubEndpoint>();
    final List<ProtocolHandler> peerHandlers = new ArrayList<ProtocolHandler>();
    final List<MockUdpPort> udpPorts = new ArrayList<MockUdpPort>();
    private IOException connectFailure;
    private IOException listenFailure;
    private IOException adoptFailure;
    private IOException udpFailure;

    void failConnect(IOException e) {
        connectFailure = e;
    }

    void failListen(IOException e) {
        listenFailure = e;
    }

    void failAdopt(IOException e) {
        adoptFailure = e;
    }

    void failUdp(IOException e) {
        udpFailure = e;
    }

    Resolve lastResolve() {
        return resolves.get(resolves.size() - 1);
    }

    Connect lastConnect() {
        return connects.get(connects.size() - 1);
    }

    MockBindListener lastListener() {
        return listeners.get(listeners.size() - 1);
    }

    MockUdpPort clientPort() {
        return udpPorts.get(0);
    }

    MockUdpPort upstreamPort() {
        return udpPorts.get(1);
    }

    @Override
    public void resolve(SelectorLoop loop, String host, ResolveCallback callback) {
        resolves.add(new Resolve(host, callback));
    }

    @Override
    public void connect(Gumdrop gumdrop, SelectorLoop loop, InetAddress address,
            int port, ProtocolHandler handler) throws IOException {
        if (connectFailure != null) {
            throw connectFailure;
        }
        connects.add(new Connect(address, port, handler));
    }

    @Override
    public BindListener listenBind(Gumdrop gumdrop,
            AcceptSelectorLoop.RawAcceptHandler acceptor) throws IOException {
        if (listenFailure != null) {
            throw listenFailure;
        }
        MockBindListener l = new MockBindListener(
                new InetSocketAddress("127.0.0.1", 45000 + listeners.size()),
                acceptor);
        listeners.add(l);
        return l;
    }

    @Override
    public Endpoint adoptBindPeer(SelectorLoop loop, SocketChannel channel,
            ProtocolHandler handler) throws IOException {
        if (adoptFailure != null) {
            throw adoptFailure;
        }
        StubEndpoint peer = new StubEndpoint();
        peers.add(peer);
        peerHandlers.add(handler);
        return peer;
    }

    @Override
    public UdpPort openUdp(Gumdrop gumdrop, SelectorLoop loop,
            ProtocolHandler handler) throws IOException {
        if (udpFailure != null) {
            throw udpFailure;
        }
        MockUdpPort p = new MockUdpPort(
                new InetSocketAddress("127.0.0.1", 46000 + udpPorts.size()),
                handler);
        udpPorts.add(p);
        return p;
    }
}
