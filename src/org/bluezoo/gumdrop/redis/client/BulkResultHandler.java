/*
 * BulkResultHandler.java
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

/**
 * Receives a single string payload as chunks: the reply of {@code GET},
 * {@code HGET}, {@code LPOP}, {@code SET} with options and the like.
 *
 * <p>The payload may be of any size and is never held by the library. It is
 * delivered as {@link #handleBegin}, any number of {@link #handleContent}
 * calls in order, then {@link #handleEnd}; a null reply is
 * {@link #handleNull}. {@link #handleComplete} always comes last, once, with
 * a null error if the command succeeded. A handler that needs the whole
 * value accumulates it itself, where the limit and the cost are visible:
 *
 * <pre>{@code
 * public ReplyFlow handleBegin(long length, ReplyStream stream) {
 *     if (length > MAX) {
 *         return ReplyFlow.SKIP;   // fails this command only
 *     }
 *     out = new ByteArrayOutputStream((int) length);
 *     return ReplyFlow.CONTINUE;
 * }
 * }</pre>
 *
 * <p>A simple string, integer or double reply (for example {@code OK}) is
 * delivered as a payload of its text. All calls are made on the connection's
 * event loop and must not block.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface BulkResultHandler {

    /**
     * The command returned null: the key does not exist, the list is empty.
     */
    void handleNull();

    /**
     * The payload begins.
     *
     * @param length its length in bytes, or -1 if the server did not say
     * (a RESP3 streamed string)
     * @param stream a handle to resume or cancel the reply
     * @return {@link ReplyFlow#CONTINUE} to go on, {@link ReplyFlow#PAUSE} to
     * stop reading until {@link ReplyStream#resume()}, or
     * {@link ReplyFlow#SKIP} to discard the rest of the reply
     */
    ReplyFlow handleBegin(long length, ReplyStream stream);

    /**
     * The next bytes of the payload, never empty. The buffer is valid only
     * during the call: copy what is wanted afterwards.
     *
     * @param chunk the bytes, from its position to its limit
     * @return what to do next
     */
    ReplyFlow handleContent(ByteBuffer chunk);

    /**
     * The payload is complete.
     */
    void handleEnd();

    /**
     * The command is over, whether it succeeded or not. The last call.
     *
     * @param error null if it succeeded, otherwise the error: one from the
     * server, or {@code TIMEOUT}, {@code CANCELLED} or {@code TOOLARGE}
     * followed by a description
     * @param session the session for further operations
     */
    void handleComplete(String error, RedisSession session);

}
