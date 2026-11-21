/*
 * ArrayResultHandler.java
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

import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.redis.codec.ReplyFlow;

/**
 * Receives an array of string payloads, element by element, each element as
 * chunks: the reply of {@code MGET}, {@code LRANGE}, {@code SMEMBERS},
 * {@code ZRANGE} and the like.
 *
 * <p>Nothing is held by the library, so neither the number of elements nor
 * the size of any one is limited by it. The array is {@link #handleBegin},
 * then for each element either {@link #handleElementNull} or
 * {@link #handleElementBegin}, {@link #handleElementContent} calls and
 * {@link #handleElementEnd}, then {@link #handleEnd}. A reply that is null
 * as a whole is {@link #handleNull}. {@link #handleComplete} always comes
 * last, once.
 *
 * <p>An element that is not a string (an integer, a double) is delivered as
 * a payload of its text. All calls are made on the connection's event loop
 * and must not block.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ArrayResultHandler {

    /**
     * The command returned null instead of an array.
     */
    void handleNull();

    /**
     * The array begins.
     *
     * @param count the number of elements, or -1 if the server did not say
     * (a RESP3 streamed array)
     * @param stream a handle to resume or cancel the reply
     * @return what to do next: continue, pause or skip the rest
     */
    ReplyFlow handleBegin(long count, ReplyStream stream);

    /**
     * An element is null (for example a key missing from an {@code MGET}).
     *
     * @return what to do next
     */
    ReplyFlow handleElementNull();

    /**
     * An element begins.
     *
     * @param length its length in bytes, or -1 if the server did not say
     * @return what to do next
     */
    ReplyFlow handleElementBegin(long length);

    /**
     * The next bytes of the element, never empty. The buffer is valid only
     * during the call.
     *
     * @param chunk the bytes, from its position to its limit
     * @return what to do next
     */
    ReplyFlow handleElementContent(ByteBuffer chunk);

    /**
     * The element is complete.
     *
     * @return what to do next
     */
    ReplyFlow handleElementEnd();

    /**
     * The array is complete.
     */
    void handleEnd();

    /**
     * The command is over, whether it succeeded or not. The last call.
     *
     * @param error null if it succeeded, otherwise the error
     * @param session the session for further operations
     */
    void handleComplete(String error, RedisSession session);

}
