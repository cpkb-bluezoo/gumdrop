/*
 * ImapCharset.java
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

package org.bluezoo.gumdrop.imap;

import java.util.Locale;

/**
 * Charset names for SORT/THREAD (RFC 5256) and related extensions.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ImapCharset {

    private ImapCharset() {
    }

    /**
     * Returns true if this server implements the charset for SORT/THREAD.
     * RFC 5256 requires US-ASCII and UTF-8.
     */
    public static boolean isSortThreadSupported(String charset) {
        if (charset == null) {
            return false;
        }
        String norm = normalize(charset);
        return "US-ASCII".equals(norm) || "UTF-8".equals(norm);
    }

    public static String normalize(String charset) {
        return charset.trim().toUpperCase(Locale.ROOT);
    }
}
