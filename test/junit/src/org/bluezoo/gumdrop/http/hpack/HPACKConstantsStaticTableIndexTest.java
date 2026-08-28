/*
 * HPACKConstantsStaticTableIndexTest.java
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

import org.bluezoo.gumdrop.http.Header;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Verifies {@code HPACKConstants.STATIC_TABLE_INDEX} - the O(1) index that
 * replaced a linear {@code STATIC_TABLE.indexOf}/name scan in {@link
 * Encoder#encode} (a measurable HTTP/2 CPU cost under profiling: every
 * response header of every request scanned all 61 static table entries,
 * each comparison re-lowercasing both header names) - answers exactly what
 * a brute-force linear scan of the real RFC 7541 Appendix A table would
 * have answered, for every real entry and for the representative miss/
 * duplicate-name cases the table's shape allows.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HPACKConstantsStaticTableIndexTest extends HPACKConstants {

    private static int bruteForceIndexOf(Header header) {
        return STATIC_TABLE.indexOf(header);
    }

    private static int bruteForceIndexOfName(String name) {
        for (int i = 0; i < STATIC_TABLE.size(); i++) {
            Header entry = STATIC_TABLE.get(i);
            if (entry != null && name.equals(entry.getName())) {
                return i;
            }
        }
        return -1;
    }

    @Test
    public void testEveryRealEntryMatchesBruteForceScan() {
        for (int i = 0; i < STATIC_TABLE.size(); i++) {
            Header entry = STATIC_TABLE.get(i);
            if (entry == null) {
                continue;
            }
            // A freshly-built Header with the same (name, value), not the
            // table's own instance: Header.equals() is only non-null-value
            // reflexive, so entry.equals(entry) is false whenever
            // entry.getValue() is null (most name-only entries) - querying
            // with that exact instance would let HashMap's key==query
            // identity fast path find it anyway, which List.indexOf's
            // unconditional .equals() call never would. Real callers always
            // encode freshly-constructed headers, never the static table's
            // own objects, so this is the representative comparison.
            Header query = new Header(entry.getName(), entry.getValue());
            assertEquals("full match for entry " + i + " (" + entry + ")",
                    bruteForceIndexOf(query), STATIC_TABLE_INDEX.indexOf(query));
            assertEquals("name-only match for entry " + i + " (" + entry + ")",
                    bruteForceIndexOfName(entry.getName()),
                    STATIC_TABLE_INDEX.indexOfName(entry.getName()));
        }
    }

    @Test
    public void testDuplicateNameFamiliesReturnFirstOccurrence() {
        // :method, :path, :scheme and :status each appear more than once
        // in the real table; indexOfName must return the lowest index,
        // exactly as a top-to-bottom scan would.
        assertEquals(bruteForceIndexOfName(":method"), STATIC_TABLE_INDEX.indexOfName(":method"));
        assertEquals(bruteForceIndexOfName(":path"), STATIC_TABLE_INDEX.indexOfName(":path"));
        assertEquals(bruteForceIndexOfName(":scheme"), STATIC_TABLE_INDEX.indexOfName(":scheme"));
        assertEquals(bruteForceIndexOfName(":status"), STATIC_TABLE_INDEX.indexOfName(":status"));
    }

    @Test
    public void testFullMatchMissFallsThroughToMinusOne() {
        Header notInTable = new Header(":method", "PATCH");
        assertEquals(-1, bruteForceIndexOf(notInTable));
        assertEquals(-1, STATIC_TABLE_INDEX.indexOf(notInTable));
    }

    @Test
    public void testNameOnlyEntryDoesNotSatisfyFullMatch() {
        // "accept-charset" is a name-only entry (null value in the table);
        // a real request header for it always carries a value, which must
        // never be treated as an indexed full match.
        Header realHeader = new Header("accept-charset", "utf-8");
        assertEquals(-1, bruteForceIndexOf(realHeader));
        assertEquals(-1, STATIC_TABLE_INDEX.indexOf(realHeader));
    }

    @Test
    public void testUnknownNameMisses() {
        assertEquals(-1, bruteForceIndexOfName("x-custom-header"));
        assertEquals(-1, STATIC_TABLE_INDEX.indexOfName("x-custom-header"));
    }
}
