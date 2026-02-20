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
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
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
public class AcceptSelectorLoop implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(AcceptSelectorLoop.class.getName());

    /**
     * Handler for raw socket channel accepts.
     * Used by subsystems like FTP data that need the raw channel
     * without the endpoint/connection infrastructure.
     */
    public interface RawAcceptHandler {
        void accepted(SocketChannel sc) throws IOException;
    }

    private final Gumdrop gumdrop;
    private Thread thread;
    private Selector selector;
    private volatile boolean active;
    private final ConcurrentLinkedQueue<PendingRegistration> pendingRegistrations;

    AcceptSelectorLoop(Gumdrop gumdrop) {
        this.gumdrop = gumdrop;
        this.pendingRegistrations = new ConcurrentLinkedQueue<PendingRegistration>();
    }

    /**
     * Starts this AcceptSelectorLoop.
     * Creates a new thread if needed and starts accepting connections.
     */
    public void start() {
        if (thread != null && thread.isAlive()) {
            return; // Already running
        }
        thread = new Thread(this, "AcceptSelectorLoop");
        thread.start();
    }

    /**
     * Returns whether this AcceptSelectorLoop is currently running.
     *
     * @return true if the thread is alive
     */
    public boolean isRunning() {
        return thread != null && thread.isAlive();
    }

    @Override
    public void run() {
        active = true;
        try {
            selector = Selector.open();

            // Process any listeners queued before the selector was open
            processPendingRegistrations();

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
                selector = null;
            }
        }
    }

    /**
     * Pending registration containing either a TCPListener (needs binding),
     * or a raw accept handler with a pre-bound ServerSocketChannel.
     */
    private static class PendingRegistration {
        final TCPListener listener;
        final RawAcceptHandler rawHandler;
        final ServerSocketChannel channel; // null if listener needs binding

        PendingRegistration(TCPListener listener) {
            this.listener = listener;
            this.rawHandler = null;
            this.channel = null;
        }

        PendingRegistration(RawAcceptHandler handler, ServerSocketChannel channel) {
            this.listener = null;
            this.rawHandler = handler;
            this.channel = channel;
        }
    }

    /**
     * Registers an endpoint server for accepting connections.
     *
     * @param server the endpoint server to register
     */
    public void registerListener(TCPListener server) {
        pendingRegistrations.add(new PendingRegistration(server));
        if (selector != null) {
            selector.wakeup();
        }
    }

    /**
     * Registers a raw accept handler for an already-bound ServerSocketChannel.
     * The handler receives raw SocketChannels without endpoint/connection
     * infrastructure. Used by subsystems like FTP data that use blocking I/O.
     *
     * @param channel an already-bound ServerSocketChannel
     * @param handler the handler to receive accepted connections
     */
    public void registerRawAcceptor(ServerSocketChannel channel, RawAcceptHandler handler) {
        pendingRegistrations.add(new PendingRegistration(handler, channel));
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
                if (pending.rawHandler != null) {
                    doRegisterRawAcceptor(pending.rawHandler, pending.channel);
                } else if (pending.listener != null) {
                    doRegisterListener(pending.listener);
                }
            } catch (IOException e) {
                String desc;
                if (pending.rawHandler != null) {
                    desc = "raw acceptor";
                } else {
                    desc = pending.listener.getDescription();
                }
                LOGGER.log(Level.SEVERE,
                        "Failed to register server: " + desc, e);
            }
        }
    }

    /**
     * Registers a raw accept handler with the selector.
     */
    private void doRegisterRawAcceptor(RawAcceptHandler handler, ServerSocketChannel ssc)
            throws IOException {
        SelectionKey key = ssc.register(selector, SelectionKey.OP_ACCEPT);
        key.attach(handler);
        if (LOGGER.isLoggable(Level.FINE)) {
            InetSocketAddress addr = (InetSocketAddress) ssc.getLocalAddress();
            LOGGER.fine("Registered raw accept handler on port " + addr.getPort());
        }
    }

    private void doRegisterListener(TCPListener server)
            throws IOException {
        String socketPath = server.getPath();
        if (socketPath != null) {
            doRegisterUnixListener(server, socketPath);
        } else {
            doRegisterTcpListener(server);
        }
    }

    private void doRegisterUnixListener(TCPListener server, String socketPath)
            throws IOException {
        Path path = Path.of(socketPath);
        Files.deleteIfExists(path);

        ServerSocketChannel ssc =
                ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        ssc.configureBlocking(false);

        long t1 = System.currentTimeMillis();
        ssc.bind(UnixDomainSocketAddress.of(path));
        long t2 = System.currentTimeMillis();

        if (LOGGER.isLoggable(Level.FINE)) {
            String message = Gumdrop.L10N.getString("info.bound_unix_server");
            if (message != null) {
                message = MessageFormat.format(message,
                        server.getDescription(), socketPath, (t2 - t1));
            } else {
                message = server.getDescription() + " bound to " + socketPath
                        + " (" + (t2 - t1) + " ms)";
            }
            LOGGER.fine(message);
        }

        SelectionKey key = ssc.register(selector, SelectionKey.OP_ACCEPT);
        key.attach(server);

        server.addServerChannel(ssc);
    }

    private void doRegisterTcpListener(TCPListener server)
            throws IOException {
        Set<InetAddress> addrs = server.getAddresses();
        int port = server.getPort();

        for (InetAddress address : addrs) {
            ServerSocketChannel ssc = ServerSocketChannel.open();
            ssc.configureBlocking(false);
            ServerSocket ss = ssc.socket();

            InetSocketAddress socketAddress =
                    new InetSocketAddress(address, port);
            long t1 = System.currentTimeMillis();
            ss.bind(socketAddress);
            long t2 = System.currentTimeMillis();

            if (LOGGER.isLoggable(Level.FINE)) {
                String message = Gumdrop.L10N.getString("info.bound_server");
                message = MessageFormat.format(message,
                        server.getDescription(), port, address, (t2 - t1));
                LOGGER.fine(message);
            }

            SelectionKey key = ssc.register(selector, SelectionKey.OP_ACCEPT);
            key.attach(server);

            server.addServerChannel(ssc);
        }
    }

    private void accept(SelectionKey key) {
        ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
        Object attachment = key.attachment();

        SocketChannel sc;
        try {
            while ((sc = ssc.accept()) != null) {
                try {
                    SocketAddress remoteAddress = sc.getRemoteAddress();

                    if (attachment instanceof TCPListener) {
                        acceptListener(
                                (TCPListener) attachment, sc, remoteAddress);
                    } else if (attachment instanceof RawAcceptHandler) {
                        sc.configureBlocking(true);
                        ((RawAcceptHandler) attachment).accepted(sc);
                    }
                } catch (IOException e) {
                    if (LOGGER.isLoggable(Level.WARNING)) {
                        LOGGER.log(Level.WARNING,
                                "Error processing accepted connection", e);
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

    private void acceptListener(TCPListener server, SocketChannel sc,
            SocketAddress remoteAddress) throws IOException {
        if (!server.acceptConnection(remoteAddress)) {
            logRejection(remoteAddress);
            sc.close();
            return;
        }

        sc.configureBlocking(false);
        SelectorLoop workerLoop = gumdrop.nextWorkerLoop();
        TCPEndpoint endpoint = server.newEndpoint(sc, workerLoop);
        endpoint.connected();
        workerLoop.register(sc, endpoint);

        if (LOGGER.isLoggable(Level.FINEST)) {
            String message = Gumdrop.L10N.getString("info.accepted");
            message = MessageFormat.format(message,
                    String.valueOf(remoteAddress));
            LOGGER.finest(message);
        }
    }

    private void logRejection(SocketAddress remoteAddress) {
        if (LOGGER.isLoggable(Level.FINE)) {
            String message = Gumdrop.L10N.getString("info.connection_rejected");
            if (message == null) {
                message = "Connection rejected from {0}";
            }
            message = MessageFormat.format(message,
                    String.valueOf(remoteAddress));
            LOGGER.fine(message);
        }
    }

    /**
     * Shuts down this AcceptSelectorLoop.
     */
    void shutdown() {
        active = false;
        if (selector != null) {
            selector.wakeup();
        }
    }

    /**
     * Waits for this AcceptSelectorLoop's thread to terminate.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public void join() throws InterruptedException {
        if (thread != null) {
            thread.join();
        }
    }

}
