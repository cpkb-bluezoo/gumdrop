/*
 * IfHeaderParserTest.java
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

package org.bluezoo.gumdrop.webdav;

import org.junit.Test;
import static org.junit.Assert.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Unit tests for {@link IfHeaderParser} — RFC 4918 §10.4 If header parsing
 * and evaluation.
 */
public class IfHeaderParserTest {

    // -- Parsing tests --

    @Test
    public void testParseSimpleNoTagList() {
        IfHeaderParser parser = new IfHeaderParser(
                "(<opaquelocktoken:a1b2c3d4>)");
        List<IfHeaderParser.IfGroup> groups = parser.parse();

        assertEquals(1, groups.size());
        assertNull(groups.get(0).resourceTag);
        assertEquals(1, groups.get(0).lists.size());
        assertEquals(1, groups.get(0).lists.get(0).conditions.size());

        IfHeaderParser.Condition cond = groups.get(0).lists.get(0).conditions.get(0);
        assertFalse(cond.negated);
        assertEquals("opaquelocktoken:a1b2c3d4", cond.stateToken);
        assertNull(cond.entityTag);
    }

    @Test
    public void testParseMultipleNoTagLists() {
        IfHeaderParser parser = new IfHeaderParser(
                "(<token-a>) (<token-b>)");
        List<IfHeaderParser.IfGroup> groups = parser.parse();

        assertEquals(1, groups.size());
        assertEquals(2, groups.get(0).lists.size());
    }

    @Test
    public void testParseTaggedList() {
        IfHeaderParser parser = new IfHeaderParser(
                "</resource> (<opaquelocktoken:xyz>)");
        List<IfHeaderParser.IfGroup> groups = parser.parse();

        assertEquals(1, groups.size());
        assertEquals("/resource", groups.get(0).resourceTag);
        assertEquals(1, groups.get(0).lists.size());
    }

    @Test
    public void testParseMultipleTaggedLists() {
        IfHeaderParser parser = new IfHeaderParser(
                "</a> (<token-a>) </b> (<token-b>)");
        List<IfHeaderParser.IfGroup> groups = parser.parse();

        assertEquals(2, groups.size());
        assertEquals("/a", groups.get(0).resourceTag);
        assertEquals("/b", groups.get(1).resourceTag);
    }

    @Test
    public void testParseEntityTag() {
        IfHeaderParser parser = new IfHeaderParser(
                "([\"etag-value\"])");
        List<IfHeaderParser.IfGroup> groups = parser.parse();

        assertEquals(1, groups.size());
        IfHeaderParser.Condition cond = groups.get(0).lists.get(0).conditions.get(0);
        assertNull(cond.stateToken);
        assertEquals("\"etag-value\"", cond.entityTag);
    }

    @Test
    public void testParseNotCondition() {
        IfHeaderParser parser = new IfHeaderParser(
                "(Not <opaquelocktoken:old-token>)");
        List<IfHeaderParser.IfGroup> groups = parser.parse();

        assertEquals(1, groups.size());
        IfHeaderParser.Condition cond = groups.get(0).lists.get(0).conditions.get(0);
        assertTrue(cond.negated);
        assertEquals("opaquelocktoken:old-token", cond.stateToken);
    }

    @Test
    public void testParseNotEntityTag() {
        IfHeaderParser parser = new IfHeaderParser(
                "(Not [\"old-etag\"])");
        List<IfHeaderParser.IfGroup> groups = parser.parse();

        assertEquals(1, groups.size());
        IfHeaderParser.Condition cond = groups.get(0).lists.get(0).conditions.get(0);
        assertTrue(cond.negated);
        assertEquals("\"old-etag\"", cond.entityTag);
    }

    @Test
    public void testParseMixedConditions() {
        IfHeaderParser parser = new IfHeaderParser(
                "(<opaquelocktoken:abc> [\"etag123\"])");
        List<IfHeaderParser.IfGroup> groups = parser.parse();

        assertEquals(1, groups.size());
        IfHeaderParser.ConditionList list = groups.get(0).lists.get(0);
        assertEquals(2, list.conditions.size());
        assertEquals("opaquelocktoken:abc", list.conditions.get(0).stateToken);
        assertEquals("\"etag123\"", list.conditions.get(1).entityTag);
    }

