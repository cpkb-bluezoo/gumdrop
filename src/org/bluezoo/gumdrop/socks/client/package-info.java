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
 * SOCKS client for tunnelling any other client connection through a
 * SOCKS proxy (RFC 1928 SOCKS5, or SOCKS4/4a).
 *
 * <p>{@link org.bluezoo.gumdrop.socks.client.SOCKSClientHandler} wraps
 * an inner {@link org.bluezoo.gumdrop.ProtocolHandler}: on {@code
 * connected()}, it runs the SOCKS handshake (version negotiation,
 * optional authentication, CONNECT request) itself, and only once the
 * tunnel is established does it call the wrapped handler's own {@code
 * connected()} and forward data transparently -- so any existing
 * gumdrop client protocol handler can be proxied without modification.
 * {@link org.bluezoo.gumdrop.socks.client.SOCKSClientConfig} configures
 * the SOCKS version preference, credentials, and handshake timeout.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.socks
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc1928">RFC 1928</a>
 */
package org.bluezoo.gumdrop.socks.client;
