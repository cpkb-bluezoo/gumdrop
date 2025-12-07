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
import java.util.Collection;
import java.util.Collections;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central configuration and lifecycle manager for the Gumdrop server.
 * Manages the AcceptSelectorLoop and worker SelectorLoop pool.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Gumdrop {

    public static final String VERSION = "0.9";

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.L10N");
    static final Logger LOGGER = Logger.getLogger(Gumdrop.class.getName());

    private static Gumdrop instance;

    private final Collection<Server> servers;
    private final Collection<DatagramServer> datagramServers;
    private final AcceptSelectorLoop acceptLoop;
    private final SelectorLoop[] workerLoops;
    private final AtomicInteger nextWorker;
    private final ScheduledTimer scheduledTimer;

    /**
     * Returns the singleton Gumdrop instance.
     */
    public static Gumdrop getInstance() {
        return instance;
    }

    /**
     * Creates a new Gumdrop instance with the default number of worker threads
     * (2 x available processors).
     *
     * @param servers the collection of TCP servers to serve
     */
    public Gumdrop(Collection<Server> servers) {
        this(servers, Collections.emptyList(), Runtime.getRuntime().availableProcessors() * 2);
    }

    /**
     * Creates a new Gumdrop instance with the default number of worker threads
     * (2 x available processors).
     *
     * @param servers the collection of TCP servers to serve
     * @param datagramServers the collection of UDP servers to serve
     */
    public Gumdrop(Collection<Server> servers, Collection<DatagramServer> datagramServers) {
        this(servers, datagramServers, Runtime.getRuntime().availableProcessors() * 2);
    }

    /**
     * Creates a new Gumdrop instance with a specified number of worker threads.
     *
     * @param servers the collection of TCP servers to serve
     * @param workerCount the number of worker SelectorLoop threads
     */
    public Gumdrop(Collection<Server> servers, int workerCount) {
        this(servers, Collections.emptyList(), workerCount);
    }

    /**
     * Creates a new Gumdrop instance with a specified number of worker threads.
     *
     * @param servers the collection of TCP servers to serve
     * @param datagramServers the collection of UDP servers to serve
     * @param workerCount the number of worker SelectorLoop threads
     */
    public Gumdrop(Collection<Server> servers, Collection<DatagramServer> datagramServers, int workerCount) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount must be at least 1");
        }
        instance = this;
        this.servers = servers;
        this.datagramServers = datagramServers;
        this.nextWorker = new AtomicInteger(0);

        // Create worker loops (1-based naming for humans)
        this.workerLoops = new SelectorLoop[workerCount];
        for (int i = 0; i < workerCount; i++) {
            workerLoops[i] = new SelectorLoop(i + 1);
        }

        // Create accept loop
        this.acceptLoop = new AcceptSelectorLoop(this);

        // Create scheduled timer
        this.scheduledTimer = new ScheduledTimer();

        Runtime.getRuntime().addShutdownHook(new ShutdownHook());
    }

    /**
     * Returns the accept loop.
     * This is necessary for dynamic server registration.
     */
    public AcceptSelectorLoop getAcceptLoop() {
        return acceptLoop;
    }

    /**
     * Returns the collection of TCP servers managed by this Gumdrop instance.
     */
    public Collection<Server> getServers() {
        return servers;
    }

    /**
     * Returns the collection of UDP servers managed by this Gumdrop instance.
     */
    public Collection<DatagramServer> getDatagramServers() {
        return datagramServers;
    }

    /**
     * Returns the next worker SelectorLoop using round-robin assignment.
     * Thread-safe for use from AcceptSelectorLoop and datagram registration.
     */
    SelectorLoop nextWorkerLoop() {
        int idx = nextWorker.getAndIncrement() % workerLoops.length;
        return workerLoops[idx];
    }

    /**
     * Schedules a timer callback for a handler.
     * The callback will be executed on the handler's SelectorLoop thread.
     *
     * @param handler the handler that will receive the callback
     * @param delayMs delay in milliseconds
     * @param callback the callback to execute
     * @return a handle that can be used to cancel the timer
     */
    TimerHandle scheduleTimer(ChannelHandler handler, long delayMs, Runnable callback) {
        return scheduledTimer.schedule(handler, delayMs, callback);
    }

    /**
     * Starts the Gumdrop server.
     * This starts all worker SelectorLoops, then the AcceptSelectorLoop,
     * and opens all datagram servers.
     */
    public void start() {
        long t1 = System.currentTimeMillis();

        // Start scheduled timer
        scheduledTimer.start();

        // Start worker loops first
        for (SelectorLoop loop : workerLoops) {
            loop.start();
        }

        // Start all TCP servers (initializes their connectors)
        for (Server server : servers) {
            server.start();
        }

        // Start accept loop (will begin accepting TCP connections)
        acceptLoop.start();

        // Open all datagram servers (registers with worker loops)
        for (DatagramServer datagramServer : datagramServers) {
            try {
                datagramServer.open();
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Failed to open datagram server: " + 
                           datagramServer.getDescription(), e);
            }
        }

        long t2 = System.currentTimeMillis();
        if (LOGGER.isLoggable(Level.INFO)) {
            String message = L10N.getString("info.started_gumdrop");
            message = MessageFormat.format(message, (t2 - t1));
            LOGGER.info(message);
        }
    }

    /**
     * Shuts down the Gumdrop server gracefully.
     */
    public void shutdown() {
        LOGGER.info(L10N.getString("info.closing_servers"));

        // Stop accepting new TCP connections
        acceptLoop.shutdown();

        // Stop all TCP servers
        for (Server server : servers) {
            try {
                server.stop();
                server.closeServerChannels();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error closing server: " + e.getMessage(), e);
            }
        }

        // Close all datagram servers
        for (DatagramServer datagramServer : datagramServers) {
            datagramServer.close();
        }

        // Stop worker loops
        for (SelectorLoop loop : workerLoops) {
            loop.shutdown();
        }

        // Stop scheduled timer
        scheduledTimer.shutdown();
    }

    /**
     * Waits for all SelectorLoop threads to complete.
     */
    public void join() throws InterruptedException {
        acceptLoop.join();
        for (SelectorLoop loop : workerLoops) {
            loop.join();
        }
    }

    private class ShutdownHook extends Thread {
        @Override
        public void run() {
            shutdown();
        }
    }

    /**
     * Shutdown task for component registry cleanup.
     */
    private static class RegistryShutdownTask implements Runnable {
        private final ComponentRegistry registry;

        RegistryShutdownTask(ComponentRegistry registry) {
            this.registry = registry;
        }

        @Override
        public void run() {
            registry.shutdown();
        }
    }

    // -- Main entry point --

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

        // Parse configuration file to get component registry and servers
        ParseResult parseResult;
        try {
            long t1 = System.currentTimeMillis();
            parseResult = new ConfigurationParser().parse(gumdroprc);
            long t2 = System.currentTimeMillis();
            if (LOGGER.isLoggable(Level.FINE)) {
                String message = L10N.getString("info.read_configuration");
                message = MessageFormat.format(message, (t2 - t1));
                LOGGER.fine(message);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to parse configuration file: " + gumdroprc, e);
            System.exit(2);
            return;
        }

        // Get servers from the component registry
        Collection<Server> servers = parseResult.getServers();

        // Determine worker count from system property or default
        int workerCount = Integer.getInteger("gumdrop.workers",
                Runtime.getRuntime().availableProcessors() * 2);

        // Create and start Gumdrop
        Gumdrop gumdrop = new Gumdrop(servers, workerCount);

        // Register shutdown hook to clean up component registry
        final ComponentRegistry registry = parseResult.getRegistry();
        Runtime.getRuntime().addShutdownHook(new Thread(new RegistryShutdownTask(registry)));

        System.out.println(L10N.getString("banner"));
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

