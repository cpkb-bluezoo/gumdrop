/*
 * WireReader.java
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

import java.nio.charset.StandardCharsets;

/**
 * A small forward-only byte cursor for RFC 8446's TLS presentation
 * language, the parsing counterpart to {@link WireWriter}. Every read
 * past the end of the underlying array throws
 * {@link HandshakeFormatException} -- a malformed handshake message is
 * an ordinary, expected failure mode here, not a programming error.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class WireReader {

    private final byte[] data;
    private final int end;
    private int pos;

    WireReader(byte[] data) {
        this(data, 0, data.length);
    }

    WireReader(byte[] data, int offset, int length) {
        this.data = data;
        this.pos = offset;
        this.end = offset + length;
    }

    boolean hasRemaining() {
        return pos < end;
    }

    int remaining() {
        return end - pos;
    }

    int u8() throws HandshakeFormatException {
        require(1);
        return data[pos++] & 0xff;
    }

    int u16() throws HandshakeFormatException {
        require(2);
        int value = ((data[pos] & 0xff) << 8) | (data[pos + 1] & 0xff);
        pos += 2;
        return value;
    }

    int u24() throws HandshakeFormatException {
        require(3);
        int value = ((data[pos] & 0xff) << 16) | ((data[pos + 1] & 0xff) << 8) | (data[pos + 2] & 0xff);
        pos += 3;
        return value;
    }

    /** Reads a genuinely unsigned 32-bit value; see {@link WireWriter#u32}. */
    int u32() throws HandshakeFormatException {
        require(4);
        int value = ((data[pos] & 0xff) << 24) | ((data[pos + 1] & 0xff) << 16)
                | ((data[pos + 2] & 0xff) << 8) | (data[pos + 3] & 0xff);
        pos += 4;
        return value;
    }

    byte[] bytes(int length) throws HandshakeFormatException {
        require(length);
        byte[] result = new byte[length];
        System.arraycopy(data, pos, result, 0, length);
        pos += length;
        return result;
    }

    /** Reads a one-octet-length-prefixed opaque vector. */
    byte[] opaque8() throws HandshakeFormatException {
        return bytes(u8());
    }

    /** Reads a two-octet-length-prefixed opaque vector. */
    byte[] opaque16() throws HandshakeFormatException {
        return bytes(u16());
    }

    /** Reads a three-octet-length-prefixed opaque vector. */
    byte[] opaque24() throws HandshakeFormatException {
        return bytes(u24());
    }

    /** Reads a one-octet-length-prefixed ASCII string. */
    String opaque8Ascii() throws HandshakeFormatException {
        return new String(opaque8(), StandardCharsets.US_ASCII);
    }

    /**
     * Returns a reader over the next {@code length} bytes, without
     * advancing past them in this reader -- for splitting a vector's
     * content out for its own bounded sub-parse.
     */
    WireReader slice(int length) throws HandshakeFormatException {
        require(length);
        WireReader sub = new WireReader(data, pos, length);
        pos += length;
        return sub;
    }

    private void require(int n) throws HandshakeFormatException {
        if (end - pos < n) {
            throw new HandshakeFormatException("Unexpected end of message: need " + n
                    + " bytes, have " + (end - pos));
        }
    }

}
