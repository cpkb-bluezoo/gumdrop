/*
 * QuicLoopback.java
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

package org.bluezoo.gumdrop.quic;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.StreamAcceptHandler;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.testsupport.TestCertificates;

/**
 * A client {@link QuicEngine} and a server {@link QuicEngine} joined by
 * in-memory datagram queues, so that complete QUIC handshakes and data
 * exchanges run deterministically on the calling thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class QuicLoopback {

    /** Decides whether a datagram is delivered. */
    public interface Filter {
        /**
         * @param toServer true when the datagram travels client to server
         * @param index the sequence number of the datagram in its direction
         * @param datagram the datagram bytes
         * @return true to deliver, false to drop
         */
        boolean deliver(boolean toServer, int index, byte[] datagram);
    }

    public static final InetSocketAddress CLIENT_ADDRESS = new InetSocketAddress("127.0.0.1", 50000);
    public static final InetSocketAddress SERVER_ADDRESS = new InetSocketAddress("127.0.0.1", 4433);
    /** Local address of the path a client opens when it migrates (RFC 9000 section 9). */
    public static final InetSocketAddress CLIENT_ADDRESS_2 = new InetSocketAddress("127.0.0.1", 50002);
    /** The server's preferred_address, served by a second server path (see {@link #enableServerPreferredAddress}). */
    public static final InetSocketAddress SERVER_PREFERRED_ADDRESS = new InetSocketAddress("127.0.0.1", 4434);

    public final QuicTransportFactory serverFactory = new QuicTransportFactory();
    public final QuicTransportFactory clientFactory = new QuicTransportFactory();
    public QuicEngine serverEngine;
    public QuicEngine clientEngine;
    public Filter filter;
    /** Source address the server sees for client datagrams. */
    public InetSocketAddress clientSource = CLIENT_ADDRESS;
    public int sentToServer;
    public int sentToClient;
    public final List<byte[]> toServerLog = new ArrayList<byte[]>();
    public final List<byte[]> toClientLog = new ArrayList<byte[]>();

    private final List<Delivery> toServer = new ArrayList<Delivery>();
    private final List<Delivery> toClient = new ArrayList<Delivery>();
    private final InlineSelectorLoop loop = new InlineSelectorLoop();
    // Every fake path on each side, by its local address: a datagram sent
    // to an address arrives on the path bound to it, like a real socket.
    private final Map<InetSocketAddress, QuicDatagramPath> serverPaths = new HashMap<InetSocketAddress, QuicDatagramPath>();
    private final Map<InetSocketAddress, QuicDatagramPath> clientPaths = new HashMap<InetSocketAddress, QuicDatagramPath>();

    /** One datagram in flight: its bytes, where it came from, and the address it was sent to. */
    private static final class Delivery {
        final byte[] bytes;
        final InetSocketAddress source;
        final InetSocketAddress destination;

        Delivery(byte[] bytes, InetSocketAddress source, InetSocketAddress destination) {
            this.bytes = bytes;
            this.source = source;
            this.destination = destination;
        }
    }

    /**
     * Creates and starts the factories with a self-signed server certificate.
     *
     * @throws Exception on failure
     */
    public QuicLoopback() throws Exception {
        serverFactory.setServerCredentials(TestCertificates.ec256().credentials());
        serverFactory.setApplicationProtocols("test");
        serverFactory.setVerifyPeer(false);
        clientFactory.setApplicationProtocols("test");
        clientFactory.setVerifyPeer(false);
        clientFactory.setVerifyHostname(false);
    }

    /** Starts both factories (call after configuring them). */
    public void startFactories() {
        serverFactory.start();
        clientFactory.start();
    }

    private QuicDatagramPath path(final boolean fromClient) {
        return path(fromClient, fromClient ? CLIENT_ADDRESS : SERVER_ADDRESS);
    }

    private QuicDatagramPath path(final boolean fromClient, final InetSocketAddress local) {
        QuicDatagramPath path = new QuicDatagramPath() {
            @Override
            public int send(SocketAddress address, ByteBuffer packet) {
                byte[] bytes = new byte[packet.remaining()];
                packet.get(bytes);
                InetSocketAddress destination = (InetSocketAddress) address;
                if (fromClient) {
                    // The primary client path is the one NAT rebinding
                    // tests move about with clientSource.
                    InetSocketAddress source = local.equals(CLIENT_ADDRESS) ? clientSource : local;
                    toServerLog.add(bytes);
                    int n = sentToServer++;
                    if (filter == null || filter.deliver(true, n, bytes)) {
                        toServer.add(new Delivery(bytes, source, destination));
                    }
                } else {
                    toClientLog.add(bytes);
                    int n = sentToClient++;
                    if (filter == null || filter.deliver(false, n, bytes)) {
                        toClient.add(new Delivery(bytes, local, destination));
                    }
                }
                return bytes.length;
            }

            @Override
            public SocketAddress getLocalAddress() {
                return local;
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public void close() {
            }
        };
        (fromClient ? clientPaths : serverPaths).put(local, path);
        return path;
    }

    /**
     * Gives the server engine a second path, bound to
     * {@link #SERVER_PREFERRED_ADDRESS}, as a real server binds a socket
     * for its preferred_address. Call after {@code startServer}, and
     * configure {@code serverFactory.setPreferredAddress} before
     * {@code startFactories} for the parameter to be advertised.
     */
    public QuicDatagramPath enableServerPreferredAddress() {
        QuicDatagramPath path = path(false, SERVER_PREFERRED_ADDRESS);
        serverEngine.addPath(path);
        return path;
    }

    // A datagram reaches the path bound to its destination; one addressed
    // to a NAT-rebound address (see clientSource) still lands on the
    // primary path, as it would on the socket behind the NAT.
    private static QuicDatagramPath pathFor(Map<InetSocketAddress, QuicDatagramPath> paths,
            InetSocketAddress destination, InetSocketAddress primary) {
        QuicDatagramPath path = paths.get(destination);
        if (path == null) {
            path = paths.get(primary);
        }
        if (path == null) {
            throw new IllegalStateException("no path bound to " + destination);
        }
        return path;
    }

    private QuicEngine newClientEngine() {
        QuicEngine engine = new QuicEngine(clientFactory, false);
        engine.init(path(true));
        engine.setSelectorLoop(loop);
        // A migrating client opens a fresh local path, as it would a new socket.
        engine.setAdditionalPathOpener(new QuicEngine.AdditionalPathOpener() {
            @Override
            public QuicDatagramPath open(InetAddress remote) {
                return path(true, CLIENT_ADDRESS_2);
            }
        });
        return engine;
    }

    /**
     * Creates the server engine.
     *
     * @param accept the server's stream accept handler
     */
    public void startServer(StreamAcceptHandler accept) {
        serverEngine = serverFactory.createServerEngine(path(false), accept, loop);
    }

    /**
     * Creates the server engine notified per connection.
     *
     * @param handler the connection handler
     */
    public void startServer(QuicEngine.ConnectionAcceptedHandler handler) {
        serverEngine = serverFactory.createServerEngine(path(false), handler, loop);
    }

    /**
     * Starts a client connection (does not pump).
     *
     * @param handler first-stream handler, may be null
     * @param conn connection-level handler, may be null
     */
    public void startClient(ProtocolHandler handler, QuicEngine.ConnectionAcceptedHandler conn) {
        clientEngine = newClientEngine();
        clientEngine.connectTo(SERVER_ADDRESS, handler, conn, "localhost");
    }

    /**
     * Starts a client connection with an early-data handler (does not pump).
     *
     * @param conn connection-level handler, may be null
     * @param early early data handler
     */
    public void startClientEarly(QuicEngine.ConnectionAcceptedHandler conn, QuicEngine.EarlyDataHandler early) {
        clientEngine = newClientEngine();
        clientEngine.connectTo(SERVER_ADDRESS, null, conn, early, "localhost");
    }

    /**
     * Delivers queued datagrams in both directions until quiescent.
     *
     * @return the number of datagrams delivered
     */
    public int pump() {
        int total = 0;
        int guard = 0;
        while ((!toServer.isEmpty() || !toClient.isEmpty()) && guard++ < 10000) {
            if (!toServer.isEmpty()) {
                Delivery d = toServer.remove(0);
                serverEngine.receivePathDatagram(d.bytes, d.source, pathFor(serverPaths, d.destination, SERVER_ADDRESS));
                total++;
            }
            if (!toClient.isEmpty()) {
                Delivery d = toClient.remove(0);
                clientEngine.receivePathDatagram(d.bytes, d.source, pathFor(clientPaths, d.destination, CLIENT_ADDRESS));
                total++;
            }
        }
        return total;
    }

    /**
     * Delivers one datagram directly to the server engine.
     *
     * @param d the datagram
     */
    public void injectToServer(byte[] d) {
        serverEngine.receivePathDatagram(d, clientSource);
    }

    /**
     * Delivers one datagram directly to the client engine.
     *
     * @param d the datagram
     */
    public void injectToClient(byte[] d) {
        clientEngine.receivePathDatagram(d, SERVER_ADDRESS);
    }
}
