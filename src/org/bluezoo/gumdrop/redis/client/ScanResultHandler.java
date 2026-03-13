/*
 * ScanResultHandler.java
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

import org.bluezoo.gumdrop.redis.codec.RESPValue;

/**
 * Handler for Redis SCAN family commands (SCAN, HSCAN, SSCAN, ZSCAN).
 *
 * <p>Scan commands return a two-element array: [cursor, elements].
 * The cursor is "0" when iteration is complete. The caller should
 * continue calling scan with the returned cursor until "0" is received
 * (Redis command reference — SCAN).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ScanResultHandler {

    /**
     * Called when the scan command succeeds.
     *
     * @param cursor the cursor for the next iteration ("0" when done)
     * @param elements the elements returned in this batch
     * @param session the session for further operations
     */
    void handleResult(String cursor, List<RESPValue> elements, RedisSession session);

    /**
     * Called when the scan command fails with a Redis error.
     *
     * @param error the error message from Redis
     * @param session the session for further operations
     */
    void handleError(String error, RedisSession session);

}
