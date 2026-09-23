/*
 * LongHeaderInvariants.java
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
 * The version-independent fields of a long-header packet (RFC 8999
 * section 5.1): the version and both connection IDs. These are the only
 * fields readable from a packet whose version is not understood, so they
 * are all a server needs to answer it with a Version Negotiation packet
 * (RFC 9000 section 6.1).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see LongHeaderCodec#parseInvariants
 */
public final class LongHeaderInvariants {

    private final int version;
    private final byte[] destinationConnectionId;
    private final byte[] sourceConnectionId;

    LongHeaderInvariants(int version, byte[] destinationConnectionId, byte[] sourceConnectionId) {
        this.version = version;
        this.destinationConnectionId = destinationConnectionId;
        this.sourceConnectionId = sourceConnectionId;
    }

    /**
     * Returns the version field.
     *
     * @return the version
     */
    public int getVersion() {
        return version;
    }

    /**
     * Returns the Destination Connection ID, which for a version other
     * than 1 may be up to 255 bytes long.
     *
     * @return the destination connection ID
     */
    public byte[] getDestinationConnectionId() {
        return destinationConnectionId;
    }

    /**
     * Returns the Source Connection ID, which for a version other than
     * 1 may be up to 255 bytes long.
     *
     * @return the source connection ID
     */
    public byte[] getSourceConnectionId() {
        return sourceConnectionId;
    }
}
