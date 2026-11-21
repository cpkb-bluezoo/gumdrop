/*
 * KeyArrayResultHandler.java
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

import org.bluezoo.gumdrop.redis.codec.ReplyFlow;

/**
 * Receives an array of identifiers (keys, field names), each delivered whole:
 * the reply of {@code KEYS} and {@code HKEYS}.
 *
 * <p>An identifier is a string the protocol uses to name something, so it is
 * bounded: up to the connection's maximum field length (64 KiB by default).
 * A longer one fails the reply, with a {@code TOOLARGE} error to
 * {@link #handleComplete}, and the rest of the reply is discarded; the
 * connection is not affected. The number of identifiers is not limited, as
 * each is passed on as it arrives.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface KeyArrayResultHandler {

    /**
     * The command returned null instead of an array.
     */
    void handleNull();

    /**
     * The array begins.
     *
     * @param count the number of identifiers, or -1 if the server did not say
     * @param stream a handle to resume or cancel the reply
     * @return what to do next: continue, pause or skip the rest
     */
    ReplyFlow handleBegin(long count, ReplyStream stream);

    /**
     * The next identifier.
     *
     * @param key the identifier
     * @return what to do next
     */
    ReplyFlow handleKey(String key);

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
