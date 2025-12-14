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
 * HPACK header compression for HTTP/2.
 *
 * <p>This package implements RFC 7541 (HPACK: Header Compression for HTTP/2),
 * providing efficient encoding and decoding of HTTP headers for HTTP/2
 * connections.
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.http.hpack.Encoder} - Compresses HTTP
 *       headers into HPACK format</li>
 *   <li>{@link org.bluezoo.gumdrop.http.hpack.Decoder} - Decompresses HPACK
 *       encoded headers back to key-value pairs</li>
 *   <li>{@link org.bluezoo.gumdrop.http.hpack.HeaderHandler} - Callback
 *       interface for decoded headers</li>
 *   <li>{@link org.bluezoo.gumdrop.http.hpack.Huffman} - Huffman coding
 *       implementation for string literals</li>
 *   <li>{@link org.bluezoo.gumdrop.http.hpack.HPACKConstants} - Static
 *       table and protocol constants</li>
 * </ul>
 *
 * <h2>HPACK Features</h2>
 *
 * <ul>
 *   <li>Static table with 61 predefined header fields</li>
 *   <li>Dynamic table with configurable size limit</li>
 *   <li>Huffman encoding for string literals</li>
 *   <li>Indexed header field representation</li>
 *   <li>Literal header field with/without indexing</li>
 *   <li>Never-indexed sensitive headers</li>
 * </ul>
 *
 * <h2>Security Considerations</h2>
 *
 * <p>The implementation includes protections against compression-based
 * attacks (CRIME/BREACH) by supporting never-indexed headers for
 * sensitive values like cookies and authorization tokens.
 *
 * <h2>Usage</h2>
 *
 * <p>This package is used internally by the HTTP/2 implementation and
 * is not typically used directly by application code.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://tools.ietf.org/html/rfc7541">RFC 7541 - HPACK</a>
 * @see org.bluezoo.gumdrop.http.hpack.Encoder
 * @see org.bluezoo.gumdrop.http.hpack.Decoder
 */
package org.bluezoo.gumdrop.http.hpack;
