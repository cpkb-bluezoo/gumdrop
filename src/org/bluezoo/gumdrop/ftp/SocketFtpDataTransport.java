/*
 * SocketFtpDataTransport.java
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

package org.bluezoo.gumdrop.ftp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;

import org.bluezoo.gumdrop.AcceptSelectorLoop;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.StorageExecutor;
import org.bluezoo.gumdrop.TcpEndpoint;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.util.AsyncFile;

/**
 * The production {@link FtpDataTransport}: real listening and outbound
 * sockets, {@link TcpEndpoint}s registered on the control loop, and
 * {@link AsyncFile}s.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class SocketFtpDataTransport implements FtpDataTransport {

    @Override
    public PassiveListener listenPassive(FtpListener server, int port,
            AcceptSelectorLoop.RawAcceptHandler acceptor) throws IOException {
        // Passive mode needs the accept loop to listen for the data
        // connection; fail cleanly (the caller replies 425) if there is
        // none, before any socket is opened.
        Gumdrop gumdrop = (server != null) ? server.getGumdrop() : null;
        AcceptSelectorLoop acceptLoop =
                (gumdrop != null) ? gumdrop.getAcceptLoop() : null;
        if (acceptLoop == null) {
            throw new IOException("No accept loop available for passive mode");
        }

        // Bind the socket synchronously so we know the port immediately
        final ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.configureBlocking(false);

        if (port == 0) {
            // System-assigned, unless the listener restricts passive mode
            // to a configured port range (issue #145) - e.g. deployments
            // behind a firewall that only forwards a fixed range.
            int minPort = (server != null) ? server.getPasvMinPort() : 0;
            int maxPort = (server != null) ? server.getPasvMaxPort() : 0;
            if (minPort > 0 && maxPort >= minPort) {
                bindWithinRange(ssc, minPort, maxPort);
            } else {
                ssc.bind(new InetSocketAddress(0));
            }
        } else {
            // An explicitly requested port bypasses the configured range.
            ssc.bind(new InetSocketAddress(port));
        }

        final int bound = ((InetSocketAddress) ssc.getLocalAddress()).getPort();

        // Register the already-bound channel with AcceptSelectorLoop
        acceptLoop.registerRawAcceptor(ssc, acceptor);
        return new PassiveListener() {
            @Override
            public int port() {
                return bound;
            }

            @Override
            public void close() {
                try {
                    ssc.close();
                } catch (IOException e) {
                    // Ignore close errors
                }
            }
        };
    }

    /**
     * Binds {@code ssc} to the first available port in {@code [minPort,
     * maxPort]}, trying each in turn.
     *
     * @throws IOException if no port in the range is available
     */
    private static void bindWithinRange(
            ServerSocketChannel ssc, int minPort, int maxPort)
            throws IOException {
        for (int p = minPort; p <= maxPort; p++) {
            try {
                ssc.bind(new InetSocketAddress(p));
                return;
            } catch (IOException e) {
                // Port in use or unavailable; try the next one.
            }
        }
        throw new IOException("No available port in configured PASV port range ["
                + minPort + "-" + maxPort + "]");
    }

    @Override
    public ActiveConnection connectActive(Gumdrop gumdrop, InetAddress address,
            int port, SelectorLoop loop, final ActiveConnectCallback callback)
            throws IOException {
        final TcpTransportFactory factory = new TcpTransportFactory();
        factory.start();
        ProtocolHandler connectHandler = new ProtocolHandler() {
            @Override
            public void connected(Endpoint ep) {
                SocketChannel sc =
                        ((TcpEndpoint) ep).takeSocketChannelForHandoff();
                if (sc == null || !sc.isOpen()) {
                    callback.failed(new IOException(
                            "Active data connection channel lost"));
                    return;
                }
                callback.connected(sc);
            }

            @Override
            public void receive(ByteBuffer data) {
            }

            @Override
            public void disconnected() {
            }

            @Override
            public void securityEstablished(SecurityInfo info) {
            }

            @Override
            public void error(Exception cause) {
                callback.failed(cause);
            }
        };
        final TcpEndpoint outbound =
                factory.connect(gumdrop, address, port, connectHandler, loop);
        return new ActiveConnection() {
            @Override
            public void close() {
                outbound.close();
            }
        };
    }

    @Override
    public Endpoint createDataEndpoint(SocketChannel channel,
            ProtocolHandler handler, TcpTransportFactory secureFactory)
            throws IOException {
        if (secureFactory != null) {
            return secureFactory.createServerEndpoint(channel, handler, true);
        }
        TcpEndpoint endpoint = new TcpEndpoint(handler);
        endpoint.setChannel(channel);
        endpoint.init();
        return endpoint;
    }

    @Override
    public void registerDataEndpoint(SelectorLoop loop, SocketChannel channel,
            Endpoint endpoint) {
        loop.registerTCP(channel, (TcpEndpoint) endpoint);
    }

    @Override
    public AsyncFile openFile(StorageExecutor storage, Path path,
            OpenOption... options) throws IOException {
        return AsyncFile.open(storage, path, options);
    }
}
