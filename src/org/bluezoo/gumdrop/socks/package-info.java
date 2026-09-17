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
 * SOCKS proxy server (RFC 1928 SOCKS5, with RFC 1929 username/password
 * authentication and RFC 1961 GSSAPI).
 *
 * <p>{@link org.bluezoo.gumdrop.socks.server.SocksServer} owns listeners,
 * configuration, and {@code compose()}; a bare {@code compose()} with no
 * session provider is a ready-to-use open proxy; {@link
 * org.bluezoo.gumdrop.socks.SocksListener} is the
 * TCP transport listener, on port 1080 (plaintext) or 1081 (TLS); {@link
 * org.bluezoo.gumdrop.socks.SocksProtocolHandler} drives the handshake
 * and command dispatch, with policy decisions delegated to staged
 * handler/state interfaces alongside {@code SocksServer} in {@link
 * org.bluezoo.gumdrop.socks.server}. CONNECT is relayed by {@link
 * org.bluezoo.gumdrop.socks.SocksRelay}, BIND by {@link
 * org.bluezoo.gumdrop.socks.SocksBindRelay}, and UDP ASSOCIATE by {@link
 * org.bluezoo.gumdrop.socks.SocksUdpRelay} (framing datagrams per {@link
 * org.bluezoo.gumdrop.socks.SocksUDPHeader}). Authentication runs
 * through {@link org.bluezoo.gumdrop.auth.Realm}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.socks.server.SocksServer
 * @see org.bluezoo.gumdrop.socks.client
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc1928">RFC 1928</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc1929">RFC 1929</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc1961">RFC 1961</a>
 */
package org.bluezoo.gumdrop.socks;
