/*
 * ReplyEventHandler.java
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

import org.bluezoo.gumdrop.redis.codec.RespEventHandler;

/**
 * Receives a reply of any shape as the events the decoder reads: for
 * {@code EVAL}, {@code EXEC}, {@code XREAD}, {@code SSCAN}, {@code HSCAN}
 * and the other commands whose replies do not fit one of the simpler
 * handlers, and for commands sent with
 * {@link RedisSession#command(ReplyEventHandler, String, String...)}.
 *
 * <p>This is the same interface the decoder reports to: the reply is
 * {@code beginReply}, scalars, aggregates and strings (as chunks), and
 * {@code endReply}. Every event that can continue returns a
 * {@link org.bluezoo.gumdrop.redis.codec.ReplyFlow}: pause to stop reading
 * until {@link ReplyStream#resume()}, or skip to discard the rest of the
 * reply. Nothing is held by the library. A reply that is an error as a whole
 * is not reported as events: it is {@link #handleComplete} with the error.
 * {@link #handleComplete} always comes last, once.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ReplyEventHandler extends RespEventHandler {

    /**
     * Called before the reply begins, with the handle to pause, resume or
     * cancel it.
     *
     * @param stream the handle
     */
    void handleStream(ReplyStream stream);

    /**
     * The command is over, whether it succeeded or not. The last call.
     *
     * @param error null if it succeeded, otherwise the error
     * @param session the session for further operations
     */
    void handleComplete(String error, RedisSession session);

}
