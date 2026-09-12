/*
 * IntegrationTestHosts.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Shared loopback and TLS naming constants for integration tests.
 *
 * <p>Servers bind on {@link #LOOPBACK} ({@code ::1}, IPv6-first). Test PKI is
 * issued for {@link #TLS_SERVER_NAME}; {@link TCPTransportFactory#tlsServerNameFor}
 * maps loopback address literals to that name during hostname verification.
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
