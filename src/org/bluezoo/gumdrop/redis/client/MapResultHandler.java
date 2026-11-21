/*
 * MapResultHandler.java
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
 * Receives pairs of an identifier and a string payload: the reply of
 * {@code HGETALL}, whether the server sends a RESP3 map or a RESP2 flat
 * array of alternating names and values.
 *
 * <p>Each pair is {@link #handleField} (the name, delivered whole and
 * bounded like any identifier) and then its value as
 * {@link #handleValueNull} or {@link #handleValueBegin}, content calls and
 * {@link #handleValueEnd}. Nothing is held by the library, so neither the
 * number of pairs nor the size of a value is limited by it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface MapResultHandler {

    /**
     * The command returned null instead of a map.
     */
    void handleNull();

    /**
     * The map begins.
     *
     * @param pairs the number of pairs, or -1 if the server did not say
     * @param stream a handle to resume or cancel the reply
     * @return what to do next: continue, pause or skip the rest
     */
    ReplyFlow handleBegin(long pairs, ReplyStream stream);

    /**
     * The name of the next pair.
     *
     * @param field the name
     * @return what to do next
     */
    ReplyFlow handleField(String field);

    /**
     * The value of the pair is null.
     *
     * @return what to do next
     */
    ReplyFlow handleValueNull();

    /**
     * The value of the pair begins.
     *
     * @param length its length in bytes, or -1 if the server did not say
     * @return what to do next
     */
    ReplyFlow handleValueBegin(long length);

    /**
     * The next bytes of the value, never empty. The buffer is valid only
     * during the call.
     *
     * @param chunk the bytes, from its position to its limit
     * @return what to do next
     */
    ReplyFlow handleValueContent(ByteBuffer chunk);

    /**
     * The value is complete.
     *
     * @return what to do next
     */
    ReplyFlow handleValueEnd();

    /**
     * The map is complete.
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
