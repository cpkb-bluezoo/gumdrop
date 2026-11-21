/*
 * WholeBulk.java
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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.redis.codec.ReplyFlow;

/**
 * Test support: a {@link BulkResultHandler} that accumulates the payload and
 * reports it whole, so that tests can check what a command returned.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class WholeBulk implements BulkResultHandler {

    private ByteArrayOutputStream out;
    private boolean sawNull;

    /** The payload. */
    public abstract void handleResult(byte[] value, RedisSession session);

    /** The reply was null. */
    public abstract void handleNull(RedisSession session);

    /** The command failed. */
    public abstract void handleError(String error, RedisSession session);

    @Override
    public void handleNull() {
        sawNull = true;
    }

    @Override
    public ReplyFlow handleBegin(long length, ReplyStream stream) {
        out = new ByteArrayOutputStream();
        return ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow handleContent(ByteBuffer chunk) {
        byte[] b = new byte[chunk.remaining()];
        chunk.get(b);
        out.write(b, 0, b.length);
        return ReplyFlow.CONTINUE;
    }

    @Override
    public void handleEnd() {
    }

    @Override
    public void handleComplete(String error, RedisSession session) {
        boolean wasNull = sawNull;
        byte[] value = out == null ? new byte[0] : out.toByteArray();
        sawNull = false;
        out = null;
        if (error != null) {
            handleError(error, session);
        } else if (wasNull) {
            handleNull(session);
        } else {
            handleResult(value, session);
        }
    }
}
