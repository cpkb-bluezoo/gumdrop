/*
 * HandshakeMode.java
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

package org.bluezoo.gumdrop.tls;

/**
 * The transport a {@link HandshakeEngine} instance's handshake bytes are
 * bound to. The handshake state machine itself (message construction,
 * transcript, key schedule) is identical either way; this only gates
 * behavior that genuinely differs by transport -- currently just whether
 * post-handshake {@code KeyUpdate} (RFC 8446 section 4.6.3/7.2) is
 * meaningful at all, since RFC 9001 section 4.6 forbids it over QUIC
 * entirely (QUIC has its own, separate packet-level key update).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum HandshakeMode {

    /**
     * Raw handshake messages handed to the caller for QUIC CRYPTO stream
     * framing (RFC 9001) -- no TLS record layer. The default, so every
     * existing QUIC caller is unaffected by this enum's addition.
     */
    QUIC,

    /**
     * Wrapped in TLS record framing and AEAD by {@link TlsRecordEngine}
     * for TCP (RFC 8446 section 5).
     */
    TCP_RECORD_LAYER,

    /**
     * Wrapped in DTLS record framing and AEAD by {@link Dtls13RecordEngine}
     * for UDP (RFC 9147).
     */
    DTLS

}
