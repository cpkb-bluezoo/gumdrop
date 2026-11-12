/*
 * NodeAdapter.java
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

import java.util.List;

/**
 * Receives the RESP3 push messages (type {@code >}) that the server sends
 * outside any command's reply and that are not Pub/Sub deliveries for a
 * {@link MessageHandler}: for example the {@code invalidate} push of
 * client-side caching ({@code CLIENT TRACKING}). Without one such pushes
 * are discarded.
 *
 * <p>Pushes arrive only on a connection that negotiated RESP3 (see
 * {@code RedisClient.protocol} and {@code RedisClusterClient.protocol}).
 * Called on the selector loop that owns the connection, so it needs no
 * locking against the connection's other callbacks.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see RedisSession#setPushHandler(PushHandler)
 */
public interface PushHandler {

    /**
     * Called for a push the connection does not otherwise handle.
     *
     * @param kind the push's first element, for example {@code invalidate}
     * @param payload the remaining elements, converted to plain Java
     *        values: simple, bulk and verbatim strings as {@code String}
     *        (decoded as UTF-8), integers as {@code Long}, doubles as
     *        {@code Double}, booleans as {@code Boolean}, nulls as
     *        {@code null}, arrays, sets and pushes as {@code List}, maps as
     *        {@code Map}. An {@code invalidate} push carries one element:
     *        the list of invalidated keys, or {@code null} when the whole
     *        cache is to be flushed.
     */
    void onPush(String kind, List<Object> payload);
}
