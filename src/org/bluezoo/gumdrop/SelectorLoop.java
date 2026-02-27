/*
 * SelectorLoop.java
 * Copyright (C) 2005, 2025, 2026 Chris Burdess
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
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.text.MessageFormat;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.quic.QuicEngine;

/**
 * Worker selector loop for handling I/O events.
 *
 * <p>Handles OP_READ and OP_WRITE events for both TCP connections and
 * UDP datagrams. Uses the {@link ChannelHandler} interface to dispatch
 * events to the appropriate handler type.
 *
 * <p>All I/O and TLS/DTLS processing for a handler occurs on its
 * assigned SelectorLoop thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SelectorLoop implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(SelectorLoop.class.getName());

    private final int index;
    private Thread thread;
    private Selector selector;
    private volatile boolean active;

    // Reusable read buffer (per selector loop)
    // Sized for max UDP datagram (65507) plus some headroom
    private final ByteBuffer readBuffer;

    // Queue for registrations (cross-thread)
    private final ConcurrentLinkedQueue<PendingRegistration> pendingRegistrations;

    // Queue for timer callbacks (cross-thread, from ScheduledTimer)
    private final ConcurrentLinkedQueue<ScheduledTimer.TimerEntry> pendingTimers;

    // Queue for general tasks (cross-thread, from invokeLater)
    private final ConcurrentLinkedQueue<Runnable> pendingTasks;

    /**
     * Creates a new SelectorLoop with the given index (1-based for display).
     *
     * @param index the 1-based index for naming
     */
    public SelectorLoop(int index) {
        this.index = index;
        this.readBuffer = ByteBuffer.allocate(65536);
        this.pendingRegistrations = new ConcurrentLinkedQueue<PendingRegistration>();
        this.pendingTimers = new ConcurrentLinkedQueue<ScheduledTimer.TimerEntry>();
        this.pendingTasks = new ConcurrentLinkedQueue<Runnable>();
    }

    /**
     * Starts this SelectorLoop.
     * Creates a new thread if needed and starts processing.
     */
    public void start() {
        if (thread != null && thread.isAlive()) {
            return; // Already running
        }
        thread = new Thread(this, "SelectorLoop-" + index);
        thread.start();
    }

    /**
     * Returns whether this SelectorLoop is currently running.
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

            while (active) {
                try {
                    // Process any pending registrations
                    processPendingRegistrations();

                    // Process any pending timer callbacks
                    processPendingTimers();

                    // Process any pending tasks
                    processPendingTasks();

                    // Select with timeout to check registrations periodically
                    selector.select(100);

                    Set<SelectionKey> keys = selector.selectedKeys();
                    for (Iterator<SelectionKey> i = keys.iterator(); i.hasNext(); ) {
                        SelectionKey key = i.next();
                        i.remove();

                        if (!key.isValid()) {
                            continue;
                        }

                        ChannelHandler handler = (ChannelHandler) key.attachment();

                        if (key.isReadable()) {
                            doRead(key, handler);
                        }

                        if (key.isValid() && key.isWritable()) {
                            doWrite(key, handler);
                        }

                        if (key.isValid() && key.isConnectable()) {
                            // Only TCP connections have OP_CONNECT
                            doTcpEndpointConnect(key, (TCPEndpoint) handler);
                        }
                    }
                } catch (CancelledKeyException e) {
                    // Key was cancelled, continue
                } catch (IOException e) {
                    if ("Bad file descriptor".equals(e.getMessage())) {
                        // Selector was closed
                    } else {
                        LOGGER.log(Level.WARNING, "Error in selector loop", e);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize SelectorLoop", e);
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

    private void processPendingRegistrations() {
        PendingRegistration reg;
        while ((reg = pendingRegistrations.poll()) != null) {
            try {
                int ops = reg.connect ? SelectionKey.OP_CONNECT : SelectionKey.OP_READ;
                SelectionKey key = reg.channel.register(selector, ops);
                key.attach(reg.handler);
                reg.handler.setSelectionKey(key);
                reg.handler.setSelectorLoop(this);
            } catch (ClosedChannelException e) {
                // Channel was closed before we could register
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Channel closed before registration");
                }
            }
        }
    }

    private void processPendingTimers() {
        ScheduledTimer.TimerEntry entry;
        while ((entry = pendingTimers.poll()) != null) {
            if (!entry.cancelled) {
                try {
                    entry.callback.run();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error in timer callback", e);
                }
            }
        }
    }

    private void processPendingTasks() {
        Runnable task;
        while ((task = pendingTasks.poll()) != null) {
            try {
                task.run();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in pending task", e);
            }
        }
    }

    /**
     * Called by ScheduledTimer when a timer fires.
     * Adds the timer entry to the pending queue and wakes up the selector.
     */
    void dispatchTimer(ScheduledTimer.TimerEntry entry) {
        pendingTimers.offer(entry);
        if (selector != null) {
            selector.wakeup();
        }
    }

    // -- Dispatch methods --

    private void doRead(SelectionKey key, ChannelHandler handler) {
        switch (handler.getChannelType()) {
            case TCP:
                doTcpEndpointRead(key, (TCPEndpoint) handler);
                break;
            case DATAGRAM_SERVER:
            case DATAGRAM_CLIENT:
                doUDPEndpointRead(key, (UDPEndpoint) handler);
                break;
            case QUIC:
                doQuicRead(key, (QuicEngine) handler);
                break;
        }
    }

    private void doWrite(SelectionKey key, ChannelHandler handler) {
        switch (handler.getChannelType()) {
            case TCP:
                doTcpEndpointWrite(key, (TCPEndpoint) handler);
                break;
            case DATAGRAM_SERVER:
            case DATAGRAM_CLIENT:
                doUDPEndpointWrite(key, (UDPEndpoint) handler);
                break;
            case QUIC:
                doQuicWrite(key, (QuicEngine) handler);
                break;
        }
    }

    // -- TCPEndpoint methods --

    private void doTcpEndpointRead(SelectionKey key, TCPEndpoint endpoint) {
        SocketChannel sc = (SocketChannel) key.channel();
        readBuffer.clear();

        try {
            int len = sc.read(readBuffer);

            if (len == -1) {
                endpoint.handleEOF();
            } else if (len > 0) {
                readBuffer.flip();

                if (LOGGER.isLoggable(Level.FINEST)) {
                    Object sa = sc.socket().getRemoteSocketAddress();
                    String message = Gumdrop.L10N.getString("info.received");
                    message = MessageFormat.format(message, len, sa);
                    LOGGER.finest(message);
                }

                endpoint.appendToNetIn(readBuffer);
                endpoint.netIn.flip();
                endpoint.processInbound();
            }
        } catch (IOException e) {
            endpoint.handleReadError(e);
        }
    }

    private void doTcpEndpointWrite(SelectionKey key, TCPEndpoint endpoint) {
        SocketChannel sc = (SocketChannel) key.channel();
        ByteBuffer netOut = endpoint.getNetOut();

        try {
            synchronized (endpoint.netOutLock) {
                netOut.flip();

                if (netOut.hasRemaining()) {
                    int len = sc.write(netOut);

                    if (LOGGER.isLoggable(Level.FINEST)) {
                        Object sa = sc.socket().getRemoteSocketAddress();
                        String message = Gumdrop.L10N.getString("info.sent");
                        message = MessageFormat.format(message, len, sa);
                        LOGGER.finest(message);
                    }

                    if (netOut.hasRemaining()) {
                        netOut.compact();
                        return;
                    }
                }

                netOut.clear();
            }

            if (endpoint.closeRequested) {
                endpoint.doClose();
                key.cancel();
                return;
            }

            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);

        } catch (IOException e) {
            endpoint.handleWriteError(e);
        }
    }

    private void doTcpEndpointConnect(SelectionKey key, TCPEndpoint endpoint) {
        SocketChannel sc = (SocketChannel) key.channel();

        try {
            if (sc.finishConnect()) {
                key.interestOps((key.interestOps() & ~SelectionKey.OP_CONNECT)
                        | SelectionKey.OP_READ);

                if (LOGGER.isLoggable(Level.FINEST)) {
                    String message = Gumdrop.L10N.getString("info.connected");
                    message = MessageFormat.format(message, sc.toString());
                    LOGGER.finest(message);
                }

                endpoint.connected();
                endpoint.initiateClientTLSHandshake();
            }
        } catch (IOException e) {
            endpoint.handleConnectError(e);
        }
    }

    // -- UDPEndpoint methods --

    private void doUDPEndpointRead(SelectionKey key,
                                         UDPEndpoint endpoint) {
        DatagramChannel dc = (DatagramChannel) key.channel();
        endpoint.netIn.clear();

        try {
            InetSocketAddress source =
                    (InetSocketAddress) dc.receive(endpoint.netIn);
            if (source == null) {
                return;
            }

            endpoint.netIn.flip();

            if (!endpoint.netIn.hasRemaining()) {
                return;
            }

            if (LOGGER.isLoggable(Level.FINEST)) {
                String message = Gumdrop.L10N.getString("info.received");
                message = MessageFormat.format(message,
                        endpoint.netIn.remaining(), source);
                LOGGER.finest(message);
            }

            endpoint.netReceive(endpoint.netIn, source);

        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Error reading from datagram endpoint", e);
            endpoint.close();
        }
    }

    private void doUDPEndpointWrite(SelectionKey key,
                                          UDPEndpoint endpoint) {
        DatagramChannel dc = (DatagramChannel) key.channel();

        try {
            UDPEndpoint.PendingDatagram pending;
            while ((pending = endpoint.pendingDatagrams.poll()) != null) {
                ByteBuffer data = pending.data;
                InetSocketAddress dest = pending.destination;

                int len;
                if (dest != null) {
                    len = dc.send(data, dest);
                } else {
                    len = dc.write(data);
                }

                if (LOGGER.isLoggable(Level.FINEST)) {
                    Object target = dest != null ? dest
                            : endpoint.getRemoteAddress();
                    String message = Gumdrop.L10N.getString("info.sent");
                    message = MessageFormat.format(message, len, target);
                    LOGGER.finest(message);
                }

                if (data.hasRemaining()) {
                    endpoint.pendingDatagrams.addFirst(pending);
                    return;
                }
            }

            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);

        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Error writing to datagram endpoint", e);
            endpoint.close();
        }
    }

    // -- QUIC methods --

    private void doQuicRead(SelectionKey key, QuicEngine engine) {
        engine.onReadable();
    }

    private void doQuicWrite(SelectionKey key, QuicEngine engine) {
        engine.onWritable();
    }

    // -- Registration methods --

    /**
     * Registers a TCPEndpoint with this SelectorLoop.
     * Thread-safe.
     *
     * @param channel the socket channel
     * @param endpoint the TCPEndpoint
     */
    void register(SocketChannel channel, TCPEndpoint endpoint) {
        pendingRegistrations.add(new PendingRegistration(channel, endpoint, false));
        if (selector != null) {
            selector.wakeup();
        }
    }

    /**
     * Registers a TCPEndpoint for CONNECT events.
     * Thread-safe.
     *
     * @param channel the socket channel
     * @param endpoint the TCPEndpoint
     */
    void registerForConnect(SocketChannel channel, TCPEndpoint endpoint) {
        pendingRegistrations.add(new PendingRegistration(channel, endpoint, true));
        if (selector != null) {
            selector.wakeup();
        }
    }

    /**
     * Registers a datagram channel with this SelectorLoop.
     * Thread-safe.
     *
     * @param channel the datagram channel
     * @param handler the datagram server or client
     */
    public void registerDatagram(DatagramChannel channel, ChannelHandler handler) {
        pendingRegistrations.add(new PendingRegistration(channel, handler, false));
        if (selector != null) {
            selector.wakeup();
        }
    }

    // -- Write request methods --

    /**
     * Schedules a task to run on this SelectorLoop thread.
     * If called from this thread, the task is executed immediately.
     * Otherwise, it is queued and the selector is woken up.
     *
     * @param task the task to execute
     */
    public void invokeLater(Runnable task) {
        if (Thread.currentThread() == thread) {
            // We're on the SelectorLoop thread, execute immediately
            try {
                task.run();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in invokeLater task", e);
            }
        } else {
            // Queue for execution on next selector wakeup
            pendingTasks.offer(task);
            if (selector != null) {
                selector.wakeup();
            }
        }
    }

    /**
     * Requests OP_WRITE interest for a TCPEndpoint.
     * May be called from any thread.
     *
     * @param endpoint the endpoint with pending data
     */
    void requestWrite(TCPEndpoint endpoint) {
        requestWriteInternal(endpoint);
    }

    /**
     * Requests OP_WRITE interest for a datagram handler.
     * Called when a datagram server/client has data to send.
     * May be called from any thread.
     *
     * @param handler the handler with pending data
     */
    public void requestDatagramWrite(ChannelHandler handler) {
        requestWriteInternal(handler);
    }

    private void requestWriteInternal(ChannelHandler handler) {
        SelectionKey key = handler.getSelectionKey();
        if (key != null && key.isValid()) {
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);

            // Wake up selector if called from a different thread
            if (Thread.currentThread() != thread) {
                if (selector != null) {
                    selector.wakeup();
                }
            }
        }
    }

    /**
     * Shuts down this SelectorLoop.
     */
    public void shutdown() {
        active = false;
        if (selector != null) {
            selector.wakeup();
        }
    }

    /**
     * Waits for this SelectorLoop's thread to terminate.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public void join() throws InterruptedException {
        if (thread != null) {
            thread.join();
        }
    }

    /**
     * Pending registration for any channel type.
     */
    private static class PendingRegistration {
        final SelectableChannel channel;
        final ChannelHandler handler;
        final boolean connect;

        PendingRegistration(SelectableChannel channel, ChannelHandler handler, boolean connect) {
            this.channel = channel;
            this.handler = handler;
            this.connect = connect;
        }
    }

}
