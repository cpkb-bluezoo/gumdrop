/*
 * ContextMappingIndexPerformanceTest.java
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

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.MappingMatch;

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
/*
 * NOTE: wall-clock thresholds live here, not in the unit suite: unit tests must
 * be deterministic (CONTRIBUTING.md). Extracted from ContextMappingIndexTest.
 */
public class ContextMappingIndexPerformanceTest {

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





    // ── filter mapping ──

    private RequestDispatcher dispatch(String path) {
        return context.getRequestDispatcher(path);
    }

    @SuppressWarnings("unchecked")
    private List<FilterMatch> filterMatchesOf(RequestDispatcher dispatcher) {
        return ((ContextRequestDispatcher) dispatcher).filterMatches;
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
        final CountDownLatch allDone = new CountDownLatch(threadCount);
        try {
            for (int t = 0; t < threadCount; t++) {
                pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        ready.countDown();
                        try {
                            go.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        try {
                            for (int i = 0; i < iterationsPerThread; i++) {
                                ServletMatch m = match("/target/leaf");
                                if (m.servletDef != target) {
                                    sawWrongResult.set(true);
                                }
                            }
                        } finally {
                            allDone.countDown();
                        }
                    }
                });
            }
            ready.await();
            long start = System.nanoTime();
            go.countDown();
            assertTrue("worker threads did not finish",
                    allDone.await(15, TimeUnit.SECONDS));
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
