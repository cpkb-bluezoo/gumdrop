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
 * HTTP/3 (RFC 9114) frame parsing and writing.
 *
 * <p>{@link org.bluezoo.gumdrop.http.h3.H3Parser}/{@link
 * org.bluezoo.gumdrop.http.h3.H3Writer} implement the HTTP/3 frame layer
 * (RFC 9114 section 7) directly. On the server side, {@link
 * org.bluezoo.gumdrop.http.h3.HTTP3Listener} binds the QUIC transport and
 * installs an {@link org.bluezoo.gumdrop.http.h3.HTTP3ServerHandler} per
 * connection; each request is a {@link
 * org.bluezoo.gumdrop.http.h3.H3Stream}, itself the QUIC stream's
 * protocol handler, implementing {@link
 * org.bluezoo.gumdrop.http.HTTPResponseState} so request handlers work
 * identically to HTTP/1.1 and HTTP/2. On the client side, {@link
 * org.bluezoo.gumdrop.http.h3.HTTP3ClientHandler} owns the connection's
 * control stream and SETTINGS exchange, and {@link
 * org.bluezoo.gumdrop.http.h3.H3ClientStream} translates each request's
 * response frames into {@link
 * org.bluezoo.gumdrop.http.client.HTTPResponseHandler} callbacks.
 *
 * <h2>103 Early Hints (RFC 8297)</h2>
 *
 * <p>{@link org.bluezoo.gumdrop.http.h3.H3Stream#sendInformational} sends
 * 1xx informational responses before the final response, exactly as the
 * HTTP/1.1 and HTTP/2 implementations do.
 *
 * <h2>Extended CONNECT (RFC 9220)</h2>
 *
 * <p>The server advertises {@code SETTINGS_ENABLE_CONNECT_PROTOCOL = 1}
 * in its initial SETTINGS frame; the client defers any Extended CONNECT
 * request until it has seen the same setting from the peer. This
 * underpins WebSocket over HTTP/3 ({@link
 * org.bluezoo.gumdrop.http.h3.H3Stream#upgradeToWebSocket} on accept,
 * {@link org.bluezoo.gumdrop.websocket.HTTP3WebSocketListener} for the
 * service-level integration, {@link
 * org.bluezoo.gumdrop.http.h3.H3ClientWebSocketResponseHandler} on the
 * client), CONNECT-UDP (RFC 9298, {@link
 * org.bluezoo.gumdrop.http.h3.H3ClientConnectUdpResponseHandler}), and
 * CONNECT-IP (RFC 9484, {@link
 * org.bluezoo.gumdrop.http.h3.H3ClientConnectIpResponseHandler}).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9114">RFC 9114</a>
 * @see org.bluezoo.gumdrop.quic
 * @see org.bluezoo.gumdrop.http.qpack
 */
package org.bluezoo.gumdrop.http.h3;
