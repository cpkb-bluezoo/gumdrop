/*
 * TableEntry.java
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

package org.bluezoo.gumdrop.http.hpack;

/**
 * One entry of the HPACK decoder's dynamic table: a field's name and value
 * as octets, exactly as decoded (RFC 7541 section 4.1).
 *
 * <p>Entries are stored as octets, not text, so that what the peer sent is what
 * is delivered whenever the entry is referenced again, whatever those octets
 * turn out to mean. The arrays are never modified once the entry exists.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class TableEntry {

    /** RFC 7541 section 4.1: the fixed overhead added to every entry's size. */
    static final int OVERHEAD = 32;

    final byte[] name;
    final byte[] value;

    TableEntry(byte[] name, byte[] value) {
        this.name = name;
        this.value = value;
    }

    /** RFC 7541 section 4.1: octets in name and value plus 32. */
    int size() {
        return name.length + value.length + OVERHEAD;
    }

}
