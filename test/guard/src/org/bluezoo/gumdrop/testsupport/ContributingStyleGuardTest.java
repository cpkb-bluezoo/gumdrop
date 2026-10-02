/*
 * ContributingStyleGuardTest.java
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

package org.bluezoo.gumdrop.testsupport;

import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.fail;

/**
 * Enforces Gumdrop coding-style rules from CONTRIBUTING.md across main sources,
 * tests, integration tests, and examples. Known debt is listed in
 * {@code contributing-style-allowlist.properties}; shrink that file as code is
 * remediated.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see CONTRIBUTING.md
 */
public class ContributingStyleGuardTest {

    @Test
    public void noLambdaExpressionsOutsideAllowlist() throws Exception {
        assertClean(ContributingStyleGuardSupport.Rule.LAMBDA,
                "Lambda expressions / arrow syntax (use anonymous classes)");
    }

    @Test
    public void noMethodReferencesOutsideAllowlist() throws Exception {
        assertClean(ContributingStyleGuardSupport.Rule.METHOD_REFERENCE,
                "Method references (use explicit calls or anonymous classes)");
    }

    @Test
    public void noVirtualThreadsOutsideAllowlist() throws Exception {
        assertClean(ContributingStyleGuardSupport.Rule.VIRTUAL_THREAD,
                "Virtual threads (use platform threads and ThreadFactory)");
    }

    @Test
    public void noScheduledFutureOutsideAllowlist() throws Exception {
        assertClean(ContributingStyleGuardSupport.Rule.SCHEDULED,
                "ScheduledFuture / scheduleAtFixedRate "
                        + "(use ScheduledTimer or callback timers)");
    }

    @Test
    public void noFunctionalInterfaceAnnotationOutsideAllowlist() throws Exception {
        assertClean(ContributingStyleGuardSupport.Rule.FUNCTIONAL_INTERFACE,
                "@FunctionalInterface (not permitted outside tests; use plain interfaces)");
    }

    @Test
    public void noCompletableFutureOutsideAllowlist() throws Exception {
        assertClean(ContributingStyleGuardSupport.Rule.COMPLETABLE_FUTURE,
                "CompletableFuture (use callbacks or CountDownLatch in tests)");
    }

    @Test
    public void noFutureOutsideAllowlist() throws Exception {
        assertClean(ContributingStyleGuardSupport.Rule.FUTURE,
                "java.util.concurrent.Future (use callbacks)");
    }

    @Test
    public void allowlistEntriesMustStillViolateTheirRule() throws Exception {
        StringBuilder problems = new StringBuilder();
        for (ContributingStyleGuardSupport.Rule rule
                : ContributingStyleGuardSupport.Rule.values()) {
            Set<String> allowlist = ContributingStyleGuardSupport.loadAllowlist(rule);
            List<String> stale = ContributingStyleGuardSupport.findStaleAllowlistEntries(
                    rule, allowlist);
            if (!stale.isEmpty()) {
                problems.append("\n  [").append(rule.name()).append("] stale: ")
                        .append(stale);
            }
        }
        if (problems.length() > 0) {
            fail("Remove remediated paths from "
                    + ContributingStyleGuardSupport.ALLOWLIST_RESOURCE + ":"
                    + problems);
        }
    }

    private static void assertClean(ContributingStyleGuardSupport.Rule rule,
            String label) throws Exception {
        Set<String> allowlist = ContributingStyleGuardSupport.loadAllowlist(rule);
        List<String> violations = ContributingStyleGuardSupport.findViolations(rule, allowlist);
        if (!violations.isEmpty()) {
            fail(label + " found outside "
                    + ContributingStyleGuardSupport.ALLOWLIST_RESOURCE
                    + " (see CONTRIBUTING.md). Violations:\n  "
                    + join(violations, "\n  "));
        }
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}
