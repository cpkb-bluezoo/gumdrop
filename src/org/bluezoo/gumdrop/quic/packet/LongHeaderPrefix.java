/*
 * LongHeaderPrefix.java
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
 * The fields of a long-header packet (RFC 9000 section 17.2) that can be
 * read before header protection is removed, plus the offset at which
 * the (still-protected) packet number field begins.
 *
 * <p>Everything up to and including the Length field is sent
 * unprotected; only the low bits of the first byte and the packet
 * number field itself are covered by header protection
 * (RFC 9001 section 5.4.1).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see LongHeaderCodec
 */
public final class LongHeaderPrefix {

    private final int packetType;
    private final int version;
    private final byte[] destinationConnectionId;
    private final byte[] sourceConnectionId;
    private final byte[] token;
    private final int pnOffset;
    private final long remainingLength;

    LongHeaderPrefix(int packetType, int version, byte[] destinationConnectionId,
            byte[] sourceConnectionId, byte[] token, int pnOffset, long remainingLength) {
        this.packetType = packetType;
        this.version = version;
        this.destinationConnectionId = destinationConnectionId;
        this.sourceConnectionId = sourceConnectionId;
        this.token = token;
        this.pnOffset = pnOffset;
        this.remainingLength = remainingLength;
    }

    /**
     * Returns the long packet type (RFC 9000 section 17.2): one of
     * {@link LongHeaderCodec#TYPE_INITIAL}, {@link LongHeaderCodec#TYPE_0RTT},
     * {@link LongHeaderCodec#TYPE_HANDSHAKE}, or {@link LongHeaderCodec#TYPE_RETRY}.
     *
     * @return the packet type
     */
    public int getPacketType() {
        return packetType;
    }

    /**
     * Returns the QUIC version from the packet header.
     *
     * @return the version
     */
    public int getVersion() {
        return version;
    }

    /**
     * Returns the Destination Connection ID.
     *
     * @return the destination connection ID
     */
    public byte[] getDestinationConnectionId() {
        return destinationConnectionId;
    }

    /**
     * Returns the Source Connection ID.
     *
     * @return the source connection ID
     */
    public byte[] getSourceConnectionId() {
        return sourceConnectionId;
    }

    /**
     * Returns the Token field (RFC 9000 section 17.2.2), empty for
     * packet types other than Initial.
     *
     * @return the token, possibly zero-length
     */
    public byte[] getToken() {
        return token;
    }

    /**
     * Returns the offset within the packet at which the (still
     * header-protected) packet number field begins.
     *
     * @return the packet number field offset
     */
    public int getPacketNumberOffset() {
        return pnOffset;
    }

    /**
     * Returns the value of the Length field: the combined length in
     * bytes of the packet number field and the following protected
     * payload (including its authentication tag).
     *
     * @return the remaining length after the Length field
     */
    public long getRemainingLength() {
        return remainingLength;
    }
}
