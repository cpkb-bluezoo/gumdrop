/*
 * BulkResultHandler.java
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
 * Handler for Redis commands that return a bulk string.
 *
 * <p>Bulk strings are binary-safe and can contain any data. They are
 * used for commands that return values stored in Redis.
 *
 * <h4>Commands returning bulk strings:</h4>
 * <ul>
 *   <li>{@code GET} - returns the value or null</li>
 *   <li>{@code HGET} - returns the field value or null</li>
 *   <li>{@code LPOP} / {@code RPOP} - returns the popped element or null</li>
 *   <li>{@code GETEX} - returns the value or null</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface BulkResultHandler {

    /**
     * Called when the command succeeds with a non-null result.
     *
     * <p>The result is returned as raw bytes. To convert to a string,
     * use {@code new String(value, StandardCharsets.UTF_8)}.
     *
     * @param value the result bytes
     * @param session the session for further operations
     */
    void handleResult(byte[] value, RedisSession session);

    /**
     * Called when the command returns null.
     *
     * <p>Null is returned when:
     * <ul>
     *   <li>The key does not exist (GET, HGET)</li>
     *   <li>The list is empty (LPOP, RPOP)</li>
     *   <li>The field does not exist (HGET)</li>
     * </ul>
     *
     * @param session the session for further operations
     */
    void handleNull(RedisSession session);

    /**
     * Called when the command fails with a Redis error.
     *
     * @param error the error message from Redis
     * @param session the session for further operations
     */
    void handleError(String error, RedisSession session);

}

