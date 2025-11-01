/*
 * HTTPVersion.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
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
