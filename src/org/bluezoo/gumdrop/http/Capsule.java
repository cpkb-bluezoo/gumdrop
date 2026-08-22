/*
 * Capsule.java
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

package org.bluezoo.gumdrop.http;

import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.quic.packet.VarInt;

/**
 * One Capsule Protocol capsule (RFC 9297 section 3.2): a type/length/value
 * item on an HTTP data stream.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see CapsuleParser
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9297#section-3">RFC 9297 section 3</a>
 */
public final class Capsule {

    /** {@code DATAGRAM} capsule type (RFC 9297 section 3.5). */
    public static final long TYPE_DATAGRAM = 0x00;

    /** {@code Capsule-Protocol} header name (RFC 9297 section 3.4). */
    public static final String PROTOCOL_HEADER = "capsule-protocol";

    private final long type;
    private final byte[] value;

    /**
     * Constructs a capsule of the given type.
     *
     * @param type the Capsule Type (varint)
     * @param value the Capsule Value; copied
     */
    public Capsule(long type, byte[] value) {
        this.type = type;
        this.value = value != null ? value.clone() : new byte[0];
    }

    /**
     * Returns a {@link #TYPE_DATAGRAM} capsule carrying {@code payload}.
     *
     * @param payload the HTTP Datagram payload
     * @return the capsule
     */
    public static Capsule datagram(byte[] payload) {
        return new Capsule(TYPE_DATAGRAM, payload);
    }

    /**
     * Returns the Capsule Type.
     *
     * @return the type
     */
    public long getType() {
        return type;
    }

    /**
     * Returns a copy of the Capsule Value.
     *
     * @return the value
     */
    public byte[] getValue() {
        return value.clone();
    }

    /**
     * Encodes this capsule (type varint, length varint, value).
     *
     * @return the encoded bytes
     */
    public byte[] encode() {
        int header = VarInt.encodedLength(type) + VarInt.encodedLength(value.length);
        ByteBuffer buf = ByteBuffer.allocate(header + value.length);
        VarInt.encode(type, buf);
        VarInt.encode(value.length, buf);
        buf.put(value);
        return buf.array();
    }

    /**
     * Returns whether {@code headers} carry {@code Capsule-Protocol: ?1}
     * (RFC 9297 section 3.4). List and other structured-field types are
     * treated as absent.
     *
     * @param headers the request or response headers
     * @return true if the Capsule Protocol is enabled
     */
    public static boolean capsuleProtocolEnabled(Headers headers) {
        if (headers == null) {
            return false;
        }
        String raw = headers.getValue(PROTOCOL_HEADER);
        if (raw == null) {
            return false;
        }
        String v = raw.trim();
        return "?1".equals(v) || "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    /**
     * Returns a {@code Capsule-Protocol: ?1} header value suitable for
     * {@link Headers#add(String, String)}.
     *
     * @return {@code ?1}
     */
    public static String protocolEnabledValue() {
        return "?1";
    }
}
