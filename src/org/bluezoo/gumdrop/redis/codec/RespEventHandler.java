/*
 * RespEventHandler.java
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

package org.bluezoo.gumdrop.redis.codec;

import java.nio.ByteBuffer;

/**
 * Receives what a {@link RespDecoder} reads, as events.
 *
 * <p>The events of one reply come between {@link #beginReply(boolean)} and
 * {@link #endReply(boolean)}. A string arrives as {@link #beginString},
 * any number of {@link #stringChunk} events in order, then
 * {@link #endString()}. An aggregate arrives as {@link #beginAggregate},
 * its elements, then {@link #endAggregate()}. RESP3 attributes are read and
 * dropped by the decoder and produce no events.
 *
 * <p>Every event that can continue returns a {@link ReplyFlow}. A handler
 * runs on the connection's event loop and must not block: to wait for
 * something it returns {@link ReplyFlow#PAUSE} and the caller resumes later.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface RespEventHandler {

    /**
     * A reply or push begins. Nothing is announced before its first event.
     * This is where a handler decides who the reply belongs to.
     *
     * @param push true for a RESP3 push (out-of-band data that belongs to no
     * command), false for the reply to the command at the head of the queue
     */
    void beginReply(boolean push);

    /**
     * The reply is complete, or has been discarded to its end.
     *
     * @param skipped true if a handler chose {@link ReplyFlow#SKIP} for it
     */
    void endReply(boolean skipped);

    /**
     * A null: RESP2's null bulk string or null array, or RESP3's null.
     *
     * @return what to do next
     */
    ReplyFlow nullValue();

    /**
     * An integer.
     *
     * @param value the number
     * @return what to do next
     */
    ReplyFlow integer(long value);

    /**
     * A RESP3 double.
     *
     * @param value the number
     * @return what to do next
     */
    ReplyFlow doubleValue(double value);

    /**
     * A RESP3 boolean.
     *
     * @param value the value
     * @return what to do next
     */
    ReplyFlow booleanValue(boolean value);

    /**
     * A RESP3 big number.
     *
     * @param digits its decimal digits, with a leading minus sign if
     * negative
     * @return what to do next
     */
    ReplyFlow bigNumber(String digits);

    /**
     * A simple string, such as {@code OK}. It is at most one line.
     *
     * @param value the text
     * @return what to do next
     */
    ReplyFlow simpleString(String value);

    /**
     * An error line, without the leading dash. It is at most one line.
     *
     * @param message the error text
     * @return what to do next
     */
    ReplyFlow error(String message);

    /**
     * An aggregate begins.
     *
     * @param kind {@link RespType#ARRAY}, {@link RespType#SET},
     * {@link RespType#MAP} or {@link RespType#PUSH}
     * @param length the number of elements (of pairs, for a map), or -1 for
     * a RESP3 streamed aggregate, which {@link #endAggregate()} closes
     * @return what to do next
     */
    ReplyFlow beginAggregate(RespType kind, long length);

    /**
     * The aggregate opened last is complete.
     *
     * @return what to do next
     */
    ReplyFlow endAggregate();

    /**
     * A string begins.
     *
     * @param kind {@link RespType#BULK_STRING},
     * {@link RespType#VERBATIM_STRING} or {@link RespType#BLOB_ERROR}
     * @param length its length in bytes (for a verbatim string, that of the
     * text without its format tag), or -1 for a RESP3 streamed string
     * @param format the three-letter format of a verbatim string, such as
     * {@code txt}; null for any other kind
     * @return what to do next
     */
    ReplyFlow beginString(RespType kind, long length, String format);

    /**
     * The next bytes of the string, never empty. The buffer is a view onto
     * the connection's receive buffer: it is valid only for the duration of
     * the call, so anything wanted afterwards must be copied. The handler
     * may read from it but must not change its limit.
     *
     * @param chunk the bytes, from its position to its limit
     * @return what to do next
     */
    ReplyFlow stringChunk(ByteBuffer chunk);

    /**
     * The string is complete.
     *
     * @return what to do next
     */
    ReplyFlow endString();

}
