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
 * WebSocket client support, over HTTP/1.1, HTTP/2, or HTTP/3.
 *
 * <p>{@link org.bluezoo.gumdrop.websocket.client.WebSocketClient} is the
 * high-level facade for connecting to WebSocket servers, with the same
 * automatic transport negotiation as {@link
 * org.bluezoo.gumdrop.http.client.HTTPClient}. It uses the same {@link
 * org.bluezoo.gumdrop.websocket.WebSocketEventHandler} interface as the
 * server side, so application code can be reused in both roles.
 *
 * <p>Over HTTP/1.1, a {@code WebSocketClientProtocolHandler} extending
 * {@link org.bluezoo.gumdrop.http.client.HTTPClientProtocolHandler}
 * handles the RFC 6455 upgrade handshake; once the server responds with
 * 101 Switching Protocols, it switches to WebSocket mode and all
 * subsequent I/O bypasses HTTP parsing entirely. Over HTTP/2 and HTTP/3,
 * the equivalent is RFC 9220's Extended CONNECT.
 *
 * @see <a href="https://tools.ietf.org/html/rfc6455">RFC 6455: The WebSocket Protocol</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9220">RFC 9220: WebSocket over HTTP/2 and HTTP/3</a>
 * @see org.bluezoo.gumdrop.websocket.client.WebSocketClient
 * @see org.bluezoo.gumdrop.websocket.WebSocketEventHandler
 * @see org.bluezoo.gumdrop.websocket.WebSocketSession
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
package org.bluezoo.gumdrop.websocket.client;
