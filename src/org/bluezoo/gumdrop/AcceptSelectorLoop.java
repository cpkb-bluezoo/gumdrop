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

import org.bluezoo.gumdrop.telemetry.EventLogger;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.ResourceBundle;
/**
 * Selector loop dedicated to accepting new connections.
 * Handles OP_ACCEPT events for all ServerSocketChannels and hands off
 * new connections to worker SelectorLoops.
 *
 * <p>This loop owns the listening sockets of every {@link TcpListener}
 * and every raw acceptor registered with it, and closes them itself, on
 * its own thread. {@link #stopAccepting()} releases the listeners'
 * sockets (the loop keeps running, so raw acceptors that in-flight
 * transfers still need stay usable); {@link #shutdown()} closes
 * everything that remains and exits. Neither returns before the sockets
 * are really released where the caller is told so: closing a channel
 * registered with a selector from another thread only cancels its key, and
 * the JDK releases the socket when the selector next deregisters it.
 *
 * <p>Once the loop has terminated, a registration offered to it is
 * refused deterministically: a raw acceptor's channel is closed and a
 * listener is reported through {@link Gumdrop#getBindFailures()}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AcceptSelectorLoop implements Runnable {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.L10N");

    private static final Logger LOGGER = Logger.getLogger(AcceptSelectorLoop.class.getName());

    /**
     * How long to pause the accept thread after a file-descriptor exhaustion
     * (EMFILE/ENFILE). Accepting again immediately would spin hot because the
     * pending connection keeps the selector readable, producing a CPU and log
     * storm. The accept thread's only job is accepting, so a brief pause while
     * descriptors free up is harmless.
     */
    private static final long ACCEPT_BACKOFF_MS = 1000L;

    /** Upper bound on how long a caller waits for the accept thread to service a close request. */
    private static final long REQUEST_WAIT_NANOS = TimeUnit.SECONDS.toNanos(10L);

    /**
     * Handler for raw socket channel accepts.
     * Used by subsystems like FTP data that need the raw channel
     * without the endpoint/connection infrastructure.
     *
     * <p>{@code sc} arrives in non-blocking mode, on the single accept
     * thread shared by every listener in the process. {@code accepted}
     * must not perform blocking I/O on it (or otherwise block) --
     * doing so would stall acceptance of new connections on every
     * other listener until it returns. Queue the channel or hand it
     * off to another thread/{@link SelectorLoop} instead, the way
     * {@code FtpClientDataConnectionCoordinator} does.
     */
    public interface RawAcceptHandler {
        void accepted(SocketChannel sc) throws IOException;
    }

    private final Gumdrop gumdrop;
    private final EventLogger events;
    private Thread thread;
    private volatile Selector selector;
    private volatile boolean active;
    // True once the final drain has finished: registrations offered from
    // then on are refused rather than queued.
    private volatile boolean terminated;
    // Loop thread only: set when stopAccepting() has run, after which
    // queued listener registrations are not bound.
    private boolean acceptingStopped;
    private final ConcurrentLinkedQueue<PendingRegistration> pendingRegistrations;
    private volatile Runnable readyCallback;

    /** Listeners that could not be bound, as "description: reason". */
    private final List<String> bindFailures = new CopyOnWriteArrayList<String>();

    AcceptSelectorLoop(Gumdrop gumdrop) {
        this.gumdrop = gumdrop;
        // A loop created outside any runtime, as in tests, reports through
        // a configuration of its own.
        TelemetryConfig telemetry = gumdrop != null ? gumdrop.getTelemetryConfig() : new TelemetryConfig();
        this.events = telemetry.getLogger(AcceptSelectorLoop.class, L10N);
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
        // Set before the thread runs, not in run(): a shutdown() that
        // arrives before the new thread is scheduled must not be undone
        // by run() setting the flag afterwards (join() would then wait
        // forever).
        active = true;
        terminated = false;
        acceptingStopped = false;
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
        try {
            selector = Selector.open();

            // Bind all listeners queued before the selector was open
            processPendingRegistrations();

            // Main accept loop
            while (active) {
                try {
                    // Read the callback BEFORE draining registrations: a
                    // callback is always installed after the registration it
                    // reports on was queued, so seeing it here guarantees
                    // that registration is processed below before it fires.
                    Runnable cb = readyCallback;

                    // Process any pending server registrations
                    processPendingRegistrations();

                    // Fire the ready callback once after initial registrations are bound
                    if (cb != null) {
                        if (readyCallback == cb) {
                            readyCallback = null;
                        }
                        cb.run();
                    }

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
                        events.warn("log.error_in_accept_loop").thrown(e).emit();
                    }
                }
            }
        } catch (IOException e) {
            events.error("log.failed_to_initialize_acceptselectorloop").thrown(e).emit();
        } finally {
            terminate();
        }
    }

    /**
     * The loop's last act, on its own thread: closes every listening socket
     * it still owns, services or refuses what is still queued, rejects
     * anything arriving from now on, and releases the selector.
     */
    private void terminate() {
        try {
            closeOwnedSockets();
            drainRegistrations();
        } finally {
            // Anything that slipped in before the flag flipped is handled
            // by the second drain, so a request is never accepted and lost.
            terminated = true;
            try {
                drainRegistrations();
            } finally {
                if (selector != null) {
                    try {
                        selector.close();
                    } catch (IOException e) {
                        events.warn("log.error_closing_selector").thrown(e).emit();
                    }
                    selector = null;
                }
            }
        }
    }

    /** Closes the listeners' and raw acceptors' sockets registered here. */
    private void closeOwnedSockets() {
        if (selector == null) {
            return;
        }
        List<SelectionKey> owned = new ArrayList<SelectionKey>(selector.keys());
        for (int i = 0; i < owned.size(); i++) {
            SelectionKey key = owned.get(i);
            Object attachment = key.attachment();
            if (attachment instanceof TcpListener) {
                ((TcpListener) attachment).closeServerChannels();
            } else if (key.channel() instanceof ServerSocketChannel) {
                closeQuietly((ServerSocketChannel) key.channel());
            }
            key.cancel();
        }
    }

    private enum Kind {
        /** Bind and register a {@link TcpListener}. */
        BIND_LISTENER,
        /** Register an already-bound raw acceptor. */
        RAW_ACCEPT,
        /** Close one raw acceptor's socket and flush its key. */
        CLOSE_RAW,
        /** Close the listening sockets of one listener, or of all when null. */
        RELEASE_LISTENERS
    }

    /**
     * A request queued for the accept thread: a listener to bind, a raw
     * acceptor to register, or a close to perform, the latter carrying a
     * latch the requester waits on.
     */
    private static class PendingRegistration {
        final Kind kind;
        final TcpListener listener;
        final RawAcceptHandler rawHandler;
        final ServerSocketChannel channel;
        final CountDownLatch done; // non-null for a close request

        PendingRegistration(TcpListener listener) {
            this.kind = Kind.BIND_LISTENER;
            this.listener = listener;
            this.rawHandler = null;
            this.channel = null;
            this.done = null;
        }

        PendingRegistration(ServerSocketChannel channel, CountDownLatch done) {
            this.kind = Kind.CLOSE_RAW;
            this.listener = null;
            this.rawHandler = null;
            this.channel = channel;
            this.done = done;
        }

        PendingRegistration(TcpListener listener, CountDownLatch done) {
            this.kind = Kind.RELEASE_LISTENERS;
            this.listener = listener;
            this.rawHandler = null;
            this.channel = null;
            this.done = done;
        }

        PendingRegistration(RawAcceptHandler handler, ServerSocketChannel channel) {
            this.kind = Kind.RAW_ACCEPT;
            this.listener = null;
            this.rawHandler = handler;
            this.channel = channel;
            this.done = null;
        }
    }

    /**
     * Sets a callback to be invoked once on the accept loop thread after
     * the initial batch of pending listener registrations has been processed.
     *
     * @param callback the callback to invoke when all listeners are bound
     */
    void onReady(Runnable callback) {
        this.readyCallback = callback;
        if (selector != null) {
            selector.wakeup();
        }
    }

    /**
     * Registers an endpoint server for accepting connections.
     *
     * @param server the endpoint server to register
     */
    public void registerListener(TcpListener server) {
        enqueue(new PendingRegistration(server));
    }

    /**
     * Registers a raw accept handler for an already-bound ServerSocketChannel.
     * The handler receives raw, non-blocking SocketChannels without
     * endpoint/connection infrastructure. Used by subsystems like FTP
     * data that need the channel itself rather than a full endpoint.
     * If this loop has terminated, the channel is closed instead.
     *
     * @param channel an already-bound ServerSocketChannel
     * @param handler the handler to receive accepted connections
     */
    public void registerRawAcceptor(ServerSocketChannel channel, RawAcceptHandler handler) {
        if (!channel.isOpen()) {
            return;
        }
        enqueue(new PendingRegistration(handler, channel));
    }

    /**
     * Queues a request for the accept thread. If the loop has already
     * terminated nobody will service it, so it is refused on the spot.
     */
    private void enqueue(PendingRegistration pending) {
        pendingRegistrations.add(pending);
        if (terminated && pendingRegistrations.remove(pending)) {
            refuse(pending);
            return;
        }
        wakeup();
    }

    private void wakeup() {
        Selector sel = selector;
        if (sel != null) {
            sel.wakeup();
        }
    }

    /**
     * Unregisters and closes a raw acceptor's listening socket, returning
     * only once the socket has really been released.
     *
     * <p>Closing a channel that is registered with a selector from another
     * thread merely cancels its key; the JDK defers the actual socket close
     * until the selector thread next deregisters the key, so the port would
     * keep accepting connections for a while. This method performs the
     * close on the accept thread (flushing the cancelled key) and waits for
     * it. Called from the accept thread itself (e.g. from within a {@link
     * RawAcceptHandler}), it closes in place and does not wait; the key is
     * deregistered when the loop next selects, immediately afterwards.
     *
     * @param channel the channel previously passed to {@link
     *      #registerRawAcceptor}
     */
    public void closeRawAcceptor(ServerSocketChannel channel) {
        if (Thread.currentThread() == thread) {
            closeQuietly(channel);
            return;
        }
        Selector sel = selector;
        if (!isRunning() || sel == null) {
            closeQuietly(channel);
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        enqueue(new PendingRegistration(channel, done));
        if (!awaitDone(done)) {
            closeQuietly(channel);
        }
    }

    /**
     * Stops accepting connections for every {@link TcpListener}: their
     * listening sockets are closed on the accept thread and really
     * released before this method returns, so no new connection is
     * admitted from that point on. The loop itself keeps running (raw
     * acceptors, such as an FTP transfer's data listener, stay usable)
     * until {@link #shutdown()}. Listeners queued but not yet bound are
     * not bound afterwards. A no-op returning at once for a loop that is
     * not running; bounded otherwise.
     */
    void stopAccepting() {
        releaseListeners(null);
    }

    /**
     * Closes one listener's listening sockets on the accept thread and
     * returns once they are really released, or at once if this loop is
     * not running (in which case the caller closes them itself).
     *
     * @param listener the listener whose sockets to release
     * @return false if the loop was not running and nothing was done
     */
    boolean releaseListener(TcpListener listener) {
        return releaseListeners(listener);
    }

    private boolean releaseListeners(TcpListener listener) {
        if (Thread.currentThread() == thread) {
            doReleaseListeners(listener);
            return true;
        }
        if (!isRunning() || selector == null) {
            return false;
        }
        CountDownLatch done = new CountDownLatch(1);
        enqueue(new PendingRegistration(listener, done));
        return awaitDone(done);
    }

    /**
     * Waits for the accept thread to service a close request, bounded: it
     * gives up if the loop exits (its final drain services the request) or
     * the bound passes, so a caller can never be left waiting.
     *
     * @return true if the request was serviced
     */
    private boolean awaitDone(CountDownLatch done) {
        long deadline = System.nanoTime() + REQUEST_WAIT_NANOS;
        try {
            // Poll so that a loop that exits before servicing the request
            // cannot leave this caller waiting.
            while (!done.await(100L, TimeUnit.MILLISECONDS)) {
                if (!isRunning() || System.nanoTime() - deadline > 0L) {
                    return done.getCount() == 0L;
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Whether the channel still holds a key in this loop's selector. Once a
     * close has been flushed this is false, which is what lets the JDK
     * release the underlying socket. Package-private for tests.
     */
    boolean isRegistered(ServerSocketChannel channel) {
        Selector sel = selector;
        return sel != null && channel.keyFor(sel) != null;
    }

    private static void closeQuietly(ServerSocketChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            // Ignore close errors
        }
    }

    /**
     * Services a close request on the accept thread: closes the channel,
     * then flushes the cancelled key so the socket is released before the
     * requester is released.
     */
    private void doCloseRawAcceptor(ServerSocketChannel channel, CountDownLatch done) {
        closeQuietly(channel);
        flushCancelledKeys();
        done.countDown();
    }

    /**
     * Closes the listening sockets of {@code only}, or of every listener
     * when it is null, then flushes the cancelled keys so they are
     * released. Accept thread only.
     */
    private void doReleaseListeners(TcpListener only) {
        if (only == null) {
            acceptingStopped = true;
        }
        Selector sel = selector;
        if (sel != null) {
            List<SelectionKey> owned = new ArrayList<SelectionKey>(sel.keys());
            for (int i = 0; i < owned.size(); i++) {
                Object attachment = owned.get(i).attachment();
                if (attachment instanceof TcpListener
                        && (only == null || attachment == only)) {
                    ((TcpListener) attachment).closeServerChannels();
                }
            }
        }
        if (only != null) {
            // A registration for it still queued must not bind afterwards.
            for (Iterator<PendingRegistration> i = pendingRegistrations.iterator(); i.hasNext(); ) {
                PendingRegistration queued = i.next();
                if (queued.kind == Kind.BIND_LISTENER && queued.listener == only) {
                    i.remove();
                }
            }
            only.closeServerChannels();
        }
        flushCancelledKeys();
    }

    private void flushCancelledKeys() {
        Selector sel = selector;
        if (sel == null) {
            return;
        }
        try {
            sel.selectNow();
        } catch (IOException e) {
            // The loop's own select will deregister the keys
        }
    }

    /**
     * Refuses a request offered to a loop that can no longer service it:
     * channels are closed and waiters released, listeners are reported.
     */
    private void refuse(PendingRegistration pending) {
        switch (pending.kind) {
            case RAW_ACCEPT:
                closeQuietly(pending.channel);
                break;
            case CLOSE_RAW:
                closeQuietly(pending.channel);
                pending.done.countDown();
                break;
            case RELEASE_LISTENERS:
                if (pending.listener != null) {
                    pending.listener.closeServerChannels();
                }
                pending.done.countDown();
                break;
            case BIND_LISTENER:
                bindFailures.add(pending.listener.getDescription() + ": "
                        + L10N.getString("log.accept_loop_terminated"));
                events.warn("log.registration_refused_accept_loop_terminated")
                        .attr("listener", pending.listener.getDescription()).emit();
                break;
        }
    }

    /**
     * Services what is still queued as the loop exits: close requests are
     * performed so their requesters are released, and registrations that
     * will never be served are refused.
     */
    private void drainRegistrations() {
        PendingRegistration pending;
        while ((pending = pendingRegistrations.poll()) != null) {
            refuse(pending);
        }
    }

    /**
     * Processes pending server registrations on the selector thread.
     */
    private void processPendingRegistrations() {
        PendingRegistration pending;
        while ((pending = pendingRegistrations.poll()) != null) {
            try {
                switch (pending.kind) {
                    case CLOSE_RAW:
                        doCloseRawAcceptor(pending.channel, pending.done);
                        break;
                    case RELEASE_LISTENERS:
                        doReleaseListeners(pending.listener);
                        pending.done.countDown();
                        break;
                    case RAW_ACCEPT:
                        doRegisterRawAcceptor(pending.rawHandler, pending.channel);
                        break;
                    case BIND_LISTENER:
                        if (acceptingStopped) {
                            if (LOGGER.isLoggable(Level.FINE)) {
                                LOGGER.fine(MessageFormat.format(
                                        L10N.getString("log.listener_not_bound_accept_stopped"),
                                        pending.listener.getDescription()));
                            }
                        } else {
                            doRegisterListener(pending.listener);
                        }
                        break;
                }
            } catch (ClosedChannelException e) {
                // FTP client PORT/EPRT and similar paths may close the
                // ServerSocketChannel before this loop drains the pending
                // registration queue; that is normal, not a server fault.
                if (pending.rawHandler == null) {
                    bindFailures.add(pending.listener.getDescription() + ": " + e);
                    events.error("log.failed_to_register_server")
                            .attr("listener", pending.listener.getDescription())
                            .attr("reason", e.getMessage()).thrown(e).emit();
                } else if (pending.rawHandler != null
                        && LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(L10N.getString("log.raw_acceptor_closed_before_registration"));
                }
            } catch (IOException e) {
                registrationFailed(pending, e);
            } catch (RuntimeException e) {
                // A misconfigured listener (for example one with neither a
                // port nor a socket path) must fail alone, not end this
                // thread and with it every other listener's accepting.
                registrationFailed(pending, e);
            }
        }
    }

    private void registrationFailed(PendingRegistration pending, Exception e) {
        String desc;
        if (pending.rawHandler != null) {
            desc = L10N.getString("log.raw_acceptor_desc");
        } else {
            desc = pending.listener.getDescription();
        }
        if (pending.rawHandler == null) {
            bindFailures.add(desc + ": " + e.getMessage());
        }
        events.error("log.failed_to_register_server")
                .attr("listener", desc).attr("reason", e.getMessage()).thrown(e).emit();
    }

    /**
     * Returns the listeners this loop could not bind, each as
     * {@code description: reason}. Raw acceptors, whose channel is already
     * bound by their owner, are not included.
     *
     * @return the failures so far, empty if every listener bound
     */
    List<String> getBindFailures() {
        return new ArrayList<String>(bindFailures);
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
            LOGGER.fine(MessageFormat.format(L10N.getString("log.registered_raw_accept_handler_on_port_0"), addr.getPort()));
        }
    }

    private void doRegisterListener(TcpListener server)
            throws IOException {
        Path socketPath = server.getPath();
        if (socketPath != null) {
            doRegisterUnixListener(server, socketPath);
        } else {
            doRegisterTcpListener(server);
        }
    }

    private void doRegisterUnixListener(TcpListener server, Path path)
            throws IOException {
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
                        server.getDescription(), path, (t2 - t1));
            } else {
                message = server.getDescription() + " bound to " + path
                        + " (" + (t2 - t1) + " ms)";
            }
            LOGGER.fine(message);
        }

        SelectionKey key = ssc.register(selector, SelectionKey.OP_ACCEPT);
        key.attach(server);

        server.addServerChannel(ssc);
    }

    private void doRegisterTcpListener(TcpListener server)
            throws IOException {
        Set<InetAddress> addrs = server.getAddresses();
        int port = server.getPort();

        // Addresses the operator did not name are best-effort: one that cannot
        // be bound (for example a tentative IPv6 link-local address in a
        // freshly started container) is skipped so the rest still serve.
        boolean bestEffort = server.hasDefaultAddresses();
        IOException failure = null;
        int bound = 0;
        for (InetAddress address : addrs) {
            ServerSocketChannel ssc = ServerSocketChannel.open();
            try {
                ssc.configureBlocking(false);
                ServerSocket ss = ssc.socket();

                // For the wildcard/any-local address, bind without an explicit
                // address so the JDK creates a single (dual-stack where the OS
                // allows) wildcard socket rather than an IPv4-only 0.0.0.0 bind.
                InetSocketAddress socketAddress = address.isAnyLocalAddress()
                        ? new InetSocketAddress(port)
                        : new InetSocketAddress(address, port);
                long t1 = System.currentTimeMillis();
                ss.bind(socketAddress);
                long t2 = System.currentTimeMillis();

                if (ss.getLocalPort() > 0) {
                    server.applyBoundTcpPort(ss.getLocalPort());
                }

                if (LOGGER.isLoggable(Level.FINE)) {
                    String message = Gumdrop.L10N.getString("info.bound_server");
                    message = MessageFormat.format(message,
                            server.getDescription(), ss.getLocalPort(), address, (t2 - t1));
                    LOGGER.fine(message);
                }

                SelectionKey key = ssc.register(selector, SelectionKey.OP_ACCEPT);
                key.attach(server);

                server.addServerChannel(ssc);
                bound++;
            } catch (IOException e) {
                closeQuietly(ssc);
                if (!bestEffort) {
                    throw e;
                }
                failure = e;
                events.warn("log.skipped_unbindable_address")
                        .attr("listener", server.getDescription())
                        .attr("address", address.getHostAddress())
                        .attr("reason", e.getMessage()).emit();
            }
        }
        if (bound == 0 && failure != null) {
            throw failure;
        }
    }

    private void accept(SelectionKey key) {
        ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
        Object attachment = key.attachment();

        SocketChannel sc;
        try {
            // A RawAcceptHandler may legitimately close its own listening
            // socket from within accepted() - e.g. FTP passive mode
            // closing its one-shot PASV/EPSV listener as soon as the
            // single expected data connection arrives (issue #145).
            // Re-checking isOpen() lets the loop exit normally in that
            // case instead of calling accept() on an already-closed
            // channel and logging a spurious warning below.
            while (ssc.isOpen() && (sc = ssc.accept()) != null) {
                try {
                    SocketAddress remoteAddress = sc.getRemoteAddress();

                    if (attachment instanceof TcpListener) {
                        acceptListener(
                                (TcpListener) attachment, sc, remoteAddress);
                    } else if (attachment instanceof RawAcceptHandler) {
                        // ServerSocketChannel.accept() always returns a
                        // channel in blocking mode, regardless of the
                        // listening channel's own mode -- flip it to
                        // non-blocking before handing it to the handler,
                        // the same as acceptListener() does below, per
                        // RawAcceptHandler's contract.
                        sc.configureBlocking(false);
                        ((RawAcceptHandler) attachment).accepted(sc);
                    }
                } catch (IOException e) {
                    events.warn("log.error_processing_accepted_connection").thrown(e).emit();
                    try {
                        sc.close();
                    } catch (IOException closeEx) {
                        // Ignore close errors
                    }
                }
            }
        } catch (IOException e) {
            if (isFileDescriptorExhausted(e)) {
                // Out of file descriptors: back off so we don't spin on a
                // permanently-readable accept selector until FDs free up.
                events.error("log.out_of_file_descriptors_accept")
                        .attr("backoff_ms", ACCEPT_BACKOFF_MS).thrown(e).emit();
                backoffAfterAcceptFailure();
            } else {
                events.warn("log.error_accepting_connection").thrown(e).emit();
            }
        }
    }

    /**
     * Returns whether the given exception indicates file-descriptor
     * exhaustion (EMFILE/ENFILE), which the JDK surfaces as a
     * "Too many open files" message.
     */
    private static boolean isFileDescriptorExhausted(IOException e) {
        String msg = e.getMessage();
        return msg != null && msg.contains("Too many open files");
    }

    /**
     * Pauses the accept thread briefly after a resource-exhaustion failure.
     */
    private void backoffAfterAcceptFailure() {
        try {
            Thread.sleep(ACCEPT_BACKOFF_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void acceptListener(TcpListener server, final SocketChannel sc,
            final SocketAddress remoteAddress) throws IOException {
        if (!server.acceptConnection(remoteAddress)) {
            logRejection(remoteAddress);
            sc.close();
            return;
        }

        sc.configureBlocking(false);
        final SelectorLoop workerLoop = gumdrop.nextWorkerLoop();
        // Record admission: increments the listener's connection counters and
        // consumes a rate-limit token. The endpoint releases this accounting
        // exactly once when it closes. Lightweight and independent of the
        // endpoint, so it stays on the accept thread.
        server.connectionOpened(remoteAddress);

        // Everything else - protocol handler construction, TLS setup in
        // TcpEndpoint.init(), buffer pool acquisition, and the
        // handler's connected() callback (which for text protocols writes
        // the greeting banner and for HTTP arms the idle timer) - is real
        // per-connection work that must not run serially on the single
        // accept thread. Defer it onto the worker loop that will actually
        // own this connection. setSelectorLoop() is called here, before
        // connected(), so that a handler which schedules an
        // establishment-timeout timer from within connected() gets the
        // per-loop timer rather than falling back to the shared
        // process-wide one (which only register() would otherwise assign,
        // one selector iteration later).
        final TcpListener listener = server;
        boolean handedOff = workerLoop.tryInvokeLater(new Runnable() {
            @Override
            public void run() {
                TcpEndpoint endpoint;
                try {
                    endpoint = listener.newEndpoint(sc, workerLoop);
                } catch (IOException e) {
                    events.warn("log.error_setting_up_accepted_connection").thrown(e).emit();
                    try {
                        sc.close();
                    } catch (IOException closeEx) {
                        // Ignore close errors
                    }
                    return;
                }
                endpoint.setSelectorLoop(workerLoop);
                endpoint.setListener(listener, remoteAddress);
                endpoint.connected();
                workerLoop.register(sc, endpoint);

                if (LOGGER.isLoggable(Level.FINEST)) {
                    String message = Gumdrop.L10N.getString("info.accepted");
                    message = MessageFormat.format(message,
                            String.valueOf(remoteAddress));
                    LOGGER.finest(message);
                }
            }
        });
        if (!handedOff) {
            // The worker loop has terminated: nobody will ever own this
            // connection, so it is refused here, with its admission undone.
            try {
                sc.close();
            } catch (IOException closeEx) {
                // Ignore close errors
            }
            server.connectionClosed(remoteAddress);
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
     * Shuts down this AcceptSelectorLoop. Returns immediately; on its own
     * thread the loop closes every listening socket it still owns (listeners'
     * and raw acceptors'), services or refuses what is still queued, and
     * exits. There is nothing to drain here, so there is no deadline.
     * Idempotent.
     */
    void shutdown() {
        active = false;
        wakeup();
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

    /**
     * Waits up to {@code timeoutMs} for this loop's thread to terminate.
     *
     * @param timeoutMs the longest to wait, in milliseconds
     * @throws InterruptedException if interrupted while waiting
     */
    void join(long timeoutMs) throws InterruptedException {
        if (thread != null) {
            thread.join(timeoutMs);
        }
    }

    /**
     * Returns the thread that runs this loop, or null if not started.
     *
     * @return the loop thread
     */
    Thread getThread() {
        return thread;
    }

}
