/*
 * IntegerResultHandler.java
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

package org.bluezoo.gumdrop.redis.client;

/**
 * Handler for Redis commands that return an integer.
 *
 * <p>Integers are used for counts, lengths, and boolean-like results
 * (where 0 means false and non-zero means true).
 *
 * <h4>Commands returning integers:</h4>
 * <ul>
 *   <li>{@code INCR} / {@code DECR} - returns new value</li>
 *   <li>{@code LPUSH} / {@code RPUSH} - returns list length</li>
 *   <li>{@code SADD} / {@code SREM} - returns count of elements added/removed</li>
 *   <li>{@code DEL} - returns count of keys deleted</li>
 *   <li>{@code EXISTS} - returns count of existing keys (0 or 1 for single key)</li>
 *   <li>{@code SETNX} - returns 1 if set, 0 if key existed</li>
 *   <li>{@code EXPIRE} - returns 1 if timeout set, 0 if key doesn't exist</li>
 *   <li>{@code TTL} - returns seconds until expiry, or negative if no expiry/not found</li>
 *   <li>{@code LLEN} - returns list length</li>
 *   <li>{@code SCARD} - returns set cardinality</li>
 *   <li>{@code PUBLISH} - returns number of subscribers who received the message</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface IntegerResultHandler {

    /**
     * Called when the command succeeds with an integer result.
     *
     * @param value the integer result
     * @param session the session for further operations
     */
    void handleResult(long value, RedisSession session);

    /**
     * Called when the command fails with a Redis error.
     *
     * @param error the error message from Redis
     * @param session the session for further operations
     */
    void handleError(String error, RedisSession session);

}

