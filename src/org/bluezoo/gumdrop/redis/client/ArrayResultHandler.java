/*
 * ArrayResultHandler.java
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

import java.util.List;

import org.bluezoo.gumdrop.redis.codec.RESPValue;

/**
 * Handler for Redis commands that return an array.
 *
 * <p>Arrays contain a list of RESP values which may be bulk strings,
 * integers, nested arrays, or null values.
 *
 * <h4>Commands returning arrays:</h4>
 * <ul>
 *   <li>{@code KEYS} - list of matching keys</li>
 *   <li>{@code LRANGE} - list of elements</li>
 *   <li>{@code SMEMBERS} - set members</li>
 *   <li>{@code HGETALL} - alternating field/value pairs</li>
 *   <li>{@code MGET} - values for multiple keys (may include nulls)</li>
 *   <li>{@code EXEC} - transaction results</li>
 *   <li>{@code ZRANGE} - sorted set members</li>
 *   <li>{@code SCAN} - cursor and array of keys</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see RESPValue
 */
public interface ArrayResultHandler {

    /**
     * Called when the command succeeds with an array result.
     *
     * <p>The array contains {@link RESPValue} objects which can be
     * queried for their type and converted to appropriate Java types.
     *
     * <h4>Processing example:</h4>
     * <pre>{@code
     * void handleResult(List<RESPValue> array, RedisSession session) {
     *     for (RESPValue value : array) {
     *         if (value.isNull()) {
     *             // Handle null
     *         } else if (value.isBulkString()) {
     *             byte[] data = value.asBytes();
     *         } else if (value.isInteger()) {
     *             long num = value.asLong();
     *         }
     *     }
     * }
     * }</pre>
     *
     * @param array the array elements
     * @param session the session for further operations
     */
    void handleResult(List<RESPValue> array, RedisSession session);

    /**
     * Called when the command returns null (empty array).
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

