/*
 * SmallKeys.java
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

import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.redis.client.KeyArrayResultHandler;
import org.bluezoo.gumdrop.redis.client.RedisSession;
import org.bluezoo.gumdrop.redis.client.ReplyStream;
import org.bluezoo.gumdrop.redis.codec.ReplyFlow;

/**
 * Accumulates a list of keys that is known to be small. At most
 * {@link #LIMIT} keys are accepted; past that the rest of the reply is
 * skipped and {@link #handleError} is told.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class SmallKeys implements KeyArrayResultHandler {

    /** The most keys accepted. */
    static final int LIMIT = 10000;

    private final List<String> keys = new ArrayList<String>();
    private boolean isNull;
    private boolean tooMany;

    /** The keys arrived. */
    public abstract void handleResult(List<String> keys, RedisSession session);

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
    public ReplyFlow handleKey(String key) {
        if (keys.size() >= LIMIT) {
            tooMany = true;
            return ReplyFlow.SKIP;
        }
        keys.add(key);
        return ReplyFlow.CONTINUE;
    }

    @Override
    public void handleEnd() {
    }

    @Override
    public void handleComplete(String error, RedisSession session) {
        if (tooMany) {
            handleError("more than " + LIMIT + " keys", session);
        } else if (error != null) {
            handleError(error, session);
        } else if (isNull) {
            handleNull(session);
        } else {
            handleResult(keys, session);
        }
    }
}
