/*
 * ContextWarResourceTest.java
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

package org.bluezoo.gumdrop.servlet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.Assert.*;

/**
 * Regression tests for the WAR-packaged-context resource lookup rewrite
 * (issue #137): {@link Context#getResource}, {@link
 * Context#getResourcePaths}, and {@link Context#getResourceAsStream} used
 * to reopen and fully re-enumerate the WAR file on every call; they now
 * build and reuse a cached index/open handles instead. No prior test in
 * this suite exercised the WAR-packaged branch of these methods at all
 * (only exploded-directory contexts) — this file builds a real WAR (zip)
 * file, including a WEB-INF/lib jar with a META-INF/resources entry, so
 * the rewritten caching logic is actually verified end to end.
 */
public class ContextWarResourceTest {

    private File warFile;
    private Context context;

    @Before
    public void setUp() throws Exception {
        warFile = File.createTempFile("gumdrop-test", ".war");
        warFile.deleteOnExit();

        // A jar to place at WEB-INF/lib/mylib.jar, containing a
        // META-INF/resources entry (Servlet 3.0 section 4.6).
        byte[] libJarBytes = buildJar(new String[][] {
            {"META-INF/resources/from-lib.html", "<html>from lib</html>"},
        });

        try (JarOutputStream out = new JarOutputStream(
                Files.newOutputStream(warFile.toPath()))) {
            putEntry(out, "index.html", "<html>index</html>");
            putEntry(out, "sub/nested.html", "<html>nested</html>");
            putEntry(out, "WEB-INF/lib/mylib.jar", libJarBytes);
        }

        Container container = new Container();
        // Registers the "resource:" URL protocol handler that
        // Context.getResource()'s returned URLs use; safe to call
        // repeatedly across the suite (setURLStreamHandlerFactory can only
        // succeed once per JVM, and init() swallows the resulting Error).
        container.init();
        context = new Context(container, "/app", warFile);
    }

    @After
    public void tearDown() {
        if (warFile != null) {
            warFile.delete();
        }
    }

    @Test
    public void testGetResourceFindsRootEntry() throws Exception {
        URL resource = context.getResource("/index.html");
        assertNotNull(resource);
    }

    @Test
    public void testGetResourceReturnsNullForMissingEntry() throws Exception {
        assertNull(context.getResource("/does-not-exist.html"));
    }

    @Test
    public void testGetResourceFindsEntryInLibJarMetaInfResources()
            throws Exception {
        URL resource = context.getResource("/from-lib.html");
        assertNotNull("resource should be found via WEB-INF/lib/mylib.jar's "
                + "META-INF/resources", resource);
    }

    @Test
    public void testGetResourceAsStreamReadsRootEntryContent()
            throws Exception {
        try (InputStream in = context.getResourceAsStream("/index.html")) {
            assertNotNull(in);
            assertEquals("<html>index</html>", readAll(in));
        }
    }

    @Test
    public void testGetResourceAsStreamReadsLibJarEntryContent()
            throws Exception {
        try (InputStream in = context.getResourceAsStream("/from-lib.html")) {
            assertNotNull(in);
            assertEquals("<html>from lib</html>", readAll(in));
        }
    }

    @Test
    public void testGetResourcePathsListsRootEntryButNotNestedEntry() {
        Set<String> paths = context.getResourcePaths("/");
        assertNotNull(paths);
        assertTrue("root listing must include the direct child",
                paths.contains("/index.html"));
        assertFalse("root listing must not include an entry nested under "
                + "a subdirectory", paths.contains("/sub/nested.html"));
    }

    @Test
    public void testGetResourcePathsListsNestedDirectoryContents() {
        Set<String> paths = context.getResourcePaths("/sub/");
        assertNotNull(paths);
        assertTrue(paths.contains("/sub/nested.html"));
    }

