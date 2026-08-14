/*
 * RetryPacket.java
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

package org.bluezoo.gumdrop.quic.packet;

/**
 * The parsed fields of a received Retry packet (RFC 9000 section 17.2.5).
 * Unlike {@link LongHeaderPrefix}, there is no packet number field or
 * Length field to describe -- a Retry packet has neither -- and the
 * {@link #getTag()}/{@link #getPacketWithoutTag()} split exists so the
 * caller can verify it via {@link RetryIntegrityTag#verify} without
 * re-deriving the boundary itself.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see LongHeaderCodec#parseRetry
 */
public final class RetryPacket {

    private final byte[] destinationConnectionId;
    private final byte[] sourceConnectionId;
    private final byte[] retryToken;
    private final byte[] tag;
    private final byte[] packetWithoutTag;

    RetryPacket(byte[] destinationConnectionId, byte[] sourceConnectionId, byte[] retryToken,
            byte[] tag, byte[] packetWithoutTag) {
        this.destinationConnectionId = destinationConnectionId;
        this.sourceConnectionId = sourceConnectionId;
        this.retryToken = retryToken;
        this.tag = tag;
        this.packetWithoutTag = packetWithoutTag;
    }

    /**
     * Returns the Destination Connection ID -- the connection ID the
     * client used as its own Source Connection ID in the Initial packet
     * this Retry responds to.
     */
    public byte[] getDestinationConnectionId() {
        return destinationConnectionId;
    }

    /**
     * Returns the Source Connection ID -- the new connection ID the
     * client must address the server with from now on (RFC 9000 section
     * 17.2.5.2), and the value the server's eventual
     * {@code retry_source_connection_id} transport parameter must match.
     */
    public byte[] getSourceConnectionId() {
        return sourceConnectionId;
    }

    /**
     * Returns the opaque Retry Token the client must echo back in its
     * next Initial packet's own Token field.
     */
    public byte[] getRetryToken() {
        return retryToken;
    }

    /**
     * Returns the 16-byte Retry Integrity Tag, for verification via
     * {@link RetryIntegrityTag#verify}.
     */
    public byte[] getTag() {
        return tag;
    }

    /**
     * Returns the packet as received, minus its trailing tag -- the
     * exact bytes {@link RetryIntegrityTag#verify} needs.
     */
    public byte[] getPacketWithoutTag() {
        return packetWithoutTag;
    }
}
