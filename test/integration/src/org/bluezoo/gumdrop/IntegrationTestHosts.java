/*
 * IntegrationTestHosts.java
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

package org.bluezoo.gumdrop;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Shared loopback and TLS naming constants for integration tests.
 *
 * <p>Servers bind on {@link #LOOPBACK} ({@code ::1}, IPv6-first). Test PKI is
 * issued for {@link #TLS_SERVER_NAME}; {@link TcpTransportFactory#tlsServerNameFor}
 * maps loopback address literals to that name during hostname verification.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class IntegrationTestHosts {

    /** IPv6 loopback -- preferred bind/connect address for integration tests. */
    public static final String LOOPBACK = "::1";

    /** DNS name on integration test server certificates (CN/SAN). */
    public static final String TLS_SERVER_NAME = "localhost";

    private IntegrationTestHosts() {
    }

    public static InetAddress loopbackAddress() throws UnknownHostException {
        return InetAddress.getByName(LOOPBACK);
    }
}
