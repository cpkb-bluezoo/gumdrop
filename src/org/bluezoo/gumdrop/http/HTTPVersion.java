/*
 * HTTPVersion.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.http;

/**
 * Enum of possible HTTP versions.
 *
 * @author Chris Burdess
 */
public enum HTTPVersion {

    UNKNOWN(null),
    HTTP_1_0("HTTP/1.0"),
    HTTP_1_1("HTTP/1.1"),
    HTTP_2_0("HTTP/2.0");

    private final String s;

    private HTTPVersion(String s) {
        this.s = s;
    }

    public String toString() {
        return s == null ? "(unknown)" : s;
    }

    public static HTTPVersion fromString(String s) {
        for (HTTPVersion v : values()) {
            if (s != null && s.equals(v.s)) {
                return v;
            }
        }
        return UNKNOWN;
    }

}
