/*
 * JspDependencyTrackerTest.java
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

package org.bluezoo.gumdrop.servlet.jsp;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Unit tests for JspDependencyTracker.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JspDependencyTrackerTest {

    private static final long FAR_FUTURE = 4102444800000L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void testNeverCompiledNeedsRecompilation() throws IOException {
        JspDependencyTracker t = new JspDependencyTracker(null, tmp.getRoot());
        assertTrue(t.needsRecompilation("/a.jsp"));
        assertEquals(-1L, t.getLastCompilationTime("/a.jsp"));
        assertTrue(t.getDependencies("/a.jsp").isEmpty());
        assertTrue(t.getDependents("/inc.jspf").isEmpty());
    }

    @Test
    public void testUpToDateAndChanged() throws IOException {
        File jsp = tmp.newFile("a.jsp");
        File inc = tmp.newFile("inc.jspf");
        jsp.setLastModified(1000L);
        inc.setLastModified(1000L);
        JspDependencyTracker t = new JspDependencyTracker(null, tmp.getRoot());
        Set<String> deps = new HashSet<String>();
        deps.add("/inc.jspf");
        t.recordCompilation("/a.jsp", deps);
        assertTrue(t.getLastCompilationTime("/a.jsp") > 0L);
        assertFalse(t.needsRecompilation("/a.jsp"));
        assertEquals(1, t.getDependencies("/a.jsp").size());
        assertTrue(t.getDependents("/inc.jspf").contains("/a.jsp"));

        inc.setLastModified(FAR_FUTURE);
        assertTrue(t.needsRecompilation("/a.jsp"));
        inc.setLastModified(1000L);
        assertFalse(t.needsRecompilation("/a.jsp"));
        jsp.setLastModified(FAR_FUTURE);
        assertTrue(t.needsRecompilation("/a.jsp"));
    }

    @Test
    public void testMissingDependencyAndRelativePath() throws IOException {
        File jsp = tmp.newFile("b.jsp");
        jsp.setLastModified(1000L);
        JspDependencyTracker t = new JspDependencyTracker(null, tmp.getRoot());
        Set<String> deps = new HashSet<String>();
        deps.add("missing.jspf");
        t.recordCompilation("b.jsp", deps);
        assertTrue(t.needsRecompilation("b.jsp"));
        t.recordCompilation("b.jsp", new HashSet<String>());
        assertFalse(t.needsRecompilation("b.jsp"));
        assertTrue(t.getDependents("missing.jspf").isEmpty());
    }

    @Test
    public void testInvalidateAndClear() throws IOException {
        JspDependencyTracker t = new JspDependencyTracker(null, tmp.getRoot());
        Set<String> deps = new HashSet<String>();
        deps.add("/inc.jspf");
        t.recordCompilation("/a.jsp", deps);
        t.recordCompilation("/b.jsp", deps);
        Set<String> affected = t.invalidate("/inc.jspf");
        assertEquals(2, affected.size());
        assertEquals(-1L, t.getLastCompilationTime("/a.jsp"));
        t.recordCompilation("/a.jsp", deps);
        Set<String> self = t.invalidate("/a.jsp");
        assertTrue(self.contains("/a.jsp"));
        assertTrue(t.invalidate("/unknown").isEmpty());
        t.recordCompilation("/a.jsp", deps);
        t.clear();
        assertEquals(-1L, t.getLastCompilationTime("/a.jsp"));
        assertTrue(t.getDependencies("/a.jsp").isEmpty());
    }
}