    @Test
    public void testParseTaggedListWithMultipleLists() {
        IfHeaderParser parser = new IfHeaderParser(
                "</file> (<token1>) (<token2>)");
        List<IfHeaderParser.IfGroup> groups = parser.parse();

        assertEquals(1, groups.size());
        assertEquals("/file", groups.get(0).resourceTag);
        assertEquals(2, groups.get(0).lists.size());
    }

    @Test
    public void testParseEmptyHeader() {
        IfHeaderParser parser = new IfHeaderParser("");
        List<IfHeaderParser.IfGroup> groups = parser.parse();
        assertTrue(groups.isEmpty());
    }

    @Test
    public void testParseWhitespaceOnly() {
        IfHeaderParser parser = new IfHeaderParser("   ");
        List<IfHeaderParser.IfGroup> groups = parser.parse();
        assertTrue(groups.isEmpty());
    }

    // -- Evaluation tests --

    @Test
    public void testEvaluateMatchingToken() {
        String header = "(<opaquelocktoken:valid-token>)";
        IfHeaderParser parser = new IfHeaderParser(header);
        List<IfHeaderParser.IfGroup> groups = parser.parse();

        Path resourcePath = Paths.get("/tmp/test/file.txt");
        StubLockManager lm = new StubLockManager();
        lm.addValidToken(resourcePath, "opaquelocktoken:valid-token");

        assertTrue(IfHeaderParser.evaluate(groups, resourcePath, "/file.txt", lm, null));
    }

    @Test
    public void testEvaluateNonMatchingToken() {
        String header = "(<opaquelocktoken:wrong-token>)";
        IfHeaderParser parser = new IfHeaderParser(header);
        List<IfHeaderParser.IfGroup> groups = parser.parse();

        Path resourcePath = Paths.get("/tmp/test/file.txt");
        StubLockManager lm = new StubLockManager();
        lm.addValidToken(resourcePath, "opaquelocktoken:correct-token");

        assertFalse(IfHeaderParser.evaluate(groups, resourcePath, "/file.txt", lm, null));
    }

    @Test
    public void testEvaluateMatchingETag() {
        String header = "([\"abc123\"])";
        IfHeaderParser parser = new IfHeaderParser(header);
        List<IfHeaderParser.IfGroup> groups = parser.parse();

        Path resourcePath = Paths.get("/tmp/test/file.txt");
        assertTrue(IfHeaderParser.evaluate(groups, resourcePath, "/file.txt",
                null, "\"abc123\""));
    }

    @Test
    public void testEvaluateNonMatchingETag() {
        String header = "([\"abc123\"])";
        IfHeaderParser parser = new IfHeaderParser(header);
        List<IfHeaderParser.IfGroup> groups = parser.parse();

        Path resourcePath = Paths.get("/tmp/test/file.txt");
        assertFalse(IfHeaderParser.evaluate(groups, resourcePath, "/file.txt",
                null, "\"different\""));
    }

    @Test
    public void testEvaluateNotConditionNegatesToken() {
        String header = "(Not <opaquelocktoken:absent-token>)";
        IfHeaderParser parser = new IfHeaderParser(header);
        List<IfHeaderParser.IfGroup> groups = parser.parse();

        Path resourcePath = Paths.get("/tmp/test/file.txt");
        StubLockManager lm = new StubLockManager();
        // Token is NOT present for this path → negated → true
        assertTrue(IfHeaderParser.evaluate(groups, resourcePath, "/file.txt", lm, null));
    }

    @Test
    public void testEvaluateNotConditionNegatesPresent() {
        String header = "(Not <opaquelocktoken:present-token>)";
        IfHeaderParser parser = new IfHeaderParser(header);
        List<IfHeaderParser.IfGroup> groups = parser.parse();

        Path resourcePath = Paths.get("/tmp/test/file.txt");
        StubLockManager lm = new StubLockManager();
        lm.addValidToken(resourcePath, "opaquelocktoken:present-token");
        // Token IS present → negated → false
        assertFalse(IfHeaderParser.evaluate(groups, resourcePath, "/file.txt", lm, null));
    }

