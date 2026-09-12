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
 * TLS 1.3 key schedule support for QUIC (RFC 9001) and the bridge to
 * {@link org.bluezoo.gumdrop.tls.HandshakeEngine}, gumdrop's own in-tree
 * TLS 1.3 handshake engine over JCA.
 *
 * <p>{@code HandshakeEngine} implements the TLS 1.3 handshake message
 * layer (RFC 8446 section 4) only; it does not implement the TLS record
 * layer, since QUIC does not use it (RFC 9001 section 3). This package
 * supplies the pieces RFC 9001 requires on top of that: the fixed
 * Initial secret derivation (RFC 9001 section 5.2), and the adapter
 * classes ({@link org.bluezoo.gumdrop.quic.tls.QuicTlsClientEngine},
 * {@link org.bluezoo.gumdrop.quic.tls.QuicTlsServerEngine}) that feed
 * handshake bytes to and from {@code HandshakeEngine} on QUIC CRYPTO
 * frames, reassembling them via {@link
 * org.bluezoo.gumdrop.quic.tls.CryptoStreamBuffer} and offloading the
 * actual handshake processing off the connection's loop thread via
 * {@link org.bluezoo.gumdrop.quic.tls.QuicHandshakeAsyncOffload}.
 *
 * <p>Key classes:
 * <ul>
 * <li>{@link org.bluezoo.gumdrop.quic.tls.InitialSecrets} -- the
 *     connection-ID-derived Initial secrets (RFC 9001 section 5.2)</li>
 * <li>{@link org.bluezoo.gumdrop.quic.tls.QuicTlsClientEngine},
 *     {@link org.bluezoo.gumdrop.quic.tls.QuicTlsServerEngine} -- the
 *     {@code HandshakeEngine} bridge</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001">RFC 9001</a>
 */
package org.bluezoo.gumdrop.quic.tls;
