/*
 * IfHeaderParserEdgeTest.java
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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Malformed-input and evaluation edge cases of {@link IfHeaderParser}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IfHeaderParserEdgeTest {

    /** Hang guard: the parser once looped forever on malformed input. */
    @Rule
    public Timeout hangGuard = Timeout.seconds(10);

    private static List<IfHeaderParser.IfGroup> parse(String header) {
        return new IfHeaderParser(header).parse();
    }

    @Test
    public void emptyAndWhitespaceHeadersParseToNothing() {
        assertTrue(parse("").isEmpty());
        assertTrue(parse("   \t ").isEmpty());
    }

    @Test
    public void unterminatedResourceTagStopsParsing() {
        assertTrue(parse("</unterminated").isEmpty());
    }

    /**
     * A bare "&lt;uri&gt;" that is not followed by a list is malformed. It
     * used to rewind the cursor to the "&lt;" and re-read it forever, so a
     * single hostile If header pinned a worker thread. The hang guard
     * timeout makes a regression fail instead of hanging the run.
     */
    @Test
    public void bareUriWithoutListIsSkippedNotLoopedOn() {
        assertTrue(parse("<only-a-uri>").isEmpty());
    }

    @Test
    public void bareUriBeforeAListLeavesTheListUntagged() {
        List<IfHeaderParser.IfGroup> groups = parse("<x> y (<t>)");
        assertEquals(1, groups.size());
        assertNull(groups.get(0).resourceTag);
    }

    @Test
    public void unexpectedCharactersAreSkipped() {
        List<IfHeaderParser.IfGroup> groups = parse("garbage (<t>)");
        assertEquals(1, groups.size());
        assertEquals(1, groups.get(0).lists.size());
    }

    @Test
    public void unterminatedListIsStillRead() {
        List<IfHeaderParser.IfGroup> groups = parse("(<t>");
        assertEquals(1, groups.size());
        assertEquals("t", groups.get(0).lists.get(0).conditions.get(0).stateToken);
    }

    @Test
    public void emptyListContributesNothing() {
        assertTrue(parse("()").isEmpty());
        List<IfHeaderParser.IfGroup> tagged = parse("</r> ()");
        assertEquals(1, tagged.size());
        assertTrue(tagged.get(0).lists.isEmpty());
    }

    /** An unterminated token inside a list used to spin without advancing. */
    @Test
    public void unterminatedTokensInsideListAreDropped() {
        assertTrue(parse("(<broken)").isEmpty());
        assertTrue(parse("([broken)").isEmpty());
    }

    @Test
    public void notWithoutSeparatorIsNotANegation() {
        // "Notx" has no delimiter after Not, so it is skipped character by character
        List<IfHeaderParser.IfGroup> groups = parse("(Notx<t>)");
        assertEquals(1, groups.size());
        assertFalse(groups.get(0).lists.get(0).conditions.get(0).negated);
    }

    @Test
    public void notAtEndOfHeaderTerminates() {
        assertTrue(parse("(Not").isEmpty());
        assertTrue(parse("(Not ").isEmpty());
    }

    @Test
    public void notDirectlyBeforeBracketsIsANegation() {
        List<IfHeaderParser.IfGroup> groups = parse("(Not<t> Not[\"e\"])");
        List<IfHeaderParser.Condition> conds = groups.get(0).lists.get(0).conditions;
        assertEquals(2, conds.size());
        assertTrue(conds.get(0).negated);
        assertTrue(conds.get(1).negated);
    }

    @Test
    public void entityTagIsTrimmed() {
        List<IfHeaderParser.IfGroup> groups = parse("([  \"e\"  ])");
        assertEquals("\"e\"", groups.get(0).lists.get(0).conditions.get(0).entityTag);
    }

    // ---- evaluation ----

    private static final Path RES = Paths.get("/data/res");

    @Test
    public void emptyGroupsAlwaysSatisfied() {
        assertTrue(IfHeaderParser.evaluate(parse(""), RES, "/res", null, null));
    }

    @Test
    public void stateTokenWithoutLockManagerNeverMatches() {
        List<IfHeaderParser.IfGroup> groups = parse("(<t>)");
        assertFalse(IfHeaderParser.evaluate(groups, RES, "/res", null, null));
    }

    @Test
    public void negatedStateTokenWithoutLockManagerMatches() {
        List<IfHeaderParser.IfGroup> groups = parse("(Not <t>)");
        assertTrue(IfHeaderParser.evaluate(groups, RES, "/res", null, null));
    }

    @Test
    public void stateTokenMatchesHeldLock() {
        WebDAVLockManager manager = new WebDAVLockManager();
        WebDAVLock lock = manager.lock(RES, WebDAVLock.Scope.EXCLUSIVE,
                WebDAVLock.Type.WRITE, 0, "me", 3600);
        assertNotNull(lock);
        List<IfHeaderParser.IfGroup> good = parse("(<" + lock.getToken() + ">)");
        assertTrue(IfHeaderParser.evaluate(good, RES, "/res", manager, null));
        List<IfHeaderParser.IfGroup> bad = parse("(<opaquelocktoken:other>)");
        assertFalse(IfHeaderParser.evaluate(bad, RES, "/res", manager, null));
    }

    @Test
    public void etagComparisonIsWeak() {
        List<IfHeaderParser.IfGroup> groups = parse("([W/\"v1\"])");
        assertTrue(IfHeaderParser.evaluate(groups, RES, "/res", null, "\"v1\""));
        assertTrue(IfHeaderParser.evaluate(parse("([\"v1\"])"), RES, "/res", null, "W/\"v1\""));
        assertFalse(IfHeaderParser.evaluate(groups, RES, "/res", null, "\"v2\""));
        assertFalse(IfHeaderParser.evaluate(groups, RES, "/res", null, null));
    }

    @Test
    public void listsAreOrdAndConditionsAreAnded() {
        List<IfHeaderParser.IfGroup> groups =
                parse("([\"a\"] [\"b\"]) ([\"a\"])");
        assertTrue(IfHeaderParser.evaluate(groups, RES, "/res", null, "\"a\""));
        List<IfHeaderParser.IfGroup> onlyAnd = parse("([\"a\"] [\"b\"])");
        assertFalse(IfHeaderParser.evaluate(onlyAnd, RES, "/res", null, "\"a\""));
    }

    @Test
    public void taggedGroupForOtherResourceIsSatisfiedVacuously() {
        List<IfHeaderParser.IfGroup> groups = parse("</other> ([\"zzz\"])");
        assertTrue(IfHeaderParser.evaluate(groups, RES, "/res", null, "\"a\""));
    }

    @Test
    public void taggedGroupForThisResourceMustMatch() {
        List<IfHeaderParser.IfGroup> groups = parse("</res> ([\"zzz\"])");
        assertFalse(IfHeaderParser.evaluate(groups, RES, "/res", null, "\"a\""));
        assertTrue(IfHeaderParser.evaluate(groups, RES, "/res", null, "\"zzz\""));
    }

    @Test
    public void absoluteResourceTagComparesPathPortion() {
        List<IfHeaderParser.IfGroup> groups =
                parse("<http://example.com/res> ([\"zzz\"])");
        assertTrue(IfHeaderParser.evaluate(groups, RES, "/res", null, "\"zzz\""));
        assertFalse(IfHeaderParser.evaluate(groups, RES, "/res", null, "\"no\""));
        // authority only, no path: falls back to whole-tag comparison
        List<IfHeaderParser.IfGroup> bare = parse("<http://example.com> ([\"zzz\"])");
        assertTrue(IfHeaderParser.evaluate(bare, RES, "/res", null, "\"no\""));
    }

    @Test
    public void nullHrefNeverMatchesATag() {
        List<IfHeaderParser.IfGroup> groups = parse("</res> ([\"zzz\"])");
        assertTrue(IfHeaderParser.evaluate(groups, RES, null, null, "\"zzz\""));
    }
}
