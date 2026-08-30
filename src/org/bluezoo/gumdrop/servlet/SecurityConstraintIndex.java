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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;

/**
 * Index over one {@link Context}'s {@code securityConstraints}, built once
 * (issue #313) so {@link ContextRequestDispatcher#authorize} can visit only
 * constraints whose URL patterns could match the request path, without
 * copying every constraint into a fresh {@code Set} on each authenticated
 * request.
 *
 * <p>URL-pattern matching follows {@link ResourceCollection#matches}: exact,
 * {@code /*} prefix ({@code pattern.substring(0, pattern.length() - 1)}), then
 * extension ({@code *.ext} via {@code path.endsWith(pattern.substring(1))}).
 * Candidates are returned in {@code securityConstraints} list order;
 * {@link SecurityConstraint#matches(String, String)} is consulted for the
 * final method/resource-collection decision.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ContextRequestDispatcher#authorize
 */
final class SecurityConstraintIndex {

    /**
     * Visited for each constraint index whose URL patterns might match a path.
     */
    interface PathCandidate {
        /**
         * @return {@code false} to stop visiting further candidates
         */
        boolean accept(int index) throws ServletException, IOException;
    }

    private final SecurityConstraint[] ordered;
    private final Map<String, int[]> exactIndices;
    private final PrefixTrie prefixTrie;
    private final Map<String, int[]> extensionIndices;
    private final int[] pathAgnosticIndices;

    private final int[] candidateGeneration;
    private final int[] candidateScratch;
    private int candidateCount;
    private int generation;

    private SecurityConstraintIndex(SecurityConstraint[] ordered,
            Map<String, int[]> exactIndices,
            PrefixTrie prefixTrie,
            Map<String, int[]> extensionIndices,
            int[] pathAgnosticIndices) {
        this.ordered = ordered;
        this.exactIndices = exactIndices;
        this.prefixTrie = prefixTrie;
        this.extensionIndices = extensionIndices;
        this.pathAgnosticIndices = pathAgnosticIndices;
        this.candidateGeneration = new int[ordered.length];
        this.candidateScratch = new int[ordered.length];
    }

    static SecurityConstraintIndex build(List<SecurityConstraint> constraints) {
        SecurityConstraint[] ordered = constraints.toArray(new SecurityConstraint[0]);
        Map<String, List<Integer>> exact = new HashMap<>();
        PrefixTrie prefixTrie = new PrefixTrie();
        Map<String, List<Integer>> extension = new LinkedHashMap<>();
        List<Integer> pathAgnostic = new ArrayList<>();

        for (int i = 0; i < ordered.length; i++) {
            registerConstraint(ordered[i], i, exact, prefixTrie, extension, pathAgnostic);
        }

        return new SecurityConstraintIndex(ordered,
                freeze(exact),
                prefixTrie,
                freeze(extension),
                toArray(pathAgnostic));
    }

    int size() {
        return ordered.length;
    }

    SecurityConstraint constraintAt(int index) {
        return ordered[index];
    }

    /**
     * Invokes {@code consumer} for each constraint index whose URL patterns
     * might match {@code path}, in {@code securityConstraints} list order.
     * Method coverage is still decided by {@link SecurityConstraint#matches}.
     */
    boolean forEachPathCandidate(String path, PathCandidate consumer)
            throws ServletException, IOException {
        synchronized (this) {
            int gen = ++generation;
            if (generation == Integer.MAX_VALUE) {
                java.util.Arrays.fill(candidateGeneration, 0);
                generation = 1;
                gen = 1;
            }
            candidateCount = 0;

            markIndices(exactIndices.get(path), gen);
            prefixTrie.collect(path, gen, this);
            for (Map.Entry<String, int[]> entry : extensionIndices.entrySet()) {
                String pattern = entry.getKey();
                if (path.endsWith(pattern.substring(1))) {
                    markIndices(entry.getValue(), gen);
                }
            }
            markIndices(pathAgnosticIndices, gen);

            if (candidateCount > 1) {
                java.util.Arrays.sort(candidateScratch, 0, candidateCount);
            }
            for (int j = 0; j < candidateCount; j++) {
                if (!consumer.accept(candidateScratch[j])) {
                    return false;
                }
            }
            return true;
        }
    }

    private void markIndices(int[] indices, int gen) {
        if (indices == null) {
            return;
        }
        for (int index : indices) {
            if (candidateGeneration[index] != gen) {
                candidateGeneration[index] = gen;
                candidateScratch[candidateCount++] = index;
            }
        }
    }

    private static void registerConstraint(SecurityConstraint constraint, int index,
            Map<String, List<Integer>> exact,
            PrefixTrie prefixTrie,
            Map<String, List<Integer>> extension,
            List<Integer> pathAgnostic) {
        for (ResourceCollection rc : constraint.resourceCollections) {
            if (rc.urlPatterns == null) {
                pathAgnostic.add(index);
                continue;
            }
            for (String pattern : rc.urlPatterns) {
                if (pattern.endsWith("/*")) {
                    prefixTrie.add(pattern.substring(0, pattern.length() - 1), index);
                } else if (pattern.startsWith("*.")) {
                    addIndex(extension, pattern, index);
                } else {
                    addIndex(exact, pattern, index);
                }
            }
        }
    }

    private static void addIndex(Map<String, List<Integer>> bucket, String key, int index) {
        List<Integer> indices = bucket.get(key);
        if (indices == null) {
            indices = new ArrayList<>();
            bucket.put(key, indices);
        }
        indices.add(index);
    }

    private static Map<String, int[]> freeze(Map<String, List<Integer>> buckets) {
        Map<String, int[]> frozen = new HashMap<>();
        for (Map.Entry<String, List<Integer>> entry : buckets.entrySet()) {
            frozen.put(entry.getKey(), toArray(entry.getValue()));
        }
        return frozen;
    }

    private static int[] toArray(List<Integer> indices) {
        int[] array = new int[indices.size()];
        for (int i = 0; i < indices.size(); i++) {
            array[i] = indices.get(i);
        }
        return array;
    }

    private static final class PrefixTrie {
        private final Map<Character, PrefixTrie> children = new HashMap<>();
        private int[] indices;

        void add(String prefixKey, int index) {
            PrefixTrie node = this;
            for (int i = 0; i < prefixKey.length(); i++) {
                char c = prefixKey.charAt(i);
                PrefixTrie next = node.children.get(c);
                if (next == null) {
                    next = new PrefixTrie();
                    node.children.put(c, next);
                }
                node = next;
            }
            node.indices = append(node.indices, index);
        }

        void collect(String path, int gen, SecurityConstraintIndex owner) {
            PrefixTrie node = this;
            owner.markIndices(node.indices, gen);
            for (int i = 0; i < path.length(); i++) {
                node = node.children.get(path.charAt(i));
                if (node == null) {
                    return;
                }
                owner.markIndices(node.indices, gen);
            }
        }

        private static int[] append(int[] existing, int index) {
            if (existing == null) {
                return new int[] { index };
            }
            int[] merged = new int[existing.length + 1];
            System.arraycopy(existing, 0, merged, 0, existing.length);
            merged[existing.length] = index;
            return merged;
        }
    }
}
