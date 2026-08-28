/*
 * HeaderTableIndex.java
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * O(1) name+value and name-only lookup over a small, fixed table of headers
 * - specifically, an HPACK (RFC 7541 Appendix A) or QPACK (RFC 9204 Appendix
 * A) static table.
 *
 * <p>Both compression schemes need to answer, for every header they encode,
 * "does this exact (name, value) pair have a static table index?" and, on a
 * miss, "does this name alone have one?". A naive implementation answers
 * both by scanning the table list linearly, comparing each entry with
 * {@link Header#equals}. That scan runs on every single response header of
 * every request, so its cost is proportional to table size times header
 * count times connection throughput - HPACK's 61-entry table alone made
 * this a measurable fraction of total CPU time under sustained HTTP/2 load.
 *
 * <p>This builds two hash maps once, from the table's existing (name,
 * value) order, and answers both questions in O(1) afterwards. A {@code
 * null} entry (HPACK reserves list index 0 as unused; see {@link
 * Header#equals} for why a null-valued {@code Header} can never match a
 * lookup key here regardless) is skipped when indexing, so the map index
 * always equals the source list index without the caller needing to adjust
 * for its own indexing convention. When more than one entry shares a name,
 * {@link #indexOfName} returns the first (lowest-index) match, exactly as a
 * top-to-bottom linear scan would.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class HeaderTableIndex {

    private final Map<Header, Integer> byHeader = new HashMap<Header, Integer>();
    private final Map<String, Integer> byName = new HashMap<String, Integer>();

    /**
     * @param table the static table to index, in its defined order; a
     * {@code null} element at any position is skipped
     */
    public HeaderTableIndex(List<Header> table) {
        for (int i = 0; i < table.size(); i++) {
            Header header = table.get(i);
            if (header == null) {
                continue;
            }
            byHeader.put(header, i);
            byName.putIfAbsent(header.getName(), i);
        }
    }

    /**
     * @return the index of the entry matching both {@code header}'s name
     * (case-insensitively) and value (exactly), or -1 if none
     */
    public int indexOf(Header header) {
        Integer index = byHeader.get(header);
        return index != null ? index : -1;
    }

    /**
     * @return the index of the first entry whose name equals {@code name}
     * exactly (case-sensitively, matching the wire requirement that HTTP/2
     * and HTTP/3 header names are already lower-case), or -1 if none
     */
    public int indexOfName(String name) {
        Integer index = byName.get(name);
        return index != null ? index : -1;
    }
}
