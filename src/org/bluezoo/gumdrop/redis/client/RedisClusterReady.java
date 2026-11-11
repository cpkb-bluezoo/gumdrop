/*
 * RedisClusterReady.java
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

package org.bluezoo.gumdrop.redis.client;

/**
 * Receives a {@link RedisClusterClient}'s session once the cluster's
 * topology has been loaded.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface RedisClusterReady {

    /**
     * Called when the cluster is ready for commands. Commands sent on
     * {@code session} are routed to the node serving each key.
     *
     * @param session the session for the whole cluster
     */
    void handleReady(RedisSession session);

    /**
     * Called if the cluster could not be reached: no seed node answered
     * with the cluster topology.
     *
     * @param cause the reason
     */
    void onError(Exception cause);
}
