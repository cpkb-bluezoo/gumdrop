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

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central configuration and lifecycle manager for the Gumdrop server.
 *
 * <p>Gumdrop is a singleton that manages the core infrastructure for event-driven
 * I/O processing: worker SelectorLoops, the AcceptSelectorLoop for TCP servers,
 * and the scheduled timer for timeouts.
 *
 * <h4>Server Mode</h4>
 * <pre>{@code
 * // Get instance with configuration
 * Gumdrop gumdrop = Gumdrop.getInstance(new File("/etc/gumdroprc"));
 *
 * // Or configure programmatically
 * Gumdrop gumdrop = Gumdrop.getInstance();
 * gumdrop.addServer(new HTTPServer(8080));
 * gumdrop.addServer(new SMTPServer(25));
 *
 * // Start processing
 * gumdrop.start();
 * }</pre>
 *
 * <h4>Client Mode</h4>
 * <pre>{@code
 * // Clients use getInstance() internally - no setup needed
 * RedisClient client = new RedisClient("localhost", 6379);
 * client.connect(handler);
 * // Infrastructure auto-starts on connect, auto-stops when done
 * }</pre>
 *
 * <h4>Lifecycle</h4>
 * <ul>
 *   <li>Infrastructure is created lazily on first {@code getInstance()} call</li>
 *   <li>{@code start()} begins event processing</li>
 *   <li>Auto-shutdown when no servers and no active handlers remain</li>
 *   <li>Can restart after shutdown by calling {@code start()} again</li>
 *   <li>JVM shutdown hook ensures cleanup</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Gumdrop {

    public static final String VERSION = "1.1";

    /** Default worker count for client-only mode (no configuration). */
    private static final int CLIENT_MODE_WORKERS = 1;

    /** Default worker count for server mode (with configuration). */
    private static final int SERVER_MODE_WORKERS = Runtime.getRuntime().availableProcessors() * 2;

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.L10N");
    static final Logger LOGGER = Logger.getLogger(Gumdrop.class.getName());

    // Singleton instance
    private static Gumdrop instance;

    // TCP servers (controls AcceptSelectorLoop lifecycle)
    private final List<Server> servers;

    // Active channel handlers (controls worker/timer lifecycle)
    private final Set<ChannelHandler> activeHandlers;

    // Infrastructure
    private AcceptSelectorLoop acceptLoop;
    private SelectorLoop[] workerLoops;
    private final int workerCount;
    private final AtomicInteger nextWorker;
    private ScheduledTimer scheduledTimer;

    // State
    private volatile boolean started;
    private volatile boolean acceptLoopRunning;

    // ─────────────────────────────────────────────────────────────────────────
    // Singleton access
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the singleton Gumdrop instance, creating it if necessary.
     *
     * <p>This method creates a minimal instance suitable for client-only use:
     * <ul>
     *   <li>1 worker thread</li>
     *   <li>No servers configured</li>
     *   <li>No AcceptSelectorLoop (created on first addServer)</li>
     * </ul>
     *
     * <p>For server mode with configuration file, use {@link #getInstance(File)}.
     *
     * @return the singleton Gumdrop instance
     */
    public static Gumdrop getInstance() {
        return getInstance(null);
    }

    /**
     * Returns the singleton Gumdrop instance, creating it if necessary.
     *
     * <p>If a configuration file is provided, it is parsed and the instance
     * is configured with the specified servers and worker count.
     *
     * <p>If the configuration file is null, a minimal client-only instance
     * is created with 1 worker thread.
     *
     * @param gumdroprc the configuration file, or null for client-only mode
     * @return the singleton Gumdrop instance
     */
    public static Gumdrop getInstance(File gumdroprc) {
        synchronized (Gumdrop.class) {
            if (instance == null) {
                int workerCount;
                Collection<Server> initialServers = null;

                if (gumdroprc != null && gumdroprc.exists()) {
                    // Server mode with configuration
                    workerCount = Integer.getInteger("gumdrop.workers", SERVER_MODE_WORKERS);
                    try {
                        ParseResult parseResult = new ConfigurationParser().parse(gumdroprc);
                        initialServers = parseResult.getServers();
                        // TODO: handle worker count from config, registry cleanup
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Failed to parse configuration: " + gumdroprc, e);
                        // Fall back to empty config
                        workerCount = SERVER_MODE_WORKERS;
                    }
                } else {
                    // Client-only mode
                    workerCount = CLIENT_MODE_WORKERS;
                }

                instance = new Gumdrop(workerCount);

                // Add any servers from configuration
                if (initialServers != null) {
                    for (Server server : initialServers) {
                        instance.addServer(server);
                    }
                }
            }
            return instance;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Construction (private)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Private constructor - use getInstance() to obtain the singleton.
     */
    private Gumdrop(int workerCount) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount must be at least 1");
        }

        this.servers = Collections.synchronizedList(new ArrayList<Server>());
        this.activeHandlers = Collections.newSetFromMap(new ConcurrentHashMap<ChannelHandler, Boolean>());
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
    // Server management
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Adds a TCP server to be managed by Gumdrop.
     *
     * <p>If Gumdrop has already been started, the server is registered
     * immediately and begins accepting connections. Otherwise, it will
     * be registered when {@link #start()} is called.
     *
     * @param server the server to add
     */
    public void addServer(Server server) {
        servers.add(server);
        server.start(); // Initialize connector, SSL context, etc.

        // If already started, handle dynamic registration
        if (started) {
            // Create or recreate AcceptSelectorLoop if needed
            if (acceptLoop == null || !acceptLoop.isRunning()) {
                acceptLoop = new AcceptSelectorLoop(this);
                acceptLoop.start();
                acceptLoopRunning = true;
            }
            acceptLoop.registerServer(server);
        }
    }

    /**
     * Removes a TCP server from Gumdrop.
     *
     * <p>The server stops accepting new connections immediately.
     * Existing connections continue until they close naturally.
     *
     * <p>If this was the last server, the AcceptSelectorLoop is shut down.
     *
     * @param server the server to remove
     */
    public void removeServer(Server server) {
        servers.remove(server);
        try {
            server.stop();
            server.closeServerChannels();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error closing server: " + e.getMessage(), e);
        }

        // If no more servers, shut down AcceptSelectorLoop
        if (servers.isEmpty() && acceptLoopRunning) {
            acceptLoop.shutdown();
            acceptLoopRunning = false;
        }

        checkAutoShutdown();
    }

    /**
     * Returns the collection of TCP servers managed by this Gumdrop instance.
     *
     * @return unmodifiable view of the servers
     */
    public Collection<Server> getServers() {
        return Collections.unmodifiableList(servers);
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
     * <p>Called when a connection closes or fails. If no servers and no
     * handlers remain, triggers automatic shutdown.
     *
     * @param handler the handler to deregister
     */
    public void removeChannelHandler(ChannelHandler handler) {
        activeHandlers.remove(handler);
        checkAutoShutdown();
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
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Starts the Gumdrop infrastructure.
     *
     * <p>This starts all worker SelectorLoops and the scheduled timer.
     * If servers are registered, the AcceptSelectorLoop is also started.
     *
     * <p>If already started, this method is a no-op.
     *
     * <p>If no servers and no active handlers exist when start() completes,
     * the infrastructure will shut down automatically.
     *
     * <p>Can be called after shutdown() to restart the infrastructure.
     */
    public void start() {
        if (started) {
            return;
        }

        long t1 = System.currentTimeMillis();
        started = true;

        // Create or recreate scheduled timer
        if (scheduledTimer == null || !scheduledTimer.isRunning()) {
            scheduledTimer = new ScheduledTimer();
        }
        scheduledTimer.start();

        // Create or recreate worker loops (1-based naming for humans)
        if (workerLoops == null) {
            workerLoops = new SelectorLoop[workerCount];
            for (int i = 0; i < workerCount; i++) {
                workerLoops[i] = new SelectorLoop(i + 1);
            }
        } else {
            // Recreate any loops that were shut down
            for (int i = 0; i < workerCount; i++) {
                if (!workerLoops[i].isRunning()) {
                    workerLoops[i] = new SelectorLoop(i + 1);
                }
            }
        }

        // Start worker loops
        for (SelectorLoop loop : workerLoops) {
            loop.start();
        }

        // Start AcceptSelectorLoop if we have servers
        if (!servers.isEmpty()) {
            // Create or recreate AcceptSelectorLoop
            if (acceptLoop == null || !acceptLoop.isRunning()) {
                acceptLoop = new AcceptSelectorLoop(this);
            }
            acceptLoop.start();
            acceptLoopRunning = true;
        }

        long t2 = System.currentTimeMillis();
        if (LOGGER.isLoggable(Level.INFO)) {
            String message = L10N.getString("info.started_gumdrop");
            message = MessageFormat.format(message, (t2 - t1));
            LOGGER.info(message);
        }

        // Schedule check for empty infrastructure
        // (deferred to allow caller to register clients/servers)
        workerLoops[0].invokeLater(new Runnable() {
            @Override
            public void run() {
                checkAutoShutdown();
            }
        });
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
     * <p>Shutdown occurs when:
     * <ul>
     *   <li>No TCP servers are registered</li>
     *   <li>No active channel handlers exist</li>
     *   <li>Gumdrop has been started (not during initial setup)</li>
     * </ul>
     */
    private void checkAutoShutdown() {
        if (!started) {
            return;
        }
        if (servers.isEmpty() && activeHandlers.isEmpty()) {
            shutdown();
        }
    }

    /**
     * Shuts down the Gumdrop infrastructure gracefully.
     *
     * <p>After shutdown, {@link #start()} can be called again to restart.
     */
    public void shutdown() {
        if (!started) {
            return;
        }

        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info(L10N.getString("info.closing_servers"));
        }

        // Stop accepting new TCP connections
        if (acceptLoopRunning) {
            acceptLoop.shutdown();
            acceptLoopRunning = false;
        }

        // Stop all TCP servers
        for (Server server : new ArrayList<Server>(servers)) {
            try {
                server.stop();
                server.closeServerChannels();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error closing server: " + e.getMessage(), e);
            }
        }
        servers.clear();

        // Stop worker loops
        for (SelectorLoop loop : workerLoops) {
            loop.shutdown();
        }

        // Stop scheduled timer
        scheduledTimer.shutdown();

        started = false;

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Gumdrop shutdown complete");
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

    // ─────────────────────────────────────────────────────────────────────────
    // Infrastructure access
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the next worker SelectorLoop using round-robin assignment.
     *
     * <p>Thread-safe for use from AcceptSelectorLoop, datagram registration,
     * and client connections that need a SelectorLoop.
     *
     * @return the next SelectorLoop in round-robin order
     */
    public SelectorLoop nextWorkerLoop() {
        int idx = nextWorker.getAndIncrement() % workerLoops.length;
        return workerLoops[idx];
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
    TimerHandle scheduleTimer(ChannelHandler handler, long delayMs, Runnable callback) {
        return scheduledTimer.schedule(handler, delayMs, callback);
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
            shutdown();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main entry point
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Main entry point for running Gumdrop as a standalone server.
     *
     * @param args command line arguments (optional: path to gumdroprc)
     */
    public static void main(String[] args) {
        // Determine configuration file location
        File gumdroprc;
        if (args.length > 0) {
            gumdroprc = new File(args[0]);
        } else {
            gumdroprc = new File(System.getProperty("user.home") + File.separator + ".gumdroprc");
        }
        if (!gumdroprc.exists()) {
            gumdroprc = new File("/etc/gumdroprc");
        }
        if (!gumdroprc.exists()) {
            System.out.println(L10N.getString("err.syntax"));
            System.exit(1);
        }

        System.out.println(L10N.getString("banner"));

        // Get instance with configuration
        Gumdrop gumdrop = getInstance(gumdroprc);

        // Start
        gumdrop.start();

        // Wait for shutdown
        try {
            gumdrop.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        LOGGER.info(L10N.getString("info.gumdrop_end_loop"));
    }

}
