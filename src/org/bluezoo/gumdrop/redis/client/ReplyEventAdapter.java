/*
 * ReplyEventAdapter.java
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
 * A {@link ReplyEventHandler} that ignores everything, for a handler that
 * wants only some of a reply to override what it wants. It holds nothing, so
 * it is not a way to collect a reply: a handler that keeps what it is told
 * keeps it where the limit and the cost are visible.
 *
 * <p>Every event continues the reply; {@link #handleStream} and
 * {@link #handleComplete} do nothing, so a handler that needs to know a
 * command failed overrides {@code handleComplete}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class ReplyEventAdapter implements ReplyEventHandler {

    @Override
    public void handleStream(ReplyStream stream) {
    }

    @Override
    public void handleComplete(String error, RedisSession session) {
    }

    @Override
    public void beginReply(boolean push) {
    }

    @Override
    public void endReply(boolean skipped) {
    }

    @Override
    public ReplyFlow nullValue() {
        return ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow integer(long value) {
        return ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow doubleValue(double value) {
        return ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow booleanValue(boolean value) {
        return ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow bigNumber(String digits) {
        return ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow simpleString(String value) {
        return ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow error(String message) {
        return ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow beginAggregate(RespType kind, long length) {
        return ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow endAggregate() {
        return ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow beginString(RespType kind, long length, String format) {
        return ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow stringChunk(ByteBuffer chunk) {
        return ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow endString() {
        return ReplyFlow.CONTINUE;
    }

}
