/*
 * package-info.java
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

/**
 * HTTP/3 support using quiche's h3 module via JNI.
 *
 * <p>HTTP/3 runs over QUIC and uses QPACK for header compression
 * (replacing HPACK used by HTTP/2). The quiche library handles all
 * HTTP/3 framing and QPACK encoding/decoding internally. This package
 * bridges between the quiche h3 event model and the gumdrop
 * {@link org.bluezoo.gumdrop.http.HTTPRequestHandler} API.
 *
 * <p>Key classes:
 * <ul>
 * <li>{@link org.bluezoo.gumdrop.http.h3.HTTP3Listener} -- QUIC transport
 *     listener on a UDP port for incoming HTTP/3 connections</li>
 * <li>{@link org.bluezoo.gumdrop.http.h3.HTTP3ServerHandler} -- server-side
 *     h3 handler on top of a QUIC connection, polls for events</li>
 * <li>{@link org.bluezoo.gumdrop.http.h3.H3Stream} -- per-request server
 *     stream implementing
 *     {@link org.bluezoo.gumdrop.http.HTTPResponseState}</li>
 * <li>{@link org.bluezoo.gumdrop.http.h3.HTTP3ClientHandler} -- client-side
 *     h3 handler for outgoing requests</li>
 * <li>{@link org.bluezoo.gumdrop.http.h3.H3ClientStream} -- per-request
 *     client stream translating h3 events into
 *     {@link org.bluezoo.gumdrop.http.client.HTTPResponseHandler}
 *     callbacks</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
package org.bluezoo.gumdrop.http.h3;
