/*
 * WallClockAssertionGuardTest.java
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

package org.bluezoo.gumdrop.testsupport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Unit tests must be deterministic: no assertion may depend on how long
 * something took. Wall-clock thresholds belong in {@code *PerformanceTest}
 * classes under {@code test/integration/src}. The detector itself is
 * exercised on snippets so its behaviour does not depend on the tree.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see CONTRIBUTING.md
 */
public class WallClockAssertionGuardTest {

    @Test
    public void noUnitTestAssertsOnElapsedTime() throws Exception {
        List<String> violations = WallClockAssertionGuardSupport.findViolations();
        if (!violations.isEmpty()) {
            StringBuilder sb = new StringBuilder(
                    "Unit tests asserting on elapsed wall-clock time (move to a *PerformanceTest in"
                    + " test/integration/src):");
            for (String v : violations) {
                sb.append("\n  ").append(v);
            }
            fail(sb.toString());
        }
    }

    private static List<String> scan(String source) {
        return WallClockAssertionGuardSupport.findViolatingMethods(source);
    }

    @Test
    public void flagsAssertionOverNanoTimeDifference() {
        String src = "class T {\n@Test public void slow() {\n"
                + " long start = System.nanoTime();\n work();\n"
                + " long elapsedMs = (System.nanoTime() - start) / 1000000;\n"
                + " assertTrue(\"took \" + elapsedMs, elapsedMs < 500);\n}\n}";
        assertEquals(Arrays.asList("slow"), scan(src));
    }

    @Test
    public void flagsDurationDerivedFromAnotherDuration() {
        String src = "class T {\n@Test public void derived() {\n"
                + " long t0 = System.currentTimeMillis();\n"
                + " long ms = System.currentTimeMillis() - t0;\n"
                + " long perOp = ms / 100;\n assertTrue(perOp < 5);\n}\n}";
        assertEquals(Arrays.asList("derived"), scan(src));
    }

    @Test
    public void doesNotFlagTimestampUsedAsData() {
        String src = "class T {\n@Test public void stamp() {\n"
                + " long before = System.currentTimeMillis();\n"
                + " Span s = new Span();\n"
                + " assertTrue(s.getStartTime() >= before);\n}\n}";
        assertEquals(Arrays.asList(), scan(src));
    }

    @Test
    public void doesNotFlagDurationNeverAsserted() {
        String src = "class T {\n@Test public void logged() {\n"
                + " long start = System.nanoTime();\n"
                + " long elapsed = System.nanoTime() - start;\n"
                + " System.out.println(elapsed);\n assertEquals(1, 1);\n}\n}";
        assertEquals(Arrays.asList(), scan(src));
    }

    @Test
    public void ignoresClockTextInsideStringsAndComments() {
        String src = "class T {\n@Test public void text() {\n"
                + " // long e = System.nanoTime() - s;\n"
                + " String s = \"System.nanoTime() - x\";\n assertNotNull(s);\n}\n}";
        assertEquals(Arrays.asList(), scan(src));
    }

    @Test
    public void reportsOnlyTheOffendingMethod() {
        String src = "class T {\n@Test public void fine() { assertTrue(true); }\n"
                + "@Test public void slow() {\n long a = System.nanoTime();\n"
                + " long d = System.nanoTime() - a;\n assertTrue(d < 10);\n}\n}";
        assertEquals(Arrays.asList("slow"), scan(src));
    }
}
