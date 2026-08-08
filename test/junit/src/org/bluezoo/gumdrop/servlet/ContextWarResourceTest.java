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
