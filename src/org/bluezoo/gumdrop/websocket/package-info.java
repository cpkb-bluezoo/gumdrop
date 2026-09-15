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
 * are established via HTTP upgrade on HTTP/1.1, or via Extended CONNECT
 * on HTTP/2 (RFC 8441) and HTTP/3 (RFC 9220).
 *
 * <h2>Handler API</h2>
 *
 * <p>The entry point for building WebSocket applications is
 * {@link org.bluezoo.gumdrop.websocket.server.WebSocketRequestHandler}, an
 * {@code HttpStreamHandler} composed directly onto a plain {@link
 * org.bluezoo.gumdrop.http.HttpServer} — there is no separate WebSocket
 * server type or dedicated listener; ordinary {@code Http2Listener} /
 * {@code Http3Listener} instances handle the upgrade:
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.websocket.server.WebSocketRequestHandler} -
 *       HTTP-to-WebSocket upgrade handler, built via
 *       {@code WebSocketRequestHandler.builder().onConnect(...).build()}</li>
 *   <li>{@link org.bluezoo.gumdrop.websocket.WebSocketEventHandler} -
 *       Handler for WebSocket lifecycle events, created per connection by
 *       the builder's {@code ConnectionHandlerFactory}</li>
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
 * <h2>Features (RFC 6455)</h2>
 *
 * <ul>
 *   <li>Text and binary message types (§5.6)</li>
 *   <li>Message fragmentation (§5.4)</li>
 *   <li>Ping/pong heartbeats (§5.5.2, §5.5.3)</li>
 *   <li>Close frame handling with status codes (§7)</li>
 *   <li>Subprotocol negotiation (§4.2.2)</li>
 *   <li>Client and server masking (§5.3)</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://tools.ietf.org/html/rfc6455">RFC 6455 - WebSocket</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8441">RFC 8441 - WebSocket over HTTP/2</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9220">RFC 9220 - WebSocket over HTTP/3</a>
 * @see org.bluezoo.gumdrop.websocket.server.WebSocketRequestHandler
 * @see docs/COMPOSITION.md
 */
package org.bluezoo.gumdrop.websocket;
