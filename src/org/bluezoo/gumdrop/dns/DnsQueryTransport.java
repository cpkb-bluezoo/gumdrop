/*
 * DnsQueryTransport.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns;

/**
 * How a DNS query arrived at the server (affects AXFR/IXFR behaviour).
 */
public enum DnsQueryTransport {

    /** Datagram (RFC 1035); large transfers should set TC. */
    UDP(false),

    /** Length-prefixed stream (RFC 1035 section 4.2.2, DoT, DoQ). */
    FRAMED_TCP(true);

    private final boolean supportsMultiMessageAnswers;

    DnsQueryTransport(boolean supportsMultiMessageAnswers) {
        this.supportsMultiMessageAnswers = supportsMultiMessageAnswers;
    }

    /**
     * Whether the transport can carry RFC 5936 multi-message zone transfers.
     */
    public boolean supportsMultiMessageAnswers() {
        return supportsMultiMessageAnswers;
    }
}
