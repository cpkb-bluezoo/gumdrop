/*
 * ThreadParserTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap;

import org.junit.Test;

import java.text.ParseException;

import static org.junit.Assert.*;

public class ThreadParserTest {

    @Test
    public void testParseOrderedSubject() throws ParseException {
        ThreadRequest req = new ThreadParser(
                "ORDEREDSUBJECT UTF-8 ALL").parse();
        assertEquals(ThreadAlgorithm.ORDEREDSUBJECT, req.getAlgorithm());
        assertEquals("UTF-8", req.getCharset());
        assertNotNull(req.getSearchCriteria());
    }

    @Test
    public void testParseReferences() throws ParseException {
        ThreadRequest req = new ThreadParser(
                "REFERENCES US-ASCII SINCE 1-Feb-2020").parse();
        assertEquals(ThreadAlgorithm.REFERENCES, req.getAlgorithm());
    }
}
