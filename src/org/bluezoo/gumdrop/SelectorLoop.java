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
import java.net.PortUnreachableException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.quic.QuicEngine;
import org.bluezoo.gumdrop.util.ByteBufferPool;
import java.util.ResourceBundle;
/**
 * Worker selector loop for handling I/O events.
 *
 * <p>Handles OP_READ and OP_WRITE events for both TCP connections and
 * UDP datagrams. Uses the {@link ChannelHandler} interface to dispatch
 * events to the appropriate handler type.
 *
 * <p>All I/O and TLS/DTLS processing for a handler occurs on its
 * assigned SelectorLoop thread. That includes closing it: sending on and
 * closing an endpoint, UDP/DTLS endpoint or QUIC engine are loop-thread
 * operations, and other threads hand the work to the owning loop with
 * {@link #invokeLater(Runnable)}.
 *
 * <h4>Shutdown</h4>
 *
 * <p>A loop owns every connection, datagram endpoint and QUIC engine
 * registered with it, and closes all of them itself, on its own thread.
 * {@link #shutdown()} is the orderly form: each owned handler is asked to
 * close with its protocol goodbyes ({@link ChannelHandler#closeForShutdown
 * closeForShutdown(true)}: queued output flushed, TLS/DTLS
 * {@code close_notify}, QUIC {@code CONNECTION_CLOSE}). {@link
 * #shutdownNow()} is the abort form: every handler is closed at once with
 * no goodbyes and queued output discarded. Calling it while an orderly
 * shutdown is waiting escalates that shutdown. Both are idempotent, return
 * immediately, and may be called from any thread.
 *
 * <p>The loop exits when it owns nothing open, or when its hard deadline
 * (see {@link #setCloseDeadlineMs}) passes, whichever is first: a peer that
 * never drains its connection cannot hang a shutdown, its socket is closed
 * regardless. Just before exiting the loop runs the tasks still queued with
 * {@code invokeLater} once more, so a queued close is never dropped.
 *
 * <p>Once a loop has terminated, {@link #tryInvokeLater(Runnable)} returns
 * {@code false} and {@link #invokeLater(Runnable)} logs a warning; neither
 * runs the task, and a channel offered to
 * {@code register*} is closed (see those methods). Nothing offered to a
 * terminated loop is silently lost: it is either run, or visibly rejected.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SelectorLoop implements Runnable {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.L10N");

    private static final Logger LOGGER = Logger.getLogger(SelectorLoop.class.getName());

    /**
     * Bound, in milliseconds, on how long a single {@link Selector#select}
     * call may block with nothing to do, or 0 to block indefinitely (the
     * default - every path that has work for this loop calls {@link
     * Selector#wakeup}, so an indefinite block costs nothing when idle and
     * is the most CPU-efficient choice for the common case of many mostly-
     * idle connections).
     *
     * <p>A blocked {@code select()} still wakes immediately the instant a
     * registered channel becomes ready, regardless of this value - the
     * kernel's readiness notification is not a polling loop. What a
     * positive value actually buys is a periodic top-of-loop check even
     * when idle, at the cost of that many extra wakeups per second per
     * loop; it does not by itself make a busy connection faster. It exists
     * to let a deployment trade idle-CPU cost for that bound where it
     * matters (e.g. matching a specific load pattern being benchmarked
     * against), not as a default performance tuning.
     */
    private static final long SELECT_TIMEOUT_MS =
            Long.getLong("gumdrop.selectorLoop.selectTimeoutMs", 0L);

    private final int index;
    private Gumdrop gumdrop;
    private Thread thread;
    // volatile so cross-thread producers reliably observe a non-null selector
    // and their wakeup() takes effect (the loop no longer polls on a timeout).
    private volatile Selector selector;

    private static final int MODE_RUNNING = 0;
    private static final int MODE_ORDERLY = 1;
    private static final int MODE_ABORT = 2;

    /**
     * Default bound on how long a loop that has begun shutting down waits
     * for its handlers to finish closing before it closes them regardless.
     */
    private static final long DEFAULT_CLOSE_DEADLINE_MS = 5000L;

    // Requested shutdown mode (MODE_*); only ever raised, orderly to abort.
    private final AtomicInteger shutdownMode = new AtomicInteger(MODE_RUNNING);
    // True once the loop has finished its final drain: from then on work
    // offered to this loop is rejected rather than queued.
    private volatile boolean terminated;
    private volatile long closeDeadlineMs = DEFAULT_CLOSE_DEADLINE_MS;
    // Loop-thread state of a shutdown in progress.
    private boolean closeStarted;
    private boolean abortApplied;
    private long closeDeadlineAt;

    // Queue for registrations (cross-thread)
    private final ConcurrentLinkedQueue<PendingRegistration> pendingRegistrations;

    // Queue for timer callbacks (cross-thread, from ScheduledTimer)
    private final ConcurrentLinkedQueue<ScheduledTimer.TimerEntry> pendingTimers;

    // Queue for general tasks (cross-thread, from invokeLater)
    private final ConcurrentLinkedQueue<Runnable> pendingTasks;

    // Per-loop timer, so timer scheduling/cancellation for this loop's handlers
    // does not contend on a single process-wide timer lock. Callbacks are still
    // dispatched back onto this loop's thread.
    private final ScheduledTimer timer;

    /**
     * Creates a new SelectorLoop with the given index (1-based for display).
     *
     * @param index the 1-based index for naming
     */
    public SelectorLoop(int index) {
        this.index = index;
        this.pendingRegistrations = new ConcurrentLinkedQueue<PendingRegistration>();
        this.pendingTimers = new ConcurrentLinkedQueue<ScheduledTimer.TimerEntry>();
        this.pendingTasks = new ConcurrentLinkedQueue<Runnable>();
        this.timer = new ScheduledTimer("SelectorLoop-" + index + "-timer");
    }

    /**
     * Returns this loop's dedicated timer. Timer callbacks scheduled here are
     * dispatched back onto this loop's thread.
     *
     * @return the per-loop scheduled timer
     */
    ScheduledTimer getTimer() {
        return timer;
    }

    /**
     * Sets the {@link Gumdrop} runtime that owns this loop, so code that
     * only holds a loop reference (e.g. a DNS transport mid-resolution)
     * can recover it without a singleton lookup. Set by {@link Gumdrop}
     * itself when it creates a worker loop; left {@code null} for a
     * standalone {@link SelectorLoop} created outside any runtime.
     *
     * @param gumdrop the owning runtime
     */
    void setGumdrop(Gumdrop gumdrop) {
        this.gumdrop = gumdrop;
    }

    /**
     * Returns the {@link Gumdrop} runtime that owns this loop, or
     * {@code null} for a standalone loop created outside any runtime.
     *
     * @return the owning runtime, or null
     */
    public Gumdrop getGumdrop() {
        return gumdrop;
    }

    /**
     * Starts this SelectorLoop.
     * Creates a new thread if needed and starts processing.
     */
    public void start() {
        if (thread != null && thread.isAlive()) {
            return; // Already running
        }
        timer.start();
        // Reset before the thread runs, not in run(): a shutdown() that
        // arrives before the new thread is scheduled must not be undone
        // by run() resetting the state afterwards (join() would then wait
        // forever).
        shutdownMode.set(MODE_RUNNING);
        terminated = false;
        closeStarted = false;
        abortApplied = false;
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

    /**
     * Returns the number of channels (TCP connections, datagram
     * registrations, etc.) currently registered on this loop - used by
     * {@link Gumdrop#nextWorkerLoop()} to pick the least-loaded loop
     * instead of assigning purely round-robin, which never rebalances
     * when connection lifetimes vary widely (issue #139).
     *
     * <p>Reads {@link Selector#keys()}'s size directly rather than
     * maintaining a separate counter: keys are cancelled from many
     * different code paths (EOF, write error, explicit close, ...), so a
     * hand-maintained increment/decrement counter would be one more place
     * to keep in sync and risk drifting; the selector's own key set can't
     * drift by construction. A few callers scanning this concurrently
     * with the loop thread mutating the set is safe for a size query -
     * only concurrent iteration needs external synchronization - and a
     * load estimate that lags by up to one loop iteration is more than
     * precise enough for this coarse rebalancing heuristic.
     *
     * @return the number of registered channels, or 0 before the loop has
     *      started
     */
    public int getConnectionCount() {
        Selector s = selector;
        if (s == null) {
            return 0;
        }
        try {
            return s.keys().size();
        } catch (ClosedSelectorException e) {
            // Closed concurrently between the null-check and keys() - the
            // loop is shutting down, treat it as unloaded.
            return 0;
        }
    }

    @Override
    public void run() {
        try {
            selector = Selector.open();

            for (;;) {
                try {
                    // Process any pending registrations
                    processPendingRegistrations();

                    // Process any pending timer callbacks
                    processPendingTimers();

                    // Process any pending tasks
                    processPendingTasks();

                    // A requested shutdown is carried out here, on this
                    // thread, after the queued work above has run.
                    if (shutdownMode.get() != MODE_RUNNING && advanceShutdown()) {
                        break;
                    }

                    // Block until an I/O event, a wakeup(), or shutdown. Every
                    // path that enqueues a registration, timer, or task calls
                    // wakeup() (whose effect is sticky), so there is no need for
                    // a periodic timeout poll and its baseline CPU wakeups -
                    // SELECT_TIMEOUT_MS is 0 (block indefinitely) unless a
                    // deployment has explicitly opted into a bound. While a
                    // shutdown is waiting on its handlers the wait is bounded
                    // by the shutdown's hard deadline instead.
                    selector.select(selectTimeoutMs());

                    Set<SelectionKey> keys = selector.selectedKeys();
                    for (Iterator<SelectionKey> i = keys.iterator(); i.hasNext(); ) {
                        SelectionKey key = i.next();
                        i.remove();

                        if (!key.isValid()) {
                            continue;
                        }

                        ChannelHandler handler = (ChannelHandler) key.attachment();

                        try {
                            if (key.isReadable()) {
                                doRead(key, handler);
                            }

                            if (key.isValid() && key.isWritable()) {
                                doWrite(key, handler);
                            }

                            if (key.isValid() && key.isConnectable()) {
                                // Only TCP connections have OP_CONNECT
                                doTcpEndpointConnect(key, (TcpEndpoint) handler);
                            }
                        } catch (CancelledKeyException e) {
                            // Key was cancelled while dispatching, continue.
                        } catch (Exception e) {
                            LOGGER.log(Level.WARNING,
                                    L10N.getString("log.error_dispatching_io_event"), e);
                            isolateFailedHandler(key, handler, e);
                        }
                    }
                } catch (CancelledKeyException e) {
                    // Key was cancelled, continue
                } catch (IOException e) {
                    if ("Bad file descriptor".equals(e.getMessage())) {
                        // Selector was closed
                    } else {
                        LOGGER.log(Level.WARNING, L10N.getString("log.error_in_selector_loop"), e);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, L10N.getString("log.failed_to_initialize_selectorloop"), e);
        } finally {
            terminate();
        }
    }

    private long selectTimeoutMs() {
        if (!closeStarted) {
            return SELECT_TIMEOUT_MS;
        }
        long remaining = Math.max(1L, closeDeadlineAt - clockMillis());
        if (SELECT_TIMEOUT_MS > 0L && SELECT_TIMEOUT_MS < remaining) {
            return SELECT_TIMEOUT_MS;
        }
        return remaining;
    }

    /**
     * The clock the shutdown deadline is measured on, in milliseconds.
     * Monotonic. Overridden by tests, which must not depend on real time.
     */
    long clockMillis() {
        return System.nanoTime() / 1000000L;
    }

    /**
     * Sets how long this loop, once told to shut down, waits for its
     * handlers to finish closing before closing them regardless. The loop
     * never lingers beyond this bound however its peers behave. Takes
     * effect for shutdowns that begin after the call.
     *
     * @param ms the bound in milliseconds
     */
    void setCloseDeadlineMs(long ms) {
        this.closeDeadlineMs = ms;
    }

    /**
     * Carries a requested shutdown one step further. Runs on the loop
     * thread. Starts the close of every owned handler the first time, and
     * applies an abort that was requested while an orderly close was
     * waiting.
     *
     * @return true when the loop should exit now
     */
    private boolean advanceShutdown() {
        int mode = shutdownMode.get();
        long now = clockMillis();
        if (!closeStarted) {
            closeStarted = true;
            closeDeadlineAt = now + closeDeadlineMs;
            abortApplied = (mode == MODE_ABORT);
            closeOwned(abortApplied);
        } else if (mode == MODE_ABORT && !abortApplied) {
            abortApplied = true;
            closeOwned(true);
        }
        if (openHandlerCount() == 0 && pendingRegistrations.isEmpty()
                && pendingTasks.isEmpty()) {
            return true;
        }
        if (now >= closeDeadlineAt) {
            int remaining = openHandlerCount();
            if (remaining > 0 && LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning(MessageFormat.format(
                        L10N.getString("log.loop_close_deadline_exceeded"),
                        Integer.valueOf(index), Integer.valueOf(remaining),
                        Long.valueOf(closeDeadlineMs)));
            }
            closeOwned(true);
            return true;
        }
        return false;
    }

    private int openHandlerCount() {
        int count = 0;
        for (SelectionKey key : selector.keys()) {
            if (key.isValid()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Asks every handler this loop owns to close, then, for an abort,
     * cancels and closes the channel of any that did not.
     */
    private void closeOwned(boolean abort) {
        List<SelectionKey> owned = new ArrayList<SelectionKey>(selector.keys());
        for (int i = 0; i < owned.size(); i++) {
            SelectionKey key = owned.get(i);
            if (key.isValid()) {
                closeOne(key, abort);
            }
        }
    }

    private void closeOne(SelectionKey key, boolean abort) {
        Object attachment = key.attachment();
        if (attachment instanceof ChannelHandler) {
            try {
                ((ChannelHandler) attachment).closeForShutdown(!abort);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        L10N.getString("log.error_closing_on_shutdown"), e);
            }
        }
        if (abort && key.isValid()) {
            try {
                key.channel().close();
            } catch (IOException e) {
                // the key is cancelled below regardless
            }
            key.cancel();
        }
    }

    /**
     * The loop's last act, on its own thread: runs the work still queued
     * exactly once, closes what that left open, rejects anything arriving
     * from now on, and releases the selector.
     */
    private void terminate() {
        try {
            drainQueues();
            flushClosingEndpoints();
            if (selector != null) {
                closeOwned(true);
            }
        } finally {
            // From here on offered work is rejected. Anything that slipped
            // in before the flag flipped is run by the second drain, so no
            // task is ever both accepted and dropped.
            terminated = true;
            try {
                drainQueues();
                if (selector != null) {
                    closeOwned(true);
                }
            } finally {
                pendingTimers.clear();
                timer.shutdown();
                if (selector != null) {
                    try {
                        selector.close();
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, L10N.getString("log.error_closing_selector_1"), e);
                    }
                    selector = null;
                }
            }
        }
    }

    /**
     * Runs the queued tasks and disposes of registrations that never got a
     * selector key: their handlers are closed (never silently dropped).
     */
    private void drainQueues() {
        PendingRegistration reg;
        while ((reg = pendingRegistrations.poll()) != null) {
            rejectRegistration(reg);
        }
        processPendingTasks();
    }

    /**
     * Gives each endpoint that has asked to close, but is waiting for its
     * output to drain, one last non-blocking attempt to write it.
     */
    private void flushClosingEndpoints() {
        if (selector == null) {
            return;
        }
        List<SelectionKey> owned = new ArrayList<SelectionKey>(selector.keys());
        for (int i = 0; i < owned.size(); i++) {
            SelectionKey key = owned.get(i);
            Object attachment = key.attachment();
            if (key.isValid() && attachment instanceof TcpEndpoint
                    && ((TcpEndpoint) attachment).closeRequested) {
                try {
                    doTcpEndpointWrite(key, (TcpEndpoint) attachment);
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING,
                            L10N.getString("log.error_closing_on_shutdown"), e);
                }
            }
        }
    }

    /**
     * Closes the handler and channel of a registration this loop will not
     * service. May run on any thread: the handler has not been registered,
     * so nothing else touches it.
     */
    private void rejectRegistration(PendingRegistration reg) {
        if (LOGGER.isLoggable(Level.WARNING)) {
            LOGGER.warning(MessageFormat.format(
                    L10N.getString("log.registration_rejected_loop_terminated"),
                    Integer.valueOf(index)));
        }
        try {
            reg.handler.closeForShutdown(false);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    L10N.getString("log.error_closing_on_shutdown"), e);
        }
        try {
            reg.channel.close();
        } catch (IOException e) {
            // already closed or unclosable; nothing more to do
        }
    }

    private void processPendingRegistrations() {
        PendingRegistration reg;
        while ((reg = pendingRegistrations.poll()) != null) {
            try {
                int ops = reg.connect ? SelectionKey.OP_CONNECT : SelectionKey.OP_READ;
                SelectionKey key;
                try {
                    key = reg.channel.register(selector, ops);
                } catch (CancelledKeyException e) {
                    // The channel was handed off (e.g. an FTP active-mode
                    // data connection) and re-registered before the
                    // selector had deregistered its cancelled key. A
                    // selection pass flushes the cancelled-key set, after
                    // which the channel can be registered afresh.
                    try {
                        selector.selectNow();
                    } catch (IOException io) {
                        LOGGER.log(Level.WARNING,
                                L10N.getString("log.error_in_selector_loop"), io);
                    }
                    key = reg.channel.register(selector, ops);
                }
                key.attach(reg.handler);
                reg.handler.setSelectionKey(key);
                reg.handler.setSelectorLoop(this);
                if (closeStarted) {
                    // Arrived after this loop began closing what it owns:
                    // it is closed too, not served.
                    closeOne(key, abortApplied);
                }
            } catch (ClosedChannelException e) {
                // Channel was closed before we could register
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(L10N.getString("log.channel_closed_before_registration"));
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
                    LOGGER.log(Level.WARNING, L10N.getString("log.error_in_timer_callback"), e);
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
                LOGGER.log(Level.WARNING, L10N.getString("log.error_in_pending_task"), e);
            }
        }
    }

    /**
     * Closes or cancels the handler that threw during per-key I/O dispatch
     * so one connection's bug cannot leave inconsistent state registered
     * on this loop. Matches the defensive pattern used by
     * {@link #processPendingTimers()} and {@link #processPendingTasks()}.
     */
    private void isolateFailedHandler(SelectionKey key, ChannelHandler handler,
            Exception cause) {
        if (handler != null) {
            try {
                switch (handler.getChannelType()) {
                    case TCP:
                        ((TcpEndpoint) handler).handleDispatchError(cause);
                        return;
                    case DATAGRAM_SERVER:
                    case DATAGRAM_CLIENT:
                        ((UdpEndpoint) handler).close();
                        return;
                    case QUIC:
                        ((QuicEngine) handler).close();
                        return;
                }
            } catch (Exception closeError) {
                LOGGER.log(Level.WARNING,
                        L10N.getString("log.error_isolating_failed_handler"), closeError);
            }
        }
        if (key != null && key.isValid()) {
            key.cancel();
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
                doTcpEndpointRead(key, (TcpEndpoint) handler);
                break;
            case DATAGRAM_SERVER:
            case DATAGRAM_CLIENT:
                doUDPEndpointRead(key, (UdpEndpoint) handler);
                break;
            case QUIC:
                doQuicRead(key, (QuicEngine) handler);
                break;
        }
    }

    private void doWrite(SelectionKey key, ChannelHandler handler) {
        switch (handler.getChannelType()) {
            case TCP:
                doTcpEndpointWrite(key, (TcpEndpoint) handler);
                break;
            case DATAGRAM_SERVER:
            case DATAGRAM_CLIENT:
                doUDPEndpointWrite(key, (UdpEndpoint) handler);
                break;
            case QUIC:
                doQuicWrite(key, (QuicEngine) handler);
                break;
        }
    }

    // -- TcpEndpoint methods --

    private void doTcpEndpointRead(SelectionKey key, TcpEndpoint endpoint) {
        SocketChannel sc = (SocketChannel) key.channel();

        try {
            // Read straight into the endpoint's netIn buffer. This avoids
            // copying through a shared scratch buffer on every read. The
            // returned buffer is in write mode with room to read into.
            ByteBuffer netIn = endpoint.prepareNetInForRead();
            int len = sc.read(netIn);

            if (len == -1) {
                endpoint.handleEOF();
            } else if (len > 0) {
                if (LOGGER.isLoggable(Level.FINEST)) {
                    Object sa = sc.socket().getRemoteSocketAddress();
                    String message = Gumdrop.L10N.getString("info.received");
                    message = MessageFormat.format(message, len, sa);
                    LOGGER.finest(message);
                }

                netIn.flip();
                endpoint.processInbound();
            }
        } catch (IOException e) {
            endpoint.handleReadError(e);
        }
    }

    private void doTcpEndpointWrite(SelectionKey key, TcpEndpoint endpoint) {
        SocketChannel sc = (SocketChannel) key.channel();

        try {
            synchronized (endpoint.netOutLock) {
                // Fetch netOut inside the lock: a concurrent appendToNetOut()
                // may have grown (replaced) it, and a concurrent close may
                // have released and nulled it.
                ByteBuffer netOut = endpoint.getNetOut();
                if (netOut == null) {
                    return;
                }
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

            Runnable writeCallback = endpoint.getWriteCompleteCallback();
            if (writeCallback != null) {
                endpoint.setWriteCompleteCallback(null);
                writeCallback.run();
            }

        } catch (IOException e) {
            endpoint.handleWriteError(e);
        }
    }

    private void doTcpEndpointConnect(SelectionKey key, TcpEndpoint endpoint) {
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

    // -- UdpEndpoint methods --

    private void doUDPEndpointRead(SelectionKey key,
                                         UdpEndpoint endpoint) {
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
            logDatagramEndpointReadFailure(e);
            endpoint.close();
        }
    }

    private void logDatagramEndpointReadFailure(IOException e) {
        Level level = datagramReadFailureLogLevel(e);
        if (!LOGGER.isLoggable(level)) {
            return;
        }
        LOGGER.log(level, L10N.getString("log.error_reading_datagram_endpoint"), e);
    }

    /**
     * ICMP port unreachable on a connected UDP socket is normal when the peer
     * is down; the endpoint is still closed so handlers can fail fast.
     */
    private static Level datagramReadFailureLogLevel(IOException e) {
        if (e instanceof PortUnreachableException) {
            return Level.FINE;
        }
        if (e instanceof ClosedChannelException) {
            return Level.FINE;
        }
        return Level.WARNING;
    }

    private void doUDPEndpointWrite(SelectionKey key,
                                          UdpEndpoint endpoint) {
        DatagramChannel dc = (DatagramChannel) key.channel();

        try {
            UdpEndpoint.PendingDatagram pending;
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
                endpoint.onPendingDatagramFullySent(pending);
            }

            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);

        } catch (IOException e) {
            // A channel closed under a pending write (the endpoint was
            // closed from another thread) is routine teardown.
            Level level = Level.WARNING;
            if (e instanceof ClosedChannelException) {
                level = Level.FINE;
            }
            LOGGER.log(level,
                    L10N.getString("log.error_writing_datagram_endpoint"), e);
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
     * Registers a TcpEndpoint with this SelectorLoop.
     * Thread-safe.
     *
     * @param channel the socket channel
     * @param endpoint the TcpEndpoint
     */
    void register(SocketChannel channel, TcpEndpoint endpoint) {
        // Set synchronously, on the calling thread, rather than waiting for
        // processPendingRegistrations() to run on this loop's own thread --
        // a handler that calls scheduleTimer() (whose default ChannelHandler
        // implementation needs getSelectorLoop() to route to this loop's own
        // timer) immediately after registering, before this loop's thread
        // has had a chance to process the registration, would otherwise see
        // a null loop and fall through to Gumdrop's shared timer, which is
        // never started outside of a full Gumdrop.start() server lifecycle.
        endpoint.setSelectorLoop(this);
        enqueueRegistration(new PendingRegistration(channel, endpoint, false));
    }

    /**
     * Registers a TcpEndpoint for CONNECT events.
     * Thread-safe.
     *
     * @param channel the socket channel
     * @param endpoint the TcpEndpoint
     */
    void registerForConnect(SocketChannel channel, TcpEndpoint endpoint) {
        endpoint.setSelectorLoop(this);
        enqueueRegistration(new PendingRegistration(channel, endpoint, true));
    }

    /**
     * Registers a TcpEndpoint with this SelectorLoop for OP_READ.
     * Thread-safe.  The endpoint must already have its channel set.
     *
     * @param channel the socket channel (must be non-blocking)
     * @param endpoint the TCP endpoint
     */
    public void registerTCP(SocketChannel channel, TcpEndpoint endpoint) {
        register(channel, endpoint);
    }

    /**
     * Registers a datagram channel with this SelectorLoop.
     * Thread-safe. If this loop has terminated, the channel is closed and
     * the handler told to close (abort) instead.
     *
     * @param channel the datagram channel
     * @param handler the datagram server or client
     */
    public void registerDatagram(DatagramChannel channel, ChannelHandler handler) {
        // See the identical comment in register(SocketChannel, TcpEndpoint).
        handler.setSelectorLoop(this);
        enqueueRegistration(new PendingRegistration(channel, handler, false));
    }

    /**
     * Queues a registration for the loop thread. If this loop has already
     * terminated nobody will service it, so the handler and channel are
     * closed instead, on the calling thread (the handler has not been
     * registered, so no other thread touches it) and a warning is logged.
     */
    private void enqueueRegistration(PendingRegistration reg) {
        pendingRegistrations.add(reg);
        if (terminated && pendingRegistrations.remove(reg)) {
            rejectRegistration(reg);
            return;
        }
        wakeup();
    }

    // -- Write request methods --

    /**
     * Schedules a task to run on this SelectorLoop thread, without
     * reporting whether it was accepted. See {@link #tryInvokeLater} for the
     * semantics; this is the form to use when there is nothing a caller
     * could do about a rejection (a rejection is logged as a warning).
     *
     * @param task the task to execute
     */
    public void invokeLater(Runnable task) {
        tryInvokeLater(task);
    }

    /**
     * Schedules a task to run on this SelectorLoop thread.
     * If called from this thread, the task is executed immediately.
     * Otherwise, it is queued and the selector is woken up.
     *
     * <p>Returns {@code true} when the task has run or will run: tasks
     * still queued when the loop is told to shut down run before it exits.
     * Returns {@code false}, without running the task, only once the loop
     * has terminated and can never run it; a warning is logged. The caller
     * decides what that means for it (a handler that posts its own close,
     * for example, has nothing left to close, since the loop closed
     * everything it owned). It never throws, so timers and completion
     * callbacks racing a shutdown are not disturbed.
     *
     * @param task the task to execute
     * @return false if the loop has terminated and the task was not accepted
     */
    public boolean tryInvokeLater(Runnable task) {
        if (Thread.currentThread() == thread) {
            // We're on the SelectorLoop thread, execute immediately
            try {
                task.run();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, L10N.getString("log.error_in_invokelater_task"), e);
            }
            return true;
        }
        // Queue for execution on next selector wakeup
        pendingTasks.offer(task);
        if (terminated && pendingTasks.remove(task)) {
            // The final drain has finished (or, in the one case where it
            // has already taken this task, remove() fails and the task
            // runs): the task can never run, so reject it visibly.
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning(MessageFormat.format(
                        L10N.getString("log.task_rejected_loop_terminated"),
                        Integer.valueOf(index)));
            }
            return false;
        }
        wakeup();
        return true;
    }

    /** Queues a loop-internal task, dropping it quietly if the loop is gone. */
    private void post(Runnable task) {
        pendingTasks.offer(task);
        if (terminated && pendingTasks.remove(task)) {
            return;
        }
        wakeup();
    }

    /**
     * Removes OP_READ interest for a TcpEndpoint (backpressure).
     * May be called from any thread.
     *
     * @param endpoint the endpoint to pause reading
     */
    void cancelRead(TcpEndpoint endpoint) {
        SelectionKey key = endpoint.getSelectionKey();
        if (key != null && key.isValid()) {
            if (Thread.currentThread() == thread) {
                try {
                    key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
                } catch (CancelledKeyException e) {
                    // closed concurrently; nothing to pause
                }
            } else {
                post(new CancelReadTask(key));
            }
        }
    }

    /**
     * Adds OP_READ interest for a TcpEndpoint (resume after pause).
     * May be called from any thread.
     *
     * @param endpoint the endpoint to resume reading
     */
    void requestRead(TcpEndpoint endpoint) {
        SelectionKey key = endpoint.getSelectionKey();
        if (key != null && key.isValid()) {
            if (Thread.currentThread() == thread) {
                try {
                    key.interestOps(key.interestOps() | SelectionKey.OP_READ);
                } catch (CancelledKeyException e) {
                    // closed concurrently; nothing to resume
                }
            } else {
                post(new RequestReadTask(key));
            }
        }
    }

    /**
     * Requests OP_WRITE interest for a TcpEndpoint.
     * May be called from any thread.
     *
     * @param endpoint the endpoint with pending data
     */
    void requestWrite(TcpEndpoint endpoint) {
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
            // Another thread (endpoint close, peer reset handled on the
            // selector thread) may cancel the key between isValid() and
            // interestOps(): the handler is then closed, so there is
            // nothing left to write.
            try {
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
            } catch (CancelledKeyException e) {
                return;
            }

            // Wake up selector if called from a different thread
            if (Thread.currentThread() != thread) {
                if (selector != null) {
                    selector.wakeup();
                }
            }
        }
    }

    /**
     * Returns the thread that runs this SelectorLoop, or null if not started.
     *
     * @return the loop thread
     */
    public Thread getThread() {
        return thread;
    }

    /**
     * Wakes up the selector if it is currently blocked in a select call.
     * Safe to call from any thread.
     */
    public void wakeup() {
        if (selector != null) {
            selector.wakeup();
        }
    }

    /**
     * Shuts this loop down in an orderly way. Returns immediately; the
     * work happens on the loop's own thread. Every handler the loop owns
     * is closed with its protocol goodbyes, queued tasks are run, and the
     * loop exits once nothing is left open or its hard deadline passes.
     * Idempotent, and a no-op for a loop that is already shutting down
     * (or has been told to abort).
     */
    public void shutdown() {
        requestShutdown(MODE_ORDERLY);
    }

    /**
     * Aborts this loop: every owned handler is closed at once with no
     * goodbyes and queued output is discarded; then the loop exits. If an
     * orderly {@link #shutdown()} is still waiting on its handlers this
     * escalates it. Returns immediately; idempotent.
     */
    void shutdownNow() {
        requestShutdown(MODE_ABORT);
    }

    private void requestShutdown(int mode) {
        int current = shutdownMode.get();
        while (current < mode) {
            if (shutdownMode.compareAndSet(current, mode)) {
                break;
            }
            current = shutdownMode.get();
        }
        if (!isRunning()) {
            // No thread will ever run the loop's own shutdown; at least
            // stop its timer thread.
            timer.shutdown();
        }
        wakeup();
    }

    /**
     * Returns whether this loop has finished shutting down: it has run its
     * final drain and now rejects offered work.
     *
     * @return true once terminated
     */
    boolean isTerminated() {
        return terminated;
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
     * Waits up to {@code timeoutMs} for this loop's thread to finish after
     * {@link #shutdown()} has been signalled, letting it flush any in-flight
     * writes and exit cleanly rather than being abandoned. Returns immediately
     * if the loop is not running or if called from this loop's own thread
     * (a thread cannot usefully wait for itself).
     *
     * @param timeoutMs the maximum time to wait, in milliseconds
     * @return true if the loop thread has terminated
     */
    public boolean awaitQuiesce(long timeoutMs) {
        Thread t = thread;
        if (t == null || t == Thread.currentThread()) {
            return t == null || !t.isAlive();
        }
        try {
            t.join(Math.max(1L, timeoutMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !t.isAlive();
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

    /**
     * Task to remove OP_READ interest on the SelectorLoop thread.
     */
    private static class CancelReadTask implements Runnable {
        private final SelectionKey key;

        CancelReadTask(SelectionKey key) {
            this.key = key;
        }

        @Override
        public void run() {
            if (key.isValid()) {
                try {
                    key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
                } catch (CancelledKeyException e) {
                    // closed concurrently
                }
            }
        }
    }

    /**
     * Task to add OP_READ interest on the SelectorLoop thread.
     */
    private static class RequestReadTask implements Runnable {
        private final SelectionKey key;

        RequestReadTask(SelectionKey key) {
            this.key = key;
        }

        @Override
        public void run() {
            if (key.isValid()) {
                try {
                    key.interestOps(key.interestOps() | SelectionKey.OP_READ);
                } catch (CancelledKeyException e) {
                    // closed concurrently
                }
            }
        }
    }

}
