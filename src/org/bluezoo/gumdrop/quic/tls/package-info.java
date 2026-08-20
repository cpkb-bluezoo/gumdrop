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
 * TLS 1.3 key schedule support for QUIC (RFC 9001) and the bridge to the
 * Agent15 handshake engine.
 *
 * <p>Agent15 (<a href="https://github.com/ptrd/agent15">tech.kwik:agent15</a>)
 * implements the TLS 1.3 handshake message layer (RFC 8446 section 4) only;
 * it does not implement the TLS record layer, since QUIC does not use it
 * (RFC 9001 section 3). This package supplies the pieces RFC 9001 requires
 * on top of that: the HKDF-Expand-Label key derivation function (RFC 8446
 * section 7.1), the fixed Initial secret derivation (RFC 9001 section 5.2),
 * and the adapter classes that feed handshake bytes to and from Agent15's
 * {@code TlsClientEngine}/{@code TlsServerEngine} on QUIC CRYPTO frames.
 *
 * <p>Key classes:
 * <ul>
 * <li>{@link org.bluezoo.gumdrop.quic.tls.Hkdf} -- HKDF-Extract,
 *     HKDF-Expand (RFC 5869), and HKDF-Expand-Label (RFC 8446 section 7.1)</li>
 * <li>{@link org.bluezoo.gumdrop.quic.tls.InitialSecrets} -- the
 *     connection-ID-derived Initial secrets (RFC 9001 section 5.2)</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001">RFC 9001</a>
 */
package org.bluezoo.gumdrop.quic.tls;
