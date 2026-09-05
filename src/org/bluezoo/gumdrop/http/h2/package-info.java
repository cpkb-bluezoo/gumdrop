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
 * HTTP/2 (RFC 9113) frame parsing and writing, shared by the server
 * ({@code HTTPProtocolHandler}) and client ({@code
 * HTTPClientProtocolHandler}).
 *
 * <p>{@link org.bluezoo.gumdrop.http.h2.H2Parser} is a zero-allocation,
 * zero-copy push-parser: it consumes complete frames from a ByteBuffer
 * using buffer slices for payloads, delivering each to a typed callback
 * on {@link org.bluezoo.gumdrop.http.h2.H2FrameHandler} -- no
 * intermediate frame objects are allocated. {@link
 * org.bluezoo.gumdrop.http.h2.H2Writer} is the corresponding streaming
 * writer, one method per frame type. {@code H2FrameHandler} also defines
 * the frame type, flag, error code, and SETTINGS parameter constants
 * (RFC 9113 sections 4, 6, 7, 6.5.2).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9113">RFC 9113</a>
 * @see org.bluezoo.gumdrop.http.hpack
 */
package org.bluezoo.gumdrop.http.h2;
