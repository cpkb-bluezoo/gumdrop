/*
 * TlsProtocolError.java
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
 * A non-fatal-to-the-JVM TLS 1.3 protocol failure, reported to
 * {@link TlsEventSink#protocolError} rather than thrown -- matching this
 * engine's convention that handshake failures are ordinary events the
 * caller reacts to (closing the connection, logging, alerting the peer),
 * not exceptions propagating out of a {@code feed_*}-shaped call.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class TlsProtocolError {

    private final AlertDescription alert;
    private final String message;

    /**
     * Creates a protocol error.
     *
     * @param alert the TLS alert this failure corresponds to, for a peer
     *              that is still reachable
     * @param message a human-readable description
     */
    public TlsProtocolError(AlertDescription alert, String message) {
        this.alert = alert;
        this.message = message;
    }

    /**
     * Returns the TLS alert description for this failure.
     *
     * @return the alert description
     */
    public AlertDescription getAlert() {
        return alert;
    }

    /**
     * Returns a human-readable description of the failure.
     *
     * @return the message
     */
    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return alert + ": " + message;
    }

}
