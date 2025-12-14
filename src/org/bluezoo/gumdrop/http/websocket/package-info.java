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
 * WebSocket protocol implementation (RFC 6455).
 *
 * <p>This package provides WebSocket support for real-time bidirectional
 * communication between clients and the server. WebSocket connections
 * are established via HTTP upgrade from the HTTP server.
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.http.websocket.WebSocketConnection} -
 *       Handles the WebSocket protocol after upgrade</li>
 *   <li>{@link org.bluezoo.gumdrop.http.websocket.WebSocketFrame} -
 *       Represents a WebSocket protocol frame</li>
 *   <li>{@link org.bluezoo.gumdrop.http.websocket.WebSocketHandshake} -
 *       Handles the HTTP upgrade handshake</li>
 *   <li>{@link org.bluezoo.gumdrop.http.websocket.WebSocketProtocolException} -
 *       Exception for protocol violations</li>
 * </ul>
 *
 * <h2>Features</h2>
 *
 * <ul>
 *   <li>Text and binary message types</li>
 *   <li>Message fragmentation</li>
 *   <li>Ping/pong heartbeats</li>
 *   <li>Close frame handling with status codes</li>
 *   <li>Per-message compression (permessage-deflate)</li>
 *   <li>Subprotocol negotiation</li>
 * </ul>
 *
 * <h2>WebSocket Frame Types</h2>
 *
 * <ul>
 *   <li>Text frames - UTF-8 encoded text messages</li>
 *   <li>Binary frames - Binary data messages</li>
 *   <li>Ping frames - Keepalive requests</li>
 *   <li>Pong frames - Keepalive responses</li>
 *   <li>Close frames - Connection termination</li>
 * </ul>
 *
 * <h2>Servlet Integration</h2>
 *
 * <p>For servlet-based applications, WebSocket endpoints can be defined
 * using the JSR 356 WebSocket API annotations.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://tools.ietf.org/html/rfc6455">RFC 6455 - WebSocket</a>
 * @see org.bluezoo.gumdrop.http.websocket.WebSocketConnection
 */
package org.bluezoo.gumdrop.http.websocket;
