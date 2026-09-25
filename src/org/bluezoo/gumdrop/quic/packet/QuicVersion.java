/*
 * QuicVersion.java
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
 * The QUIC versions this implementation speaks, together with everything
 * that differs between them at the packet layer.
 *
 * <p>QUIC version 2 (RFC 9369) is deliberately near-identical to version
 * 1: it changes only the Initial salt, the HKDF labels used to derive
 * packet protection keys, the 2-bit long-header packet type values, and
 * the Retry Integrity Tag key and nonce. Every other aspect of the
 * transport, TLS integration and HTTP/3 is shared, so a connection simply
 * carries one of these values and consults it at those four points.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9369">RFC 9369</a>
 */
public enum QuicVersion {

    /** QUIC version 1 (RFC 9000, RFC 9001). */
    V1(0x00000001, "quic", new int[] { 0, 1, 2, 3 },
            new byte[] {
                (byte) 0x38, (byte) 0x76, (byte) 0x2c, (byte) 0xf7,
                (byte) 0xf5, (byte) 0x59, (byte) 0x34, (byte) 0xb3,
                (byte) 0x4d, (byte) 0x17, (byte) 0x9a, (byte) 0xe6,
                (byte) 0xa4, (byte) 0xc8, (byte) 0x0c, (byte) 0xad,
                (byte) 0xcc, (byte) 0xbb, (byte) 0x7f, (byte) 0x0a
            },
            new byte[] {
                (byte) 0xbe, (byte) 0x0c, (byte) 0x69, (byte) 0x0b,
                (byte) 0x9f, (byte) 0x66, (byte) 0x57, (byte) 0x5a,
                (byte) 0x1d, (byte) 0x76, (byte) 0x6b, (byte) 0x54,
                (byte) 0xe3, (byte) 0x68, (byte) 0xc8, (byte) 0x4e
            },
            new byte[] {
                (byte) 0x46, (byte) 0x15, (byte) 0x99, (byte) 0xd3,
                (byte) 0x5d, (byte) 0x63, (byte) 0x2b, (byte) 0xf2,
                (byte) 0x23, (byte) 0x98, (byte) 0x25, (byte) 0xbb
            }),

    /** QUIC version 2 (RFC 9369). */
    V2(0x6b3343cf, "quicv2", new int[] { 1, 2, 3, 0 },
            new byte[] {
                (byte) 0x0d, (byte) 0xed, (byte) 0xe3, (byte) 0xde,
                (byte) 0xf7, (byte) 0x00, (byte) 0xa6, (byte) 0xdb,
                (byte) 0x81, (byte) 0x93, (byte) 0x81, (byte) 0xbe,
                (byte) 0x6e, (byte) 0x26, (byte) 0x9d, (byte) 0xcb,
                (byte) 0xf9, (byte) 0xbd, (byte) 0x2e, (byte) 0xd9
            },
            new byte[] {
                (byte) 0x8f, (byte) 0xb4, (byte) 0xb0, (byte) 0x1b,
                (byte) 0x56, (byte) 0xac, (byte) 0x48, (byte) 0xe2,
                (byte) 0x60, (byte) 0xfb, (byte) 0xcb, (byte) 0xce,
                (byte) 0xad, (byte) 0x7c, (byte) 0xcc, (byte) 0x92
            },
            new byte[] {
                (byte) 0xd8, (byte) 0x69, (byte) 0x69, (byte) 0xbc,
                (byte) 0x2d, (byte) 0x7c, (byte) 0x6d, (byte) 0x99,
                (byte) 0x90, (byte) 0xef, (byte) 0xb0, (byte) 0x4a
            });

    private final int wireValue;
    private final String labelPrefix;
    /** Wire type bits, indexed by the version 1 packet type constants. */
    private final int[] wireTypes;
    private final byte[] initialSalt;
    private final byte[] retryKey;
    private final byte[] retryNonce;

    QuicVersion(int wireValue, String labelPrefix, int[] wireTypes, byte[] initialSalt,
            byte[] retryKey, byte[] retryNonce) {
        this.wireValue = wireValue;
        this.labelPrefix = labelPrefix;
        this.wireTypes = wireTypes;
        this.initialSalt = initialSalt;
        this.retryKey = retryKey;
        this.retryNonce = retryNonce;
    }

    /**
     * Returns the 32-bit value carried in the Version field of long headers.
     *
     * @return the wire value
     */
    public int getWireValue() {
        return wireValue;
    }

    /**
     * Returns the QUIC version for a Version field value.
     *
     * @param wireValue the Version field of a long header
     * @return the version, or {@code null} if this implementation does not
     *         speak it (including zero, which marks Version Negotiation)
     */
    public static QuicVersion fromWireValue(int wireValue) {
        QuicVersion[] all = values();
        for (int i = 0; i < all.length; i++) {
            if (all[i].wireValue == wireValue) {
                return all[i];
            }
        }
        return null;
    }

    /**
     * Returns the prefix of the HKDF labels used to derive packet
     * protection keys and key updates: {@code "quic"} or {@code "quicv2"}
     * (RFC 9001 section 5.1, RFC 9369 section 3.3.2).
     *
     * @return the label prefix
     */
    public String getLabelPrefix() {
        return labelPrefix;
    }

    /**
     * Returns the salt from which Initial secrets are derived.
     *
     * @return a copy of the salt
     */
    public byte[] getInitialSalt() {
        return initialSalt.clone();
    }

    byte[] getRetryKey() {
        return retryKey;
    }

    byte[] getRetryNonce() {
        return retryNonce;
    }

    /**
     * Converts a packet type, expressed with the version-independent
     * {@code LongHeaderCodec.TYPE_*} constants, to the two bits this
     * version puts on the wire.
     *
     * @param packetType a {@code LongHeaderCodec.TYPE_*} constant
     * @return the wire value of the long-header type bits
     */
    int toWireType(int packetType) {
        return wireTypes[packetType];
    }

    /**
     * Converts the two long-header type bits of this version to the
     * version-independent {@code LongHeaderCodec.TYPE_*} constant.
     *
     * @param wireType the type bits, 0-3
     * @return the packet type constant
     */
    int fromWireType(int wireType) {
        for (int i = 0; i < wireTypes.length; i++) {
            if (wireTypes[i] == wireType) {
                return i;
            }
        }
        throw new IllegalArgumentException("Bad long header type " + wireType);
    }
}
