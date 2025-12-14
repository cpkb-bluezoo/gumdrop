/*
 * StringResultHandler.java
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
 * Handler for Redis commands that return a simple string.
 *
 * <p>Simple strings are used for status replies like "OK" or "PONG".
 * They are single-line strings without binary data.
 *
 * <h4>Commands returning simple strings:</h4>
 * <ul>
 *   <li>{@code SET} - returns "OK"</li>
 *   <li>{@code PING} - returns "PONG"</li>
 *   <li>{@code AUTH} - returns "OK"</li>
 *   <li>{@code SELECT} - returns "OK"</li>
 *   <li>{@code FLUSHDB} - returns "OK"</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface StringResultHandler {

    /**
     * Called when the command succeeds with a string result.
     *
     * @param result the result string (e.g., "OK", "PONG")
     * @param session the session for further operations
     */
    void handleResult(String result, RedisSession session);

    /**
     * Called when the command fails with a Redis error.
     *
     * <p>Common errors include:
     * <ul>
     *   <li>{@code NOAUTH} - authentication required</li>
     *   <li>{@code ERR} - general error</li>
     *   <li>{@code WRONGTYPE} - operation on wrong data type</li>
     * </ul>
     *
     * @param error the error message from Redis
     * @param session the session for further operations
     */
    void handleError(String error, RedisSession session);

}

