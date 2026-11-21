/*
 * WholeScan.java
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.redis.codec.ReplyFlow;

/**
 * Test support: a {@link ScanResultHandler} that accumulates the batch and
 * reports it whole.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class WholeScan implements ScanResultHandler {

    private String cursor;
    private final List<RespValue> keys = new ArrayList<RespValue>();

    /** The batch. */
    public abstract void handleResult(String cursor, List<RespValue> elements, RedisSession session);

    /** The command failed. */
    public abstract void handleError(String error, RedisSession session);

    @Override
    public ReplyFlow handleCursor(String c, ReplyStream stream) {
        cursor = c;
        return ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow handleKey(String key) {
        keys.add(RespValue.bulkString(key.getBytes(StandardCharsets.UTF_8)));
        return ReplyFlow.CONTINUE;
    }

    @Override
    public void handleEnd() {
    }

    @Override
    public void handleComplete(String error, RedisSession session) {
        String c = cursor;
        List<RespValue> batch = new ArrayList<RespValue>(keys);
        cursor = null;
        keys.clear();
        if (error != null) {
            handleError(error, session);
        } else {
            handleResult(c, batch, session);
        }
    }
}
