/*
 * SmallList.java
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.redis.client.ArrayResultHandler;
import org.bluezoo.gumdrop.redis.client.RedisSession;
import org.bluezoo.gumdrop.redis.client.ReplyStream;
import org.bluezoo.gumdrop.redis.codec.ReplyFlow;

/**
 * Accumulates an array of strings that is known to be small, as text. The
 * total is limited: a reply over the limit is skipped, the rest of it
 * discarded as it arrives, and {@link #handleError} is told.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class SmallList implements ArrayResultHandler {

    /** The most bytes accepted in all. */
    static final int LIMIT = 256 * 1024;

    private final List<String> elements = new ArrayList<String>();
    private ByteArrayOutputStream current;
    private int held;
    private boolean isNull;
    private boolean tooLarge;

    /** The elements arrived. */
    public abstract void handleResult(List<String> elements, RedisSession session);

    /** The reply was null. */
    public abstract void handleNull(RedisSession session);

    /** The command failed. */
    public abstract void handleError(String error, RedisSession session);

    @Override
    public void handleNull() {
        isNull = true;
    }

    @Override
    public ReplyFlow handleBegin(long count, ReplyStream stream) {
        return ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow handleElementNull() {
        elements.add(null);
        return ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow handleElementBegin(long length) {
        current = new ByteArrayOutputStream();
        return ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow handleElementContent(ByteBuffer chunk) {
        held += chunk.remaining();
        if (held > LIMIT) {
            tooLarge = true;
            return ReplyFlow.SKIP;
        }
        byte[] bytes = new byte[chunk.remaining()];
        chunk.get(bytes);
        current.write(bytes, 0, bytes.length);
        return ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow handleElementEnd() {
        elements.add(new String(current.toByteArray(), StandardCharsets.UTF_8));
        return ReplyFlow.CONTINUE;
    }

    @Override
    public void handleEnd() {
    }

    @Override
    public void handleComplete(String error, RedisSession session) {
        if (tooLarge) {
            handleError("reply larger than " + LIMIT + " bytes", session);
        } else if (error != null) {
            handleError(error, session);
        } else if (isNull) {
            handleNull(session);
        } else {
            handleResult(elements, session);
        }
    }
}
