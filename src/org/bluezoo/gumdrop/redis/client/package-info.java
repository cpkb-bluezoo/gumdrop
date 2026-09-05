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
 * Asynchronous Redis client, entirely callback-based over the RESP wire
 * protocol ({@link org.bluezoo.gumdrop.redis.codec}).
 *
 * <p>{@link org.bluezoo.gumdrop.redis.client.RedisClientProtocolHandler}
 * drives the connection; {@link
 * org.bluezoo.gumdrop.redis.client.RedisConnectionReady} is the entry
 * point, handing the application a {@link
 * org.bluezoo.gumdrop.redis.client.RedisSession} once connected (and
 * authenticated, via {@code AUTH}, if configured) through which every
 * Redis command is issued. Commands are pipelined naturally: nothing
 * blocks waiting for a reply, so issuing several commands back to back
 * sends them immediately and each result arrives via its own callback as
 * the server replies. Different result handler interfaces distinguish
 * Redis's several reply shapes -- {@link
 * org.bluezoo.gumdrop.redis.client.StringResultHandler} (simple
 * strings), {@link org.bluezoo.gumdrop.redis.client.BulkResultHandler}
 * (bulk strings, nullable), {@link
 * org.bluezoo.gumdrop.redis.client.IntegerResultHandler}, {@link
 * org.bluezoo.gumdrop.redis.client.BooleanResultHandler} (integer 0/1
 * results), {@link org.bluezoo.gumdrop.redis.client.ArrayResultHandler},
 * and {@link org.bluezoo.gumdrop.redis.client.MessageHandler} for
 * Pub/Sub. MULTI/EXEC transactions and Lua scripting (EVAL) are
 * supported directly on {@code RedisSession}.
 *
 * <p>The client is single-threaded, tied to one {@link
 * org.bluezoo.gumdrop.SelectorLoop}: every callback is invoked on that
 * loop's own thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.redis.codec
 * @see <a href="https://redis.io/commands">Redis Commands</a>
 * @see <a href="https://redis.io/docs/reference/protocol-spec/">RESP Protocol</a>
 */
package org.bluezoo.gumdrop.redis.client;
