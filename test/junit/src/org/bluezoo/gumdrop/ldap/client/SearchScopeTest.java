/*
 * SearchScopeTest.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.ldap.client;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for SearchScope and DerefAliases enums.
 */
public class SearchScopeTest {

    // SearchScope tests

    @Test
    public void testSearchScopeBase() {
        assertEquals(0, SearchScope.BASE.getValue());
    }

    @Test
    public void testSearchScopeOne() {
        assertEquals(1, SearchScope.ONE.getValue());
    }

    @Test
    public void testSearchScopeSubtree() {
        assertEquals(2, SearchScope.SUBTREE.getValue());
    }

    @Test
    public void testSearchScopeFromValue() {
        assertEquals(SearchScope.BASE, SearchScope.fromValue(0));
        assertEquals(SearchScope.ONE, SearchScope.fromValue(1));
        assertEquals(SearchScope.SUBTREE, SearchScope.fromValue(2));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSearchScopeFromValueInvalid() {
        SearchScope.fromValue(99);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSearchScopeFromValueNegative() {
        SearchScope.fromValue(-1);
    }

    @Test
    public void testSearchScopeValuesAreUnique() {
        SearchScope[] values = SearchScope.values();
        for (int i = 0; i < values.length; i++) {
            for (int j = i + 1; j < values.length; j++) {
                assertNotEquals(values[i].getValue(), values[j].getValue());
            }
        }
    }

    // DerefAliases tests

    @Test
    public void testDerefAliasesNever() {
        assertEquals(0, DerefAliases.NEVER.getValue());
    }

    @Test
    public void testDerefAliasesInSearching() {
        assertEquals(1, DerefAliases.IN_SEARCHING.getValue());
    }

    @Test
    public void testDerefAliasesFindingBaseObj() {
        assertEquals(2, DerefAliases.FINDING_BASE_OBJ.getValue());
    }

    @Test
    public void testDerefAliasesAlways() {
        assertEquals(3, DerefAliases.ALWAYS.getValue());
    }

    @Test
    public void testDerefAliasesFromValue() {
        assertEquals(DerefAliases.NEVER, DerefAliases.fromValue(0));
        assertEquals(DerefAliases.IN_SEARCHING, DerefAliases.fromValue(1));
        assertEquals(DerefAliases.FINDING_BASE_OBJ, DerefAliases.fromValue(2));
        assertEquals(DerefAliases.ALWAYS, DerefAliases.fromValue(3));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDerefAliasesFromValueInvalid() {
        DerefAliases.fromValue(99);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDerefAliasesFromValueNegative() {
        DerefAliases.fromValue(-1);
    }

    @Test
    public void testDerefAliasesValuesAreUnique() {
        DerefAliases[] values = DerefAliases.values();
        for (int i = 0; i < values.length; i++) {
            for (int j = i + 1; j < values.length; j++) {
                assertNotEquals(values[i].getValue(), values[j].getValue());
            }
        }
    }

    // Test enum count (to catch if new values are added without tests)

    @Test
    public void testSearchScopeCount() {
        assertEquals(3, SearchScope.values().length);
    }

    @Test
    public void testDerefAliasesCount() {
        assertEquals(4, DerefAliases.values().length);
    }
}

