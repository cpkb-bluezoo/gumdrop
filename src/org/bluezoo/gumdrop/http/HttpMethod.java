/*
 * HttpMethod.java
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

package org.bluezoo.gumdrop.http;

import java.nio.ByteBuffer;

/**
 * An HTTP request method (RFC 9110 section 9).
 *
 * <p>This is an open enumeration: the methods Gumdrop knows are constants,
 * compared with {@code ==} like an enum, and any other valid method token is
 * carried as an extension instance holding its name. Extension methods are
 * real HTTP (every WebDAV method started as one), so a parser that could only
 * name a fixed set would have to refuse traffic that is perfectly valid.
 *
 * <p>Method names are case-sensitive (RFC 9110 section 9.1).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class HttpMethod {

    public static final HttpMethod GET = new HttpMethod("GET");
    public static final HttpMethod HEAD = new HttpMethod("HEAD");
    public static final HttpMethod POST = new HttpMethod("POST");
    public static final HttpMethod PUT = new HttpMethod("PUT");
    public static final HttpMethod DELETE = new HttpMethod("DELETE");
    public static final HttpMethod CONNECT = new HttpMethod("CONNECT");
    public static final HttpMethod OPTIONS = new HttpMethod("OPTIONS");
    public static final HttpMethod TRACE = new HttpMethod("TRACE");
    public static final HttpMethod PATCH = new HttpMethod("PATCH");

    /** RFC 4918 (WebDAV). */
    public static final HttpMethod PROPFIND = new HttpMethod("PROPFIND");
    public static final HttpMethod PROPPATCH = new HttpMethod("PROPPATCH");
    public static final HttpMethod MKCOL = new HttpMethod("MKCOL");
    public static final HttpMethod COPY = new HttpMethod("COPY");
    public static final HttpMethod MOVE = new HttpMethod("MOVE");
    public static final HttpMethod LOCK = new HttpMethod("LOCK");
    public static final HttpMethod UNLOCK = new HttpMethod("UNLOCK");

    private static final HttpMethod[] KNOWN = {
        GET, HEAD, POST, PUT, DELETE, CONNECT, OPTIONS, TRACE, PATCH,
        PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, LOCK, UNLOCK
    };

    private final String name;
    private final boolean known;

    private HttpMethod(String name) {
        this.name = name;
        this.known = true;
    }

    private HttpMethod(String name, boolean known) {
        this.name = name;
        this.known = known;
    }

    /**
     * Returns the method with the given name: the shared constant if Gumdrop
     * knows it, otherwise an extension method.
     *
     * @param name the method token
     * @return the method
     * @throws IllegalArgumentException if the name is not a token
     *     (RFC 9110 section 5.6.2)
     */
    public static HttpMethod of(String name) {
        for (int i = 0; i < KNOWN.length; i++) {
            if (KNOWN[i].name.equals(name)) {
                return KNOWN[i];
            }
        }
        if (!isToken(name)) {
            throw new IllegalArgumentException("Not an HTTP method token: '" + name + "'");
        }
        return new HttpMethod(name, false);
    }

    /**
     * Returns the method named by the octets from the position to the limit,
     * without consuming them and without allocating when the method is a
     * known one.
     *
     * @param token the method octets
     * @return the method
     * @throws IllegalArgumentException if the octets are not a token
     */
    public static HttpMethod of(ByteBuffer token) {
        int start = token.position();
        int length = token.remaining();
        for (int i = 0; i < KNOWN.length; i++) {
            String candidate = KNOWN[i].name;
            if (candidate.length() != length) {
                continue;
            }
            boolean match = true;
            for (int j = 0; j < length; j++) {
                if (token.get(start + j) != (byte) candidate.charAt(j)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return KNOWN[i];
            }
        }
        char[] chars = new char[length];
        for (int j = 0; j < length; j++) {
            chars[j] = (char) (token.get(start + j) & 0xFF);
        }
        return of(new String(chars));
    }

    /**
     * Returns the method name, e.g. {@code "GET"}.
     *
     * @return the name
     */
    public String name() {
        return name;
    }

    /**
     * Returns whether this is one of the methods Gumdrop knows, as opposed to
     * an extension method.
     *
     * @return true for a known method
     */
    public boolean isKnown() {
        return known;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof HttpMethod && ((HttpMethod) other).name.equals(name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }

    private static boolean isToken(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean tchar = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!tchar) {
                return false;
            }
        }
        return true;
    }

}
