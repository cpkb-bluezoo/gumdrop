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
 * <h2>Service API</h2>
 *
 * <p>The primary entry point for building WebSocket applications is
 * {@link org.bluezoo.gumdrop.websocket.WebSocketService}. Extend this
 * abstract class and implement
 * {@link org.bluezoo.gumdrop.websocket.WebSocketService#createConnectionHandler
 * createConnectionHandler} to receive WebSocket connections:
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.websocket.WebSocketService} -
 *       Abstract service base class (extends
 *       {@link org.bluezoo.gumdrop.http.HTTPService})</li>
 *   <li>{@link org.bluezoo.gumdrop.websocket.WebSocketEventHandler} -
 *       Handler for WebSocket lifecycle events</li>
 *   <li>{@link org.bluezoo.gumdrop.websocket.DefaultWebSocketEventHandler} -
 *       Convenience base class with empty event methods</li>
 *   <li>{@link org.bluezoo.gumdrop.websocket.WebSocketSession} -
 *       Session interface for sending messages to the peer</li>
 * </ul>
 *
 * <h2>Internal Components</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.websocket.WebSocketConnection} -
 *       WebSocket protocol state machine (frame parsing, lifecycle)</li>
 *   <li>{@link org.bluezoo.gumdrop.websocket.WebSocketFrame} -
 *       WebSocket protocol frame codec</li>
 *   <li>{@link org.bluezoo.gumdrop.websocket.WebSocketHandshake} -
 *       HTTP upgrade handshake validation</li>
 *   <li>{@link org.bluezoo.gumdrop.websocket.WebSocketProtocolException} -
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
 *   <li>Subprotocol negotiation</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://tools.ietf.org/html/rfc6455">RFC 6455 - WebSocket</a>
 * @see org.bluezoo.gumdrop.websocket.WebSocketService
 */
package org.bluezoo.gumdrop.websocket;
