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
    private final boolean fromPeer;

    /**
     * Creates a protocol error.
     *
     * @param alert the TLS alert this failure corresponds to, for a peer
     *              that is still reachable
     * @param message a human-readable description
     */
    public TlsProtocolError(AlertDescription alert, String message) {
        this(alert, message, false);
    }

    /**
     * Creates a protocol error, saying which side detected it.
     *
     * @param alert the TLS alert this failure corresponds to
     * @param message a human-readable description
     * @param fromPeer true if this is an alert the peer sent, false if
     *                 it is a failure this side detected
     */
    public TlsProtocolError(AlertDescription alert, String message, boolean fromPeer) {
        this.alert = alert;
        this.message = message;
        this.fromPeer = fromPeer;
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

    /**
     * Returns whether this is an alert received from the peer, as
     * opposed to a failure detected locally -- for which this side owes
     * the peer an alert of its own (RFC 8446 section 6.2).
     *
     * @return true if the peer sent this alert
     */
    public boolean isFromPeer() {
        return fromPeer;
    }

    @Override
    public String toString() {
        return alert + ": " + message;
    }

}
