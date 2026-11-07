/*
 * WildmatTest.java
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

package org.bluezoo.gumdrop.nntp;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link Wildmat}, including the examples of RFC 3977 section 4.2.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WildmatTest {

    @Test
    public void testNullMatchesEverything() {
        assertTrue(Wildmat.matches(null, "comp.lang.java"));
    }

    @Test
    public void testLiteral() {
        assertTrue(Wildmat.matches("comp.lang.java", "comp.lang.java"));
        assertFalse(Wildmat.matches("comp.lang.java", "comp.lang.javascript"));
    }

    @Test
    public void testStarCrossesDots() {
        assertTrue(Wildmat.matches("comp.*", "comp.lang.java"));
        assertTrue(Wildmat.matches("*", ""));
        assertTrue(Wildmat.matches("*.java", "comp.lang.java"));
        assertTrue(Wildmat.matches("c*g*a", "comp.lang.java"));
        assertFalse(Wildmat.matches("comp.*", "alt.comp.lang"));
    }

    @Test
    public void testQuestionMark() {
        assertTrue(Wildmat.matches("a?c", "abc"));
        assertFalse(Wildmat.matches("a?c", "ac"));
    }

    @Test
    public void testSets() {
        assertTrue(Wildmat.matches("a[bc]d", "abd"));
        assertTrue(Wildmat.matches("a[a-c]d", "acd"));
        assertFalse(Wildmat.matches("a[a-c]d", "aed"));
        assertTrue(Wildmat.matches("a[^a-c]d", "aed"));
    }

    @Test
    public void testEscape() {
        assertTrue(Wildmat.matches("a\\*b", "a*b"));
        assertFalse(Wildmat.matches("a\\*b", "axb"));
    }

    @Test
    public void testRfcExamples() {
        // RFC 3977 section 4.2: "a.*,!a.b.*" matches a.x but not a.b.c
        assertTrue(Wildmat.matches("a.*,!a.b.*", "a.x"));
        assertFalse(Wildmat.matches("a.*,!a.b.*", "a.b.c"));
        // the last pattern that matches decides
        assertTrue(Wildmat.matches("a.*,!a.b.*,a.b.c", "a.b.c"));
        assertFalse(Wildmat.matches("*,!*.test", "x.test"));
        assertTrue(Wildmat.matches("*,!*.test", "x.prod"));
    }

    @Test
    public void testNoMatchWithoutPositivePattern() {
        assertFalse(Wildmat.matches("!a.*", "b.c"));
    }


    @Test
    public void testUnclosedBracketIsLiteral() {
        assertTrue(Wildmat.matches("[abc", "[abc"));
        assertFalse(Wildmat.matches("[abc", "a"));
    }
}
