/*
 * AcceptSelectorLoop.java
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

package org.bluezoo.gumdrop;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.text.MessageFormat;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Selector loop dedicated to accepting new connections.
 * Handles OP_ACCEPT events for all ServerSocketChannels and hands off
 * new connections to worker SelectorLoops.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AcceptSelectorLoop extends Thread {

    private static final Logger LOGGER = Logger.getLogger(AcceptSelectorLoop.class.getName());

    private final Gumdrop gumdrop;
    private Selector selector;
    private volatile boolean active;
    private final ConcurrentLinkedQueue<PendingRegistration> pendingRegistrations;

    AcceptSelectorLoop(Gumdrop gumdrop) {
        super("AcceptSelectorLoop");
        this.gumdrop = gumdrop;
        this.pendingRegistrations = new ConcurrentLinkedQueue<>();
    }

    public void run() {
        active = true;
        try {
            selector = Selector.open();

            // Register all servers' ServerSocketChannels
            for (Server server : gumdrop.getServers()) {
                doRegisterServer(server);
            }

                // Main accept loop
            while (active) {
                try {
                    // Process any pending server registrations
                    processPendingRegistrations();

                    selector.select();

                    Set<SelectionKey> keys = selector.selectedKeys();
                    for (Iterator<SelectionKey> i = keys.iterator(); i.hasNext(); ) {
                        SelectionKey key = i.next();
                        i.remove();

                        if (!key.isValid()) {
                            continue;
                        }

                        if (key.isAcceptable()) {
                            accept(key);
                        }
                    }
                } catch (CancelledKeyException e) {
                    // Key was cancelled, continue
                } catch (IOException e) {
                    if ("Bad file descriptor".equals(e.getMessage())) {
                        // Selector was closed
                    } else {
                        LOGGER.log(Level.WARNING, "Error in accept loop", e);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize AcceptSelectorLoop", e);
        } finally {
            if (selector != null) {
                try {
                    selector.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error closing selector", e);
                }
            }
        }
    }

    /**
     * Pending registration containing either a Server (needs binding) or
     * a pre-bound ServerSocketChannel.
     */
    private static class PendingRegistration {
        final Server server;
        final ServerSocketChannel channel; // null if server needs binding

        PendingRegistration(Server server) {
            this.server = server;
            this.channel = null;
        }

        PendingRegistration(Server server, ServerSocketChannel channel) {
            this.server = server;
            this.channel = channel;
        }
    }

    /**
     * Registers a server for accepting connections.
     * This method is thread-safe and can be called from any thread.
     * The actual registration will be performed on the selector thread.
     * The server socket channels will be created and bound by the selector thread.
     *
     * @param server the server to register
     */
    public void registerServer(Server server) {
        pendingRegistrations.add(new PendingRegistration(server));
        if (selector != null) {
            selector.wakeup();
        }
    }

    /**
     * Registers an already-bound ServerSocketChannel for a server.
     * This method is thread-safe and can be called from any thread.
     * The actual registration will be performed on the selector thread.
     * 
     * Use this method when you need to know the bound port synchronously,
     * for example for FTP passive mode.
     *
     * @param server the server that owns the channel
     * @param channel an already-bound ServerSocketChannel
     */
    public void registerChannel(Server server, ServerSocketChannel channel) {
        pendingRegistrations.add(new PendingRegistration(server, channel));
        if (selector != null) {
            selector.wakeup();
        }
    }

    /**
     * Processes pending server registrations on the selector thread.
     */
    private void processPendingRegistrations() {
        PendingRegistration pending;
        while ((pending = pendingRegistrations.poll()) != null) {
            try {
                if (pending.channel != null) {
                    // Pre-bound channel - just register with selector
                    doRegisterChannel(pending.server, pending.channel);
                } else {
                    // Server needs binding
                    doRegisterServer(pending.server);
                }
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Failed to register server: " + pending.server.getDescription(), e);
            }
        }
    }

    /**
     * Registers a pre-bound ServerSocketChannel with the selector.
     */
    private void doRegisterChannel(Server server, ServerSocketChannel ssc) throws IOException {
        SelectionKey key = ssc.register(selector, SelectionKey.OP_ACCEPT);
        key.attach(server);
        server.addServerChannel(ssc);

        if (LOGGER.isLoggable(Level.FINE)) {
            InetSocketAddress addr = (InetSocketAddress) ssc.getLocalAddress();
            String message = Gumdrop.L10N.getString("info.bound_server");
            message = MessageFormat.format(message, server.getDescription(), addr.getPort(), addr.getAddress(), 0L);
            LOGGER.fine(message);
        }
    }

    private void doRegisterServer(Server server) throws IOException {
        Set<InetAddress> addresses = server.getAddresses();
        int port = server.getPort();

        for (InetAddress address : addresses) {
            ServerSocketChannel ssc = ServerSocketChannel.open();
            ssc.configureBlocking(false);
            ServerSocket ss = ssc.socket();

            // Bind server socket to port
            InetSocketAddress socketAddress = new InetSocketAddress(address, port);
            long t1 = System.currentTimeMillis();
            ss.bind(socketAddress);
            long t2 = System.currentTimeMillis();

            if (LOGGER.isLoggable(Level.FINE)) {
                String message = Gumdrop.L10N.getString("info.bound_server");
                message = MessageFormat.format(message, server.getDescription(), port, address, (t2 - t1));
                LOGGER.fine(message);
            }

            // Register selector for accept
            SelectionKey key = ssc.register(selector, SelectionKey.OP_ACCEPT);
            key.attach(server);

            server.addServerChannel(ssc);
        }
    }

    private void accept(SelectionKey key) {
        ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
        Server server = (Server) key.attachment();

        // Process all pending connections to avoid selector thrashing
        SocketChannel sc;
        try {
            while ((sc = ssc.accept()) != null) {
                try {
                    // Check if server accepts this connection
                    InetSocketAddress remoteAddress = (InetSocketAddress) sc.getRemoteAddress();
                    if (!server.acceptConnection(remoteAddress)) {
                        if (LOGGER.isLoggable(Level.FINE)) {
                            String message = Gumdrop.L10N.getString("info.connection_rejected");
                            if (message == null) {
                                message = "Connection rejected from {0}";
                            }
                            message = MessageFormat.format(message, remoteAddress.toString());
                            LOGGER.fine(message);
                        }
                        sc.close();
                        continue;
                    }

                    // Configure the channel
                    sc.configureBlocking(false);

                    // Assign to next worker loop (round-robin)
                    SelectorLoop workerLoop = gumdrop.nextWorkerLoop();

                    // Create connection (key will be assigned by worker loop)
                    Connection connection = server.newConnection(sc, (SelectionKey) null);

                    // Hand off to worker loop for registration
                    workerLoop.register(sc, connection);

                    if (LOGGER.isLoggable(Level.FINEST)) {
                        String message = Gumdrop.L10N.getString("info.accepted");
                        message = MessageFormat.format(message, remoteAddress.toString());
                        LOGGER.finest(message);
                    }
                } catch (IOException e) {
                    if (LOGGER.isLoggable(Level.WARNING)) {
                        LOGGER.log(Level.WARNING, "Error processing accepted connection", e);
                    }
                    try {
                        sc.close();
                    } catch (IOException closeEx) {
                        // Ignore close errors
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error accepting connection", e);
        }
    }

    void shutdown() {
        active = false;
        if (selector != null) {
            selector.wakeup();
        }
    }
}

