/*
 * Service.java
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
 * A service represents a unit of application logic that owns one or more
 * transport listeners (endpoints).
 *
 * <p>Services are the primary configuration entity in Gumdrop. Each service
 * defines <em>what</em> to do with connections or requests (protocol
 * behaviour, authentication, routing), while its listeners define
 * <em>where</em> to listen (ports, addresses, TLS configuration).
 *
 * <p>The lifecycle contract is:
 * <ol>
 * <li>{@link #start()} initialises application logic, then wires and
 *     starts all listeners.</li>
 * <li>{@link #stop()} stops all listeners (static and dynamic), then
 *     tears down application logic.</li>
 * </ol>
 *
 * <p>Services may own both <em>static</em> listeners (declared in
 * configuration) and <em>dynamic</em> listeners (created at runtime,
 * e.g., FTP data connections or cluster multicast endpoints).
 * {@link #getListeners()} returns all current listeners of both kinds.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see TCPListener
 */
public interface Service {

    /**
     * Returns all current listeners owned by this service, including
     * both static (configured) and dynamic (runtime-created) listeners.
     *
     * <p>The returned list may change over the lifetime of the service
     * as dynamic listeners are created and destroyed.
     *
     * @return a list of listener endpoints, never null
     */
    List getListeners();

    /**
     * Starts this service. Implementations should first initialise
     * application-level resources (containers, thread pools, caches),
     * then wire and start each listener.
     */
    void start();

    /**
     * Stops this service. Implementations should first stop all
     * listeners (both static and dynamic), then tear down
     * application-level resources.
     */
    void stop();

}
