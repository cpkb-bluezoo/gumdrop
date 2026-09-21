/*
 * DnsClientTransportHandler.java
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

package org.bluezoo.gumdrop.dns.client;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Callback interface for DNS client transport events.
 *
 * <p>Implemented by {@link DnsResolver} to receive data and error
 * notifications from the underlying {@link DnsClientTransport}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DnsClientTransport
 */
public interface DnsClientTransportHandler {

    /**
     * Called when DNS response data is received from the server.
     *
     * @param data the received data
     */
    void onReceive(ByteBuffer data);

    /**
     * Called when several length-prefixed DNS messages arrive on one DoQ stream
     * (RFC 5936 AXFR/IXFR). The default delivers each message via
     * {@link #onReceive} in order.
     */
    default void onReceiveSequence(List<ByteBuffer> messages) {
        for (int i = 0; i < messages.size(); i++) {
            onReceive(messages.get(i));
        }
    }

    /**
     * Called when a transport-level error occurs.
     *
     * @param cause the exception that caused the error
     */
    void onError(Exception cause);

    /**
     * Called when the TCP (or TLS) connection has closed normally. Zone
     * transfers (RFC 5936 AXFR/IXFR) treat this as end-of-stream after
     * length-prefixed messages; single-query clients usually override
     * with {@link #onError(Exception)} semantics via the default below.
     */
    default void onClosed() {
        onError(new java.io.IOException("Connection closed by server"));
    }

}
