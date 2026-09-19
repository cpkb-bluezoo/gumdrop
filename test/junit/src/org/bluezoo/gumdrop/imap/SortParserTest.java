/*
 * SortParserTest.java
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

package org.bluezoo.gumdrop.imap;

import org.junit.Test;

import java.text.ParseException;

import static org.junit.Assert.*;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SortParserTest {

    @Test
    public void testParseSortWithSearch() throws ParseException {
        SortRequest req = new SortParser(
                "(SUBJECT) UTF-8 SINCE 1-Feb-1994").parse();
        assertEquals("UTF-8", req.getCharset());
        assertEquals(1, req.getSortProgram().size());
        assertEquals(SortKey.SUBJECT, req.getSortProgram().get(0).getKey());
        assertFalse(req.getSortProgram().get(0).isReverse());
        assertNotNull(req.getSearchCriteria());
    }

    @Test
    public void testParseReverseMultiKey() throws ParseException {
        SortRequest req = new SortParser(
                "(SUBJECT REVERSE DATE) US-ASCII ALL").parse();
        assertEquals(2, req.getSortProgram().size());
        assertEquals(SortKey.SUBJECT, req.getSortProgram().get(0).getKey());
        assertTrue(req.getSortProgram().get(1).isReverse());
        assertEquals(SortKey.DATE, req.getSortProgram().get(1).getKey());
    }

    @Test(expected = ParseException.class)
    public void testEmptySortProgramRejected() throws ParseException {
        new SortParser("() UTF-8 ALL").parse();
    }
}
