/*
 * ContextSecurityConstraintIndexTest.java
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Regression coverage for issue #313: {@link ContextRequestDispatcher#authorize}
 * used to copy every {@link Context#securityConstraints} entry into a fresh
 * {@code LinkedHashSet} on each authenticated request before linearly scanning
 * it. Constraints are now indexed once per context and matched without a
 * per-request collection allocation.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContextSecurityConstraintIndexTest {

    private Container container;
    private Context context;
    private File webappRoot;

    @Before
    public void setUp() throws Exception {
        container = new Container();
        webappRoot = Files.createTempDirectory("gumdrop-security-constraint-index").toFile();
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

    private static SecurityConstraint constraintForPattern(String pattern) {
        SecurityConstraint sc = new SecurityConstraint();
        ResourceCollection rc = new ResourceCollection();
        rc.urlPatterns.add(pattern);
        sc.addResourceCollection(rc);
        return sc;
    }

    @Test
    public void testSecurityConstraintIndexIsBuiltOnceAndCached() {
        context.securityConstraints.add(constraintForPattern("/admin/*"));
        SecurityConstraintIndex first = context.securityConstraintIndex();
        SecurityConstraintIndex second = context.securityConstraintIndex();
        assertSame("security constraint index must be built once and reused",
                first, second);
    }

    @Test
    public void testResetDiscardsSecurityConstraintIndex() {
        context.securityConstraints.add(constraintForPattern("/admin/*"));
        SecurityConstraintIndex beforeReset = context.securityConstraintIndex();
        context.reset();
        context.securityConstraints.add(constraintForPattern("/api/*"));
        SecurityConstraintIndex afterReset = context.securityConstraintIndex();
        assertNotSame("reset() must discard the previous security constraint index",
                beforeReset, afterReset);
    }

    @Test
    public void testIndexMightApplyMatchesFullConstraintScan() throws Exception {
        List<SecurityConstraint> constraints = new ArrayList<>();
        constraints.add(constraintForPattern("/noise0/*"));
        constraints.add(constraintForPattern("/target/*"));
        constraints.add(constraintForPattern("/noise1/*"));
        SecurityConstraintIndex index = SecurityConstraintIndex.build(constraints);

        String method = "GET";
        String path = "/target/page";
        List<SecurityConstraint> expected = new ArrayList<>();
        for (SecurityConstraint sc : constraints) {
            if (sc.matches(method, path)) {
                expected.add(sc);
            }
        }

        final List<SecurityConstraint> viaIndex = new ArrayList<>();
        index.forEachPathCandidate(path, new SecurityConstraintIndex.PathCandidate() {
            @Override
            public boolean accept(int i) {
                SecurityConstraint sc = index.constraintAt(i);
                if (sc.matches(method, path)) {
                    viaIndex.add(sc);
                }
                return true;
            }
        });
        assertEquals(expected, viaIndex);
    }

    @Test(timeout = 3000)
    public void testMatchingCostDoesNotScanEveryConstraint() throws Exception {
        for (int i = 0; i < 5000; i++) {
            context.securityConstraints.add(constraintForPattern("/noise" + i + "/*"));
        }
        context.securityConstraints.add(constraintForPattern("/target/*"));
        SecurityConstraintIndex index = context.securityConstraintIndex();

        long start = System.nanoTime();
        final int[] hits = { 0 };
        for (int i = 0; i < 50000; i++) {
            index.forEachPathCandidate("/target/page", new SecurityConstraintIndex.PathCandidate() {
                @Override
                public boolean accept(int c) {
                    if (index.constraintAt(c).matches("GET", "/target/page")) {
                        hits[0]++;
                    }
                    return true;
                }
            });
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue("expected at least one matching constraint", hits[0] > 0);
        assertTrue("50,000 indexed lookups against 5,001 constraints took " + elapsedMs
                        + "ms -- a per-request linear scan would be far slower",
                elapsedMs < 2000);
    }

    @Test
    public void testAuthorizeDoesNotAllocatePerRequestConstraintCollection() throws Exception {
        Path source = Paths.get("src/org/bluezoo/gumdrop/servlet/ContextRequestDispatcher.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        assertFalse("authorize must not copy security constraints into a new collection each request",
                text.contains("new LinkedHashSet<SecurityConstraint>"));
    }
}
