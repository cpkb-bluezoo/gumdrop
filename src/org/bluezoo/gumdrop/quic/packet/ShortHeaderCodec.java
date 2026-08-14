/*
 * ShortHeaderCodec.java
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
 * Builds and parses the unprotected fields of a QUIC short-header
 * (1-RTT) packet (RFC 9000 section 17.3.1).
 *
 * <p>Unlike long-header packets, a short-header packet carries no
 * version, no Source Connection ID, and -- critically -- no Length
 * field: it always runs to the end of its UDP datagram, and its
 * Destination Connection ID has no length prefix, since by this point
 * in the connection both endpoints already agree on the length of
 * connection IDs in use. This class therefore takes that length as a
 * parameter rather than reading it from the wire.
 *
 * <p>Connection ID issuance and rotation (RFC 9000 section 5.1) are not
 * implemented yet; callers currently use a single, fixed-length
 * connection ID for the lifetime of a connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-17.3.1">RFC 9000 section 17.3.1</a>
 */
public final class ShortHeaderCodec {

    /** RFC 9000 section 17.3.1: header form bit, clear for short-header packets. */
    private static final int HEADER_FORM_SHORT = 0x00;
    /** RFC 9000 section 17.3.1: fixed bit, always set on the wire. */
    private static final int FIXED_BIT = 0x40;

    private ShortHeaderCodec() {
    }

    /**
     * Returns the offset of the packet number field for a given
     * connection ID length: always {@code 1 + dcidLength}.
     *
     * @param dcidLength the (out-of-band agreed) Destination Connection ID length
     * @return the packet number field offset
     */
    public static int packetNumberOffset(int dcidLength) {
        return 1 + dcidLength;
    }

    /**
     * Builds the unprotected header of a short-header packet: the first
     * byte, the Destination Connection ID, and the packet number field.
     *
     * @param destinationConnectionId the Destination Connection ID
     * @param keyPhase the current Key Phase bit (RFC 9001 section 6)
     * @param packetNumber the full packet number
     * @param packetNumberLength the packet number encoding length, 1-4
     * @return the unprotected header bytes
     */
    public static byte[] build(byte[] destinationConnectionId, boolean keyPhase,
            long packetNumber, int packetNumberLength) {
        byte[] header = new byte[1 + destinationConnectionId.length + packetNumberLength];

        int firstByte = HEADER_FORM_SHORT | FIXED_BIT
                | (keyPhase ? 0x04 : 0x00)
                | (packetNumberLength - 1);
        header[0] = (byte) firstByte;

        System.arraycopy(destinationConnectionId, 0, header, 1, destinationConnectionId.length);

        int pnOffset = packetNumberOffset(destinationConnectionId.length);
        PacketNumberCodec.encode(packetNumber, packetNumberLength, header, pnOffset);

        return header;
    }

    /**
     * Extracts the Destination Connection ID from a received
     * short-header packet.
     *
     * @param packet the received packet bytes
     * @param dcidLength the (out-of-band agreed) Destination Connection ID length
     * @return the Destination Connection ID
     */
    public static byte[] parseDestinationConnectionId(byte[] packet, int dcidLength) {
        byte[] dcid = new byte[dcidLength];
        System.arraycopy(packet, 1, dcid, 0, dcidLength);
        return dcid;
    }
}
