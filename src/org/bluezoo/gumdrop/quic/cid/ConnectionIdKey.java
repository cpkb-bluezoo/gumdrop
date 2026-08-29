/*
 * ConnectionIdKey.java
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

package org.bluezoo.gumdrop.quic.cid;

import java.util.Arrays;

import org.bluezoo.util.ByteArrays;

/**
 * Hashable wrapper around a connection ID's raw bytes (RFC 9000 section
 * 5.1: an opaque byte sequence of 0-20 bytes), used to key connection
 * lookup maps on the per-datagram demultiplexing hot path without paying
 * for a hex-{@code String} conversion on every packet.
 *
 * <p>The wrapped array is stored by reference, not defensively copied --
 * this class exists specifically for the hot path where the caller
 * already holds a freshly parsed, otherwise-unshared connection ID array
 * (e.g. {@code LongHeaderCodec#parsePrefix}'s destination connection ID,
 * or a freshly generated one), matching the convention already used
 * elsewhere in this package that connection ID byte arrays are treated as
 * immutable once produced. A caller that cannot make that guarantee
 * should copy the array before constructing a key from it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-5.1">RFC 9000 section 5.1</a>
 */
public final class ConnectionIdKey {

    private final byte[] bytes;
    private final int hash;

    /**
     * Wraps the given connection ID bytes.
     *
     * @param bytes the connection ID bytes, not copied
     */
    public ConnectionIdKey(byte[] bytes) {
        this.bytes = bytes;
        this.hash = Arrays.hashCode(bytes);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ConnectionIdKey)) {
            return false;
        }
        return Arrays.equals(bytes, ((ConnectionIdKey) obj).bytes);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return ByteArrays.toHexString(bytes);
    }

}
