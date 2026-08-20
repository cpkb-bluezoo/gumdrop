/*
 * PacketNumberCodec.java
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
 * Encoding and reconstruction of QUIC's truncated packet numbers
 * (RFC 9000 section 17.1 and Appendix A).
 *
 * <p>On the wire, a packet number is truncated to the fewest low-order
 * bytes (1-4) that unambiguously identify it, given how many
 * packet-number-space packets the peer is known to have already
 * acknowledged; the receiver reconstructs the full value from the
 * truncated bytes and the largest packet number it has successfully
 * processed so far in that space.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#appendix-A">RFC 9000 Appendix A</a>
 */
public final class PacketNumberCodec {

    private PacketNumberCodec() {
    }

    /**
     * Returns the number of low-order bytes needed to encode
     * {@code fullPacketNumber} unambiguously, given the largest packet
     * number the peer is known to have acknowledged in this packet
     * number space (RFC 9000 Appendix A, {@code EncodePacketNumber}).
     *
     * @param fullPacketNumber the packet number being sent
     * @param largestAcked the largest packet number acknowledged by the
     *                     peer in this space, or -1 if none has been
     *                     acknowledged yet
     * @return the encoding length in bytes, 1-4
     */
    public static int encodedLength(long fullPacketNumber, long largestAcked) {
        long numUnacked = (largestAcked < 0)
                ? fullPacketNumber + 1
                : fullPacketNumber - largestAcked;
        int minBits = 64 - Long.numberOfLeadingZeros(numUnacked) + 1;
        int numBytes = (minBits + 7) / 8;
        if (numBytes < 1) {
            numBytes = 1;
        }
        if (numBytes > 4) {
            numBytes = 4;
        }
        return numBytes;
    }

    /**
     * Writes the low-order {@code length} bytes of a full packet number,
     * big-endian, into {@code out} starting at {@code offset}.
     *
     * @param fullPacketNumber the packet number being sent
     * @param length the encoding length from {@link #encodedLength}
     * @param out the destination array
     * @param offset the offset within {@code out} to write at
     */
    public static void encode(long fullPacketNumber, int length, byte[] out, int offset) {
        for (int i = 0; i < length; i++) {
            int shift = 8 * (length - 1 - i);
            out[offset + i] = (byte) ((fullPacketNumber >>> shift) & 0xff);
        }
    }

    /**
     * Reconstructs a full packet number from its truncated wire
     * encoding (RFC 9000 Appendix A, {@code DecodePacketNumber}).
     *
     * @param largestReceived the largest full packet number successfully
     *                        processed so far in this packet number
     *                        space, or -1 if none has been processed yet
     * @param truncatedValue the truncated packet number bytes, interpreted
     *                       as an unsigned big-endian integer
     * @param length the encoding length the bytes were read from, 1-4
     * @return the reconstructed full packet number
     */
    public static long decode(long largestReceived, long truncatedValue, int length) {
        long expected = largestReceived + 1;
        int pnBits = length * 8;
        long pnWindow = 1L << pnBits;
        long pnHalfWindow = pnWindow / 2;
        long pnMask = pnWindow - 1;

        long candidate = (expected & ~pnMask) | truncatedValue;
        if (candidate <= expected - pnHalfWindow && candidate < (1L << 62) - pnWindow) {
            return candidate + pnWindow;
        }
        if (candidate > expected + pnHalfWindow && candidate >= pnWindow) {
            return candidate - pnWindow;
        }
        return candidate;
    }
}
