/*
 * Gumdrop.java
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
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.ResourceBundle;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.dns.client.HostsFile;
import org.bluezoo.gumdrop.dns.client.ResolvConf;
import org.bluezoo.gumdrop.mailbox.spi.MailboxLifecycle;

/**
 * Central configuration and lifecycle manager for the Gumdrop server.
 *
 * <p>Manages the core infrastructure for event-driven I/O processing:
 * worker SelectorLoops, the AcceptSelectorLoop for TCP servers, and the
 * scheduled timer for timeouts.
 *
 * <h4>Server Mode</h4>
 * <pre>{@code
 * // Boot a fresh instance and compose servers against it
 * Gumdrop gumdrop = Gumdrop.boot();
 * gumdrop.addServer(myServletServer);
 * gumdrop.addServer(mySmtpServer);
 * }</pre>
 *
 * <h4>Client Mode</h4>
 * <pre>{@code
 * Gumdrop gumdrop = Gumdrop.boot();
 * RedisClient client = new RedisClient("localhost", 6379);
 * client.connect(gumdrop, handler);
 * }</pre>
 *
 * <h4>Lifecycle</h4>
 * <ul>
 *   <li>{@link #boot()} / {@link #boot(GumdropConfig)} create and start a
 *       fresh instance</li>
 *   <li>Auto-shutdown when no server listeners and no active handlers remain</li>
 *   <li>Can restart after shutdown by calling {@code start()} again</li>
 *   <li>JVM shutdown hook ensures cleanup</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Gumdrop {

    public static final String VERSION = "2.0";

    /**
     * Default graceful-drain timeout in milliseconds. On shutdown, the server
     * stops accepting new connections and waits up to this long for in-flight
     * connections to finish before force-closing. Sized below the typical
     * orchestrator grace period (Kubernetes defaults to 30s).
     */
    private static final long DEFAULT_DRAIN_TIMEOUT_MS = 25_000L;

    /** Poll interval while waiting for in-flight connections to drain. */
    private static final long DRAIN_POLL_INTERVAL_MS = 100L;

    /**
     * Grace, beyond a loop's own hard close deadline, that the shutdown
     * coordinator allows for the loop thread to actually exit. A loop whose
     * thread is still alive after that is stuck in handler code that never
     * returns; shutdown reports it and carries on rather than hang.
     */
    private static final long LOOP_QUIESCE_TIMEOUT_MS = 5_000L;

    /** Lower bound on the per-loop hard close deadline, so goodbyes get a chance even with draining disabled. */
    private static final long MIN_LOOP_CLOSE_DEADLINE_MS = 1_000L;

    /** Upper bound on the per-loop hard close deadline, so a stuck peer never holds a shutdown for long. */
    private static final long MAX_LOOP_CLOSE_DEADLINE_MS = 5_000L;

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.L10N");
    static final Logger LOGGER = Logger.getLogger(Gumdrop.class.getName());

    // Application-tier protocol servers (own and manage their listeners)
    private final List<Server> servers;

    // Server listeners (controls AcceptSelectorLoop lifecycle)
    private final List<TcpListener> serverListeners;

    // Active channel handlers (internal bookkeeping for selector dispatch)
    private final Set<ChannelHandler> activeHandlers;

    // Active client connections (gates auto-shutdown)
    private final Set<ClientEndpoint> activeClients;

    // Infrastructure
    private volatile AcceptSelectorLoop acceptLoop;
    private volatile SelectorLoop[] workerLoops;
    private final int workerCount;
    private final AtomicInteger nextWorker;
    private ScheduledTimer scheduledTimer;
    private StorageExecutor storageExecutor;
    private CryptoExecutor cryptoExecutor;

    // State
    private volatile boolean started;
    private volatile boolean acceptLoopRunning;
    private volatile boolean draining;

    // Set by checkAutoShutdown() when it has to dispatch shutdown() to a
    // separate thread (reentrant call from a worker loop's own thread,
    // see that method); start() joins it before proceeding, so any
    // caller of start() is guaranteed to observe a fully-completed
    // shutdown rather than racing against one still in progress.
    private volatile Thread pendingAsyncShutdown;
    private volatile boolean ready;
    // Released each time the start sequence finishes binding its listeners
    private volatile CountDownLatch readyLatch = new CountDownLatch(1);
    // Accept-loop callback for servers/listeners added to a running instance
    private final Runnable lateBindReady = new Runnable() {
        @Override
        public void run() {
            ready = true;
            readyLatch.countDown();
        }
    };

    // Guards the decision-and-flag step of checkAutoShutdown() (checking
    // activeClients/servers/serverListeners are empty and publishing
    // pendingAsyncShutdown) so it is atomic with start()'s own read of
    // pendingAsyncShutdown/started (issue #426): without this, a client's
    // disconnect could be judged "nothing left running" and decide to tear
    // the infrastructure down in the exact instant between a new client's
    // start() observing no pending shutdown and it (wrongly) trusting the
    // still-true started flag, handing out a SelectorLoop that is about to
    // be shut down out from under the new connection. The lock is held only
    // around that quick decision, never across the (possibly long-running)
    // shutdown()/join() itself, so it cannot serialise unrelated drains.
    private final Object lifecycleLock = new Object();

    /** True while a shutdown is running (hook, signal, interrupt, or call). Guarded by {@link #lifecycleLock}. */
    private boolean shutdownInProgress;

    /**
     * Set when {@link #shutdownNow()} is called during a shutdown, to turn it
     * into an abort. Guarded by {@link #lifecycleLock}; cleared when the
     * shutdown finishes.
     */
    private boolean abortRequested;

    /** Wakes the coordinator out of the drain wait when an abort arrives. */
    private final Object drainMonitor = new Object();

    /**
     * Test hook: run on the coordinator thread each time it is about to wait
     * for in-flight connections to drain, after the listening sockets have
     * been released.
     */
    volatile Runnable drainWaitObserver;

    /**
     * Thread blocked in {@link #awaitShutdown()}, signalled on {@code SIGTERM}
     * so graceful teardown runs there (logging still works) instead of on the
     * JVM shutdown-hook thread (often after {@code LogManager} has closed handlers).
     */
    private volatile Thread launcherThread;

    /**
     * Graceful-drain timeout in milliseconds. Overridable via the
     * {@code gumdrop.drainTimeoutMs} system property, or explicitly via
     * {@link #setDrainTimeoutMs} / {@link GumdropConfig#drainTimeoutMs}.
     * 0 disables draining (immediate force-close on shutdown).
     */
    private volatile long drainTimeoutMs =
            Long.getLong("gumdrop.drainTimeoutMs", DEFAULT_DRAIN_TIMEOUT_MS);

    // ─────────────────────────────────────────────────────────────────────────
    // Construction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates and starts a new {@code Gumdrop} instance with the given
     * configuration. Each call constructs a fresh instance, which the
     * caller is responsible for threading through to whatever servers,
     * listeners, and clients it composes (e.g. {@code
     * server.start(gumdrop)}, {@code client.connect(gumdrop, handler)}).
     *
     * <p>Named {@code boot} rather than {@code start} because the latter
     * is already the instance lifecycle method ({@link #start()}) this
     * factory calls internally — Java does not allow a static and
     * instance method to share a name and parameter list.
     *
     * @param config the configuration
     * @return a new, started Gumdrop instance
     */
    public static Gumdrop boot(GumdropConfig config) {
        Gumdrop gumdrop = new Gumdrop(config.getWorkerThreads());
        gumdrop.setDrainTimeoutMs(config.getDrainTimeoutMs());
        gumdrop.start();
        return gumdrop;
    }

    /**
     * {@link #boot(GumdropConfig)} with default configuration.
     *
     * @return a new, started Gumdrop instance
     */
    public static Gumdrop boot() {
        return boot(GumdropConfig.create());
    }

    /**
     * Boots a runtime, registers one or more {@link Server}s, and blocks until
     * shutdown completes. This is the lifecycle tail of the former
     * {@code Gumdrop.main}: the JVM shutdown hook registered in the
     * {@link Gumdrop} constructor (via {@link #boot()}) calls {@link #shutdown()}
     * on {@code SIGTERM}; Ctrl+C typically interrupts {@link #awaitShutdown()}
     * and triggers {@code shutdown()} directly.
     *
     * @param servers protocol servers to manage (each receives {@link Server#start(Gumdrop)})
     * @return the instance that was shut down (for tests or post-mortem inspection)
     * @throws InterruptedException if the waiting thread is interrupted after shutdown
     */
    public static Gumdrop serve(Server... servers) throws InterruptedException {
        Gumdrop gumdrop = boot();
        for (Server server : servers) {
            gumdrop.addServer(server);
        }
        gumdrop.awaitShutdown();
        return gumdrop;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Construction (private)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Private constructor - use {@link #boot()} / {@link #boot(GumdropConfig)}
     * to create an instance.
     */
    private Gumdrop(int workerCount) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount must be at least 1");
        }

        this.servers = Collections.synchronizedList(new ArrayList<Server>());
        this.serverListeners =
                Collections.synchronizedList(new ArrayList<TcpListener>());
        this.activeHandlers = Collections.newSetFromMap(new ConcurrentHashMap<ChannelHandler, Boolean>());
        this.activeClients = Collections.newSetFromMap(new ConcurrentHashMap<ClientEndpoint, Boolean>());
        this.workerCount = workerCount;
        this.nextWorker = new AtomicInteger(0);

        // Worker loops created on start() - allows restart after shutdown
        this.workerLoops = null;

        // AcceptSelectorLoop created lazily when first server is added
        this.acceptLoop = null;
        this.acceptLoopRunning = false;

        // Scheduled timer created on start() - allows restart after shutdown
        this.scheduledTimer = null;

        this.started = false;

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new ShutdownHook());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Server registry (application-tier protocol servers)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Adds a protocol server to be managed by Gumdrop.
     *
     * <p>If Gumdrop has already been started, the server is started
     * immediately and its TCP listeners are registered with the accept
     * loop. Otherwise, the server is queued and will be started when
     * {@link #start()} is called.
     *
     * @param server the server to add
     */
    public void addServer(Server server) {
        servers.add(server);

        if (started) {
            server.start(this);
            registerServerListeners(server);
            if (acceptLoop != null) {
                // As in addListener(): a server added to a running instance
                // is bound later on the accept loop, so re-arm the startup
                // signal for awaitStartupComplete(). The callback is
                // installed after the registrations were queued.
                if (readyLatch.getCount() == 0) {
                    readyLatch = new CountDownLatch(1);
                }
                acceptLoop.onReady(lateBindReady);
            }
        }
    }

    /**
     * Removes a protocol server from Gumdrop and stops it.
     *
     * @param server the server to remove
     */
    public void removeServer(Server server) {
        servers.remove(server);
        unregisterServerListeners(server);
        server.stop();

        if (servers.isEmpty() && serverListeners.isEmpty()
                && acceptLoopRunning) {
            acceptLoop.shutdown();
            acceptLoopRunning = false;
        }

        checkAutoShutdown();
    }

    /**
     * Returns the protocol servers managed by this Gumdrop instance.
     *
     * @return unmodifiable view of the servers
     */
    public List<Server> getServers() {
        return Collections.unmodifiableList(servers);
    }

    /**
     * Registers a server's TCP listeners with the accept loop.
     * Listeners that manage their own I/O (e.g. QUIC) are tracked
     * but not registered for TCP accept.
     */
    private void registerServerListeners(Server server) {
        List<?> listeners = server.getListeners();
        for (int i = 0; i < listeners.size(); i++) {
            Object listener = listeners.get(i);
            if (listener instanceof TcpListener) {
                TcpListener ep = (TcpListener) listener;
                serverListeners.add(ep);
                if (ep.requiresTcpAccept()) {
                    ensureAcceptLoop();
                    acceptLoop.registerListener(ep);
                }
            }
        }
    }

    /**
     * Unregisters a server's TCP listeners from the accept loop.
     */
    private void unregisterServerListeners(Server server) {
        List<?> listeners = server.getListeners();
        for (int i = 0; i < listeners.size(); i++) {
            Object listener = listeners.get(i);
            if (listener instanceof TcpListener) {
                TcpListener ep = (TcpListener) listener;
                serverListeners.remove(ep);
                if (ep.requiresTcpAccept()) {
                    releaseListenerSockets(ep);
                }
            }
        }
    }

    /**
     * Releases a listener's listening sockets: the accept loop closes them,
     * on its own thread, and this returns once they are really released.
     * With no running accept loop nothing else owns the sockets, so they
     * are closed directly.
     */
    private void releaseListenerSockets(TcpListener listener) {
        AcceptSelectorLoop accept = acceptLoop;
        if (accept == null || !accept.releaseListener(listener)) {
            listener.closeServerChannels();
        }
    }

    /**
     * Ensures the AcceptSelectorLoop is created and running.
     *
     * <p>Public so that standalone accept-side use of {@link
     * #getAcceptLoop()} (e.g. an FTP active-mode client, which is not
     * itself a registered server/service and so never otherwise triggers
     * this) can guarantee the loop exists before calling {@link
     * AcceptSelectorLoop#registerRawAcceptor}.
     */
    public void ensureAcceptLoop() {
        if (acceptLoop == null || !acceptLoop.isRunning()) {
            acceptLoop = new AcceptSelectorLoop(this);
            acceptLoop.start();
            acceptLoopRunning = true;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Endpoint server management (direct, non-service endpoints)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Adds a standalone endpoint server to be managed by Gumdrop.
     *
     * <p>For endpoints that are part of a {@link Server}, use
     * {@link #addServer(Server)} instead. This method is for
     * standalone endpoints not owned by any protocol server.
     *
     * <p>If Gumdrop has already been started, the server is registered
     * immediately and begins accepting connections. Otherwise, it will
     * be registered when {@link #start()} is called.
     *
     * @param server the endpoint server to add
     */
    public void addListener(TcpListener server) {
        serverListeners.add(server);
        server.start(this);

        if (started) {
            ensureAcceptLoop();
            if (readyLatch.getCount() == 0) {
                // A listener added to a running instance is bound later on
                // the accept loop; re-arm the startup signal so that
                // awaitStartupComplete() covers it too.
                readyLatch = new CountDownLatch(1);
            }
            acceptLoop.registerListener(server);
            acceptLoop.onReady(lateBindReady);
        }
    }

    /**
     * Removes an endpoint server from Gumdrop.
     *
     * <p>The server stops accepting new connections immediately.
     * Existing connections continue until they close naturally.
     *
     * @param server the endpoint server to remove
     */
    public void removeListener(TcpListener server) {
        serverListeners.remove(server);
        server.stop();
        releaseListenerSockets(server);

        if (serverListeners.isEmpty() && acceptLoopRunning) {
            acceptLoop.shutdown();
            acceptLoopRunning = false;
        }

        checkAutoShutdown();
    }

    /**
     * Returns the collection of server listeners managed by this
     * Gumdrop instance.
     *
     * @return unmodifiable view of the server listeners
     */
    public Collection<TcpListener> getListeners() {
        return Collections.unmodifiableList(serverListeners);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Active handler tracking
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Registers an active channel handler.
     *
     * <p>Called by clients when a connection is initiated. The handler
     * remains registered until {@link #removeChannelHandler} is called.
     *
     * @param handler the handler to register
     */
    public void addChannelHandler(ChannelHandler handler) {
        activeHandlers.add(handler);
    }

    /**
     * Deregisters an active channel handler.
     *
     * <p>Called when a connection closes or fails.
     *
     * @param handler the handler to deregister
     */
    public void removeChannelHandler(ChannelHandler handler) {
        activeHandlers.remove(handler);
    }

    /**
     * Returns the set of active channel handlers.
     *
     * <p>Useful for debugging to see what's still active.
     *
     * @return unmodifiable copy of active handlers
     */
    public Set<ChannelHandler> getActiveHandlers() {
        return Collections.unmodifiableSet(new HashSet<ChannelHandler>(activeHandlers));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Active client tracking
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Registers an active client connection.
     *
     * <p>Called by {@link ClientEndpoint#connect} when a client-initiated
     * connection already has a {@link SelectorLoop} of its own (server
     * integration mode). The client remains registered until {@link
     * #removeClient} is called (on disconnect, error, or explicit close).
     * Active clients gate automatic shutdown.
     *
     * <p>Registration is serialised with {@link #checkAutoShutdown()}'s
     * decision through {@link #lifecycleLock} (issue #426): otherwise a
     * concurrent disconnect elsewhere could judge everything empty, decide
     * to shut down, and then have this client's registration either race
     * that decision unseen, or land in {@link #activeClients} just before
     * {@link #shutdown()}'s Phase 3 clears it, silently dropping a client
     * that believes it is registered.
     *
     * @param client the client endpoint to register
     */
    public void addClient(ClientEndpoint client) {
        for (;;) {
            Thread pending;
            synchronized (lifecycleLock) {
                pending = pendingAsyncShutdown;
                if (pending == null) {
                    activeClients.add(client);
                    return;
                }
            }
            awaitPendingShutdown(pending);
        }
    }

    /**
     * Ensures the infrastructure is started, obtains a worker loop from
     * it, and registers {@code client} as a reason to keep it running --
     * as one operation with respect to {@link #checkAutoShutdown()}'s
     * decision (issue #426).
     *
     * <p>Doing this as three separate calls ({@link #start()}, {@link
     * #nextWorkerLoop()}, {@link #addClient}), as {@link ClientEndpoint}
     * used to, left windows where a disconnecting client's auto-shutdown
     * decision could be judged against a stale "already started" state
     * that {@code start()} trusted without knowing the decision was
     * already made, or could tear down the very loop just handed back
     * before this client's own registration had a chance to prevent it.
     *
     * @param client the client that is about to start using the returned loop
     * @return a worker loop of the now-guaranteed-running instance
     */
    public SelectorLoop startForClient(ClientEndpoint client) {
        for (;;) {
            Thread pending;
            boolean needsInit;
            synchronized (lifecycleLock) {
                pending = pendingAsyncShutdown;
                if (pending == null) {
                    needsInit = !started;
                    started = true;
                    activeClients.add(client);
                } else {
                    needsInit = false;
                }
            }
            if (pending != null) {
                awaitPendingShutdown(pending);
                continue;
            }
            if (needsInit) {
                doStart();
            }
            SelectorLoop loop = nextWorkerLoop();
            synchronized (lifecycleLock) {
                if (pendingAsyncShutdown != null) {
                    continue;
                }
            }
            if (!loop.isRunning()) {
                continue;
            }
            return loop;
        }
    }

    /**
     * Deregisters an active client connection.
     *
     * <p>Called when a client connection closes, fails, or is explicitly
     * closed. If no protocol servers, listeners, or clients remain, triggers
     * automatic shutdown.
     *
     * @param client the client endpoint to deregister
     */
    public void removeClient(ClientEndpoint client) {
        Thread shutdownThread;
        synchronized (lifecycleLock) {
            activeClients.remove(client);
            shutdownThread = scheduleAutoShutdownIfIdleLocked();
        }
        if (shutdownThread != null) {
            shutdownThread.start();
        }
    }

    /**
     * Returns the set of active client connections.
     *
     * <p>Useful for debugging to see what clients are still connected.
     *
     * @return unmodifiable copy of active clients
     */
    public Set<ClientEndpoint> getActiveClients() {
        return Collections.unmodifiableSet(
                new HashSet<ClientEndpoint>(activeClients));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Starts the Gumdrop infrastructure.
     *
     * <p>This starts all worker SelectorLoops and the scheduled timer.
     * If server listeners are registered, the AcceptSelectorLoop is also
     * started.
     *
     * <p>If already started, this method is a no-op.
     *
     * <p>Can be called after shutdown() to restart the infrastructure.
     *
     * <p>Automatic shutdown is triggered by removal events (client
     * disconnect, listener removal, service removal), not proactively
     * at start time.
     */
    public void start() {
        if (claimStart()) {
            doStart();
        }
    }

    /** The actual (re)initialisation work of {@link #start()}, run only once {@link #claimStart()} or {@link #startForClient} has claimed the started state. */
    private void doStart() {
        long t1 = System.currentTimeMillis();
        readyLatch = new CountDownLatch(1);
        ready = false;
        draining = false;

        // Snapshot the standalone listeners added via addListener() before
        // start(). registerServerListeners() below appends server-owned
        // listeners to serverListeners, so we capture the standalone ones now
        // to register them exactly once (and avoid double-registering the
        // service listeners, which register themselves).
        List<TcpListener> standaloneListeners =
                new ArrayList<TcpListener>(serverListeners);

        // Create or recreate scheduled timer
        if (scheduledTimer == null || !scheduledTimer.isRunning()) {
            scheduledTimer = new ScheduledTimer();
        }
        scheduledTimer.start();

        // Create the shared storage I/O worker pool. Blocking filesystem work
        // (mailbox scans, directory listing, rename/delete, expunge, mbox
        // reads, ...) is offloaded here so it never stalls a SelectorLoop.
        if (storageExecutor == null) {
            storageExecutor = StorageExecutor.createDefault();
        }

        // Create the shared crypto worker pool. TLS handshake delegated
        // tasks (in-tree TLS handshake offload -- RSA/ECDHE key exchange, certificate
        // chain validation) are CPU-bound work with no non-blocking JDK
        // API; offloaded here so a burst of new TLS connections never
        // stalls a SelectorLoop's other connections.
        if (cryptoExecutor == null) {
            cryptoExecutor = CryptoExecutor.createDefault();
        }

        startMailboxLifecycle();

        // Parse /etc/hosts (or Windows hosts) once off the selector so the
        // first DnsResolver.resolve after accept cannot stall a reactor
        // thread on cold hosts-file I/O.
        HostsFile.warm();

        // Parse /etc/resolv.conf once off the selector so the first
        // DnsResolver.forLoop() call cannot stall a reactor thread on cold
        // resolver-configuration I/O (see ResolvConf.warm()).
        ResolvConf.warm();

        // Create or recreate worker loops (1-based naming for humans)
        if (workerLoops == null) {
            workerLoops = new SelectorLoop[workerCount];
            for (int i = 0; i < workerCount; i++) {
                workerLoops[i] = new SelectorLoop(i + 1);
                workerLoops[i].setGumdrop(this);
            }
        } else {
            // Recreate any loops that were shut down
            for (int i = 0; i < workerCount; i++) {
                if (!workerLoops[i].isRunning()) {
                    workerLoops[i] = new SelectorLoop(i + 1);
                    workerLoops[i].setGumdrop(this);
                }
            }
        }

        // Start worker loops
        for (SelectorLoop loop : workerLoops) {
            loop.setCloseDeadlineMs(loopCloseDeadlineMs());
            loop.start();
        }

        // Start all registered protocol servers and collect their TCP listeners
        for (int i = 0; i < servers.size(); i++) {
            Server server = servers.get(i);
            server.start(this);
            registerServerListeners(server);
        }

        // Standalone listeners that don't use the TCP accept loop (e.g.
        // Http3Listener's QUIC/UDP bind) can't complete their own start()
        // if addListener() ran before workerLoops existed — their start()
        // call at addListener() time deferred in that case. Give them a
        // second chance now that the worker-loop pool is ready (issue #106).
        for (TcpListener listener : standaloneListeners) {
            if (!listener.requiresTcpAccept()) {
                listener.start(this);
            }
        }

        // Start AcceptSelectorLoop if we have TCP listeners
        boolean hasTcpListeners = false;
        for (TcpListener listener : serverListeners) {
            if (listener.requiresTcpAccept()) {
                hasTcpListeners = true;
                break;
            }
        }

        if (hasTcpListeners) {
            ensureAcceptLoop();
            // Register standalone listeners added before start(). Listeners
            // added after start() are registered directly by addListener(),
            // and protocol-server-owned listeners were registered by
            // registerServerListeners() above.
            for (TcpListener listener : standaloneListeners) {
                if (listener.requiresTcpAccept()) {
                    acceptLoop.registerListener(listener);
                }
            }
            acceptLoop.onReady(new Runnable() {
                @Override
                public void run() {
                    ready = true;
                    readyLatch.countDown();
                    long t2 = System.currentTimeMillis();
                    if (LOGGER.isLoggable(Level.INFO)) {
                        String message = L10N.getString("info.started_gumdrop");
                        message = MessageFormat.format(message, (t2 - t1));
                        LOGGER.info(message);
                    }
                }
            });
        } else {
            ready = true;
            readyLatch.countDown();
            long t2 = System.currentTimeMillis();
            if (LOGGER.isLoggable(Level.INFO)) {
                String message = L10N.getString("info.started_gumdrop");
                message = MessageFormat.format(message, (t2 - t1));
                LOGGER.info(message);
            }
        }
    }

    /**
     * Returns whether Gumdrop has been started.
     *
     * @return true if started and not yet shut down
     */
    public boolean isStarted() {
        return started;
    }

    /**
     * Checks if automatic shutdown should occur.
     *
     * <p>Shutdown occurs when Gumdrop has been started and no first-class
     * lifecycle participants remain:
     * <ul>
     *   <li>No protocol servers are registered</li>
     *   <li>No server listeners are registered</li>
     *   <li>No active client connections exist</li>
     * </ul>
     *
     * <p>Internal bookkeeping ({@code activeHandlers}) is not checked.
     * Dependent infrastructure such as DNS resolver sockets does not
     * independently prevent auto-shutdown; it is cleaned up during
     * {@link #shutdown()}.
     */
    private void checkAutoShutdown() {
        Thread shutdownThread;
        synchronized (lifecycleLock) {
            shutdownThread = scheduleAutoShutdownIfIdleLocked();
        }
        if (shutdownThread != null) {
            shutdownThread.start();
        }
    }

    /**
     * Schedules asynchronous {@link #shutdown()} when nothing remains to
     * keep this instance running. Caller must hold {@link #lifecycleLock}.
     */
    private Thread scheduleAutoShutdownIfIdleLocked() {
        if (!started || shutdownInProgress) {
            return null;
        }
        if (!(servers.isEmpty() && serverListeners.isEmpty()
                && activeClients.isEmpty())) {
            return null;
        }
        if (pendingAsyncShutdown != null) {
            return null;
        }
        // Always dispatch to a separate thread, even when not called from a
        // worker loop's own thread: removeClient() can be invoked from a
        // ClientEndpoint's disconnected()/error() callback, which runs on the
        // SelectorLoop thread handling that very connection -- making this a
        // reentrant call from a worker loop's own thread. shutdown() below
        // calls SelectorLoop.awaitQuiesce() on every loop including this one,
        // and a thread cannot join itself: awaitQuiesce() short-circuits
        // without actually waiting, but shutdown() never checks that return
        // value, so it proceeds believing every loop is stopped while this
        // one's thread is still alive and mid-unwind. A concurrent
        // nextWorkerLoop()/start() call from another thread then sees
        // isRunning()==true (the thread hasn't exited yet) and hands out a
        // reference to it -- whatever gets registered on it afterwards is
        // silently lost the moment this thread finishes exiting its dispatch
        // loop, since nothing will ever come back to process it. Running
        // shutdown() off-thread unconditionally lets awaitQuiesce() perform a
        // real join() for every loop, closing the window entirely -- provided
        // every path back into this instance (start(), in practice) waits for
        // that thread first; see claimStart().
        Thread shutdownThread = new Thread(new Runnable() {
            @Override
            public void run() {
                shutdown();
            }
        }, "gumdrop-auto-shutdown");
        shutdownThread.setDaemon(true);
        pendingAsyncShutdown = shutdownThread;
        return shutdownThread;
    }

    /**
     * Atomically waits out any shutdown already decided by {@link
     * #checkAutoShutdown()} (whether or not its thread has been started
     * yet) and then claims the started state for this call, so {@link
     * #start()} never proceeds while racing a shutdown still being
     * decided or run.
     *
     * <p>Loops rather than doing a single check-then-join: after joining
     * a terminated thread, another shutdown could in principle already
     * have been decided (and a new {@link #pendingAsyncShutdown} published)
     * before this method re-takes {@link #lifecycleLock}, so the
     * pending-shutdown check is redone under the lock each time round
     * rather than assumed to still hold from before the join.
     *
     * @return true if the caller should (re)initialise the infrastructure;
     *      false if it is already running and there is nothing to do
     */
    private boolean claimStart() {
        for (;;) {
            Thread pending;
            synchronized (lifecycleLock) {
                pending = pendingAsyncShutdown;
                if (pending == null) {
                    if (started) {
                        return false;
                    }
                    started = true;
                    return true;
                }
            }
            awaitPendingShutdown(pending);
        }
    }

    /**
     * Joins an in-flight shutdown thread published as {@link
     * #pendingAsyncShutdown} (without holding {@link #lifecycleLock}
     * across the join -- {@link #shutdown()} can run for as long as its
     * drain timeout), then clears the field once it has genuinely
     * terminated. Callers loop and re-check {@link #pendingAsyncShutdown}
     * under the lock afterwards rather than assuming it is now null: a
     * new shutdown can in principle have been decided in the gap between
     * this method returning and the caller re-taking the lock.
     *
     * @param pending the shutdown thread to wait for
     */
    private void awaitPendingShutdown(Thread pending) {
        try {
            pending.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (lifecycleLock) {
            if (pendingAsyncShutdown == pending
                    && pending.getState() == Thread.State.TERMINATED) {
                pendingAsyncShutdown = null;
            }
        }
    }

    /**
     * Shuts down the Gumdrop infrastructure in an orderly way: predictable,
     * safe, and bounded. This is the default form of shutdown;
     * {@link #shutdownNow()} is the abort form.
     *
     * <p>Shutdown proceeds in four steps, in this order, so that in-flight
     * work is not cut off (important for rolling deploys and scale-down in
     * orchestrators) and every connection is closed by the loop that owns
     * it:
     * <ol>
     *   <li><b>Stop accepting</b> - {@link #isReady()} turns false at once.
     *       The accept loop closes every TCP listener's listening socket, on
     *       its own thread, and this does not move on until the sockets are
     *       really released, so no new connection is admitted from here on.
     *       QUIC listeners stop admitting new connections, and each server
     *       gets {@link Server#beginShutdown()} to send anything it must say
     *       on the network on its way out (via its loops). Established
     *       connections are untouched. A raw acceptor that an in-flight
     *       transfer needs (an FTP data listener) stays usable.</li>
     *   <li><b>Drain</b> - wait up to {@link #getDrainTimeoutMs()} for the
     *       currently-open connections of the TCP listeners to finish
     *       naturally while the loops keep serving them. 0 skips this.</li>
     *   <li><b>Close</b> - every worker loop, and the accept loop, is told
     *       to close everything it owns, each on its own thread: TCP
     *       connections flush their queued output and send TLS
     *       {@code close_notify}, DTLS sessions send {@code close_notify},
     *       QUIC connections send {@code CONNECTION_CLOSE}. A loop exits when
     *       it owns nothing open, or when its hard deadline (the drain
     *       timeout, but never less than one nor more than five seconds)
     *       passes: a peer that never drains cannot hang the shutdown.
     *       Tasks still queued on a loop are run before it exits.</li>
     *   <li><b>Tear down</b> - only now are protocol servers stopped
     *       ({@link Server#stop()}: application teardown, no network I/O),
     *       so every {@code disconnected()} callback ran against a live
     *       application; then the scheduled timer and worker pools are
     *       shut down.</li>
     * </ol>
     *
     * <p>No connection is ever closed by the thread that calls this method:
     * the calling thread coordinates and waits, bounded at every step.
     *
     * <p>Shutdown is idempotent. If another thread is already shutting down,
     * this call waits for that shutdown to finish instead of starting a
     * second (a call made on one of the runtime's own loop threads cannot
     * wait for its own loop, so returns at once). {@code shutdownNow()}
     * during a shutdown escalates it to an abort.
     *
     * <p>After shutdown, {@link #start()} can be called again to restart.
     */
    public void shutdown() {
        shutdown(false);
    }

    /**
     * Aborts the Gumdrop infrastructure: shutdown without drain and without
     * protocol goodbyes. No grace is given to in-flight work. Each loop
     * still closes what it owns on its own thread, but immediately and
     * discarding queued output: sockets are closed, QUIC and DTLS state is
     * dropped with nothing sent to the peers, and handlers see their
     * connection end. Use it for a second Ctrl+C, or when an orderly
     * shutdown must be cut short.
     *
     * <p>If an orderly {@link #shutdown()} is in progress (including one in
     * its drain wait) it is escalated: the drain ends at once and the loops
     * are told to abort. Like {@code shutdown()} it is idempotent and
     * returns only once the runtime has stopped, bounded as described
     * there.
     */
    public void shutdownNow() {
        shutdown(true);
    }

    private void shutdown(boolean abort) {
        boolean coordinator;
        synchronized (lifecycleLock) {
            if (shutdownInProgress) {
                coordinator = false;
                if (abort) {
                    abortRequested = true;
                }
            } else if (!started) {
                return;
            } else {
                shutdownInProgress = true;
                abortRequested = abort;
                coordinator = true;
            }
        }
        if (!coordinator) {
            if (abort) {
                escalateToAbort();
            }
            awaitOtherShutdown();
            return;
        }
        try {
            doShutdown();
        } finally {
            synchronized (lifecycleLock) {
                started = false;
                draining = false;
                shutdownInProgress = false;
                abortRequested = false;
                lifecycleLock.notifyAll();
            }
            flushConsoleLogging();
        }
    }

    /**
     * Waits for the shutdown another thread coordinates. A thread running on
     * one of this runtime's own loops cannot wait: that loop could never
     * finish closing while its thread is blocked here.
     */
    private void awaitOtherShutdown() {
        if (isLoopThread(Thread.currentThread())) {
            return;
        }
        try {
            awaitShutdownFinished();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isLoopThread(Thread t) {
        AcceptSelectorLoop accept = acceptLoop;
        if (accept != null && accept.getThread() == t) {
            return true;
        }
        SelectorLoop[] loops = workerLoops;
        if (loops != null) {
            for (int i = 0; i < loops.length; i++) {
                if (loops[i].getThread() == t) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Turns the shutdown in progress into an abort from any thread: ends the
     * drain wait and tells every loop to abort its close. Loops are told
     * directly, so this takes effect even while the coordinator is blocked
     * waiting on them.
     */
    private void escalateToAbort() {
        synchronized (drainMonitor) {
            drainMonitor.notifyAll();
        }
        SelectorLoop[] loops = workerLoops;
        if (loops != null) {
            for (int i = 0; i < loops.length; i++) {
                loops[i].shutdownNow();
            }
        }
        AcceptSelectorLoop accept = acceptLoop;
        if (accept != null) {
            accept.shutdown();
        }
    }

    private boolean isAbortRequested() {
        synchronized (lifecycleLock) {
            return abortRequested;
        }
    }

    /**
     * The hard deadline given to each loop for its close phase: the drain
     * timeout, bounded to a range that always allows goodbyes yet never lets
     * a stuck peer hold a shutdown for long.
     */
    private long loopCloseDeadlineMs() {
        long drain = drainTimeoutMs;
        if (drain < MIN_LOOP_CLOSE_DEADLINE_MS) {
            return MIN_LOOP_CLOSE_DEADLINE_MS;
        }
        if (drain > MAX_LOOP_CLOSE_DEADLINE_MS) {
            return MAX_LOOP_CLOSE_DEADLINE_MS;
        }
        return drain;
    }

    private void doShutdown() {
        long drainTimeout = drainTimeoutMs;
        if (isAbortRequested()) {
            operatorInfo(L10N.getString("info.closing_servers_abort"));
        } else if (drainTimeout > 0) {
            operatorInfo(MessageFormat.format(
                    L10N.getString("info.closing_servers"), drainTimeout));
        } else {
            operatorInfo(L10N.getString("info.closing_servers_no_drain"));
        }

        // No longer ready: fail readiness immediately so load balancers stop
        // routing new work before we begin draining.
        ready = false;
        draining = true;

        // ── Step 1: stop accepting new connections ──
        stopAccepting();
        // Servers say their goodbyes, via their loops, before the drain.
        // An abort sends none.
        if (!isAbortRequested()) {
            beginShutdownServers();
        }

        // ── Step 2: drain in-flight connections (bounded) ──
        if (drainTimeout > 0 && !isAbortRequested()) {
            awaitConnectionsDrained(drainTimeout);
        }

        // ── Step 3: each loop closes everything it owns, on its own thread ──
        // Connections first, so disconnected() callbacks run against a live
        // application; only then is the application torn down.
        closeLoops();

        // ── Step 4: application teardown (no network I/O) ──
        stopServers();

        // Clear active clients and handlers
        activeClients.clear();
        activeHandlers.clear();

        // Stop scheduled timer
        scheduledTimer.shutdown();

        // Stop the storage I/O worker pool
        if (storageExecutor != null) {
            storageExecutor.shutdown();
            storageExecutor = null;
        }

        // Stop the crypto worker pool
        if (cryptoExecutor != null) {
            cryptoExecutor.shutdown();
            cryptoExecutor = null;
        }

        stopMailboxLifecycle();

        operatorInfo(L10N.getString("info.servers_closed"));
    }

    /** Gives each protocol server its first-phase notice, isolating failures. */
    private void beginShutdownServers() {
        for (Server server : new ArrayList<Server>(servers)) {
            try {
                server.beginShutdown();
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, L10N.getString("log.error_closing_on_shutdown"), e);
            }
        }
    }

    /**
     * Stops every protocol server and standalone listener. Their network
     * work is handed to the loops that own it, so this never closes a
     * connection itself.
     */
    private void stopServers() {
        // Stop all protocol servers (servers stop their own listeners)
        for (int i = 0; i < servers.size(); i++) {
            Server server = servers.get(i);
            server.stop();
        }
        servers.clear();

        // Stop any standalone server listeners. closeServerChannels() covers
        // a listener that was never bound to a running accept loop.
        for (TcpListener server :
                new ArrayList<TcpListener>(serverListeners)) {
            server.stop();
            server.closeServerChannels();
        }
        serverListeners.clear();
    }

    /**
     * Step 1. The accept loop releases the TCP listeners' sockets on its own
     * thread and this returns only when they are really released. Other
     * listeners are told to stop admitting.
     */
    private void stopAccepting() {
        AcceptSelectorLoop accept = acceptLoop;
        if (accept != null && accept.isRunning()) {
            accept.stopAccepting();
        }
        acceptLoopRunning = false;
        for (TcpListener server :
                new ArrayList<TcpListener>(serverListeners)) {
            server.closeServerChannels();
        }
    }

    /**
     * Step 3. Tells every loop to close what it owns, then waits for the
     * loops to exit, bounded. Each loop also closes its DNS resolver first,
     * on its own thread, since that resolver's sockets belong to the loop.
     */
    private void closeLoops() {
        SelectorLoop[] loops = workerLoops;
        AcceptSelectorLoop accept = acceptLoop;
        boolean abort = isAbortRequested();
        if (loops != null) {
            for (int i = 0; i < loops.length; i++) {
                final SelectorLoop loop = loops[i];
                loop.setCloseDeadlineMs(loopCloseDeadlineMs());
                loop.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        DnsResolver.removeForLoop(loop);
                    }
                });
                if (abort) {
                    loop.shutdownNow();
                } else {
                    loop.shutdown();
                }
            }
        }
        if (accept != null && accept.isRunning()) {
            accept.shutdown();
        }
        long waitMs = loopCloseDeadlineMs() + LOOP_QUIESCE_TIMEOUT_MS;
        long deadline = System.nanoTime() + waitMs * 1_000_000L;
        if (loops != null) {
            for (int i = 0; i < loops.length; i++) {
                awaitLoopTerminated(loops[i], deadline);
            }
        }
        if (accept != null) {
            long remaining = Math.max(1L, (deadline - System.nanoTime()) / 1_000_000L);
            try {
                if (Thread.currentThread() != accept.getThread()) {
                    accept.join(remaining);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void awaitLoopTerminated(SelectorLoop loop, long deadlineNanos) {
        long remaining = Math.max(1L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
        if (!loop.awaitQuiesce(remaining)
                && Thread.currentThread() != loop.getThread()
                && LOGGER.isLoggable(Level.WARNING)) {
            LOGGER.warning(L10N.getString("warn.loop_did_not_terminate"));
        }
    }

    /**
     * Operator-visible lifecycle line (matches {@link org.bluezoo.gumdrop.util.LaconicFormatter}).
     * Written to stderr so Ctrl+C / {@code SIGTERM} still show progress after
     * {@code LogManager} shutdown hooks close JUL handlers.
     */
    private static void operatorInfo(String message) {
        System.err.println("INFO: " + message);
        System.err.flush();
    }

    private void awaitShutdownFinished() throws InterruptedException {
        synchronized (lifecycleLock) {
            while (shutdownInProgress) {
                lifecycleLock.wait();
            }
        }
    }

    private static void flushConsoleLogging() {
        for (Logger logger = LOGGER; logger != null; logger = logger.getParent()) {
            Handler[] handlers = logger.getHandlers();
            for (int i = 0; i < handlers.length; i++) {
                handlers[i].flush();
            }
        }
        Handler[] rootHandlers = Logger.getLogger("").getHandlers();
        for (int i = 0; i < rootHandlers.length; i++) {
            rootHandlers[i].flush();
        }
    }

    /**
     * Returns whether Gumdrop is currently draining in-flight connections as
     * part of a graceful shutdown. Intended for readiness/health reporting so
     * orchestrators can observe the draining state.
     *
     * @return true while a graceful shutdown drain is in progress
     */
    public boolean isDraining() {
        return draining;
    }

    /**
     * Returns whether Gumdrop is fully started and ready to serve traffic
     * (all listeners bound and not draining). Intended for readiness probes:
     * it becomes true only once the accept loop has bound the listeners, stays
     * false if any listener failed to bind (see {@link #getBindFailures()}),
     * and flips back to false at the start of {@link #shutdown()}.
     *
     * @return true when the server is ready to accept work
     */
    public boolean isReady() {
        return started && ready && !draining && getBindFailures().isEmpty();
    }

    /**
     * Blocks until the current start sequence has finished binding its
     * listeners (successfully or not). Synchronisation point for tests;
     * the timeout only converts a hang into a failure.
     *
     * @param timeoutMs hang-guard timeout in milliseconds
     * @return false only if the timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    boolean awaitStartupComplete(long timeoutMs) throws InterruptedException {
        return readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Returns the listeners that could not be bound (for example because the
     * port is in use), each as {@code description: reason}. Binding happens on
     * the accept loop after {@link #start()} returns, so this is complete once
     * {@link #isReady()} is true or this is non-empty.
     *
     * @return the bind failures so far, empty if every listener bound
     */
    public List<String> getBindFailures() {
        AcceptSelectorLoop loop = acceptLoop;
        if (loop == null) {
            return Collections.emptyList();
        }
        return loop.getBindFailures();
    }

    /**
     * Returns the graceful-drain timeout in milliseconds.
     *
     * @return the drain timeout, or 0 if draining is disabled
     */
    public long getDrainTimeoutMs() {
        return drainTimeoutMs;
    }

    /**
     * Sets the graceful-drain timeout in milliseconds. 0 disables draining
     * (shutdown force-closes connections immediately).
     *
     * @param drainTimeoutMs the drain timeout in milliseconds
     */
    public void setDrainTimeoutMs(long drainTimeoutMs) {
        this.drainTimeoutMs = drainTimeoutMs;
    }

    /**
     * Returns the total number of in-flight connections currently open across
     * all TCP server listeners.
     */
    private int activeServerConnectionCount() {
        int total = 0;
        for (TcpListener listener :
                new ArrayList<TcpListener>(serverListeners)) {
            total += listener.getActiveConnectionCount();
        }
        return total;
    }

    /**
     * Waits up to {@code timeoutMs} for in-flight server connections to finish,
     * polling their aggregate count. Returns immediately when nothing is in
     * flight (e.g. client-only auto-shutdown). Runs on the caller's thread
     * (the shutdown-hook thread on SIGTERM), so the blocking wait here does
     * not stall the worker loops that are draining the connections.
     */
    private void awaitConnectionsDrained(long timeoutMs) {
        int remaining = activeServerConnectionCount();
        if (remaining == 0) {
            return;
        }
        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info(MessageFormat.format(
                    L10N.getString("info.draining_connections"), remaining, timeoutMs));
        }
        Runnable observer = drainWaitObserver;
        if (observer != null) {
            observer.run();
        }
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (remaining > 0 && !isAbortRequested()) {
            long left = (deadline - System.nanoTime()) / 1_000_000L;
            if (left <= 0L) {
                break;
            }
            synchronized (drainMonitor) {
                if (isAbortRequested()) {
                    break;
                }
                try {
                    drainMonitor.wait(Math.min(left, DRAIN_POLL_INTERVAL_MS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            remaining = activeServerConnectionCount();
        }
        if (remaining > 0) {
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning(MessageFormat.format(
                        L10N.getString("warn.drain_timeout"), remaining));
            }
        } else if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info(L10N.getString("info.drain_complete"));
        }
    }

    /**
     * Waits for all SelectorLoop threads to complete.
     *
     * @throws InterruptedException if the current thread is interrupted
     */
    public void join() throws InterruptedException {
        if (acceptLoop != null) {
            acceptLoop.join();
        }
        for (SelectorLoop loop : workerLoops) {
            loop.join();
        }
    }

    /**
     * Blocks until {@link #shutdown()} has finished and all selector threads
     * have exited. Intended for {@code main} after {@link #boot()} /
     * {@link #addServer(Server)}.
     *
     * <p>On Unix, Ctrl+C often {@linkplain Thread#interrupt() interrupts} the
     * blocked thread instead of running JVM shutdown hooks first. This method
     * treats that interrupt as a shutdown request and calls {@link #shutdown()}
     * before waiting again. {@code SIGTERM} and the registered shutdown hook
     * still call {@code shutdown()} as usual; repeated calls are harmless.
     *
     * @throws InterruptedException if interrupted while waiting for shutdown
     */
    public void awaitShutdown() throws InterruptedException {
        launcherThread = Thread.currentThread();
        try {
            try {
                join();
            } catch (InterruptedException e) {
                Thread.interrupted();
                shutdown();
            }
            awaitShutdownFinished();
            Thread.interrupted();
            join();
        } finally {
            launcherThread = null;
            flushConsoleLogging();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Infrastructure access
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the least-loaded worker SelectorLoop, breaking ties
     * round-robin.
     *
     * <p>Pure round-robin assignment never rebalances: for protocols with
     * widely varying connection lifetimes (e.g. IMAP IDLE sessions
     * alongside short-lived HTTP requests), long-lived connections can
     * accumulate unevenly across loops with no mechanism to migrate or
     * even out the skew (issue #139). Scanning every loop's current
     * {@link SelectorLoop#getConnectionCount()} on each call is cheap -
     * this runs once per accept/outbound-connection, not per request -
     * and the scan starts from the next round-robin position so loops
     * that are equally (un)loaded, such as at startup, are still spread
     * evenly rather than always favoring index 0.
     *
     * <p>Thread-safe for use from AcceptSelectorLoop, datagram registration,
     * and client connections that need a SelectorLoop.
     *
     * @return the least-loaded SelectorLoop
     */
    public SelectorLoop nextWorkerLoop() {
        int n = workerLoops.length;
        // Math.floorMod keeps the index non-negative once the counter wraps
        // past Integer.MAX_VALUE; a plain % would yield a negative index and
        // an ArrayIndexOutOfBoundsException after ~2.1 billion assignments.
        int start = Math.floorMod(nextWorker.getAndIncrement(), n);
        SelectorLoop best = workerLoops[start];
        int bestLoad = best.getConnectionCount();
        for (int i = 1; i < n; i++) {
            SelectorLoop candidate = workerLoops[Math.floorMod(start + i, n)];
            int load = candidate.getConnectionCount();
            if (load < bestLoad) {
                best = candidate;
                bestLoad = load;
            }
        }
        return best;
    }

    /**
     * Returns the accept loop.
     *
     * <p>This is primarily for internal use and dynamic server registration.
     *
     * @return the AcceptSelectorLoop, or null if no servers have been added
     */
    public AcceptSelectorLoop getAcceptLoop() {
        return acceptLoop;
    }

    /**
     * Schedules a timer callback for a handler.
     *
     * <p>The callback will be executed on the handler's SelectorLoop thread.
     *
     * @param handler the handler that will receive the callback
     * @param delayMs delay in milliseconds
     * @param callback the callback to execute
     * @return a handle that can be used to cancel the timer
     */
    public TimerHandle scheduleTimer(ChannelHandler handler, long delayMs, Runnable callback) {
        // Prefer the handler's own SelectorLoop timer to avoid contending on a
        // single process-wide timer lock under high connection churn. The
        // callback fires on that loop's thread either way. Fall back to the
        // shared timer for handlers not yet assigned to a loop.
        if (handler != null) {
            SelectorLoop loop = handler.getSelectorLoop();
            if (loop != null) {
                ScheduledTimer loopTimer = loop.getTimer();
                if (loopTimer != null && loopTimer.isRunning()) {
                    return loopTimer.schedule(handler, delayMs, callback);
                }
            }
        }
        // Fall back to the shared timer -- lazily start it if this is the
        // first time it's needed, e.g. a handler whose own SelectorLoop
        // timer isn't (yet, or any longer) running and no full
        // Gumdrop.start() has brought this singleton's own timer up.
        synchronized (this) {
            if (scheduledTimer == null || !scheduledTimer.isRunning()) {
                scheduledTimer = new ScheduledTimer();
                scheduledTimer.start();
            }
        }
        return scheduledTimer.schedule(handler, delayMs, callback);
    }

    /**
     * Returns the shared storage I/O worker pool used to run blocking
     * filesystem operations off the SelectorLoop threads.
     *
     * <p>Available after {@link #start()}. Protocol handlers that would
     * otherwise perform blocking disk I/O (mailbox scans, directory listing,
     * rename/delete, expunge, whole-message reads, ...) should submit that
     * work here and resume on their loop via the callback.
     *
     * @return the storage executor, or null if the server has not been started
     * @see StorageExecutor#submit
     */
    public StorageExecutor getStorageExecutor() {
        return storageExecutor;
    }

    /**
     * Returns the shared crypto worker pool used to run CPU-bound TLS
     * handshake delegated tasks off the SelectorLoop threads.
     *
     * <p>Available after {@link #start()}. {@link SSLState} and {@code
     * DTLSSession} submit TLS/DTLS handshake delegated tasks (RSA/ECDHE key
     * exchange, certificate chain validation) here and resume on the
     * connection's loop via the callback; {@code
     * org.bluezoo.gumdrop.quic.tls.QuicHandshakeAsyncOffload} does the same
     * for QUIC's TLS 1.3 handshake (issue #300), which is why this is
     * public rather than package-private -- that class lives in a
     * different package.
     *
     * @return the crypto executor, or null if the server has not been started
     * @see CryptoExecutor#submit
     */
    public CryptoExecutor getCryptoExecutor() {
        return cryptoExecutor;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shutdown hook
    // ─────────────────────────────────────────────────────────────────────────

    private class ShutdownHook extends Thread {
        ShutdownHook() {
            super("Gumdrop-ShutdownHook");
        }

        @Override
        public void run() {
            Thread launcher = launcherThread;
            if (launcher != null) {
                launcher.interrupt();
                try {
                    launcher.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            shutdown();
        }
    }

    private void startMailboxLifecycle() {
        for (MailboxLifecycle lifecycle : ServiceLoader.load(MailboxLifecycle.class)) {
            try {
                lifecycle.onServerStart();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, L10N.getString("log.could_not_start_mailbox_infrastructure"), e);
            }
        }
    }

    private void stopMailboxLifecycle() {
        for (MailboxLifecycle lifecycle : ServiceLoader.load(MailboxLifecycle.class)) {
            try {
                lifecycle.onServerStop();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, L10N.getString("log.error_stopping_mailbox_infrastructure"), e);
            }
        }
    }

}
