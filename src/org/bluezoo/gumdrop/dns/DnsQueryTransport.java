/*
 * DnsQueryTransport.java
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

package org.bluezoo.gumdrop.dns;

/**
 * How a DNS query arrived at the server (affects AXFR/IXFR behaviour).
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
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
