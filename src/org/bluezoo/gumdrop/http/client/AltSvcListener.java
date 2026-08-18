/*
 * AltSvcListener.java
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

package org.bluezoo.gumdrop.http.client;

/**
 * Listener interface for Alt-Svc header notifications.
 *
 * <p>Implementations receive the raw Alt-Svc header value when it
 * appears in an HTTP response, enabling protocol upgrade discovery.
 *
 * <p>Also holds the shared, dependency-free Alt-Svc header parsing logic
 * (RFC 7838), so both {@code HTTPClient} and {@code WebSocketClient} (in a
 * different package) can reuse the same parser instead of duplicating it.
 */
public interface AltSvcListener {

    /**
     * Called when an Alt-Svc header is received in a response.
     *
     * @param value the raw Alt-Svc header value
     */
    void altSvcReceived(String value);

    /**
     * RFC 7838 section 3: default max-age (in seconds) assumed when an
     * Alt-Svc entry omits the {@code ma} parameter. Not an RFC-mandated
     * value -- a pragmatic 24-hour default.
     */
    long DEFAULT_MAX_AGE_SECONDS = 86400;

    /**
     * A parsed {@code h3} entry from an Alt-Svc header value.
     */
    final class H3Entry {
        /** Length of the host substring in the original header value (0 means same origin). */
        public final int hostLength;
        /** The advertised port. */
        public final int port;
        /** RFC 7838 section 3 {@code ma} parameter, or {@link #DEFAULT_MAX_AGE_SECONDS} if absent. */
        public final long maxAgeSeconds;

        H3Entry(int hostLength, int port, long maxAgeSeconds) {
            this.hostLength = hostLength;
            this.port = port;
            this.maxAgeSeconds = maxAgeSeconds;
        }
    }

    /**
     * Parses the h3 entry from an Alt-Svc header value.
     * Character-by-character parsing; no regex.
     *
     * <p>Looks for {@code h3="[host]:port"} in the value, and an optional
     * trailing {@code ; ma=NNN} parameter (RFC 7838 section 3).
     *
     * @param value the raw Alt-Svc header value
     * @return the parsed entry, or {@code null} if no h3 entry is found
     */
    static H3Entry parseAltSvcH3(String value) {
        int len = value.length();
        int i = 0;

        while (i < len) {
            while (i < len && (value.charAt(i) == ' '
                    || value.charAt(i) == '\t')) {
                i++;
            }

            if (i + 4 <= len
                    && value.charAt(i) == 'h'
                    && value.charAt(i + 1) == '3'
                    && value.charAt(i + 2) == '='
                    && value.charAt(i + 3) == '"') {
                i += 4;

                int hostStart = i;
                int colonPos = -1;

                while (i < len && value.charAt(i) != '"') {
                    if (value.charAt(i) == ':') {
                        colonPos = i;
                    }
                    i++;
                }

                if (i >= len || colonPos < 0) {
                    return null;
                }

                int hostLen = colonPos - hostStart;
                int portStart = colonPos + 1;
                int portEnd = i;

                int port = 0;
                for (int p = portStart; p < portEnd; p++) {
                    char c = value.charAt(p);
                    if (c < '0' || c > '9') {
                        return null;
                    }
                    port = port * 10 + (c - '0');
                }

                if (port <= 0 || port > 65535) {
                    return null;
                }

                i++; // past closing quote

                long maxAge = parseTrailingMaxAge(value, i);

                return new H3Entry(hostLen, port, maxAge);
            }

            while (i < len && value.charAt(i) != ',') {
                i++;
            }
            if (i < len) {
                i++;
            }
        }

        return null;
    }

    /**
     * Scans {@code ; name=value} parameters following an Alt-Svc entry
     * (starting at {@code start}, just past the closing quote of the
     * entry's value) for {@code ma} (RFC 7838 section 3), stopping at the
     * next comma-separated entry or end of string.
     */
    private static long parseTrailingMaxAge(String value, int start) {
        int len = value.length();
        int i = start;
        long maxAge = DEFAULT_MAX_AGE_SECONDS;
        while (i < len) {
            while (i < len && (value.charAt(i) == ' '
                    || value.charAt(i) == '\t')) {
                i++;
            }
            if (i >= len || value.charAt(i) != ';') {
                break;
            }
            i++;
            while (i < len && (value.charAt(i) == ' '
                    || value.charAt(i) == '\t')) {
                i++;
            }
            if (i + 3 <= len
                    && value.charAt(i) == 'm'
                    && value.charAt(i + 1) == 'a'
                    && value.charAt(i + 2) == '=') {
                i += 3;
                long ma = 0;
                boolean any = false;
                while (i < len && value.charAt(i) >= '0' && value.charAt(i) <= '9') {
                    ma = ma * 10 + (value.charAt(i) - '0');
                    i++;
                    any = true;
                }
                if (any) {
                    maxAge = ma;
                }
            } else {
                while (i < len && value.charAt(i) != ';' && value.charAt(i) != ',') {
                    i++;
                }
            }
        }
        return maxAge;
    }

    /**
     * Extracts the host portion from the h3 Alt-Svc entry.
     * Assumes the value starts with {@code h3="host:port"} and the
     * host is {@code hostLen} characters after the opening quote.
     *
     * @param value the raw Alt-Svc header value
     * @param hostLen the host substring length, from {@link H3Entry#hostLength}
     * @return the host substring, or {@code null} if not found
     */
    static String extractAltSvcHost(String value, int hostLen) {
        int i = 0;
        int len = value.length();
        while (i < len) {
            while (i < len && (value.charAt(i) == ' '
                    || value.charAt(i) == '\t')) {
                i++;
            }
            if (i + 4 <= len
                    && value.charAt(i) == 'h'
                    && value.charAt(i + 1) == '3'
                    && value.charAt(i + 2) == '='
                    && value.charAt(i + 3) == '"') {
                return value.substring(i + 4, i + 4 + hostLen);
            }
            while (i < len && value.charAt(i) != ',') {
                i++;
            }
            if (i < len) {
                i++;
            }
        }
        return null;
    }
}
