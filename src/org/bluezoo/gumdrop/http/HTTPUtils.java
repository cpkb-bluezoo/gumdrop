/*
 * HTTPUtils.java
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

package org.bluezoo.gumdrop.http;

/**
 * Utility methods for HTTP protocol validation.
 *
 * <p>These methods provide efficient character-by-character validation
 * without the overhead of regular expressions.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class HTTPUtils {

    // Lookup table for token characters (RFC 7230 section 3.2.6)
    // token = 1*tchar
    // tchar = "!" / "#" / "$" / "%" / "&" / "'" / "*" / "+" / "-" / "." /
    //         "^" / "_" / "`" / "|" / "~" / DIGIT / ALPHA
    private static final boolean[] TOKEN_CHARS = new boolean[128];

    // Lookup table for request-target characters
    // Includes: unreserved / pct-encoded / sub-delims / ":" / "@" / "/" / "?"
    // Plus "#" for fragment and "[" "]" for IPv6 literals
    private static final boolean[] REQUEST_TARGET_CHARS = new boolean[128];

    // Lookup table for header name characters
    // Matches: :?[a-zA-Z0-9\-_]+
    // Optional leading colon for pseudo-headers, then alphanumeric/hyphen/underscore
    private static final boolean[] HEADER_NAME_CHARS = new boolean[128];

    // Lookup table for header value characters  
    // Matches: [\x20-\x7E\x80-\xFF\t]*
    // Visible ASCII (0x20-0x7E), high bytes (0x80-0xFF), and tab
    private static final boolean[] HEADER_VALUE_CHARS = new boolean[256];

    static {
        // Initialize token characters
        // ALPHA
        for (char c = 'A'; c <= 'Z'; c++) {
            TOKEN_CHARS[c] = true;
        }
        for (char c = 'a'; c <= 'z'; c++) {
            TOKEN_CHARS[c] = true;
        }
        // DIGIT
        for (char c = '0'; c <= '9'; c++) {
            TOKEN_CHARS[c] = true;
        }
        // Special token characters: !#$%&'*+-.^_`|~
        TOKEN_CHARS['!'] = true;
        TOKEN_CHARS['#'] = true;
        TOKEN_CHARS['$'] = true;
        TOKEN_CHARS['%'] = true;
        TOKEN_CHARS['&'] = true;
        TOKEN_CHARS['\''] = true;
        TOKEN_CHARS['*'] = true;
        TOKEN_CHARS['+'] = true;
        TOKEN_CHARS['-'] = true;
        TOKEN_CHARS['.'] = true;
        TOKEN_CHARS['^'] = true;
        TOKEN_CHARS['_'] = true;
        TOKEN_CHARS['`'] = true;
        TOKEN_CHARS['|'] = true;
        TOKEN_CHARS['~'] = true;

        // Initialize request-target characters
        // ALPHA
        for (char c = 'A'; c <= 'Z'; c++) {
            REQUEST_TARGET_CHARS[c] = true;
        }
        for (char c = 'a'; c <= 'z'; c++) {
            REQUEST_TARGET_CHARS[c] = true;
        }
        // DIGIT
        for (char c = '0'; c <= '9'; c++) {
            REQUEST_TARGET_CHARS[c] = true;
        }
        // unreserved: - . _ ~
        REQUEST_TARGET_CHARS['-'] = true;
        REQUEST_TARGET_CHARS['.'] = true;
        REQUEST_TARGET_CHARS['_'] = true;
        REQUEST_TARGET_CHARS['~'] = true;
        // pct-encoded: %
        REQUEST_TARGET_CHARS['%'] = true;
        // sub-delims: ! $ & ' ( ) * + , ; =
        REQUEST_TARGET_CHARS['!'] = true;
        REQUEST_TARGET_CHARS['$'] = true;
        REQUEST_TARGET_CHARS['&'] = true;
        REQUEST_TARGET_CHARS['\''] = true;
        REQUEST_TARGET_CHARS['('] = true;
        REQUEST_TARGET_CHARS[')'] = true;
        REQUEST_TARGET_CHARS['*'] = true;
        REQUEST_TARGET_CHARS['+'] = true;
        REQUEST_TARGET_CHARS[','] = true;
        REQUEST_TARGET_CHARS[';'] = true;
        REQUEST_TARGET_CHARS['='] = true;
        // Additional: : @ / ? # [ ]
        REQUEST_TARGET_CHARS[':'] = true;
        REQUEST_TARGET_CHARS['@'] = true;
        REQUEST_TARGET_CHARS['/'] = true;
        REQUEST_TARGET_CHARS['?'] = true;
        REQUEST_TARGET_CHARS['#'] = true;
        REQUEST_TARGET_CHARS['['] = true;
        REQUEST_TARGET_CHARS[']'] = true;

        // Initialize header name characters
        // ALPHA
        for (char c = 'A'; c <= 'Z'; c++) {
            HEADER_NAME_CHARS[c] = true;
        }
        for (char c = 'a'; c <= 'z'; c++) {
            HEADER_NAME_CHARS[c] = true;
        }
        // DIGIT
        for (char c = '0'; c <= '9'; c++) {
            HEADER_NAME_CHARS[c] = true;
        }
        // Hyphen, underscore, colon (for pseudo-headers)
        HEADER_NAME_CHARS['-'] = true;
        HEADER_NAME_CHARS['_'] = true;
        HEADER_NAME_CHARS[':'] = true;

        // Initialize header value characters
        // Space through tilde (visible ASCII)
        for (int c = 0x20; c <= 0x7E; c++) {
            HEADER_VALUE_CHARS[c] = true;
        }
        // High bytes (obs-text)
        for (int c = 0x80; c <= 0xFF; c++) {
            HEADER_VALUE_CHARS[c] = true;
        }
        // Tab (HTAB)
        HEADER_VALUE_CHARS['\t'] = true;
    }

    private HTTPUtils() {
        // Utility class - prevent instantiation
    }

    /**
     * Validates an HTTP method token.
     *
     * <p>A valid token consists of one or more of the following characters:
     * {@code !#$%&'*+-.^_`|~0-9a-zA-Z}
     *
     * @param method the method string to validate
     * @return true if valid, false otherwise
     */
    public static boolean isValidMethod(String method) {
        if (method == null || method.isEmpty()) {
            return false;
        }
        int len = method.length();
        for (int i = 0; i < len; i++) {
            char c = method.charAt(i);
            if (c >= 128 || !TOKEN_CHARS[c]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates an HTTP method token.
     *
     * <p>A valid token consists of one or more of the following characters:
     * {@code !#$%&'*+-.^_`|~0-9a-zA-Z}
     *
     * @param method the method CharSequence to validate
     * @return true if valid, false otherwise
     */
    public static boolean isValidMethod(CharSequence method) {
        if (method == null || method.length() == 0) {
            return false;
        }
        int len = method.length();
        for (int i = 0; i < len; i++) {
            char c = method.charAt(i);
            if (c >= 128 || !TOKEN_CHARS[c]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates an HTTP request-target.
     *
     * <p>Valid characters include alphanumerics, unreserved characters,
     * percent-encoded sequences, sub-delimiters, and path/query characters.
     *
     * @param target the request-target string to validate
     * @return true if valid, false otherwise
     */
    public static boolean isValidRequestTarget(String target) {
        if (target == null || target.isEmpty()) {
            return false;
        }
        int len = target.length();
        for (int i = 0; i < len; i++) {
            char c = target.charAt(i);
            if (c >= 128 || !REQUEST_TARGET_CHARS[c]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates an HTTP request-target.
     *
     * <p>Valid characters include alphanumerics, unreserved characters,
     * percent-encoded sequences, sub-delimiters, and path/query characters.
     *
     * @param target the request-target CharSequence to validate
     * @return true if valid, false otherwise
     */
    public static boolean isValidRequestTarget(CharSequence target) {
        if (target == null || target.length() == 0) {
            return false;
        }
        int len = target.length();
        for (int i = 0; i < len; i++) {
            char c = target.charAt(i);
            if (c >= 128 || !REQUEST_TARGET_CHARS[c]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if a character is a valid token character.
     *
     * @param c the character to check
     * @return true if valid token character
     */
    public static boolean isTokenChar(char c) {
        return c < 128 && TOKEN_CHARS[c];
    }

    /**
     * Checks if a character is valid in a request-target.
     *
     * @param c the character to check
     * @return true if valid request-target character
     */
    public static boolean isRequestTargetChar(char c) {
        return c < 128 && REQUEST_TARGET_CHARS[c];
    }

    /**
     * Validates an HTTP header name.
     *
     * <p>A valid header name starts with an optional colon (for pseudo-headers)
     * followed by one or more of: {@code a-zA-Z0-9-_}
     *
     * @param name the header name to validate
     * @return true if valid, false otherwise
     */
    public static boolean isValidHeaderName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        int len = name.length();
        int start = 0;
        
        // Allow optional leading colon for pseudo-headers like :status, :path
        if (name.charAt(0) == ':') {
            if (len == 1) {
                return false; // Just ":" is not valid
            }
            start = 1;
        }
        
        // Rest must be alphanumeric, hyphen, or underscore
        for (int i = start; i < len; i++) {
            char c = name.charAt(i);
            if (c >= 128) {
                return false;
            }
            // Only allow alphanumeric, hyphen, underscore (not colon in the middle)
            if (c == ':') {
                return false;
            }
            if (!HEADER_NAME_CHARS[c]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates an HTTP header value.
     *
     * <p>A valid header value contains only visible ASCII characters (0x20-0x7E),
     * high-byte characters (0x80-0xFF) for backwards compatibility, and tab.
     * Control characters other than tab are not allowed.
     *
     * @param value the header value to validate (may be null)
     * @return true if valid or null, false otherwise
     */
    public static boolean isValidHeaderValue(String value) {
        if (value == null) {
            return true; // Null values are allowed
        }
        int len = value.length();
        for (int i = 0; i < len; i++) {
            char c = value.charAt(i);
            if (c >= 256 || !HEADER_VALUE_CHARS[c]) {
                return false;
            }
        }
        return true;
    }

}

