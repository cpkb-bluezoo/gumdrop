/*
 * SecurityConstraintIndexTest.java
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

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.ServletException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Tests {@link SecurityConstraintIndex}: which constraints are offered as
 * candidates for a path (exact, prefix, extension and path-agnostic
 * patterns), in list order, and early termination of the visit.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SecurityConstraintIndexTest {

    private static SecurityConstraint constraint(String... patterns) {
        SecurityConstraint sc = new SecurityConstraint();
        ResourceCollection rc = new ResourceCollection();
        for (int i = 0; i < patterns.length; i++) {
            rc.urlPatterns.add(patterns[i]);
        }
        sc.addResourceCollection(rc);
        return sc;
    }

    private static SecurityConstraint pathAgnostic() {
        SecurityConstraint sc = new SecurityConstraint();
        ResourceCollection rc = new ResourceCollection();
        rc.urlPatterns = null;
        sc.addResourceCollection(rc);
        return sc;
    }

    private static List<Integer> candidates(SecurityConstraintIndex index, String path)
            throws ServletException, IOException {
        final List<Integer> seen = new ArrayList<Integer>();
        boolean completed = index.forEachPathCandidate(path, new SecurityConstraintIndex.PathCandidate() {
            @Override
            public boolean accept(int i) {
                seen.add(Integer.valueOf(i));
                return true;
            }
        });
        assertTrue(completed);
        return seen;
    }

    @Test
    public void testEmptyIndexOffersNothing() throws Exception {
        SecurityConstraintIndex index = SecurityConstraintIndex.build(new ArrayList<SecurityConstraint>());
        assertEquals(0, index.size());
        assertTrue(candidates(index, "/anything").isEmpty());
    }

    @Test
    public void testPatternKindsAreIndexedSeparately() throws Exception {
        List<SecurityConstraint> list = new ArrayList<SecurityConstraint>();
        list.add(constraint("/exact"));
        list.add(constraint("/admin/*"));
        list.add(constraint("*.jsp"));
        list.add(pathAgnostic());
        list.add(constraint("/exact", "/admin/*"));
        SecurityConstraintIndex index = SecurityConstraintIndex.build(list);
        assertEquals(5, index.size());
        assertSame(list.get(2), index.constraintAt(2));
        List<Integer> exact = candidates(index, "/exact");
        assertEquals("[0, 3, 4]", exact.toString());
        List<Integer> admin = candidates(index, "/admin/users/list");
        assertEquals("[1, 3, 4]", admin.toString());
        List<Integer> jsp = candidates(index, "/page.jsp");
        assertEquals("[2, 3]", jsp.toString());
        List<Integer> other = candidates(index, "/other");
        assertEquals("[3]", other.toString());
    }

    @Test
    public void testSharedPrefixesAreAllOffered() throws Exception {
        List<SecurityConstraint> list = new ArrayList<SecurityConstraint>();
        list.add(constraint("/a/*"));
        list.add(constraint("/a/b/*"));
        list.add(constraint("/a/b/*"));
        SecurityConstraintIndex index = SecurityConstraintIndex.build(list);
        assertEquals("[0, 1, 2]", candidates(index, "/a/b/c").toString());
        assertEquals("[0]", candidates(index, "/a/x").toString());
        assertEquals("[]", candidates(index, "/b/c").toString());
    }

    @Test
    public void testVisitStopsWhenTheConsumerDeclines() throws Exception {
        List<SecurityConstraint> list = new ArrayList<SecurityConstraint>();
        list.add(constraint("/x/*"));
        list.add(constraint("/x/*"));
        SecurityConstraintIndex index = SecurityConstraintIndex.build(list);
        final List<Integer> seen = new ArrayList<Integer>();
        boolean completed = index.forEachPathCandidate("/x/y", new SecurityConstraintIndex.PathCandidate() {
            @Override
            public boolean accept(int i) {
                seen.add(Integer.valueOf(i));
                return false;
            }
        });
        assertFalse(completed);
        assertEquals(1, seen.size());
    }

    @Test
    public void testRepeatedLookupsStartFresh() throws Exception {
        List<SecurityConstraint> list = new ArrayList<SecurityConstraint>();
        list.add(constraint("/r"));
        SecurityConstraintIndex index = SecurityConstraintIndex.build(list);
        assertEquals("[0]", candidates(index, "/r").toString());
        assertEquals("[0]", candidates(index, "/r").toString());
    }
}
