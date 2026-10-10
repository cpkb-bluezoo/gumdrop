/*
 * Server.java
 * Copyright (C) 2026 Chris Burdess
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

import java.util.List;

/**
 * A protocol server owns one or more transport {@link Listener}s and the
 * application wiring (handlers, session providers, authentication) for each.
 *
 * <p>Servers are the primary application-tier configuration entity in Gumdrop 3.
 * Each server defines <em>what</em> to do with connections or requests, while
 * its listeners define <em>where</em> to listen (ports, addresses, TLS).
 *
 * <p>The lifecycle contract is:
 * <ol>
 * <li>{@link #start(Gumdrop)} initialises application logic, then wires and
 *     starts all listeners.</li>
 * <li>{@link #stop()} stops all listeners (static and dynamic), then
 *     tears down application logic.</li>
 * </ol>
 *
 * <p>Servers may own both <em>static</em> listeners (declared in
 * configuration) and <em>dynamic</em> listeners (created at runtime,
 * e.g., FTP data connections or cluster multicast endpoints).
 * {@link #getListeners()} returns all current listeners of both kinds.
 *
 * <p>Stateful protocol servers ({@code SmtpServer}, {@code FtpServer}, …)
 * compose application logic via {@link ServerSessionProvider}. Stateless
 * servers ({@link org.bluezoo.gumdrop.http.HttpServer},
 * {@link org.bluezoo.gumdrop.dns.server.DnsServer}) compose request or query
 * handlers instead — see {@code web/configuration.html}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerSessionProvider
 * @see <a href="https://github.com/cpkb-bluezoo/gumdrop/blob/main/web/configuration.html">web/configuration.html</a>
 */
public interface Server {

    /**
     * Returns all current listeners owned by this server, including
     * both static (configured) and dynamic (runtime-created) listeners.
     *
     * <p>The returned list may change over the lifetime of the server
     * as dynamic listeners are created and destroyed.
     *
     * @return a list of listener endpoints, never null
     */
    // Raw type is intentional here: implementations return lists of
    // different concrete Listener subtypes (e.g. List<MqttListener>,
    // List<SocksListener>), and parameterizing this method would ripple
    // unrelated [unchecked] warnings through many protocol classes.
    @SuppressWarnings("rawtypes")
    List getListeners();

    /**
     * Starts this server. Implementations should first initialise
     * application-level resources (containers, thread pools, caches),
     * then wire and start each listener.
     *
     * @param gumdrop the runtime this server is starting under
     */
    void start(Gumdrop gumdrop);

    /**
     * First phase of an orderly shutdown: anything this server must say on
     * the network on its way out (an mDNS goodbye, announcing departure).
     * Called by {@link Gumdrop#shutdown()} after the listening sockets have
     * been released and before the drain; never called for an abort
     * ({@link Gumdrop#shutdownNow()}), which sends no goodbyes.
     *
     * <p>Network work must be handed to the loop that owns the endpoint
     * (see {@link Endpoint#execute}), never done on the calling thread; the
     * loop flushes it before it closes the endpoint. Must not tear down
     * application state: connections are still being served. The default
     * does nothing.
     */
    default void beginShutdown() {
    }

    /**
     * Final phase: stops this server once every connection has been closed
     * by its loop (so every {@code disconnected()} callback has run against
     * a live application). Implementations tear down application-level
     * resources (containers, caches, pools) and must not do network I/O or
     * touch loop-owned endpoints, which are already closed and whose loops
     * may have terminated; a listener's own {@code stop()} skips endpoints
     * that are already closed. Also used on its own to remove a server from
     * a running instance, in which case a listener hands any remaining
     * close to its loop.
     */
    void stop();

}
