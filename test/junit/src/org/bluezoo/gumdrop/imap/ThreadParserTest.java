/*
 * ThreadParserTest.java
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
