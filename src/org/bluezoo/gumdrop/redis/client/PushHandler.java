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

import org.bluezoo.gumdrop.redis.codec.RespEventHandler;

/**
 * Receives the RESP3 push messages (type {@code >}) that the server sends
 * outside any command's reply and that are not Pub/Sub deliveries for a
 * {@link MessageHandler}: for example the {@code invalidate} push of
 * client-side caching ({@code CLIENT TRACKING}). Without one such pushes
 * are discarded.
 *
 * <p>A push is delivered as the events the decoder reads: {@code beginReply}
 * with {@code push} true, an aggregate of kind
 * {@link org.bluezoo.gumdrop.redis.codec.RespType#PUSH} whose first element
 * is the kind of the push, its other elements, and {@code endReply}. Nothing
 * is held by the library. Every event that can continue returns a
 * {@link org.bluezoo.gumdrop.redis.codec.ReplyFlow}: skip discards the rest
 * of that push, and pause stops reading until {@link ReplyStream#resume()}.
 * For client-side caching, {@link InvalidationHandler} does the reading.
 *
 * <p>Pushes arrive only on a connection that negotiated RESP3 (see
 * {@code RedisClient.protocol} and {@code RedisClusterClient.protocol}).
 * Called on the selector loop that owns the connection, so it needs no
 * locking against the connection's other callbacks.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see RedisSession#setPushHandler(PushHandler)
 */
public interface PushHandler extends RespEventHandler {

    /**
     * Called before each push, with the handle to resume a paused
     * connection or to give up the push.
     *
     * @param stream the handle
     */
    void handleStream(ReplyStream stream);

}
