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
 * QUIC packet protection: AEAD payload encryption and header protection
 * (RFC 9001 sections 5.1-5.4).
 *
 * <p>Key derivation for a given encryption level (Initial, Handshake, or
 * 1-RTT) produces a {@link org.bluezoo.gumdrop.quic.packet.PacketProtectionKeys}
 * from a traffic secret -- for Initial, from
 * {@link org.bluezoo.gumdrop.quic.tls.InitialSecrets}; for Handshake and
 * 1-RTT, from the corresponding secret Agent15 exposes once the handshake
 * reaches that level. {@link org.bluezoo.gumdrop.quic.packet.PacketProtection}
 * then performs the actual AEAD seal/open and header-protection mask
 * computation and application (RFC 9001 sections 5.3-5.4) using those keys.
 *
 * <p>Long-header/short-header field parsing and building, and QUIC frame
 * encoding (RFC 9000 section 19), are separate, non-cryptographic concerns
 * layered on top of this package.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001">RFC 9001</a>
 */
package org.bluezoo.gumdrop.quic.packet;
