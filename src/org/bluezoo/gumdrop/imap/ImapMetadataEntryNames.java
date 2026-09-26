/*
 * ImapMetadataEntryNames.java
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
 * RFC 5464 entry name validation and well-known entries.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ImapMetadataEntryNames {

    public static final String SHARED_ADMIN = "/shared/admin";

    private ImapMetadataEntryNames() {
    }

    public static boolean isValidEntryName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (name.contains("*") || name.contains("%")) {
            return false;
        }
        if (name.contains("//") || name.endsWith("/")) {
            return false;
        }
        if (!name.startsWith("/")) {
            return false;
        }
        if (ImapMetadataScope.fromEntryName(name) == null) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                return false;
            }
        }
        String[] parts = name.substring(1).split("/");
        if (parts.length < 2) {
            return false;
        }
        if (isVendorEntry(name)) {
            return parts.length >= 4;
        }
        return true;
    }

    public static boolean isReadOnly(String entryName) {
        return SHARED_ADMIN.equalsIgnoreCase(entryName);
    }

    public static String canonicalEntryName(String entryName) {
        if (entryName == null) {
            return null;
        }
        return entryName.toLowerCase(Locale.ROOT);
    }

    private static boolean isVendorEntry(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("/shared/vendor/")
                || lower.startsWith("/private/vendor/");
    }
}
