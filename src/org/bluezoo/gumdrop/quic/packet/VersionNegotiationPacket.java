/*
 * VersionNegotiationPacket.java
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

import java.nio.ByteBuffer;

/**
 * A Version Negotiation packet (RFC 9000 section 17.2.1): a long-header
 * packet with a zero version field, echoing the connection IDs of the
 * packet it answers and listing the versions the sender supports. It is
 * never encrypted or authenticated.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-17.2.1">RFC 9000 section 17.2.1</a>
 */
public final class VersionNegotiationPacket {

    private static final int HEADER_FORM_LONG = 0x80;

    private final byte[] destinationConnectionId;
    private final byte[] sourceConnectionId;
    private final int[] supportedVersions;

    private VersionNegotiationPacket(byte[] destinationConnectionId, byte[] sourceConnectionId,
            int[] supportedVersions) {
        this.destinationConnectionId = destinationConnectionId;
        this.sourceConnectionId = sourceConnectionId;
        this.supportedVersions = supportedVersions;
    }

    /**
     * Builds a Version Negotiation packet answering a packet that carried
     * {@code clientSourceConnectionId} and {@code clientDestinationConnectionId}.
     *
     * @param destinationConnectionId the answered packet's Source Connection ID
     * @param sourceConnectionId the answered packet's Destination Connection ID
     * @param supportedVersions the versions to advertise, at least one
     * @param unusedBits arbitrary value whose low seven bits fill the
     *        packet's unused first-byte bits (RFC 9000 section 17.2.1)
     * @return the packet bytes
     */
    public static byte[] build(byte[] destinationConnectionId, byte[] sourceConnectionId,
            int[] supportedVersions, int unusedBits) {
        ByteBuffer buf = ByteBuffer.allocate(1 + 4 + 1 + destinationConnectionId.length
                + 1 + sourceConnectionId.length + 4 * supportedVersions.length);
        buf.put((byte) (HEADER_FORM_LONG | (unusedBits & 0x7f)));
        buf.putInt(0);
        buf.put((byte) destinationConnectionId.length);
        buf.put(destinationConnectionId);
        buf.put((byte) sourceConnectionId.length);
        buf.put(sourceConnectionId);
        for (int i = 0; i < supportedVersions.length; i++) {
            buf.putInt(supportedVersions[i]);
        }
        return buf.array();
    }

    /**
     * Parses a Version Negotiation packet.
     *
     * @param packet the whole datagram
     * @return the parsed packet
     * @throws IllegalArgumentException if it is not a well-formed Version
     *         Negotiation packet: short header, non-zero version,
     *         truncated connection IDs, or a version list that is empty or
     *         not a whole number of 4-byte entries
     */
    public static VersionNegotiationPacket parse(byte[] packet) {
        LongHeaderInvariants inv = LongHeaderCodec.parseInvariants(packet);
        if (inv.getVersion() != 0) {
            throw new IllegalArgumentException("Not a Version Negotiation packet");
        }
        int listOffset = 1 + 4 + 1 + inv.getDestinationConnectionId().length
                + 1 + inv.getSourceConnectionId().length;
        int listLength = packet.length - listOffset;
        if (listLength < 4 || listLength % 4 != 0) {
            throw new IllegalArgumentException("Malformed Supported Version list");
        }
        ByteBuffer buf = ByteBuffer.wrap(packet, listOffset, listLength);
        int[] versions = new int[listLength / 4];
        for (int i = 0; i < versions.length; i++) {
            versions[i] = buf.getInt();
        }
        return new VersionNegotiationPacket(inv.getDestinationConnectionId(),
                inv.getSourceConnectionId(), versions);
    }

    /**
     * Returns the Destination Connection ID (the answered packet's Source Connection ID).
     *
     * @return the destination connection ID
     */
    public byte[] getDestinationConnectionId() {
        return destinationConnectionId;
    }

    /**
     * Returns the Source Connection ID (the answered packet's Destination Connection ID).
     *
     * @return the source connection ID
     */
    public byte[] getSourceConnectionId() {
        return sourceConnectionId;
    }

    /**
     * Returns the advertised versions.
     *
     * @return the supported versions
     */
    public int[] getSupportedVersions() {
        return supportedVersions.clone();
    }
}
