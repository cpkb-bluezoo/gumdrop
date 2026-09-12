/*
 * WireWriter.java
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

package org.bluezoo.gumdrop.tls;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A small append-only byte writer for RFC 8446's TLS presentation
 * language: fixed-width big-endian integers and length-prefixed opaque
 * vectors. Package-private -- an implementation detail of
 * {@link HandshakeMessages}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class WireWriter {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    void u8(int value) {
        out.write(value & 0xff);
    }

    void u16(int value) {
        out.write((value >> 8) & 0xff);
        out.write(value & 0xff);
    }

    void u24(int value) {
        out.write((value >> 16) & 0xff);
        out.write((value >> 8) & 0xff);
        out.write(value & 0xff);
    }

    /**
     * Writes a genuinely unsigned 32-bit value (a ticket lifetime, age,
     * or age-add) as four big-endian octets. {@code value} is an
     * ordinary Java {@code int} relying on two's-complement wraparound
     * for values above {@code Integer.MAX_VALUE} -- the same bit pattern
     * either way, so callers doing wraparound arithmetic on these fields
     * (e.g. {@code age + ageAdd}) need no special unsigned handling.
     */
    void u32(int value) {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    void bytes(byte[] data) {
        out.write(data, 0, data.length);
    }

    /** Writes {@code data} preceded by its length as a one-octet vector. */
    void opaque8(byte[] data) {
        u8(data.length);
        bytes(data);
    }

    /** Writes {@code data} preceded by its length as a two-octet vector. */
    void opaque16(byte[] data) {
        u16(data.length);
        bytes(data);
    }

    /** Writes {@code data} preceded by its length as a three-octet vector. */
    void opaque24(byte[] data) {
        u24(data.length);
        bytes(data);
    }

    /** Writes an ASCII string preceded by its length as a one-octet vector. */
    void opaque8Ascii(String s) {
        opaque8(s.getBytes(StandardCharsets.US_ASCII));
    }

    int length() {
        return out.size();
    }

    byte[] toByteArray() {
        return out.toByteArray();
    }

    /**
     * Wraps {@code content} as a complete RFC 8446 section 4 handshake
     * message: a one-octet type, a three-octet length, then the content.
     */
    static byte[] frameHandshakeMessage(int handshakeType, byte[] content) {
        WireWriter w = new WireWriter();
        w.u8(handshakeType);
        w.u24(content.length);
        w.bytes(content);
        return w.toByteArray();
    }

}
