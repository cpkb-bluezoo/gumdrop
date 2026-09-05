/*
 * package-info.java
 * Copyright (C) 2005, 2025 Chris Burdess
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
 * HTTP/1.1 and HTTP/2 server implementation, with automatic protocol
 * negotiation via ALPN on TLS connections. HTTP/3 is a first-class peer
 * protocol implemented in the sibling {@link org.bluezoo.gumdrop.http.h3}
 * package, running over QUIC rather than TCP; {@link
 * org.bluezoo.gumdrop.http.HTTPResponseState} and {@link
 * org.bluezoo.gumdrop.http.HTTPRequestHandler} are shared by all three
 * versions, so request handlers are written once.
 *
 * <p>{@link org.bluezoo.gumdrop.http.HTTPService} is the abstract base
 * for HTTP application services, owning listeners and the request
 * handler factory; {@link org.bluezoo.gumdrop.http.HTTPListener} is the
 * TCP transport listener for HTTP/1.1 and HTTP/2; {@link
 * org.bluezoo.gumdrop.http.HTTPProtocolHandler} handles a single
 * connection in either version; {@link org.bluezoo.gumdrop.http.Stream}
 * represents one HTTP/2 stream or HTTP/1.1 request/response pair.
 * HTTP/2 framing (binary framing, multiplexed streams, server push, flow
 * control, prioritization) is parsed and written by {@link
 * org.bluezoo.gumdrop.http.h2}, with header compression in {@link
 * org.bluezoo.gumdrop.http.hpack} (HPACK, RFC 7541) -- HTTP/3 uses QPACK
 * ({@link org.bluezoo.gumdrop.http.qpack}, RFC 9204) instead.
 *
 * <p>Handlers can send 1xx informational responses (e.g. 103 Early
 * Hints, RFC 8297) before the final response via {@link
 * org.bluezoo.gumdrop.http.HTTPResponseState#sendInformational}, across
 * all three HTTP versions.
 *
 * <h2>Subpackages</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.http.h3} - HTTP/3 over QUIC</li>
 *   <li>{@link org.bluezoo.gumdrop.http.h2} - HTTP/2 frame parsing and writing</li>
 *   <li>{@link org.bluezoo.gumdrop.http.hpack} - HPACK header compression</li>
 *   <li>{@link org.bluezoo.gumdrop.http.qpack} - QPACK header compression</li>
 *   <li>{@link org.bluezoo.gumdrop.http.client} - HTTP client (all three versions)</li>
 *   <li>{@link org.bluezoo.gumdrop.websocket} - WebSocket, over any HTTP version</li>
 *   <li>{@link org.bluezoo.gumdrop.webdav} - static file serving and WebDAV</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.http.HTTPListener
 * @see org.bluezoo.gumdrop.http.HTTPProtocolHandler
 * @see org.bluezoo.gumdrop.http.h3
 * @see org.bluezoo.gumdrop.websocket
 */
package org.bluezoo.gumdrop.http;
