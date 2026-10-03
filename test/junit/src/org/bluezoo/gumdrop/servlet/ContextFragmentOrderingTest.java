/*
 * ContextFragmentOrderingTest.java
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


package org.bluezoo.gumdrop.servlet;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests the web fragment ordering comparator implemented by {@link Context}
 * (Servlet specification section 8.2.2): absolute ordering by name and
 * {@code others}, and relative ordering by {@code before} and {@code after}
 * declarations, which must be symmetric whichever fragment declares them.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContextFragmentOrderingTest {

    public MemoryFolder tmp = new MemoryFolder();

    private Context context;

    @Before
    public void setUp() throws Exception {
        context = new Context(new Container(), "/order", tmp.newFolder("order"));
    }

    private static WebFragment fragment(String name) {
        WebFragment f = new WebFragment();
        f.name = name;
        return f;
    }

    private static WebFragment fragment(String name, List<String> before, List<String> after) {
        WebFragment f = fragment(name);
        f.before = before;
        f.after = after;
        return f;
    }

    private static List<String> names(String... names) {
        return new ArrayList<String>(Arrays.asList(names));
    }

    private static void assertOrdered(Context c, WebFragment first, WebFragment second) {
        int forward = c.compare(first, second);
        int backward = c.compare(second, first);
        assertTrue(first.name + " before " + second.name + ": " + forward, forward < 0);
        assertTrue(second.name + " after " + first.name + ": " + backward, backward > 0);
    }

    @Test
    public void testUnorderedFragmentsAreEqual() {
        assertEquals(0, context.compare(fragment("a"), fragment("b")));
    }

    @Test
    public void testAbsoluteOrderingWithOthers() {
        context.absoluteOrdering.add("a");
        context.absoluteOrdering.add(WebFragment.OTHERS);
        context.absoluteOrdering.add("c");
        WebFragment a = fragment("a");
        WebFragment b = fragment("b");
        WebFragment c = fragment("c");
        WebFragment d = fragment("d");
        assertOrdered(context, a, b);
        assertOrdered(context, b, c);
        assertOrdered(context, a, c);
        assertEquals(0, context.compare(b, d));
    }

    @Test
    public void testAbsoluteOrderingWithoutOthersPutsUnlistedLast() {
        context.absoluteOrdering.add("a");
        WebFragment a = fragment("a");
        WebFragment b = fragment("b");
        WebFragment c = fragment("c");
        assertOrdered(context, a, b);
        assertEquals(0, context.compare(b, c));
    }

    @Test
    public void testBeforeDeclarationIsSymmetric() {
        WebFragment a = fragment("a");
        WebFragment b = fragment("b", names("a"), null);
        assertOrdered(context, b, a);
    }

    @Test
    public void testAfterDeclarationIsSymmetric() {
        WebFragment a = fragment("a");
        WebFragment b = fragment("b", null, names("a"));
        assertOrdered(context, a, b);
    }

    @Test
    public void testMutualDeclarationsCancelOut() {
        WebFragment a = fragment("a", names("b"), null);
        WebFragment b = fragment("b", names("a"), null);
        assertEquals(0, context.compare(a, b));
        WebFragment c = fragment("c", null, names("d"));
        WebFragment d = fragment("d", null, names("c"));
        assertEquals(0, context.compare(c, d));
    }

    @Test
    public void testBeforeAndAfterOthers() {
        WebFragment plain = fragment("plain");
        WebFragment early = fragment("early", names(WebFragment.OTHERS), null);
        WebFragment late = fragment("late", null, names(WebFragment.OTHERS));
        assertOrdered(context, early, plain);
        assertOrdered(context, plain, late);
        assertOrdered(context, early, late);
    }
}
