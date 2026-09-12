/*
 * TlsVersion.java
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
 * The TLS protocol version a TCP listener or client connection is pinned
 * to -- a deployment-time choice, not something negotiated per-connection
 * by inspecting a {@code ClientHello}. A deployment wanting to serve both
 * modern (TLS 1.3) and legacy (TLS 1.2) peers runs two listeners on two
 * ports, each with its own fixed {@code TlsVersion}, rather than one
 * listener detecting the version at runtime -- deliberately, to avoid a
 * ClientHello-inspection downgrade-attack surface.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum TlsVersion {

    /** RFC 5246, ECDHE + AEAD cipher suites only -- legacy interop. */
    TLS_1_2,

    /** RFC 8446. */
    TLS_1_3

}
