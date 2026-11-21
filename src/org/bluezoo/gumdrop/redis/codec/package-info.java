/*
 * package-info.java
 * Copyright (C) 2025 Chris Burdess
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

/**
 * RESP (REdis Serialization Protocol) codec, supporting both RESP2
 * (simple strings {@code +}, errors {@code -}, integers {@code :}, bulk
 * strings {@code $}, arrays {@code *}) and RESP3.
 *
 * <p>{@link org.bluezoo.gumdrop.redis.codec.RespDecoder} is an incremental
 * push parser and an event source: it is given whatever bytes have arrived,
 * in any split, and reports what they hold to a {@link
 * org.bluezoo.gumdrop.redis.codec.RespEventHandler}: scalars, the start and
 * end of aggregates, and strings as chunks that are views onto the buffer it
 * was given. It assembles nothing and holds no payload, whatever its size;
 * what it keeps between calls is an unfinished header line, a stack of counts
 * for the aggregates that are open, and a few counters. A handler steers it
 * with the {@link org.bluezoo.gumdrop.redis.codec.ReplyFlow} it returns:
 * continue, pause (the unconsumed bytes stay with the caller), or skip (the
 * rest of that reply is discarded as it arrives, in constant memory).
 * {@link org.bluezoo.gumdrop.redis.codec.RespEncoder} writes commands in the
 * corresponding wire format, including the head and tail of a command with a
 * streamed argument; encoder instances are thread-safe, decoder instances are
 * not (each is meant for one connection's SelectorLoop thread). {@link
 * org.bluezoo.gumdrop.redis.codec.RespType} is the type tag of an aggregate or
 * string, and {@link org.bluezoo.gumdrop.redis.codec.RespException} reports
 * malformed input.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://redis.io/docs/reference/protocol-spec/">Redis Protocol Specification</a>
 * @see org.bluezoo.gumdrop.redis.client
 */
package org.bluezoo.gumdrop.redis.codec;