    @Test
    public void testEvaluateOrSemantics() {
        // Two lists: first fails, second succeeds → overall true
        String header = "(<opaquelocktoken:wrong>) (<opaquelocktoken:right>)";
        IfHeaderParser parser = new IfHeaderParser(header);
        List<IfHeaderParser.IfGroup> groups = parser.parse();

        Path resourcePath = Paths.get("/tmp/test/file.txt");
        StubLockManager lm = new StubLockManager();
        lm.addValidToken(resourcePath, "opaquelocktoken:right");

        assertTrue(IfHeaderParser.evaluate(groups, resourcePath, "/file.txt", lm, null));
    }

    @Test
    public void testEvaluateAndSemantics() {
        // Two conditions in same list: both must be true
        String header = "(<opaquelocktoken:tok> [\"etag1\"])";
        IfHeaderParser parser = new IfHeaderParser(header);
        List<IfHeaderParser.IfGroup> groups = parser.parse();

        Path resourcePath = Paths.get("/tmp/test/file.txt");
        StubLockManager lm = new StubLockManager();
        lm.addValidToken(resourcePath, "opaquelocktoken:tok");

        // Token matches but ETag doesn't → false
        assertFalse(IfHeaderParser.evaluate(groups, resourcePath, "/file.txt",
                lm, "\"different\""));

        // Both match → true
        assertTrue(IfHeaderParser.evaluate(groups, resourcePath, "/file.txt",
                lm, "\"etag1\""));
    }

    @Test
    public void testEvaluateTaggedListForDifferentResource() {
        // Tagged list targets /other, but we're evaluating /file.txt
        String header = "</other> (<opaquelocktoken:tok>)";
        IfHeaderParser parser = new IfHeaderParser(header);
        List<IfHeaderParser.IfGroup> groups = parser.parse();

        Path resourcePath = Paths.get("/tmp/test/file.txt");
        // No group targets this resource → conditions are satisfied
        assertTrue(IfHeaderParser.evaluate(groups, resourcePath, "/file.txt",
                null, null));
    }

    @Test
    public void testEvaluateTaggedListForThisResource() {
        String header = "</file.txt> (<opaquelocktoken:tok>)";
        IfHeaderParser parser = new IfHeaderParser(header);
        List<IfHeaderParser.IfGroup> groups = parser.parse();

        Path resourcePath = Paths.get("/tmp/test/file.txt");
        StubLockManager lm = new StubLockManager();
        lm.addValidToken(resourcePath, "opaquelocktoken:tok");

        assertTrue(IfHeaderParser.evaluate(groups, resourcePath, "/file.txt", lm, null));
    }

    @Test
    public void testEvaluateEmptyGroups() {
        assertTrue(IfHeaderParser.evaluate(
                new java.util.ArrayList<>(),
                Paths.get("/tmp/file"), "/file", null, null));
    }

    @Test
    public void testEvaluateWeakETagComparison() {
        String header = "([W/\"weak-etag\"])";
        IfHeaderParser parser = new IfHeaderParser(header);
        List<IfHeaderParser.IfGroup> groups = parser.parse();

        Path resourcePath = Paths.get("/tmp/test/file.txt");
        // Weak comparison: W/ prefix stripped for comparison
        assertTrue(IfHeaderParser.evaluate(groups, resourcePath, "/file.txt",
                null, "W/\"weak-etag\""));
        assertTrue(IfHeaderParser.evaluate(groups, resourcePath, "/file.txt",
                null, "\"weak-etag\""));
    }

    // -- Stub lock manager for testing --

    private static class StubLockManager extends WebDAVLockManager {
        private final java.util.Map<String, java.util.Set<Path>> validTokens = new java.util.HashMap<>();

        void addValidToken(Path path, String token) {
            validTokens.computeIfAbsent(token, k -> new java.util.HashSet<>()).add(path);
        }

        @Override
        boolean validateToken(Path path, String token) {
            java.util.Set<Path> paths = validTokens.get(token);
            return paths != null && paths.contains(path);
        }
    }

}
