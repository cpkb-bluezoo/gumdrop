/*
 * ContainerGetContextByPathTest.java
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

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Regression tests for issue #194: {@link Container#getContextByPath}
 * previously did an unindexed linear scan with {@code String.startsWith}
 * over every deployed context on every request. It's now backed by a
 * sorted index ({@code contextsByPath}), navigated via {@code
 * floorEntry}/{@code lowerEntry} rather than scanned -- these tests lock
 * in that the longest-matching-prefix semantics are unchanged.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContainerGetContextByPathTest {

    private Container container;
    private final List<File> webappRoots = new ArrayList<File>();

    @Before
    public void setUp() {
        container = new Container();
    }

    @After
    public void tearDown() {
        for (File root : webappRoots) {
            deleteRecursively(root);
        }
    }

    private Context addContext(String contextPath) throws Exception {
        File webappRoot = Files.createTempDirectory("gumdrop-container-test").toFile();
        webappRoots.add(webappRoot);
        Context context = new Context(container, contextPath, webappRoot);
        container.addContext(context);
        return context;
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    @Test
    public void testNoContextsReturnsNull() {
        assertNull(container.getContextByPath("/anything"));
    }

    @Test
    public void testExactMatch() throws Exception {
        Context app = addContext("/app");
        assertSame(app, container.getContextByPath("/app"));
    }

    @Test
    public void testLongestPrefixWins() throws Exception {
        Context app = addContext("/app");
        Context appApi = addContext("/app/api");

        assertSame("a request under /app/api must match the more specific context",
                appApi, container.getContextByPath("/app/api/users"));
        assertSame("a request under /app but not /app/api must match the less specific context",
                app, container.getContextByPath("/app/other"));
    }

    @Test
    public void testRootContextMatchesWhenNothingElseDoes() throws Exception {
        Context root = addContext("");
        Context app = addContext("/app");

        assertSame(app, container.getContextByPath("/app/page"));
        assertSame("an unrelated path must fall back to the root context",
                root, container.getContextByPath("/unrelated"));
    }

    @Test
    public void testNonMatchingPathReturnsNull() throws Exception {
        addContext("/app");
        assertNull("a path that isn't actually a prefix match must return null",
                container.getContextByPath("/other"));
    }

    @Test
    public void testManyContextsLongestPrefixStillCorrect() throws Exception {
        // Exercises the floorEntry/lowerEntry walk against a spread of
        // context paths, including ones that are lexicographically
        // adjacent to a real match but are not themselves prefixes of it
        // (e.g. "/ab" between "/a" and "/ac" below) -- the case a naive
        // single floorEntry() lookup (without walking to smaller keys)
        // would get wrong.
        Context a = addContext("/a");
        addContext("/ab");
        Context ac = addContext("/ac");
        addContext("/b");

        assertSame(a, container.getContextByPath("/a/nested"));
        assertSame(ac, container.getContextByPath("/ac/nested"));
        // "/ad" is not a match for "/ac" or "/ab" (they diverge at the
        // last character) but "/a" is still a valid, shorter prefix --
        // this is the case a naive single floorEntry() lookup (without
        // walking on to smaller keys after a non-match) would miss.
        assertSame(a, container.getContextByPath("/ad"));
        assertNull("a path sharing no registered prefix at all must return null",
                container.getContextByPath("/zzz"));
    }

    @Test
    public void testSetContextsReplacesIndexEntirely() throws Exception {
        addContext("/old");
        File replacementRoot = Files.createTempDirectory("gumdrop-container-test").toFile();
        webappRoots.add(replacementRoot);
        Context replacement = new Context(container, "/new", replacementRoot);

        List<Context> replacementList = new ArrayList<Context>();
        replacementList.add(replacement);
        container.setContexts(replacementList);

        assertNull("a context removed by setContexts must no longer be found",
                container.getContextByPath("/old"));
        assertSame(replacement, container.getContextByPath("/new"));
    }
}
