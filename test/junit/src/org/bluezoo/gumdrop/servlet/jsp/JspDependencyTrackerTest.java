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

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashSet;
import java.util.Set;

import org.bluezoo.gumdrop.servlet.MemoryFolder;

/**
 * Unit tests for JspDependencyTracker.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JspDependencyTrackerTest {

    private static final long FAR_FUTURE = 4102444800000L;

    public MemoryFolder tmp = new MemoryFolder();

    private static void touch(Path file, long millis) throws IOException {
        Files.setLastModifiedTime(file, FileTime.fromMillis(millis));
    }

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
        Path jsp = tmp.newFile("a.jsp");
        Path inc = tmp.newFile("inc.jspf");
        touch(jsp, 1000L);
        touch(inc, 1000L);
        JspDependencyTracker t = new JspDependencyTracker(null, tmp.getRoot());
        Set<String> deps = new HashSet<String>();
        deps.add("/inc.jspf");
        t.recordCompilation("/a.jsp", deps);
        assertTrue(t.getLastCompilationTime("/a.jsp") > 0L);
        assertFalse(t.needsRecompilation("/a.jsp"));
        assertEquals(1, t.getDependencies("/a.jsp").size());
        assertTrue(t.getDependents("/inc.jspf").contains("/a.jsp"));

        touch(inc, FAR_FUTURE);
        assertTrue(t.needsRecompilation("/a.jsp"));
        touch(inc, 1000L);
        assertFalse(t.needsRecompilation("/a.jsp"));
        touch(jsp, FAR_FUTURE);
        assertTrue(t.needsRecompilation("/a.jsp"));
    }

    @Test
    public void testMissingDependencyAndRelativePath() throws IOException {
        Path jsp = tmp.newFile("b.jsp");
        touch(jsp, 1000L);
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
