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
 * WebSocket client support.
 *
 * <p>Provides {@link org.bluezoo.gumdrop.websocket.client.WebSocketClient},
 * a high-level facade for connecting to WebSocket servers. The client
 * uses the same {@link org.bluezoo.gumdrop.websocket.WebSocketEventHandler}
 * interface as the server side, so application code can be reused in
 * both roles.
 *
 * <h3>Architecture</h3>
 *
 * <p>The client composes a TCP transport with a
 * {@code WebSocketClientProtocolHandler} that extends
 * {@link org.bluezoo.gumdrop.http.client.HTTPClientProtocolHandler}.
 * The HTTP layer handles the initial upgrade handshake; once the
 * server responds with 101 Switching Protocols, the protocol handler
 * switches to WebSocket mode and all subsequent I/O bypasses HTTP
 * parsing entirely.
 *
 * @see org.bluezoo.gumdrop.websocket.client.WebSocketClient
 * @see org.bluezoo.gumdrop.websocket.WebSocketEventHandler
 * @see org.bluezoo.gumdrop.websocket.WebSocketSession
 */
package org.bluezoo.gumdrop.websocket.client;
