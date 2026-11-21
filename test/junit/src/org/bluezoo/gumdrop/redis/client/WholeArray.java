/*
 * WholeArray.java
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
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.redis.codec.ReplyFlow;
import org.bluezoo.gumdrop.redis.codec.RespValue;

/**
 * Test support: an {@link ArrayResultHandler} that accumulates the elements
 * and reports them whole.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class WholeArray implements ArrayResultHandler {

    private final List<RespValue> elements = new ArrayList<RespValue>();
    private ByteArrayOutputStream current;
    private boolean sawNull;

    /** The elements. */
    public abstract void handleResult(List<RespValue> values, RedisSession session);

    /** The reply was null. */
    public abstract void handleNull(RedisSession session);

    /** The command failed. */
    public abstract void handleError(String error, RedisSession session);

    @Override
    public void handleNull() {
        sawNull = true;
    }

    @Override
    public ReplyFlow handleBegin(long count, ReplyStream stream) {
        return ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow handleElementNull() {
        elements.add(RespValue.nullValue());
        return ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow handleElementBegin(long length) {
        current = new ByteArrayOutputStream();
        return ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow handleElementContent(ByteBuffer chunk) {
        byte[] b = new byte[chunk.remaining()];
        chunk.get(b);
        current.write(b, 0, b.length);
        return ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow handleElementEnd() {
        elements.add(RespValue.bulkString(current.toByteArray()));
        return ReplyFlow.CONTINUE;
    }

    @Override
    public void handleEnd() {
    }

    @Override
    public void handleComplete(String error, RedisSession session) {
        boolean wasNull = sawNull;
        List<RespValue> values = new ArrayList<RespValue>(elements);
        sawNull = false;
        elements.clear();
        if (error != null) {
            handleError(error, session);
        } else if (wasNull) {
            handleNull(session);
        } else {
            handleResult(values, session);
        }
    }
}
