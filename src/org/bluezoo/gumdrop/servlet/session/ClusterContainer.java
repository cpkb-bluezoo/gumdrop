/*
 * ClusterContainer.java
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

package org.bluezoo.gumdrop.servlet.session;

/**
 * Interface providing cluster configuration from the servlet container.
 *
 * <p>This interface is implemented by the servlet container to provide
 * cluster configuration and context lookup for distributed session
 * replication. It allows the session package to work with the container
 * without a direct dependency on the Container class.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ClusterContainer {

    /**
     * Returns the port used for cluster communication.
     *
     * @return the cluster port number
     */
    int getClusterPort();

    /**
     * Returns the multicast group address for cluster discovery.
     *
     * @return the multicast group address (e.g., "224.0.80.80")
     */
    String getClusterGroupAddress();

    /**
     * Returns the shared secret key for cluster encryption.
     * The key must be 32 bytes (256 bits) for AES-256.
     *
     * @return the cluster encryption key, or null if clustering is disabled
     */
    byte[] getClusterKey();

    /**
     * Looks up a context by its digest.
     * Used to route incoming cluster messages to the correct context.
     *
     * @param digest the context digest to look up
     * @return the matching SessionContext, or null if not found
     */
    SessionContext getContextByDigest(byte[] digest);

    /**
     * Returns all contexts that have distributable sessions.
     * Used when a new node joins the cluster and needs all sessions.
     *
     * @return iterable of distributable session contexts
     */
    Iterable<SessionContext> getDistributableContexts();

}

