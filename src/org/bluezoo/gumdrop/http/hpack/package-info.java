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
 * HPACK header compression for HTTP/2 (RFC 7541).
 *
 * <p>{@link org.bluezoo.gumdrop.http.hpack.Encoder}/{@link
 * org.bluezoo.gumdrop.http.hpack.Decoder} implement the full codec: the
 * static table (61 predefined fields), a configurable-size dynamic
 * table, Huffman coding for string literals ({@link
 * org.bluezoo.gumdrop.http.hpack.Huffman}), and both indexed and literal
 * header field representations, including never-indexed fields for
 * sensitive values (cookies, authorization tokens) as a mitigation
 * against compression-oracle attacks (CRIME/BREACH). {@link
 * org.bluezoo.gumdrop.http.hpack.HeaderHandler} is the callback
 * interface the decoder delivers headers through; {@link
 * org.bluezoo.gumdrop.http.hpack.HPACKConstants} holds the static table
 * and other protocol constants.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7541">RFC 7541</a>
 * @see org.bluezoo.gumdrop.http.qpack
 */
package org.bluezoo.gumdrop.http.hpack;
