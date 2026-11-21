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

import org.bluezoo.gumdrop.redis.codec.ReplyFlow;

/**
 * Receives one batch of {@code SCAN}: the cursor for the next call, then the
 * keys, each delivered whole as an identifier. The scan of the members of a
 * set, hash or sorted set ({@code SSCAN}, {@code HSCAN}, {@code ZSCAN})
 * carries payloads and is received with a {@link ReplyEventHandler}.
 *
 * <p>A key longer than the connection's maximum field length (64 KiB by
 * default) fails the reply with a {@code TOOLARGE} error to
 * {@link #handleComplete}; the connection is not affected.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ScanResultHandler {

    /**
     * The cursor, which comes first.
     *
     * @param cursor the cursor for the next iteration ({@code "0"} when done)
     * @param stream a handle to resume or cancel the reply
     * @return what to do next: continue, pause or skip the rest
     */
    ReplyFlow handleCursor(String cursor, ReplyStream stream);

    /**
     * The next key of the batch.
     *
     * @param key the key
     * @return what to do next
     */
    ReplyFlow handleKey(String key);

    /**
     * The batch is complete.
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
