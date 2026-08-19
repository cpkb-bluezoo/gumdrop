/*
 * StatelessResetPacket.java
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
import java.security.SecureRandom;

import org.bluezoo.gumdrop.quic.cid.StatelessResetToken;

/**
 * Stateless reset datagram construction and detection (RFC 9000 section 10.3).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-10.3">RFC 9000 section 10.3</a>
 */
public final class StatelessResetPacket {

    /** RFC 9000 section 10.3: minimum datagram length for a stateless reset. */
    public static final int MIN_DATAGRAM_LENGTH = 21;

    private StatelessResetPacket() {
    }

    /** RFC 9000 section 14.1: minimum supported datagram size. */
    private static final int MIN_SUPPORTED_DATAGRAM_SIZE = 1200;

    /**
     * Computes the wire length of a stateless reset response to a
     * received datagram (RFC 9000 section 10.3.3).
     *
     * @param receivedLength the received datagram length in bytes
     * @return the reset length, or {@code -1} if no reset may be sent
     */
    public static int computeLength(int receivedLength) {
        if (receivedLength < MIN_DATAGRAM_LENGTH) {
            return -1;
        }
        int size = Math.max(receivedLength, MIN_DATAGRAM_LENGTH);
        if (receivedLength < MIN_SUPPORTED_DATAGRAM_SIZE) {
            size = Math.min(size, MIN_SUPPORTED_DATAGRAM_SIZE);
        }
        if (size > 3 * receivedLength) {
            size = 3 * receivedLength;
        }
        if (size < MIN_DATAGRAM_LENGTH) {
            return -1;
        }
        return size;
    }

    /**
     * Builds a stateless reset datagram.
     *
     * @param receivedLength the received datagram length used for sizing
     * @param token the 16-byte stateless reset token
     * @param random a source of random bytes for the unpredictable prefix
     * @return the wire image, or {@code null} if {@link #computeLength}
     *         returns {@code -1}
     */
    public static byte[] build(int receivedLength, byte[] token, SecureRandom random) {
        int length = computeLength(receivedLength);
        if (length < 0) {
            return null;
        }
        byte[] packet = new byte[length];
        random.nextBytes(packet);
        packet[0] = (byte) ((packet[0] & 0x3f) | 0x40);
        System.arraycopy(token, 0, packet, length - StatelessResetToken.LENGTH, StatelessResetToken.LENGTH);
        return packet;
    }

    /**
     * Returns whether the tail of a datagram matches a stateless reset
     * token (RFC 9000 section 10.3.1).
     *
     * @param datagram the datagram buffer
     * @param offset the datagram start offset
     * @param length the datagram length
     * @param token the expected 16-byte token
     * @return {@code true} if the datagram is long enough and the tail
     *         matches
     */
    public static boolean matchesToken(ByteBuffer datagram, int offset, int length, byte[] token) {
        if (token == null || token.length != StatelessResetToken.LENGTH
                || length < MIN_DATAGRAM_LENGTH || datagram == null) {
            return false;
        }
        int tokenStart = offset + length - StatelessResetToken.LENGTH;
        if (tokenStart < offset) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < StatelessResetToken.LENGTH; i++) {
            diff |= datagram.get(tokenStart + i) ^ token[i];
        }
        return diff == 0;
    }

    /**
     * Returns whether the tail of a datagram matches a stateless reset
     * token.
     *
     * @param datagram the datagram bytes
     * @param offset the datagram start offset
     * @param length the datagram length
     * @param token the expected 16-byte token
     * @return {@code true} if the datagram is long enough and the tail
     *         matches
     */
    public static boolean matchesToken(byte[] datagram, int offset, int length, byte[] token) {
        if (token == null || token.length != StatelessResetToken.LENGTH
                || length < MIN_DATAGRAM_LENGTH || datagram == null) {
            return false;
        }
        int tokenStart = offset + length - StatelessResetToken.LENGTH;
        if (tokenStart < offset || tokenStart + StatelessResetToken.LENGTH > datagram.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < StatelessResetToken.LENGTH; i++) {
            diff |= datagram[tokenStart + i] ^ token[i];
        }
        return diff == 0;
    }

    /**
     * Returns whether the datagram tail matches any known peer token.
     *
     * @param datagram the datagram buffer
     * @param offset the datagram start offset
     * @param length the datagram length
     * @param tokens the known peer tokens
     * @return {@code true} if any token matches
     */
    public static boolean matchesAnyKnownToken(ByteBuffer datagram, int offset, int length,
            Iterable<byte[]> tokens) {
        for (byte[] token : tokens) {
            if (matchesToken(datagram, offset, length, token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether the datagram tail matches any known peer token.
     *
     * @param datagram the datagram bytes
     * @param offset the datagram start offset
     * @param length the datagram length
     * @param tokens the known peer tokens
     * @return {@code true} if any token matches
     */
    public static boolean matchesAnyKnownToken(byte[] datagram, int offset, int length,
            Iterable<byte[]> tokens) {
        for (byte[] token : tokens) {
            if (matchesToken(datagram, offset, length, token)) {
                return true;
            }
        }
        return false;
    }
}
