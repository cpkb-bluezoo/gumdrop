/*
 * ServletMappingIndex.java
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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Index over one {@link Context}'s {@code servletMappings}, built once
 * (issue #302) so a request's {@code matchServletMapping} lookup is O(1)
 * (exact/extension) or O(log n) (path prefix) instead of an O(mappings x
 * patterns) linear scan of every mapping's every URL pattern on every
 * request.
 *
 * <p>Reproduces the exact-vs-prefix-vs-extension priority and tie-break
 * rules of the scan it replaces:
 * <ul>
 *   <li>Exact ({@code pattern.equals(path)}): the scan applies whichever
 *       mapping is encountered <em>last</em>, with no tie-break logic at
 *       all -- so {@link #exact} is a plain overwrite-on-insert map,
 *       populated in {@code servletMappings} order.</li>
 *   <li>Path prefix ({@code pattern.endsWith("/*")}): the scan keeps the
 *       longest matching prefix, comparing lengths with a strict {@code
 *       &gt;} -- so on a length tie (only possible for the exact same
 *       prefix string, since two different strings of equal length can't
 *       both be a
 *       prefix of the same path) the <em>first</em> mapping encountered
 *       wins. {@link #prefix} is therefore populated with {@code
 *       putIfAbsent}, and read with the same {@code floorEntry}/{@code
 *       lowerEntry} walk {@link Container#getContextByPath} already uses
 *       for the analogous longest-prefix problem one level up (issue
 *       #194): among registered prefixes that are a prefix of {@code
 *       path}, the longest one is also the lexicographically greatest, so
 *       walking down from {@code floorEntry(path)} to strictly smaller
 *       keys finds it without a scan.</li>
 *   <li>Extension ({@code pattern.startsWith("*.")}): only ever consulted
 *       by the scan when nothing exact or prefix has matched, and even
 *       then via {@code path.endsWith(...)} against the pattern's raw
 *       suffix -- not the request path's own extracted extension -- so a
 *       pattern like {@code *.sp} can shadow {@code *.jsp}. {@link
 *       #extension} preserves this by staying a small, insertion-ordered,
 *       linearly-scanned map (deduplicated by exact pattern string via
 *       {@code putIfAbsent}) rather than an extension-keyed index -- exact
 *       and prefix mappings are typically the vast majority of a
 *       registration table, so even an unindexed scan of just the
 *       extension subset is a large win over scanning everything.</li>
 * </ul>
 *
 * <p>Mappings resolved to {@link Context#defaultServletDef} are excluded
 * from {@link #exact} only, matching the scan: the {@code "/"} pattern
 * that (by construction, see {@link Context#load}) is always what {@code
 * defaultServletDef} resolves from is handled entirely by {@link
 * Context#getRequestDispatcher}'s own fallback once no other match is
 * found, not by this index.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Context#matchServletMapping
 */
final class ServletMappingIndex {

    final Map<String, ServletDef> exact;
    final NavigableMap<String, ServletDef> prefix;
    final Map<String, ServletDef> extension;

    private ServletMappingIndex(Map<String, ServletDef> exact,
            NavigableMap<String, ServletDef> prefix, Map<String, ServletDef> extension) {
        this.exact = exact;
        this.prefix = prefix;
        this.extension = extension;
    }

    static ServletMappingIndex build(Iterable<ServletMapping> servletMappings, ServletDef defaultServletDef) {
        Map<String, ServletDef> exact = new HashMap<>();
        NavigableMap<String, ServletDef> prefix = new TreeMap<>();
        Map<String, ServletDef> extension = new LinkedHashMap<>();
        for (ServletMapping servletMapping : servletMappings) {
            ServletDef servletDef = servletMapping.servletDef;
            if (servletDef == null) {
                continue;
            }
            for (String pattern : servletMapping.urlPatterns) {
                if (pattern.endsWith("/*")) {
                    String stripped = pattern.substring(0, pattern.length() - 2);
                    prefix.putIfAbsent(stripped, servletDef);
                } else if (pattern.startsWith("*.")) {
                    extension.putIfAbsent(pattern, servletDef);
                } else if (servletDef != defaultServletDef) {
                    exact.put(pattern, servletDef);
                }
            }
        }
        return new ServletMappingIndex(exact, prefix, extension);
    }

    /**
     * Finds the longest registered path-prefix pattern that is an actual
     * prefix of {@code path}, or null if none is.
     *
     * @param path the request path
     * @return the longest matching prefix's entry, or null
     */
    Map.Entry<String, ServletDef> longestPrefixMatch(String path) {
        Map.Entry<String, ServletDef> entry = prefix.floorEntry(path);
        while (entry != null) {
            if (path.startsWith(entry.getKey())) {
                return entry;
            }
            entry = prefix.lowerEntry(entry.getKey());
        }
        return null;
    }
}
