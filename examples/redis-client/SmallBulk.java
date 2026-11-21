/*
 * SmallBulk.java
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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.redis.client.BulkResultHandler;
import org.bluezoo.gumdrop.redis.client.RedisSession;
import org.bluezoo.gumdrop.redis.client.ReplyStream;
import org.bluezoo.gumdrop.redis.codec.ReplyFlow;

/**
 * Accumulates a value that is known to be small, in the application's own
 * handler where the limit is visible. A value over the limit is skipped: the
 * rest of the reply is discarded as it arrives, the connection is not
 * affected, and {@link #handleError} is told.
 *
 * <p>A value that may be large should not be accumulated at all, but passed
 * on as it arrives (see {@code StreamingExample}).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class SmallBulk implements BulkResultHandler {

    /** The largest value accepted, in bytes. */
    static final int LIMIT = 64 * 1024;

    private ByteArrayOutputStream value;
    private boolean isNull;
    private boolean tooLarge;

    /** The value arrived. */
    public abstract void handleResult(byte[] value, RedisSession session);

    /** The reply was null. */
    public abstract void handleNull(RedisSession session);

    /** The command failed. */
    public abstract void handleError(String error, RedisSession session);

    @Override
    public void handleNull() {
        isNull = true;
    }

    @Override
    public ReplyFlow handleBegin(long length, ReplyStream stream) {
        if (length > LIMIT) {
            tooLarge = true;
            return ReplyFlow.SKIP;
        }
        value = new ByteArrayOutputStream();
        return ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow handleContent(ByteBuffer chunk) {
        if (value.size() + chunk.remaining() > LIMIT) {
            tooLarge = true;
            return ReplyFlow.SKIP;
        }
        byte[] bytes = new byte[chunk.remaining()];
        chunk.get(bytes);
        value.write(bytes, 0, bytes.length);
        return ReplyFlow.CONTINUE;
    }

    @Override
    public void handleEnd() {
    }

    @Override
    public void handleComplete(String error, RedisSession session) {
        if (tooLarge) {
            handleError("value larger than " + LIMIT + " bytes", session);
        } else if (error != null) {
            handleError(error, session);
        } else if (isNull) {
            handleNull(session);
        } else {
            handleResult(value.toByteArray(), session);
        }
    }
}
