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
 * Asynchronous Redis RESP client.
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
 * the server replies.
 *
 * <p>Replies stream: the library offers no call that returns, or passes to
 * a handler, a value whose size depends on the server's data. A
 * <em>payload</em> (a value, a list element, a hash value) is delivered
 * only as chunks; an <em>identifier</em> (a key, a field name, a cursor) is
 * delivered whole, up to the connection's maximum field length; a
 * <em>scalar</em> (an integer, {@code OK}) is delivered as a value. The
 * handler interfaces are the shapes of reply: {@link
 * org.bluezoo.gumdrop.redis.client.StringResultHandler}, {@link
 * org.bluezoo.gumdrop.redis.client.IntegerResultHandler} and {@link
 * org.bluezoo.gumdrop.redis.client.BooleanResultHandler} for scalars;
 * {@link org.bluezoo.gumdrop.redis.client.BulkResultHandler} for one
 * payload; {@link org.bluezoo.gumdrop.redis.client.ArrayResultHandler} for
 * an array of payloads; {@link
 * org.bluezoo.gumdrop.redis.client.KeyArrayResultHandler} for an array of
 * identifiers; {@link org.bluezoo.gumdrop.redis.client.MapResultHandler}
 * for pairs of an identifier and a payload; {@link
 * org.bluezoo.gumdrop.redis.client.ScanResultHandler} for a batch of
 * {@code SCAN}; and {@link
 * org.bluezoo.gumdrop.redis.client.ReplyEventHandler} for any other shape.
 * There is no handler that collects: an application that needs a whole
 * value accumulates it in its own handler, where the limit and the cost are
 * visible. A handler steers the reply with the {@link
 * org.bluezoo.gumdrop.redis.codec.ReplyFlow} it returns: continue, pause
 * (reading stops until {@link
 * org.bluezoo.gumdrop.redis.client.ReplyStream#resume()}), or skip (the rest
 * of that reply is discarded, and the command completes with a {@code
 * CANCELLED} error). {@link org.bluezoo.gumdrop.redis.client.MessageHandler}
 * receives Pub/Sub. MULTI/EXEC transactions and Lua scripting (EVAL) are
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
