/*
 * ConnectionIdEntry.java
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

package org.bluezoo.gumdrop.quic.cid;

/**
 * One connection ID and its associated state (RFC 9000 section 5.1),
 * used symmetrically by {@link ConnectionIdManager} for both the
 * connection IDs this endpoint has issued to the peer and the
 * connection IDs the peer has issued to this endpoint.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-5.1">RFC 9000 section 5.1</a>
 */
public final class ConnectionIdEntry {

    private final long sequenceNumber;
    private final byte[] connectionId;
    private final byte[] statelessResetToken;

    /**
     * Creates a connection ID entry.
     *
     * @param sequenceNumber the connection ID's sequence number
     * @param connectionId the connection ID bytes
     * @param statelessResetToken the associated stateless reset token
     *                            ({@code null} only for the very first,
     *                            handshake-established connection ID,
     *                            which RFC 9000 section 19.15 says never
     *                            carries one of its own)
     */
    public ConnectionIdEntry(long sequenceNumber, byte[] connectionId, byte[] statelessResetToken) {
        this.sequenceNumber = sequenceNumber;
        this.connectionId = connectionId;
        this.statelessResetToken = statelessResetToken;
    }

    /**
     * Returns the sequence number.
     *
     * @return the sequence number
     */
    public long getSequenceNumber() {
        return sequenceNumber;
    }

    /**
     * Returns the connection ID bytes.
     *
     * @return the connection ID
     */
    public byte[] getConnectionId() {
        return connectionId;
    }

    /**
     * Returns the associated stateless reset token, or {@code null} if none.
     *
     * @return the stateless reset token, or {@code null}
     */
    public byte[] getStatelessResetToken() {
        return statelessResetToken;
    }
}
