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
 * RESP (Redis Serialization Protocol) codec for Redis client communication.
 *
 * <p>This package provides encoding and decoding for the Redis protocol,
 * supporting both RESP2 (Redis 2.0+) and RESP3 (Redis 6.0+) formats.
 *
 * <h2>RESP Data Types</h2>
 *
 * <p>RESP defines several data types, each prefixed by a single byte:
 *
 * <table border="1" cellpadding="5">
 *   <caption>RESP Data Types</caption>
 *   <tr><th>Prefix</th><th>Type</th><th>Example</th></tr>
 *   <tr><td>{@code +}</td><td>Simple String</td><td>{@code +OK\r\n}</td></tr>
 *   <tr><td>{@code -}</td><td>Error</td><td>{@code -ERR unknown command\r\n}</td></tr>
 *   <tr><td>{@code :}</td><td>Integer</td><td>{@code :1000\r\n}</td></tr>
 *   <tr><td>{@code $}</td><td>Bulk String</td><td>{@code $6\r\nfoobar\r\n}</td></tr>
 *   <tr><td>{@code *}</td><td>Array</td><td>{@code *2\r\n$3\r\nfoo\r\n$3\r\nbar\r\n}</td></tr>
 * </table>
 *
 * <h2>Null Values</h2>
 *
 * <p>Null bulk strings are represented as {@code $-1\r\n}.
 * Null arrays are represented as {@code *-1\r\n}.
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.redis.codec.RESPType} - Enumeration of RESP data types</li>
 *   <li>{@link org.bluezoo.gumdrop.redis.codec.RESPValue} - Represents a decoded RESP value</li>
 *   <li>{@link org.bluezoo.gumdrop.redis.codec.RESPDecoder} - Decodes RESP wire format to values</li>
 *   <li>{@link org.bluezoo.gumdrop.redis.codec.RESPEncoder} - Encodes commands to RESP wire format</li>
 *   <li>{@link org.bluezoo.gumdrop.redis.codec.RESPException} - Protocol parsing errors</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * // Encoding a command
 * RESPEncoder encoder = new RESPEncoder();
 * ByteBuffer command = encoder.encodeCommand("SET", "mykey", "myvalue");
 *
 * // Decoding a response
 * RESPDecoder decoder = new RESPDecoder();
 * decoder.receive(responseBuffer);
 * RESPValue value = decoder.next();
 * if (value != null) {
 *     if (value.isSimpleString()) {
 *         String reply = value.asString();  // "OK"
 *     }
 * }
 * }</pre>
 *
 * <h2>Streaming Decoding</h2>
 *
 * <p>The decoder handles partial data gracefully. If a complete RESP value
 * cannot be parsed from the available data, {@code next()} returns null
 * and the decoder retains the partial data for the next {@code receive()}.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Encoder instances are thread-safe. Decoder instances are not thread-safe
 * and should be used from a single thread (typically the SelectorLoop thread).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://redis.io/docs/reference/protocol-spec/">Redis Protocol Specification</a>
 * @see org.bluezoo.gumdrop.redis.client
 */
package org.bluezoo.gumdrop.redis.codec;

