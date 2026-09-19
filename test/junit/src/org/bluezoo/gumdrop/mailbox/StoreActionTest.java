/*
 * StoreActionTest.java
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

package org.bluezoo.gumdrop.mailbox;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link StoreAction}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class StoreActionTest {

    @Test
    public void testFromImapKeyword() {
        assertEquals(StoreAction.REPLACE, StoreAction.fromImapKeyword("FLAGS"));
        assertEquals(StoreAction.ADD, StoreAction.fromImapKeyword("+FLAGS"));
        assertEquals(StoreAction.REMOVE, StoreAction.fromImapKeyword("-FLAGS"));
    }

    @Test
    public void testFromImapKeywordCaseInsensitive() {
        assertEquals(StoreAction.REPLACE, StoreAction.fromImapKeyword("flags"));
        assertEquals(StoreAction.ADD, StoreAction.fromImapKeyword("+flags"));
        assertEquals(StoreAction.REMOVE, StoreAction.fromImapKeyword("-flags"));
    }

    @Test
    public void testFromImapKeywordSilent() {
        assertEquals(StoreAction.REPLACE, StoreAction.fromImapKeyword("FLAGS.SILENT"));
        assertEquals(StoreAction.ADD, StoreAction.fromImapKeyword("+FLAGS.SILENT"));
        assertEquals(StoreAction.REMOVE, StoreAction.fromImapKeyword("-FLAGS.SILENT"));
    }

    @Test
    public void testFromImapKeywordSilentCaseInsensitive() {
        assertEquals(StoreAction.REPLACE, StoreAction.fromImapKeyword("flags.silent"));
        assertEquals(StoreAction.ADD, StoreAction.fromImapKeyword("+Flags.Silent"));
    }

    @Test
    public void testFromImapKeywordNull() {
        assertNull(StoreAction.fromImapKeyword(null));
    }

    @Test
    public void testFromImapKeywordEmpty() {
        assertNull(StoreAction.fromImapKeyword(""));
    }

    @Test
    public void testFromImapKeywordUnknown() {
        assertNull(StoreAction.fromImapKeyword("INVALID"));
        assertNull(StoreAction.fromImapKeyword("LABELS"));
    }

    @Test
    public void testGetImapKeyword() {
        assertEquals("FLAGS", StoreAction.REPLACE.getImapKeyword());
        assertEquals("+FLAGS", StoreAction.ADD.getImapKeyword());
        assertEquals("-FLAGS", StoreAction.REMOVE.getImapKeyword());
    }

    @Test
    public void testToString() {
        assertEquals("FLAGS", StoreAction.REPLACE.toString());
        assertEquals("+FLAGS", StoreAction.ADD.toString());
        assertEquals("-FLAGS", StoreAction.REMOVE.toString());
    }

    @Test
    public void testRoundTrip() {
        for (StoreAction action : StoreAction.values()) {
            assertEquals(action, StoreAction.fromImapKeyword(action.getImapKeyword()));
        }
    }
}
