/*
 * DtlsVersion.java
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
 * DTLS 1.2 / 1.3 selection on UDP (same semantics as {@link TlsVersion} on
 * TCP). {@link #NEGOTIATE} is the default on one UDP port.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum DtlsVersion {

    /** Prefer DTLS 1.3 from the first handshake flight when offered. */
    NEGOTIATE,

    /** RFC 6347, ECDHE + AEAD cipher suites only. */
    DTLS_1_2,

    /** RFC 9147, TLS 1.3 over datagrams. */
    DTLS_1_3;

    /**
     * Whether a server-side version pick is permitted by this policy.
     *
     * @param pick the version inferred from the peer's first flight
     * @return true if the pick may be used
     */
    public boolean allowsServerPick(TlsVersionPick.Picked pick) {
        switch (this) {
            case NEGOTIATE:
                return true;
            case DTLS_1_3:
                return pick == TlsVersionPick.Picked.V13;
            case DTLS_1_2:
                return pick == TlsVersionPick.Picked.V12;
            default:
                return false;
        }
    }

    /**
     * Whether a client-side version pick is permitted by this policy.
     *
     * @param pick the version inferred from the server's first flight
     * @return true if the pick may be used
     */
    public boolean allowsClientPick(TlsVersionPick.Picked pick) {
        return allowsServerPick(pick);
    }
}
