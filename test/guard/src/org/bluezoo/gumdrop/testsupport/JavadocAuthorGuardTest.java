/*
 * JavadocAuthorGuardTest.java
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
 * Enforces {@code @author} on main, unit-test, and integration types per CONTRIBUTING.md.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see CONTRIBUTING.md
 */
public class JavadocAuthorGuardTest {

    @Test
    public void allTypesHaveAuthorTag() throws Exception {
        Set<String> allowlist = JavadocAuthorGuardSupport.loadAllowlist();
        List<String> violations = JavadocAuthorGuardSupport.findViolations(allowlist);
        if (!violations.isEmpty()) {
            fail("Missing @author (see CONTRIBUTING.md). "
                    + "Run scripts/add-javadoc-author.py. Violations:\n  "
                    + join(violations, "\n  "));
        }
    }

    @Test
    public void allowlistEntriesMustStillViolateAuthorRule() throws Exception {
        Set<String> allowlist = JavadocAuthorGuardSupport.loadAllowlist();
        List<String> stale = JavadocAuthorGuardSupport.findStaleAllowlistEntries(allowlist);
        if (!stale.isEmpty()) {
            fail("Remove remediated paths from "
                    + JavadocAuthorGuardSupport.ALLOWLIST_RESOURCE + ": " + stale);
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
