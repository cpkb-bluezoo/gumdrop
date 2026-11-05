/*
 * Wildmat.java
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

package org.bluezoo.gumdrop.nntp;

/**
 * Matching of newsgroup names against a wildmat (RFC 3977 section 4.2).
 *
 * <p>A wildmat is a comma separated list of patterns. A pattern may start
 * with {@code !} to exclude what it matches. A name matches the wildmat if
 * the last pattern that matches it is not an exclusion. Within a pattern,
 * {@code *} matches any run of characters, {@code ?} any one character,
 * {@code [...]} a set (with ranges and {@code ^} to negate), and a
 * backslash quotes the character after it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Wildmat {

    private Wildmat() {
    }

    /**
     * Tests a name against a wildmat.
     *
     * @param wildmat the wildmat, or null to match every name
     * @param name the name
     * @return true if the name matches
     */
    public static boolean matches(String wildmat, String name) {
        if (wildmat == null) {
            return true;
        }
        boolean result = false;
        int len = wildmat.length();
        int start = 0;
        while (start <= len) {
            int end = wildmat.indexOf(',', start);
            if (end < 0) {
                end = len;
            }
            boolean exclude = start < end && wildmat.charAt(start) == '!';
            String pattern = wildmat.substring(exclude ? start + 1 : start, end);
            if (glob(pattern, name)) {
                result = !exclude;
            }
            start = end + 1;
        }
        return result;
    }

    private static boolean glob(String p, String s) {
        int pi = 0;
        int si = 0;
        int starP = -1;
        int starS = 0;
        while (si < s.length()) {
            if (pi < p.length()) {
                if (p.charAt(pi) == '*') {
                    starP = pi++;
                    starS = si;
                    continue;
                }
                int next = matchOne(p, pi, s.charAt(si));
                if (next >= 0) {
                    pi = next;
                    si++;
                    continue;
                }
            }
            if (starP < 0) {
                return false;
            }
            pi = starP + 1;
            si = ++starS;
        }
        while (pi < p.length() && p.charAt(pi) == '*') {
            pi++;
        }
        return pi == p.length();
    }

    /** Matches the single-character pattern at pi; returns the index after it, or -1. */
    private static int matchOne(String p, int pi, char ch) {
        int len = p.length();
        char c = p.charAt(pi);
        if (c == '?') {
            return pi + 1;
        }
        if (c == '\\' && pi + 1 < len) {
            return p.charAt(pi + 1) == ch ? pi + 2 : -1;
        }
        if (c == '[') {
            int j = pi + 1;
            boolean negate = false;
            if (j < len && p.charAt(j) == '^') {
                negate = true;
                j++;
            }
            boolean found = false;
            boolean first = true;
            while (j < len && (first || p.charAt(j) != ']')) {
                first = false;
                char lo = p.charAt(j);
                if (j + 2 < len && p.charAt(j + 1) == '-' && p.charAt(j + 2) != ']') {
                    char hi = p.charAt(j + 2);
                    if (ch >= lo && ch <= hi) {
                        found = true;
                    }
                    j += 3;
                } else {
                    if (ch == lo) {
                        found = true;
                    }
                    j++;
                }
            }
            if (j >= len) {
                return ch == '[' ? pi + 1 : -1; // no closing bracket: literal
            }
            return found != negate ? j + 1 : -1;
        }
        return c == ch ? pi + 1 : -1;
    }

}
