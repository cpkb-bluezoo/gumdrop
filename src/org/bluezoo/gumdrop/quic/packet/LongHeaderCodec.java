/*
 * LongHeaderCodec.java
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
 * Builds and parses the unprotected fields of QUIC long-header packets
 * (RFC 9000 section 17.2): Initial, 0-RTT, Handshake, and Retry.
 *
 * <p>{@link #build} constructs the header exactly as it must look
 * <em>before</em> header protection is applied -- real reserved bits,
 * real packet-number length bits, real packet-number bytes -- since
 * header protection (RFC 9001 section 5.4) is an XOR applied on top by
 * {@link PacketProtection} afterwards, not something this class does.
 *
 * <p>{@link #parsePrefix} is the receive-side counterpart: it reads only
 * the fields that are never protected (everything through the Length
 * field) and stops at the packet number field, since that field and the
 * low bits of the first byte are still protected at that point and must
 * be unmasked via {@link PacketProtection} before they can be read.
 *
 * <p>Retry and 0-RTT packets are recognised by type but 0-RTT has no
 * additional fields beyond a Handshake-shaped header (it never carries
 * CRYPTO frames), and Retry is not otherwise supported yet.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-17.2">RFC 9000 section 17.2</a>
 */
public final class LongHeaderCodec {

    /** RFC 9000 section 17.2.2. */
    public static final int TYPE_INITIAL = 0;
    /** RFC 9000 section 17.2.3. */
    public static final int TYPE_0RTT = 1;
    /** RFC 9000 section 17.2.4. */
    public static final int TYPE_HANDSHAKE = 2;
    /** RFC 9000 section 17.2.5. */
    public static final int TYPE_RETRY = 3;

    /** RFC 9000 section 17.2: header form bit, set for all long-header packets. */
    private static final int HEADER_FORM_LONG = 0x80;
    /** RFC 9000 section 17.2: fixed bit, always set on the wire. */
    private static final int FIXED_BIT = 0x40;

    private static final byte[] EMPTY_TOKEN = new byte[0];

    private LongHeaderCodec() {
    }

    /**
     * Builds the unprotected header of a long-header packet, from the
     * first byte through the end of the packet number field.
     *
     * @param packetType one of the {@code TYPE_*} constants
     * @param version the QUIC version
     * @param destinationConnectionId the Destination Connection ID, at most 20 bytes
     * @param sourceConnectionId the Source Connection ID, at most 20 bytes
     * @param token the Token field (Initial packets only; pass an empty
     *              array for other packet types)
     * @param packetNumber the full packet number
     * @param packetNumberLength the packet number encoding length, 1-4
     *                           (see {@link PacketNumberCodec#encodedLength})
     * @param protectedPayloadLength the length in bytes of the AEAD
     *                               ciphertext that will follow this
     *                               header (frame bytes plus the 16-byte tag)
     * @return the unprotected header bytes
     */
    public static byte[] build(int packetType, int version, byte[] destinationConnectionId,
            byte[] sourceConnectionId, byte[] token, long packetNumber, int packetNumberLength,
            int protectedPayloadLength) {
        long remainingLength = packetNumberLength + protectedPayloadLength;
        int tokenFieldLength = (packetType == TYPE_INITIAL)
                ? VarInt.encodedLength(token.length) + token.length
                : 0;

        int size = 1 + 4
                + 1 + destinationConnectionId.length
                + 1 + sourceConnectionId.length
                + tokenFieldLength
                + VarInt.encodedLength(remainingLength)
                + packetNumberLength;

        ByteBuffer buf = ByteBuffer.allocate(size);

        int firstByte = HEADER_FORM_LONG | FIXED_BIT
                | ((packetType & 0x03) << 4)
                | (packetNumberLength - 1);
        buf.put((byte) firstByte);
        buf.putInt(version);

        buf.put((byte) destinationConnectionId.length);
        buf.put(destinationConnectionId);
        buf.put((byte) sourceConnectionId.length);
        buf.put(sourceConnectionId);

        if (packetType == TYPE_INITIAL) {
            VarInt.encode(token.length, buf);
            buf.put(token);
        }

        VarInt.encode(remainingLength, buf);

        byte[] header = buf.array();
        int pnOffset = size - packetNumberLength;
        PacketNumberCodec.encode(packetNumber, packetNumberLength, header, pnOffset);

        return header;
    }

    /**
     * Reads the unprotected prefix of a long-header packet: everything
     * through the Length field. The packet number field itself is left
     * unread (and, at this point, is still header-protected); use
     * {@link LongHeaderPrefix#getPacketNumberOffset()} together with
     * {@link PacketProtection} to remove header protection before
     * reading it.
     *
     * @param packet the received packet bytes, starting at the first byte
     * @return the parsed prefix
     */
    public static LongHeaderPrefix parsePrefix(byte[] packet) {
        ByteBuffer buf = ByteBuffer.wrap(packet);

        int firstByte = buf.get() & 0xff;
        int packetType = (firstByte >>> 4) & 0x03;
        int version = buf.getInt();

        int dcidLength = buf.get() & 0xff;
        byte[] dcid = new byte[dcidLength];
        buf.get(dcid);

        int scidLength = buf.get() & 0xff;
        byte[] scid = new byte[scidLength];
        buf.get(scid);

        byte[] token = EMPTY_TOKEN;
        if (packetType == TYPE_INITIAL) {
            int tokenLength = (int) VarInt.decode(buf);
            token = new byte[tokenLength];
            buf.get(token);
        }

        long remainingLength = VarInt.decode(buf);
        int pnOffset = buf.position();

        return new LongHeaderPrefix(packetType, version, dcid, scid, token, pnOffset, remainingLength);
    }
}
