/*
 * FilterMappingIndex.java
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Index over one {@link Context}'s {@code filterMappings}, built once
 * (issue #302) so the filter-matching block in {@link
 * Context#getRequestDispatcher} touches only the mappings that could
 * plausibly apply to a request instead of scanning every mapping's every
 * URL pattern.
 *
 * <p>Unlike servlet mapping, filter mapping has no single winner: every
 * mapping that matches (by servlet name <em>or</em> by any of its own
 * kinds of URL pattern) contributes its filter to the chain, deduplicated
 * only by {@code FilterDef} once all sources are combined -- so this
 * index is four independent buckets, all consulted, rather than a
 * priority chain:
 * <ul>
 *   <li>{@link #byServletName}, keyed by the {@link ServletDef} a mapping
 *       names (a mapping can name several).</li>
 *   <li>{@link #exact}, keyed by an exact-match pattern string -- several
 *       different mappings can legitimately share one.</li>
 *   <li>{@link #prefix} and {@link #extension}: mappings with at least
 *       one {@code "/*"}- or {@code "*."}-shaped pattern respectively,
 *       each added at most once even if the mapping has several patterns
 *       of that kind. These stay small, unindexed lists, scanned with the
 *       exact same per-pattern test expressions {@code
 *       getRequestDispatcher} always used (including the filter-specific
 *       {@code /*} stripping, which -- unlike {@link
 *       Context#matchServletMapping}'s -- keeps the trailing slash, e.g.
 *       {@code "/api/*"} strips to {@code "/api/"} here, not {@code
 *       "/api"}): the two kinds of pattern already disagreed before this
 *       index existed, so replicating each scan's own expression exactly,
 *       rather than sharing one, is what keeps both behaviours
 *       unchanged. Exact and prefix servlet mappings dominate a typical
 *       registration table, so even an unindexed scan of just the
 *       filter-side prefix/extension subset remains a large win.</li>
 * </ul>
 *
 * <p>A mapping that names a servlet is still indexed into {@link #exact}/
 * {@link #prefix}/{@link #extension} too, unconditionally -- the original
 * scan only skipped a mapping's own pattern check when the specific
 * request's resolved servlet happened to be one of the names it listed,
 * purely to avoid redundant work, never to exclude a filter that would
 * otherwise apply. Consulting every applicable bucket and letting the
 * caller's {@code Map<FilterDef,FilterMatch>} dedupe produces the
 * identical resulting filter set.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class FilterMappingIndex {

    final Map<ServletDef, List<FilterMapping>> byServletName;
    final Map<String, List<FilterMapping>> exact;
    final List<FilterMapping> prefix;
    final List<FilterMapping> extension;

    private FilterMappingIndex(Map<ServletDef, List<FilterMapping>> byServletName,
            Map<String, List<FilterMapping>> exact,
            List<FilterMapping> prefix, List<FilterMapping> extension) {
        this.byServletName = byServletName;
        this.exact = exact;
        this.prefix = prefix;
        this.extension = extension;
    }

    static FilterMappingIndex build(Iterable<FilterMapping> filterMappings) {
        Map<ServletDef, List<FilterMapping>> byServletName = new HashMap<>();
        Map<String, List<FilterMapping>> exact = new HashMap<>();
        List<FilterMapping> prefix = new ArrayList<>();
        List<FilterMapping> extension = new ArrayList<>();
        for (FilterMapping filterMapping : filterMappings) {
            if (filterMapping.filterDef == null) {
                continue;
            }
            for (ServletDef servletDef : filterMapping.servletDefs) {
                List<FilterMapping> list = byServletName.get(servletDef);
                if (list == null) {
                    list = new ArrayList<>();
                    byServletName.put(servletDef, list);
                }
                list.add(filterMapping);
            }
            boolean addedToPrefix = false;
            boolean addedToExtension = false;
            for (String pattern : filterMapping.urlPatterns) {
                if (pattern.endsWith("/*")) {
                    if (!addedToPrefix) {
                        prefix.add(filterMapping);
                        addedToPrefix = true;
                    }
                } else if (pattern.startsWith("*.")) {
                    if (!addedToExtension) {
                        extension.add(filterMapping);
                        addedToExtension = true;
                    }
                } else {
                    List<FilterMapping> list = exact.get(pattern);
                    if (list == null) {
                        list = new ArrayList<>();
                        exact.put(pattern, list);
                    }
                    list.add(filterMapping);
                }
            }
        }
        return new FilterMappingIndex(byServletName, exact, prefix, extension);
    }
}
