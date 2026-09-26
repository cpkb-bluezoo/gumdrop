/*
 * ImapMetadataScope.java
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

/**
 * RFC 5464 metadata scope prefix on an entry name.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum ImapMetadataScope {
    SHARED("/shared"),
    PRIVATE("/private");

    private final String prefix;

    ImapMetadataScope(String prefix) {
        this.prefix = prefix;
    }

    public String getPrefix() {
        return prefix;
    }

    public static ImapMetadataScope fromEntryName(String entryName) {
        if (entryName == null) {
            return null;
        }
        String lower = entryName.toLowerCase();
        if (lower.startsWith(SHARED.prefix + "/")
                || lower.equals(SHARED.prefix)) {
            return SHARED;
        }
        if (lower.startsWith(PRIVATE.prefix + "/")
                || lower.equals(PRIVATE.prefix)) {
            return PRIVATE;
        }
        return null;
    }
}
