/*
 * InvalidationHandler.java
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

import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.redis.codec.ReplyFlow;
import org.bluezoo.gumdrop.redis.codec.RespType;

/**
 * Reads the {@code invalidate} pushes of client-side caching
 * ({@code CLIENT TRACKING}) for a cache: each key the server says has changed
 * is {@link #invalidate(String)}, and a push that says everything has changed
 * is {@link #invalidateAll()}. Other pushes are ignored.
 *
 * <p>A key is an identifier, delivered whole up to the connection's maximum
 * field length. A key too long to deliver is reported as
 * {@link #invalidateAll()}, because invalidating too much of a cache is safe
 * and invalidating too little is not; the rest of that push is then
 * discarded.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class InvalidationHandler implements PushHandler {

    private static final int KIND_LIMIT = 64;

    private final int maxKey;

    private int depth;
    private boolean invalidate;
    private boolean skip;
    // 0: nothing read of the push; 1: its kind is being read; 2: the kind
    // has been read
    private int element;
    private byte[] bytes = new byte[64];
    private int length;
    private boolean overflowed;
    private boolean everything;

    /**
     * Creates a handler.
     *
     * @param maxKey the longest key to deliver whole, in bytes
     */
    protected InvalidationHandler(int maxKey) {
        this.maxKey = maxKey;
    }

    /**
     * Creates a handler that delivers keys of up to 64 KiB.
     */
    protected InvalidationHandler() {
        this(64 * 1024);
    }

    /**
     * A key has changed.
     *
     * @param key the key
     */
    protected abstract void invalidate(String key);

    /**
     * Everything may have changed: the server flushed, or a key could not be
     * delivered.
     */
    protected abstract void invalidateAll();

    @Override
    public void handleStream(ReplyStream stream) {
    }

    @Override
    public void beginReply(boolean push) {
        depth = 0;
        invalidate = false;
        skip = false;
        element = 0;
        overflowed = false;
        everything = false;
    }

    @Override
    public void endReply(boolean skipped) {
    }

    private ReplyFlow next() {
        return skip ? ReplyFlow.SKIP : ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow nullValue() {
        if (invalidate && depth == 1 && element == 1 && !everything) {
            // a null where the keys should be: the whole cache is stale
            everything = true;
            invalidateAll();
        }
        element++;
        return next();
    }

    @Override
    public ReplyFlow integer(long value) {
        element++;
        return next();
    }

    @Override
    public ReplyFlow doubleValue(double value) {
        element++;
        return next();
    }

    @Override
    public ReplyFlow booleanValue(boolean value) {
        element++;
        return next();
    }

    @Override
    public ReplyFlow bigNumber(String digits) {
        element++;
        return next();
    }

    @Override
    public ReplyFlow simpleString(String value) {
        if (depth == 1 && element == 0) {
            invalidate = "invalidate".equals(value);
            skip = !invalidate;
        } else if (invalidate && depth == 2) {
            invalidate(value);
        }
        if (depth == 1) {
            element++;
        }
        return next();
    }

    @Override
    public ReplyFlow error(String message) {
        skip = true;
        return ReplyFlow.SKIP;
    }

    @Override
    public ReplyFlow beginAggregate(RespType kind, long count) {
        depth++;
        if (depth == 2 && !invalidate) {
            skip = true;
        }
        return next();
    }

    @Override
    public ReplyFlow endAggregate() {
        depth--;
        if (depth == 1) {
            element++;
        }
        return next();
    }

    @Override
    public ReplyFlow beginString(RespType kind, long len, String format) {
        // the kind of the push is short whatever the limit on keys
        overflowed = len > (depth == 1 ? KIND_LIMIT : maxKey);
        length = 0;
        return next();
    }

    @Override
    public ReplyFlow stringChunk(ByteBuffer chunk) {
        int n = chunk.remaining();
        if (!overflowed && length + n > (depth == 1 ? KIND_LIMIT : maxKey)) {
            overflowed = true;
        }
        if (overflowed) {
            chunk.position(chunk.limit());
            return next();
        }
        if (length + n > bytes.length) {
            byte[] grown = new byte[Math.max(length + n, bytes.length * 2)];
            System.arraycopy(bytes, 0, grown, 0, length);
            bytes = grown;
        }
        chunk.get(bytes, length, n);
        length += n;
        return next();
    }

    @Override
    public ReplyFlow endString() {
        String value = overflowed ? null
                : new String(bytes, 0, length, java.nio.charset.StandardCharsets.UTF_8);
        if (depth == 1 && element == 0) {
            invalidate = "invalidate".equals(value);
            skip = !invalidate;
        } else if (invalidate && depth == 2) {
            if (value == null) {
                if (!everything) {
                    everything = true;
                    invalidateAll();
                }
                skip = true;
            } else if (!everything) {
                invalidate(value);
            }
        }
        if (depth == 1) {
            element++;
        }
        return next();
    }

}
