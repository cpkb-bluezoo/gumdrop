/*
 * JavaSnippets.java
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

package org.bluezoo.gumdrop.servlet.jsp;

/**
 * Regex-free single-pass scanning of the Java code in JSP scriptlets and
 * declarations. Comments and string, character and text block literals are
 * recognised so that code-like text inside them is never misread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class JavaSnippets {

    /** {@link #memberKinds} flag: a method or constructor with a body. */
    static final int METHODS = 1;

    /** {@link #memberKinds} flag: a field declaration. */
    static final int FIELDS = 2;

    private JavaSnippets() {
    }

    /**
     * Returns true if the code contains nothing but whitespace and
     * well-formed comments. An unterminated block comment counts as code.
     */
    static boolean isBlank(String code) {
        int len = code.length();
        int i = 0;
        while (i < len) {
            char c = code.charAt(i);
            if (c <= ' ') {
                i++;
            } else if (c == '/' && i + 1 < len && code.charAt(i + 1) == '/') {
                i = lineEnd(code, i + 2);
            } else if (c == '/' && i + 1 < len && code.charAt(i + 1) == '*') {
                int end = code.indexOf("*/", i + 2);
                if (end < 0) {
                    return false;
                }
                i = end + 2;
            } else {
                return false;
            }
        }
        return true;
    }

    /**
     * Classifies the top-level members of a declaration block. A member
     * ending in a brace-delimited body whose header has parentheses and no
     * initializer is a method or constructor; a member ending in a
     * semicolon is a field unless it is a body-less method signature.
     * Initializer blocks and nested types are neither.
     *
     * @return a combination of {@link #METHODS} and {@link #FIELDS}
     */
    static int memberKinds(String code) {
        int len = code.length();
        int kinds = 0;
        boolean content = false;   // current member has tokens
        boolean paren = false;     // saw a top-level '(' in the member
        boolean equals = false;    // saw a top-level '=' in the member
        int parens = 0;
        int i = 0;
        while (i < len) {
            int next = skipNonCode(code, i);
            if (next != i) {
                content = content || code.charAt(i) != '/';
                i = next;
                continue;
            }
            char c = code.charAt(i);
            if (c == '(') {
                parens++;
                paren = true;
                content = true;
            } else if (c == ')') {
                parens--;
            } else if (parens > 0) {
                content = true;
            } else if (c == '=') {
                equals = true;
                content = true;
            } else if (c == '{') {
                i = skipBraces(code, i);
                if (equals) {
                    // array initializer or anonymous class inside a field
                    continue;
                }
                if (paren) {
                    kinds |= METHODS;
                }
                content = false;
                paren = false;
                continue;
            } else if (c == ';') {
                if (content && (equals || !paren)) {
                    kinds |= FIELDS;
                }
                content = false;
                paren = false;
                equals = false;
            } else if (c > ' ') {
                content = true;
            }
            i++;
        }
        return kinds;
    }

    /**
     * If a comment or literal starts at i, returns the index after it
     * (the end of the text if it is unterminated); otherwise returns i.
     */
    private static int skipNonCode(String s, int i) {
        int len = s.length();
        char c = s.charAt(i);
        if (c == '/' && i + 1 < len) {
            char d = s.charAt(i + 1);
            if (d == '/') {
                return lineEnd(s, i + 2);
            }
            if (d == '*') {
                int end = s.indexOf("*/", i + 2);
                return end < 0 ? len : end + 2;
            }
        } else if (c == '"') {
            if (s.startsWith("\"\"\"", i)) {
                return literalEnd(s, i + 3, '"', true);
            }
            return literalEnd(s, i + 1, '"', false);
        } else if (c == '\'') {
            return literalEnd(s, i + 1, '\'', false);
        }
        return i;
    }

    /** Index of the end of the line (the newline itself, or the end of text). */
    private static int lineEnd(String s, int from) {
        int end = s.indexOf('\n', from);
        return end < 0 ? s.length() : end;
    }

    /**
     * Returns the index after the closing quote of a literal whose content
     * starts at from, honouring backslash escapes. A text block closes on
     * three quotes; other literals also stop at a newline.
     */
    private static int literalEnd(String s, int from, char quote, boolean textBlock) {
        int len = s.length();
        int i = from;
        while (i < len) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (textBlock) {
                if (c == quote && s.startsWith("\"\"\"", i)) {
                    return i + 3;
                }
                i++;
            } else if (c == quote) {
                return i + 1;
            } else if (c == '\n') {
                return i;
            } else {
                i++;
            }
        }
        return len;
    }

    /** Returns the index after the brace that closes the one at open. */
    private static int skipBraces(String s, int open) {
        int len = s.length();
        int depth = 0;
        int i = open;
        while (i < len) {
            int next = skipNonCode(s, i);
            if (next != i) {
                i = next;
                continue;
            }
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
            i++;
        }
        return len;
    }

}
