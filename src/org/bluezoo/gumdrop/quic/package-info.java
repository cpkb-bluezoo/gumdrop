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
 * QUIC transport (RFC 9000), a pure-Java implementation with no native or
 * JNI dependency. HTTP/3 ({@link org.bluezoo.gumdrop.http.h3}) and
 * DNS-over-QUIC ({@link org.bluezoo.gumdrop.dns.DoQListener}) both run on
 * top of it.
 *
 * <p>{@link org.bluezoo.gumdrop.quic.QuicEngine} owns one UDP socket and
 * demultiplexes datagrams across the {@link
 * org.bluezoo.gumdrop.quic.QuicConnection}s it serves, by destination
 * connection ID. Each {@code QuicConnection} implements RFC 9000's
 * connection-level state machine -- handshake sequencing, stream
 * lifecycle, flow control -- over a {@code QuicTlsClientEngine}/{@code
 * QuicTlsServerEngine} (in {@link org.bluezoo.gumdrop.quic.tls}) built on
 * Agent15's TLS 1.3 handshake engine. {@link
 * org.bluezoo.gumdrop.quic.QuicStreamEndpoint} exposes an individual
 * stream as a plain {@link org.bluezoo.gumdrop.Endpoint}, so protocol
 * handlers written against {@link org.bluezoo.gumdrop.ProtocolHandler}
 * work over QUIC exactly as they do over TCP. {@link
 * org.bluezoo.gumdrop.quic.QuicTransportFactory} is the {@link
 * org.bluezoo.gumdrop.TransportFactory} implementation that configures
 * and bootstraps all of this, client or server.
 *
 * <h2>Subpackages</h2>
 * <ul>
 * <li>{@link org.bluezoo.gumdrop.quic.tls} -- the TLS 1.3 key schedule
 *     and Agent15 bridge (RFC 9001)</li>
 * <li>{@link org.bluezoo.gumdrop.quic.packet} -- packet protection: AEAD
 *     payload encryption and header protection (RFC 9001 sections 5.1-5.4)</li>
 * <li>{@link org.bluezoo.gumdrop.quic.frame} -- frame encoding/decoding
 *     (RFC 9000 section 19)</li>
 * <li>{@link org.bluezoo.gumdrop.quic.cid} -- connection ID issuance and
 *     retirement (RFC 9000 sections 5.1, 10.3, 19.15-19.16)</li>
 * <li>{@link org.bluezoo.gumdrop.quic.recovery} -- loss detection and
 *     congestion control (RFC 9002)</li>
 * </ul>
 *
 * <p>Connection migration and path validation (RFC 9000 section 9,
 * 19.17-19.18) are not implemented -- a connection always treats the
 * most recently issued peer connection ID as active on the path it was
 * received on.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000">RFC 9000</a>
 * @see org.bluezoo.gumdrop.http.h3
 */
package org.bluezoo.gumdrop.quic;
