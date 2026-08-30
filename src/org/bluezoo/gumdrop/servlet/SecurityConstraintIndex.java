/*
 * SecurityConstraintIndex.java
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

import java.util.ArrayList;
import java.util.List;

/**
 * Index over one {@link Context}'s {@code securityConstraints}, built once
 * (issue #313) so {@link ContextRequestDispatcher#authorize} can skip
 * constraints whose URL patterns cannot match the request path without
 * copying every constraint into a fresh {@code Set} on each authenticated
 * request.
 *
 * <p>URL-pattern matching follows {@link ResourceCollection#matches}: exact,
 * {@code /*} prefix ({@code pattern.substring(0, pattern.length() - 1)}), then
 * extension ({@code *.ext} via {@code path.endsWith(pattern.substring(1))}).
 * Constraints are still returned in {@code securityConstraints} list order;
 * {@link SecurityConstraint#matches(String, String)} is consulted for the
 * final method/resource-collection decision.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ContextRequestDispatcher#authorize
 */
final class SecurityConstraintIndex {

    private final SecurityConstraint[] ordered;
    private final PathPattern[] pathPatterns;

    private SecurityConstraintIndex(SecurityConstraint[] ordered, PathPattern[] pathPatterns) {
        this.ordered = ordered;
        this.pathPatterns = pathPatterns;
    }

    static SecurityConstraintIndex build(List<SecurityConstraint> constraints) {
        SecurityConstraint[] ordered = constraints.toArray(new SecurityConstraint[0]);
        PathPattern[] pathPatterns = new PathPattern[ordered.length];
        for (int i = 0; i < ordered.length; i++) {
            pathPatterns[i] = PathPattern.forConstraint(ordered[i]);
        }
        return new SecurityConstraintIndex(ordered, pathPatterns);
    }

    int size() {
        return ordered.length;
    }

    SecurityConstraint constraintAt(int index) {
        return ordered[index];
    }

    /**
     * Returns whether the constraint at {@code index} has any resource
     * collection whose URL patterns could match {@code path}. Method
     * coverage is still decided by {@link SecurityConstraint#matches}.
     */
    boolean mightApplyToPath(int index, String path) {
        return pathPatterns[index].mightMatch(path);
    }

    private static final class PathPattern {
        private final boolean pathAgnostic;
        private final String[] exactPaths;
        private final String[] prefixKeys;
        private final String[] extensionPatterns;

        private PathPattern(boolean pathAgnostic, String[] exactPaths,
                String[] prefixKeys, String[] extensionPatterns) {
            this.pathAgnostic = pathAgnostic;
            this.exactPaths = exactPaths;
            this.prefixKeys = prefixKeys;
            this.extensionPatterns = extensionPatterns;
        }

        static PathPattern forConstraint(SecurityConstraint constraint) {
            boolean pathAgnostic = false;
            List<String> exact = new ArrayList<>();
            List<String> prefix = new ArrayList<>();
            List<String> extension = new ArrayList<>();
            for (ResourceCollection rc : constraint.resourceCollections) {
                if (rc.urlPatterns == null) {
                    pathAgnostic = true;
                    continue;
                }
                for (String pattern : rc.urlPatterns) {
                    if (pattern.endsWith("/*")) {
                        prefix.add(pattern.substring(0, pattern.length() - 1));
                    } else if (pattern.startsWith("*.")) {
                        extension.add(pattern);
                    } else {
                        exact.add(pattern);
                    }
                }
            }
            return new PathPattern(pathAgnostic,
                    exact.toArray(new String[0]),
                    prefix.toArray(new String[0]),
                    extension.toArray(new String[0]));
        }

        boolean mightMatch(String path) {
            if (pathAgnostic) {
                return true;
            }
            for (String exactPath : exactPaths) {
                if (exactPath.equals(path)) {
                    return true;
                }
            }
            for (String prefixKey : prefixKeys) {
                if (path.startsWith(prefixKey)) {
                    return true;
                }
            }
            for (String pattern : extensionPatterns) {
                if (path.endsWith(pattern.substring(1))) {
                    return true;
                }
            }
            return false;
        }
    }
}