    /**
     * Regression test for a CodeQL Zip Slip / CWE-22 finding on the WAR
     * index build (getWarIndex()): a crafted archive entry name containing
     * ".." must never be indexed or otherwise flow toward a filesystem
     * path, since the index is later consulted by contextClassLoader
     * .getFile(...) to extract lib jar entries to disk.
     */
    @Test
    public void testMaliciousEntryNameWithParentTraversalIsIgnored()
            throws Exception {
        File maliciousWar = File.createTempFile("gumdrop-test-evil", ".war");
        maliciousWar.deleteOnExit();
        try {
            try (JarOutputStream out = new JarOutputStream(
                    Files.newOutputStream(maliciousWar.toPath()))) {
                putEntry(out, "index.html", "<html>index</html>");
                // A path-traversal entry name, as a crafted/malicious WAR
                // might contain, and an absolute-path entry - both must be
                // rejected before ever reaching a filesystem operation.
                putEntry(out, "../../../tmp/evil.txt", "evil");
                putEntry(out, "WEB-INF/lib/../../../../tmp/evil.jar",
                        buildJar(new String[][] {
                            {"META-INF/resources/evil.html", "evil"},
                        }));
            }

            Container container = new Container();
            container.init();
            Context evilContext = new Context(container, "/evil", maliciousWar);

            // Must not throw, and must not index the malicious entries.
            Set<String> rootPaths = evilContext.getResourcePaths("/");
            assertNotNull(rootPaths);
            assertTrue(rootPaths.contains("/index.html"));
            for (String p : rootPaths) {
                assertFalse("indexed path must not contain a parent-directory "
                        + "traversal segment: " + p, p.contains(".."));
            }

            Set<String> libPaths = evilContext.getResourcePaths("/WEB-INF/lib/");
            if (libPaths != null) {
                for (String p : libPaths) {
                    assertFalse("indexed lib jar path must not contain a "
                            + "parent-directory traversal segment: " + p,
                            p.contains(".."));
                }
            }

            // The resource "smuggled" via the traversal entry must not be
            // reachable through the normal lookup API either.
            assertNull(evilContext.getResource("/evil.html"));

            evilContext.destroy();
        } finally {
            maliciousWar.delete();
        }
    }

    /**
     * Regression test for issue #173: a prior Zip Slip fix sanitized the
     * WAR-level entry scan (getWarIndex()) but missed this second, separate
     * archive-entry enumeration - getResourcePaths()'s searchJars block,
     * which reads entries *inside* a lib jar under WEB-INF/lib/ (not the
     * WAR itself). A well-formed lib jar filename containing a malicious
     * entry (e.g. from a crafted/compromised dependency) must not have that
     * entry's name flow into the returned resource path set.
     */
    @Test
    public void testMaliciousLibJarInternalEntryIsIgnored() throws Exception {
        File maliciousWar = File.createTempFile("gumdrop-test-evil-lib", ".war");
        maliciousWar.deleteOnExit();
        try {
            byte[] maliciousLibJarBytes = buildJar(new String[][] {
                {"META-INF/resources/good.html", "<html>good</html>"},
                {"META-INF/resources/../../../../tmp/evil.html", "evil"},
            });

            try (JarOutputStream out = new JarOutputStream(
                    Files.newOutputStream(maliciousWar.toPath()))) {
                putEntry(out, "index.html", "<html>index</html>");
                // The lib jar's own filename is well-formed; the traversal
                // is in an entry *inside* it.
                putEntry(out, "WEB-INF/lib/evil-lib.jar", maliciousLibJarBytes);
            }

            Container container = new Container();
            container.init();
            Context evilContext = new Context(container, "/evil-lib", maliciousWar);

            Set<String> rootPaths = evilContext.getResourcePaths("/");
            assertNotNull(rootPaths);
            assertTrue("the legitimate lib-jar resource must still be found",
                    rootPaths.contains("/good.html"));
            // Without the fix, this entry produces a literal "/.." result
            // (prefix-stripping the traversal entry's name yields ".."):
            // the unguarded loop must not surface it.
            for (String p : rootPaths) {
                assertFalse("indexed path must not contain a parent-directory "
                        + "traversal segment: " + p, p.contains(".."));
            }

            evilContext.destroy();
        } finally {
            maliciousWar.delete();
        }
    }

    @Test
    public void testRepeatedCallsAreConsistent() throws Exception {
        // Exercises the cached WarIndex / kept-open JarFile handles across
        // multiple calls rather than just a single cold lookup.
        for (int i = 0; i < 5; i++) {
            assertNotNull(context.getResource("/index.html"));
            assertNull(context.getResource("/does-not-exist.html"));
            Set<String> paths = context.getResourcePaths("/");
            assertTrue(paths.contains("/index.html"));
        }
    }

    @Test
    public void testDestroyClosesResourceCachesWithoutError() throws Exception {
        assertNotNull(context.getResource("/index.html"));
        context.destroy();
        // No assertion beyond "does not throw" - destroy() must be able to
        // close the cached WAR/lib-jar handles cleanly.
    }

    // ── helpers ──

    private static void putEntry(JarOutputStream out, String name, String content)
            throws Exception {
        out.putNextEntry(new JarEntry(name));
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private static void putEntry(JarOutputStream out, String name, byte[] content)
            throws Exception {
        out.putNextEntry(new JarEntry(name));
        out.write(content);
        out.closeEntry();
    }

    private static byte[] buildJar(String[][] nameContentPairs) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes)) {
            for (String[] pair : nameContentPairs) {
                putEntry(out, pair[0], pair[1]);
            }
        }
        return bytes.toByteArray();
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
