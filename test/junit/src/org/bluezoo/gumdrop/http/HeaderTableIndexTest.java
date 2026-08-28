/*
 * HeaderTableIndexTest.java
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

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link HeaderTableIndex} pinning its contract to exactly
 * what a linear {@code List.indexOf}/name scan over the source table would
 * have returned - the behaviour it replaces in the HPACK and QPACK
 * encoders' static table lookups (see {@code HPACKConstants.STATIC_TABLE}
 * and {@code QPACKConstants.STATIC_TABLE}), since those tables carry a
 * {@code null} placeholder entry and duplicate names that a naive hash
 * index could easily get wrong.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HeaderTableIndexTest {

    // Mirrors the shape of HPACK's real static table closely enough to
    // exercise every edge case: a leading null (HPACK reserves list index 0),
    // and a name shared by more than one entry (like :method / :status).
    private static final List<Header> TABLE = Arrays.asList(
            null,
            new Header(":method", "GET"),
            new Header(":method", "POST"),
            new Header("content-type", null),
            new Header("accept-encoding", "gzip, deflate")
    );

    private final HeaderTableIndex index = new HeaderTableIndex(TABLE);

    @Test
    public void testNullEntryIsSkippedAndDoesNotShiftIndices() {
        // Index 0 is the null placeholder; real entries keep their actual
        // list positions (1..4), matching List.indexOf's own indexing.
        assertEquals(1, index.indexOf(new Header(":method", "GET")));
        assertEquals(2, index.indexOf(new Header(":method", "POST")));
    }

    @Test
    public void testFullMatchIsCaseInsensitiveOnName() {
        assertEquals(1, index.indexOf(new Header(":METHOD", "GET")));
    }

    @Test
    public void testFullMatchMissReturnsMinusOne() {
        assertEquals(-1, index.indexOf(new Header(":method", "PUT")));
        assertEquals(-1, index.indexOf(new Header("x-unknown", "value")));
    }

    @Test
    public void testNameOnlyEntryNeverMatchesAsFullMatch() {
        // "content-type" is stored with a null value (name-only table
        // entry); a real header for the same name always carries a real
        // value, so it must never satisfy the full name+value lookup -
        // that would silently emit the wrong (indexed, no value) wire
        // representation.
        assertEquals(-1, index.indexOf(new Header("content-type", "text/plain")));
    }

    @Test
    public void testIndexOfNameReturnsFirstOccurrenceForDuplicateNames() {
        // Two ":method" entries exist, at indices 1 and 2; a linear scan
        // from the front would report the first one.
        assertEquals(1, index.indexOfName(":method"));
    }

    @Test
    public void testIndexOfNameMatchesNameOnlyTableEntry() {
        assertEquals(3, index.indexOfName("content-type"));
    }

    @Test
    public void testIndexOfNameIsCaseSensitive() {
        // HTTP/2 and HTTP/3 wire header names are always lower-case
        // already; a mixed-case name reaching here should not match, same
        // as the exact-String-equals scan it replaces.
        assertEquals(-1, index.indexOfName(":METHOD"));
    }

    @Test
    public void testIndexOfNameMissReturnsMinusOne() {
        assertEquals(-1, index.indexOfName("x-unknown"));
    }

    @Test
    public void testEmptyTableAlwaysMisses() {
        HeaderTableIndex empty = new HeaderTableIndex(Arrays.<Header>asList());
        assertEquals(-1, empty.indexOf(new Header("x", "y")));
        assertEquals(-1, empty.indexOfName("x"));
    }
}
