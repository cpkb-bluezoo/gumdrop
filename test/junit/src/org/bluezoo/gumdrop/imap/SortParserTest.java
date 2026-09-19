/*
 * SortParserTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap;

import org.junit.Test;

import java.text.ParseException;

import static org.junit.Assert.*;

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
