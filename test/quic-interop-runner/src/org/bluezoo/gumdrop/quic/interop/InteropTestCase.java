/*
 * InteropTestCase.java
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

package org.bluezoo.gumdrop.quic.interop;

/**
 * The test cases the quic-interop-runner hands an endpoint in its
 * {@code TESTCASE} environment variable, and which of them this endpoint
 * implements in each role.
 *
 * <p>The runner probes every image with a random, unknown test case name
 * before building its matrix and expects exit status {@link
 * #EXIT_UNSUPPORTED} back; the same status tells it a known case is not
 * implemented, so that case shows as "unsupported" rather than "failed".
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum InteropTestCase {

    HANDSHAKE("handshake", true, true),
    TRANSFER("transfer", true, true),
    CHACHA20("chacha20", true, true),
    RETRY("retry", true, true),
    RESUMPTION("resumption", true, true),
    ZERORTT("zerortt", true, true),
    HTTP3("http3", true, true),
    MULTICONNECT("multiconnect", true, true),
    V2("v2", true, true),
    IPV6("ipv6", true, true),
    /** Client-only case; the server side of it runs as {@link #TRANSFER}. The client updates keys early in the transfer. */
    KEYUPDATE("keyupdate", true, false),
    /** Server-only case; the client side of it runs as {@link #TRANSFER}. The server advertises a {@code preferred_address}. */
    CONNECTIONMIGRATION("connectionmigration", false, true),
    /** Needs IP-layer ECN codepoints on received datagrams, which NIO does not expose. */
    ECN("ecn", false, false),
    VERSIONNEGOTIATION("versionnegotiation", false, false);

    /** Exit status the runner reads as "this endpoint does not implement the test case". */
    public static final int EXIT_UNSUPPORTED = 127;

    private final String wireName;
    private final boolean clientSupported;
    private final boolean serverSupported;

    InteropTestCase(String wireName, boolean clientSupported, boolean serverSupported) {
        this.wireName = wireName;
        this.clientSupported = clientSupported;
        this.serverSupported = serverSupported;
    }

    /**
     * Returns the {@code TESTCASE} value naming this case.
     */
    public String getWireName() {
        return wireName;
    }

    public boolean isClientSupported() {
        return clientSupported;
    }

    public boolean isServerSupported() {
        return serverSupported;
    }

    /**
     * Looks up a case by its {@code TESTCASE} value.
     *
     * @return the case, or null if the name is unknown
     */
    public static InteropTestCase fromWireName(String name) {
        if (name == null) {
            return null;
        }
        InteropTestCase[] all = values();
        for (int i = 0; i < all.length; i++) {
            if (all[i].wireName.equals(name)) {
                return all[i];
            }
        }
        return null;
    }

}
