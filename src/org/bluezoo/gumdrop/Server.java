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
 * <li>{@link #start()} initialises application logic, then wires and
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
 * handlers instead — see {@code docs/COMPOSITION.md}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerSessionProvider
 * @see ClientSessionProvider
 * @see docs/COMPOSITION.md
 * @see Service
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
     */
    void start();

    /**
     * Stops this server. Implementations should first stop all
     * listeners (both static and dynamic), then tear down
     * application-level resources.
     */
    void stop();

}
