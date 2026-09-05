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
 * <p>{@link org.bluezoo.gumdrop.redis.codec.RESPDecoder} is a streaming
 * decoder: if a complete value isn't yet available, {@code next()}
 * returns null and the partial data is retained across calls to {@code
 * receive()}. {@link org.bluezoo.gumdrop.redis.codec.RESPEncoder} writes
 * commands in the corresponding wire format; encoder instances are
 * thread-safe, decoder instances are not (each is meant for one
 * connection's SelectorLoop thread). {@link
 * org.bluezoo.gumdrop.redis.codec.RESPValue} is the decoded value,
 * {@link org.bluezoo.gumdrop.redis.codec.RESPType} its type tag, and
 * {@link org.bluezoo.gumdrop.redis.codec.RESPException} reports
 * malformed input.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://redis.io/docs/reference/protocol-spec/">Redis Protocol Specification</a>
 * @see org.bluezoo.gumdrop.redis.client
 */
package org.bluezoo.gumdrop.redis.codec;
