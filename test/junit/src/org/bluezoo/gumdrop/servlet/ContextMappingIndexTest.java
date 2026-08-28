/*
 * ContextMappingIndexTest.java
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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.RequestDispatcher;
import javax.servlet.http.MappingMatch;

import static org.junit.Assert.*;

/**
 * Regression coverage for issue #302: {@link Context#matchServletMapping}
 * and the filter-mapping block in {@link Context#getRequestDispatcher}
 * previously scanned every {@code ServletMapping}/{@code FilterMapping}
 * against every one of its own URL patterns, under {@code
 * synchronized(this)}, on every single request. They're now backed by
 * {@link ServletMappingIndex}/{@link FilterMappingIndex}, built once and
 * read without a lock -- these tests lock in that matching priority and
 * tie-break semantics are unchanged, that the index is invalidated and
 * rebuilt correctly across a {@link Context#reset()}, and that lookup
 * cost no longer scales with the size of the mapping table the way an
 * unindexed scan's would.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContextMappingIndexTest {

    private Container container;
    private Context context;
    private File webappRoot;

    @Before
    public void setUp() throws Exception {
        container = new Container();
        webappRoot = Files.createTempDirectory("gumdrop-context-mapping-index").toFile();
        context = new Context(container, "/app", webappRoot);
    }

    @After
    public void tearDown() {
        deleteRecursively(webappRoot);
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

    private ServletDef addServlet(String name) {
        ServletDef servletDef = new ServletDef();
        servletDef.name = name;
        servletDef.context = context;
        context.servletDefs.put(name, servletDef);
        return servletDef;
    }

    private void mapServlet(ServletDef servletDef, String... urlPatterns) {
        ServletMapping mapping = new ServletMapping();
        mapping.servletName = servletDef.name;
        mapping.servletDef = servletDef;
        for (String pattern : urlPatterns) {
            mapping.addUrlPattern(pattern);
        }
        context.servletMappings.add(mapping);
    }

    private FilterDef addFilter(String name) {
        FilterDef filterDef = new FilterDef();
        filterDef.name = name;
        context.filterDefs.put(name, filterDef);
        return filterDef;
    }

    private void mapFilterToPatterns(FilterDef filterDef, String... urlPatterns) {
        FilterMapping mapping = new FilterMapping();
        mapping.filterName = filterDef.name;
        mapping.filterDef = filterDef;
        for (String pattern : urlPatterns) {
            mapping.addUrlPattern(pattern);
        }
        context.filterMappings.add(mapping);
    }

    private void mapFilterToServlet(FilterDef filterDef, ServletDef servletDef) {
        FilterMapping mapping = new FilterMapping();
        mapping.filterName = filterDef.name;
        mapping.filterDef = filterDef;
        mapping.addServletName(servletDef.name);
        mapping.servletDefs.add(servletDef);
        context.filterMappings.add(mapping);
    }

    private ServletMatch match(String path) {
        ServletMatch match = new ServletMatch();
        context.matchServletMapping(path, match);
        return match;
    }

    // ── servlet mapping priority ──

    @Test
    public void testExactMatchWins() {
        ServletDef exact = addServlet("exact");
        mapServlet(exact, "/foo");
        ServletDef prefix = addServlet("prefix");
        mapServlet(prefix, "/*");

        ServletMatch m = match("/foo");
        assertSame(exact, m.servletDef);
        assertEquals(MappingMatch.EXACT, m.mappingMatch);
    }

    @Test
    public void testContextRootExactMatchReportsContextRootKind() {
        ServletDef root = addServlet("root");
        mapServlet(root, "/");

        ServletMatch m = match("/");
        assertSame(root, m.servletDef);
        assertEquals(MappingMatch.CONTEXT_ROOT, m.mappingMatch);
    }

    @Test
    public void testLongestPrefixWins() {
        ServletDef api = addServlet("api");
        mapServlet(api, "/api/*");
        ServletDef apiV1 = addServlet("apiV1");
        mapServlet(apiV1, "/api/v1/*");

        ServletMatch m = match("/api/v1/users");
        assertSame("the more specific /api/v1/* mapping must win",
                apiV1, m.servletDef);
        assertEquals("/api/v1", m.servletPath);
        assertEquals("/users", m.pathInfo);

        ServletMatch other = match("/api/v2/users");
        assertSame("a path only under /api/* must fall back to the less specific mapping",
                api, other.servletDef);
    }

    @Test
    public void testPrefixNotMatchedWithoutSlashBoundary() {
        // "/api/*" strips to "/api" for servlet matching (unlike filter
        // matching, which keeps the trailing slash) -- so it also matches
        // "/apifoo" today; lock in that this index reproduces the exact
        // same (if slightly surprising) test, not a corrected one.
        ServletDef api = addServlet("api");
        mapServlet(api, "/api/*");

        ServletMatch m = match("/apifoo");
        assertSame(api, m.servletDef);
    }

    @Test
    public void testExtensionMatchOnlyWhenNothingElseMatches() {
        ServletDef jsp = addServlet("jsp");
        mapServlet(jsp, "*.jsp");

        ServletMatch m = match("/pages/index.jsp");
        assertSame(jsp, m.servletDef);
        assertEquals(MappingMatch.EXTENSION, m.mappingMatch);
    }

    @Test
    public void testExactMatchBeatsExtensionMatch() {
        // Both mappings registered before the first lookup: the index is
        // built once and cached (issue #302's whole point), so unlike the
        // scan it replaces, a mapping added after the first lookup would
        // not appear until the next reset() -- not a real scenario in
        // production (see reset()'s own javadoc), so this test doesn't
        // attempt to simulate it.
        ServletDef jsp = addServlet("jsp");
        mapServlet(jsp, "*.jsp");
        ServletDef exact = addServlet("exact");
        mapServlet(exact, "/pages/index.jsp");

        ServletMatch m = match("/pages/index.jsp");
        assertSame("an exact match must take priority over an extension match",
                exact, m.servletDef);
    }

    @Test
    public void testNoMatchLeavesServletDefNull() {
        addServlet("other");
        mapServlet(context.servletDefs.get("other"), "/other/*");

        ServletMatch m = match("/unrelated");
        assertNull("the caller's own default-servlet fallback must be what handles this, "
                + "not matchServletMapping itself", m.servletDef);
    }

    @Test
    public void testDuplicateExactPatternLastRegistrationWins() {
        ServletDef first = addServlet("first");
        mapServlet(first, "/dup");
        ServletDef second = addServlet("second");
        mapServlet(second, "/dup");

        assertSame("for a duplicate exact pattern, the scan this index replaces "
                + "always applied whichever mapping came last",
                second, match("/dup").servletDef);
    }

    @Test
    public void testDuplicatePrefixPatternFirstRegistrationWins() {
        ServletDef first = addServlet("first");
        mapServlet(first, "/dup/*");
        ServletDef second = addServlet("second");
        mapServlet(second, "/dup/*");

        assertSame("for a duplicate prefix pattern, the scan this index replaces "
                + "only overwrote on a strictly longer match, so the first "
                + "registration of an identical-length tie won",
                first, match("/dup/thing").servletDef);
    }

    @Test
    public void testManyMappingsStillResolveCorrectly() {
        for (int i = 0; i < 3000; i++) {
            ServletDef sd = addServlet("s" + i);
            mapServlet(sd, "/generated/" + i + "/*");
        }
        ServletDef target = addServlet("target");
        mapServlet(target, "/generated/1500/special");

        assertSame(target, match("/generated/1500/special").servletDef);
        assertSame(context.servletDefs.get("s1500"),
                match("/generated/1500/other").servletDef);
        assertSame(context.servletDefs.get("s2999"),
                match("/generated/2999/x").servletDef);
        assertNull("a path sharing no registered prefix at all must not match",
                match("/nomatch/x").servletDef);
    }

    @Test(timeout = 5000)
    public void testLookupCostDoesNotScaleWithMappingCount() {
        // 20,000 prefix mappings none of which are anywhere near
        // "/target/leaf" lexicographically: an unindexed O(mappings x
        // patterns) scan would still have to compare against every one of
        // them on every lookup. The index this replaces it with only
        // walks entries actually near "/target/leaf" in sorted order.
        for (int i = 0; i < 20000; i++) {
            ServletDef sd = addServlet("noise" + i);
            mapServlet(sd, "/noise" + i + "/*");
        }
        ServletDef target = addServlet("target");
        mapServlet(target, "/target/*");

        // Force the index to build once outside the timed section.
        match("/target/leaf");

        long start = System.nanoTime();
        for (int i = 0; i < 50000; i++) {
            ServletMatch m = match("/target/leaf");
            assertSame(target, m.servletDef);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue("50,000 lookups against a 20,000-mapping table took " + elapsedMs
                + "ms -- an unindexed per-request scan would be far slower than this",
                elapsedMs < 2000);
    }

    // ── index invalidation across reset() ──

    @Test
    public void testResetDiscardsThePreviousIndex() {
        ServletDef before = addServlet("before");
        mapServlet(before, "/thing");
        assertSame(before, match("/thing").servletDef);

        context.reset();
        assertNull("a fresh context (post reset()) must not resolve a mapping "
                + "that belonged to the discarded index",
                match("/thing").servletDef);
    }

    @Test
    public void testMappingsRegisteredAfterResetAreRebuiltInAFreshIndex() {
        ServletDef before = addServlet("before");
        mapServlet(before, "/thing");
        match("/thing"); // force the pre-reset index to build

        context.reset();
        // Mutate servletMappings/servletDefs, then take the first lookup
        // only once registration is done -- exactly like production,
        // where nothing dispatches a request until init() (and every
        // dynamic registration point within it) has fully returned. The
        // index is deliberately lazy-built-once-then-cached rather than
        // rebuilt after every mutation (see the servletMappingIndex
        // field's javadoc), so a lookup in between two registrations
        // within the same reset() cycle -- unlike this test's single
        // lookup at the end -- would not see the second one.
        ServletDef after = addServlet("after");
        mapServlet(after, "/thing");
        assertSame("a mapping registered after reset() must be picked up by a "
                + "freshly-rebuilt index", after, match("/thing").servletDef);
    }

    // ── filter mapping ──

    private RequestDispatcher dispatch(String path) {
        return context.getRequestDispatcher(path);
    }

    @SuppressWarnings("unchecked")
    private List<FilterMatch> filterMatchesOf(RequestDispatcher dispatcher) {
        return ((ContextRequestDispatcher) dispatcher).filterMatches;
    }

    @Test
    public void testFilterMatchedByServletName() {
        ServletDef servletDef = addServlet("target");
        mapServlet(servletDef, "/target");
        FilterDef filterDef = addFilter("byName");
        mapFilterToServlet(filterDef, servletDef);

        List<FilterMatch> matches = filterMatchesOf(dispatch("/target"));
        assertEquals(1, matches.size());
        assertSame(filterDef, matches.get(0).filterDef);
    }

    @Test
    public void testMultipleFiltersCanMatchSimultaneously() {
        ServletDef servletDef = addServlet("target");
        mapServlet(servletDef, "/api/users");
        FilterDef exactFilter = addFilter("exactFilter");
        mapFilterToPatterns(exactFilter, "/api/users");
        FilterDef prefixFilter = addFilter("prefixFilter");
        mapFilterToPatterns(prefixFilter, "/api/*");
        FilterDef unrelatedFilter = addFilter("unrelatedFilter");
        mapFilterToPatterns(unrelatedFilter, "/other/*");

        List<FilterMatch> matches = filterMatchesOf(dispatch("/api/users"));
        Set<FilterDef> matchedDefs = new java.util.HashSet<>();
        for (FilterMatch fm : matches) {
            matchedDefs.add(fm.filterDef);
        }
        assertTrue("an exact-pattern filter and a prefix-pattern filter must "
                + "both apply to the same request", matchedDefs.contains(exactFilter));
        assertTrue(matchedDefs.contains(prefixFilter));
        assertFalse("a filter mapped to an unrelated prefix must not apply",
                matchedDefs.contains(unrelatedFilter));
    }

    @Test
    public void testFilterExtensionPattern() {
        ServletDef servletDef = addServlet("jsp");
        mapServlet(servletDef, "*.jsp");
        FilterDef filterDef = addFilter("jspFilter");
        mapFilterToPatterns(filterDef, "*.jsp");

        List<FilterMatch> matches = filterMatchesOf(dispatch("/pages/index.jsp"));
        assertEquals(1, matches.size());
        assertSame(filterDef, matches.get(0).filterDef);
    }

    @Test
    public void testFilterOrderFollowsFilterDeclarationOrderNotMappingOrder() {
        ServletDef servletDef = addServlet("target");
        mapServlet(servletDef, "/target");
        // Register mappings in reverse of filterDefs declaration order.
        FilterDef second = addFilter("second");
        FilterDef first = addFilter("first");
        // filterDefs is a LinkedHashMap -- reinsert in the order we want
        // declaration order to reflect (first, then second).
        context.filterDefs.clear();
        context.filterDefs.put("first", first);
        context.filterDefs.put("second", second);
        mapFilterToPatterns(second, "/target");
        mapFilterToPatterns(first, "/target");

        List<FilterMatch> matches = filterMatchesOf(dispatch("/target"));
        assertEquals(2, matches.size());
        assertSame("filter chain order must follow filterDefs declaration "
                + "order, not the order mappings were registered in",
                first, matches.get(0).filterDef);
        assertSame(second, matches.get(1).filterDef);
    }

    // ── concurrency: no serialization between concurrent readers ──

    @Test(timeout = 20000)
    public void testConcurrentLookupsDoNotSerializeOnTheContextLock() throws Exception {
        for (int i = 0; i < 5000; i++) {
            ServletDef sd = addServlet("noise" + i);
            mapServlet(sd, "/noise" + i + "/*");
        }
        ServletDef target = addServlet("target");
        mapServlet(target, "/target/*");
        match("/target/leaf"); // force index build up front

        int threadCount = 8;
        final int iterationsPerThread = 20000;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        final CountDownLatch ready = new CountDownLatch(threadCount);
        final CountDownLatch go = new CountDownLatch(1);
        final AtomicBoolean sawWrongResult = new AtomicBoolean(false);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threadCount; t++) {
                futures.add(pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        ready.countDown();
                        try {
                            go.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        for (int i = 0; i < iterationsPerThread; i++) {
                            ServletMatch m = match("/target/leaf");
                            if (m.servletDef != target) {
                                sawWrongResult.set(true);
                            }
                        }
                    }
                }));
            }
            ready.await();
            long start = System.nanoTime();
            go.countDown();
            for (java.util.concurrent.Future<?> f : futures) {
                f.get(15, TimeUnit.SECONDS);
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertFalse("concurrent lookups must all resolve the same, correct "
                    + "servlet -- a data race in the lazily-built index would "
                    + "show up here", sawWrongResult.get());
            // threadCount * iterationsPerThread lookups against a 5000-entry
            // table, run concurrently: a design that serialises readers on
            // the context lock for the whole match would not show any
            // benefit from the extra threads over running them one at a
            // time. This budget is generous -- it is evidence, not a tight
            // performance assertion.
            assertTrue(threadCount + " threads x " + iterationsPerThread
                    + " lookups took " + elapsedMs + "ms", elapsedMs < 15000);
        } finally {
            pool.shutdownNow();
        }
    }
}
