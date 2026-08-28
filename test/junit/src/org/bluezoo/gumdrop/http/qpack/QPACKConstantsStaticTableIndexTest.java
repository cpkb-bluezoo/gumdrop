/*
 * QPACKConstantsStaticTableIndexTest.java
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

package org.bluezoo.gumdrop.http.qpack;

import org.bluezoo.gumdrop.http.Header;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * QPACK counterpart to {@code HPACKConstantsStaticTableIndexTest}: verifies
 * {@code QPACKConstants.STATIC_TABLE_INDEX} matches a brute-force linear
 * scan of the real RFC 9204 Appendix A table for every real entry, and for
 * the miss/duplicate-name cases its shape allows. Unlike HPACK's table,
 * QPACK's is zero-indexed with no unused placeholder entry - worth pinning
 * down explicitly since {@link org.bluezoo.gumdrop.http.HeaderTableIndex}
 * is shared between both and must handle both indexing conventions.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QPACKConstantsStaticTableIndexTest extends QPACKConstants {

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
    public void testFirstEntryIsIndexZero() {
        // QPACK's table has no null placeholder at index 0, unlike HPACK's -
        // the very first real entry must resolve to index 0, not 1.
        Header first = STATIC_TABLE.get(0);
        assertEquals(0, STATIC_TABLE_INDEX.indexOfName(first.getName()));
    }

    @Test
    public void testEveryRealEntryMatchesBruteForceScan() {
        for (int i = 0; i < STATIC_TABLE.size(); i++) {
            Header entry = STATIC_TABLE.get(i);
            if (entry == null) {
                continue;
            }
            // A freshly-built Header with the same (name, value), not the
            // table's own instance - see the HPACK counterpart of this test
            // for why: Header.equals() is only reflexive for a non-null
            // value, so comparing the table's own null-valued entry against
            // itself would let HashMap's key==query identity fast path find
            // it anyway, which a real caller (always encoding a freshly
            // constructed header) could never rely on.
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
        assertEquals(bruteForceIndexOfName(":method"), STATIC_TABLE_INDEX.indexOfName(":method"));
        assertEquals(bruteForceIndexOfName(":path"), STATIC_TABLE_INDEX.indexOfName(":path"));
        assertEquals(bruteForceIndexOfName(":status"), STATIC_TABLE_INDEX.indexOfName(":status"));
    }

    @Test
    public void testUnknownNameMisses() {
        assertEquals(-1, bruteForceIndexOfName("x-custom-header"));
        assertEquals(-1, STATIC_TABLE_INDEX.indexOfName("x-custom-header"));
    }

    @Test
    public void testFullMatchMissFallsThroughToMinusOne() {
        Header notInTable = new Header(":method", "PATCH");
        assertEquals(-1, bruteForceIndexOf(notInTable));
        assertEquals(-1, STATIC_TABLE_INDEX.indexOf(notInTable));
    }
}
