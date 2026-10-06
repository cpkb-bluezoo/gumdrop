/*
 * Tokens.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.util;

/**
 * Regex-free whitespace tokenizing for protocol lines.
 *
 * <p>Whitespace is the same set a regular expression {@code \s} matches:
 * space, tab, line feed, vertical tab, form feed and carriage return.
 * Tokens are never empty, so leading, trailing and repeated whitespace
 * produce no empty strings. Each call scans the input twice (count, then
 * fill) and allocates only the result array and the token strings.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Tokens {

    private static final String[] NONE = new String[0];

    // Package-visible rather than private only so the unit test can
    // exercise it; the class is not intended to be instantiated.
    Tokens() {
    }

    /**
     * Splits on runs of whitespace.
     *
     * @param s the text, may be null
     * @return the tokens; empty if s is null or blank
     */
    public static String[] split(String s) {
        return split(s, 0);
    }

    /**
     * Splits on runs of whitespace into at most {@code limit} tokens. When
     * the limit is reached the final token is the remainder of the text,
     * with its internal whitespace preserved and trailing whitespace
     * removed (for example a filename that contains spaces).
     *
     * @param s the text, may be null
     * @param limit the maximum number of tokens; zero or negative means
     * no limit
     * @return the tokens; empty if s is null or blank
     */
    public static String[] split(String s, int limit) {
        if (s == null) {
            return NONE;
        }
        int len = s.length();
        int count = 0;
        int i = 0;
        while (true) {
            while (i < len && isWhitespace(s.charAt(i))) {
                i++;
            }
            if (i >= len) {
                break;
            }
            count++;
            if (count == limit) {
                break;
            }
            while (i < len && !isWhitespace(s.charAt(i))) {
                i++;
            }
        }
        if (count == 0) {
            return NONE;
        }
        String[] tokens = new String[count];
        i = 0;
        for (int n = 0; n < count; n++) {
            while (isWhitespace(s.charAt(i))) {
                i++;
            }
            int start = i;
            if (n == limit - 1) {
                int end = len;
                while (isWhitespace(s.charAt(end - 1))) {
                    end--;
                }
                tokens[n] = s.substring(start, end);
            } else {
                while (i < len && !isWhitespace(s.charAt(i))) {
                    i++;
                }
                tokens[n] = s.substring(start, i);
            }
        }
        return tokens;
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || (c >= '\t' && c <= '\r');
    }

}
