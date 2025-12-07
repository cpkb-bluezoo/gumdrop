/*
 * JSPDependencyTrackerTest.java
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

import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for JSPDependencyTracker.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JSPDependencyTrackerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private JSPDependencyTracker tracker;
    private File webappRoot;

    @Before
    public void setUp() throws IOException {
        webappRoot = tempFolder.newFolder("webapp");
        tracker = new JSPDependencyTracker(null, webappRoot);
    }

    @Test
    public void testNeverCompiledNeedsRecompilation() {
        assertTrue("Never compiled JSP should need recompilation",
            tracker.needsRecompilation("/index.jsp"));
    }

    @Test
    public void testCompiledDoesNotNeedRecompilation() throws IOException {
        // Create JSP file
        File jspFile = new File(webappRoot, "index.jsp");
        writeFile(jspFile, "<html><body>Test</body></html>");
        
        // Record compilation with no dependencies
        Set<String> noDeps = new HashSet<String>();
        tracker.recordCompilation("/index.jsp", noDeps);
        
        // Should not need recompilation immediately after
        assertFalse("Just compiled JSP should not need recompilation",
            tracker.needsRecompilation("/index.jsp"));
    }

    @Test
    public void testModifiedNeedsRecompilation() throws Exception {
        // Create JSP file
        File jspFile = new File(webappRoot, "index.jsp");
        writeFile(jspFile, "<html><body>Test</body></html>");
        
        // Record compilation
        Set<String> noDeps = new HashSet<String>();
        tracker.recordCompilation("/index.jsp", noDeps);
        
        // Wait a bit and modify the file
        Thread.sleep(100);
        writeFile(jspFile, "<html><body>Modified</body></html>");
        
        // Should now need recompilation
        assertTrue("Modified JSP should need recompilation",
            tracker.needsRecompilation("/index.jsp"));
    }

    @Test
    public void testDependencyModifiedNeedsRecompilation() throws Exception {
        // Create main JSP and included file
        File mainJsp = new File(webappRoot, "main.jsp");
        File header = new File(webappRoot, "header.jspf");
        
        writeFile(mainJsp, "<%@ include file=\"header.jspf\" %><body>Main</body>");
        writeFile(header, "<header>Header</header>");
        
        // Record compilation with dependency
        Set<String> deps = new HashSet<String>();
        deps.add("/header.jspf");
        tracker.recordCompilation("/main.jsp", deps);
        
        // Verify initially does not need recompilation
        assertFalse("Should not need recompilation initially",
            tracker.needsRecompilation("/main.jsp"));
        
        // Wait and modify the dependency
        Thread.sleep(100);
        writeFile(header, "<header>Modified Header</header>");
        
        // Should now need recompilation due to dependency change
        assertTrue("Should need recompilation after dependency modified",
            tracker.needsRecompilation("/main.jsp"));
    }

    @Test
    public void testInvalidateSingleFile() throws IOException {
        // Create JSP files
        File jspFile = new File(webappRoot, "page1.jsp");
        writeFile(jspFile, "<html>Page 1</html>");
        
        // Record compilation
        Set<String> noDeps = new HashSet<String>();
        tracker.recordCompilation("/page1.jsp", noDeps);
        
        // Invalidate the file
        Set<String> affected = tracker.invalidate("/page1.jsp");
        
        assertTrue("Affected set should contain the invalidated file",
            affected.contains("/page1.jsp"));
        assertTrue("Invalidated file should need recompilation",
            tracker.needsRecompilation("/page1.jsp"));
    }

    @Test
    public void testInvalidateCascade() throws IOException {
        // Create files: main.jsp depends on header.jspf, footer.jspf
        File mainJsp = new File(webappRoot, "main.jsp");
        File header = new File(webappRoot, "header.jspf");
        File footer = new File(webappRoot, "footer.jspf");
        
        writeFile(mainJsp, "<%@ include file=\"header.jspf\" %>Body<%@ include file=\"footer.jspf\" %>");
        writeFile(header, "<header/>");
        writeFile(footer, "<footer/>");
        
        // Record compilation with dependencies
        Set<String> deps = new HashSet<String>();
        deps.add("/header.jspf");
        deps.add("/footer.jspf");
        tracker.recordCompilation("/main.jsp", deps);
        
        // Invalidate header - should cascade to main
        Set<String> affected = tracker.invalidate("/header.jspf");
        
        assertTrue("Affected set should contain the dependent file",
            affected.contains("/main.jsp"));
        assertTrue("Dependent file should need recompilation",
            tracker.needsRecompilation("/main.jsp"));
    }

    @Test
    public void testMultipleDependents() throws IOException {
        // Create files: page1.jsp and page2.jsp both depend on common.jspf
        File page1 = new File(webappRoot, "page1.jsp");
        File page2 = new File(webappRoot, "page2.jsp");
        File common = new File(webappRoot, "common.jspf");
        
        writeFile(page1, "<%@ include file=\"common.jspf\" %>Page 1");
        writeFile(page2, "<%@ include file=\"common.jspf\" %>Page 2");
        writeFile(common, "<common/>");
        
        // Record compilations
        Set<String> deps = new HashSet<String>();
        deps.add("/common.jspf");
        tracker.recordCompilation("/page1.jsp", deps);
        tracker.recordCompilation("/page2.jsp", deps);
        
        // Invalidate common - should affect both pages
        Set<String> affected = tracker.invalidate("/common.jspf");
        
        assertTrue("Affected set should contain page1", affected.contains("/page1.jsp"));
        assertTrue("Affected set should contain page2", affected.contains("/page2.jsp"));
        assertEquals("Should have 2 affected files", 2, affected.size());
    }

    @Test
    public void testGetDependencies() throws IOException {
        File mainJsp = new File(webappRoot, "main.jsp");
        File header = new File(webappRoot, "header.jspf");
        File footer = new File(webappRoot, "footer.jspf");
        
        writeFile(mainJsp, "content");
        writeFile(header, "header");
        writeFile(footer, "footer");
        
        Set<String> deps = new HashSet<String>();
        deps.add("/header.jspf");
        deps.add("/footer.jspf");
        tracker.recordCompilation("/main.jsp", deps);
        
        Set<String> retrievedDeps = tracker.getDependencies("/main.jsp");
        
        assertEquals("Should have 2 dependencies", 2, retrievedDeps.size());
        assertTrue(retrievedDeps.contains("/header.jspf"));
        assertTrue(retrievedDeps.contains("/footer.jspf"));
    }

    @Test
    public void testGetDependents() throws IOException {
        File page1 = new File(webappRoot, "page1.jsp");
        File page2 = new File(webappRoot, "page2.jsp");
        File common = new File(webappRoot, "common.jspf");
        
        writeFile(page1, "page1");
        writeFile(page2, "page2");
        writeFile(common, "common");
        
        Set<String> deps = new HashSet<String>();
        deps.add("/common.jspf");
        tracker.recordCompilation("/page1.jsp", deps);
        tracker.recordCompilation("/page2.jsp", deps);
        
        Set<String> dependents = tracker.getDependents("/common.jspf");
        
        assertEquals("Should have 2 dependents", 2, dependents.size());
        assertTrue(dependents.contains("/page1.jsp"));
        assertTrue(dependents.contains("/page2.jsp"));
    }

    @Test
    public void testClear() throws IOException {
        File jspFile = new File(webappRoot, "test.jsp");
        File commonFile = new File(webappRoot, "common.jspf");
        writeFile(jspFile, "test");
        writeFile(commonFile, "common content");
        
        Set<String> deps = new HashSet<String>();
        deps.add("/common.jspf");
        tracker.recordCompilation("/test.jsp", deps);
        
        // Verify recorded
        assertFalse("Should not need recompilation after recording",
            tracker.needsRecompilation("/test.jsp"));
        
        // Clear all
        tracker.clear();
        
        // Should need recompilation again
        assertTrue("After clear, should need recompilation",
            tracker.needsRecompilation("/test.jsp"));
    }

    @Test
    public void testLastCompilationTime() throws IOException {
        assertEquals("Never compiled should return -1", 
            -1L, tracker.getLastCompilationTime("/test.jsp"));
        
        File jspFile = new File(webappRoot, "test.jsp");
        writeFile(jspFile, "test");
        
        long before = System.currentTimeMillis();
        tracker.recordCompilation("/test.jsp", new HashSet<String>());
        long after = System.currentTimeMillis();
        
        long compilationTime = tracker.getLastCompilationTime("/test.jsp");
        
        assertTrue("Compilation time should be >= before", compilationTime >= before);
        assertTrue("Compilation time should be <= after", compilationTime <= after);
    }

    @Test
    public void testDependencyUpdate() throws IOException {
        // Test that updating a compilation replaces old dependencies
        File mainJsp = new File(webappRoot, "main.jsp");
        File oldDep = new File(webappRoot, "old.jspf");
        File newDep = new File(webappRoot, "new.jspf");
        
        writeFile(mainJsp, "main");
        writeFile(oldDep, "old");
        writeFile(newDep, "new");
        
        // First compilation with old dependency
        Set<String> oldDeps = new HashSet<String>();
        oldDeps.add("/old.jspf");
        tracker.recordCompilation("/main.jsp", oldDeps);
        
        // Second compilation with new dependency
        Set<String> newDeps = new HashSet<String>();
        newDeps.add("/new.jspf");
        tracker.recordCompilation("/main.jsp", newDeps);
        
        // Verify old dependency is no longer tracked
        Set<String> currentDeps = tracker.getDependencies("/main.jsp");
        assertEquals("Should have 1 dependency", 1, currentDeps.size());
        assertTrue("Should have new dependency", currentDeps.contains("/new.jspf"));
        assertFalse("Should not have old dependency", currentDeps.contains("/old.jspf"));
        
        // Verify old.jspf no longer lists main.jsp as dependent
        Set<String> oldDependents = tracker.getDependents("/old.jspf");
        assertFalse("Old dep should not have main.jsp as dependent",
            oldDependents.contains("/main.jsp"));
    }

    @Test
    public void testMissingFileNeedsRecompilation() throws IOException {
        // Record compilation for a file that exists
        File jspFile = new File(webappRoot, "exists.jsp");
        writeFile(jspFile, "content");
        
        Set<String> deps = new HashSet<String>();
        deps.add("/missing.jspf"); // Dependency that doesn't exist
        tracker.recordCompilation("/exists.jsp", deps);
        
        // Should need recompilation because dependency is missing
        assertTrue("Missing dependency should trigger recompilation",
            tracker.needsRecompilation("/exists.jsp"));
    }

    private void writeFile(File file, String content) throws IOException {
        file.getParentFile().mkdirs();
        FileWriter writer = new FileWriter(file);
        try {
            writer.write(content);
        } finally {
            writer.close();
        }
    }
}

