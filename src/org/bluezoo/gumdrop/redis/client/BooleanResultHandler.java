/*
 * BooleanResultHandler.java
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
 * Handler for Redis commands that return a boolean-like result.
 *
 * <p>Redis uses integers for boolean results (0 = false, non-zero = true).
 * This handler provides a more natural boolean interface.
 *
 * <h4>Commands with boolean results:</h4>
 * <ul>
 *   <li>{@code EXISTS} - true if key exists</li>
 *   <li>{@code SETNX} - true if key was set (didn't exist)</li>
 *   <li>{@code SISMEMBER} - true if element is in set</li>
 *   <li>{@code EXPIRE} - true if timeout was set</li>
 *   <li>{@code PERSIST} - true if timeout was removed</li>
 *   <li>{@code HEXISTS} - true if field exists in hash</li>
 *   <li>{@code MOVE} - true if key was moved</li>
 *   <li>{@code RENAMENX} - true if key was renamed</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface BooleanResultHandler {

    /**
     * Called when the command succeeds with a boolean result.
     *
     * @param value true if the Redis integer result was non-zero
     * @param session the session for further operations
     */
    void handleResult(boolean value, RedisSession session);

    /**
     * Called when the command fails with a Redis error.
     *
     * @param error the error message from Redis
     * @param session the session for further operations
     */
    void handleError(String error, RedisSession session);

}

