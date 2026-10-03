/*
 * MimeSectionSpec.java
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

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.StringTokenizer;

/**
 * A parsed IMAP body section specifier (RFC 9051 section 6.4.5 syntax
 * {@code section-spec}): an optional part path followed by an optional
 * HEADER, HEADER.FIELDS, HEADER.FIELDS.NOT, TEXT or MIME qualifier.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class MimeSectionSpec {

    /** What of the addressed entity is selected. */
    enum Kind {
        /** The whole message (empty path) or the body of a part. */
        FULL,
        /** The header block of a message. */
        HEADER,
        /** Only the named header fields of a message. */
        HEADER_FIELDS,
        /** All header fields except the named ones. */
        HEADER_FIELDS_NOT,
        /** The body of a message. */
        TEXT,
        /** The MIME header block of a part. */
        MIME,
        /** The first text part (used for PREVIEW). */
        PREVIEW,
        /** The message structure (ENVELOPE, BODY, BODYSTRUCTURE). */
        STRUCTURE
    }

    final int[] path;
    final Kind kind;
    /** Lower-cased field names for the HEADER.FIELDS kinds. */
    final Set<String> fields;

    MimeSectionSpec(int[] path, Kind kind, Set<String> fields) {
        this.path = path;
        this.kind = kind;
        this.fields = fields;
    }

    /** The spec selecting the first text part. */
    static MimeSectionSpec preview() {
        return new MimeSectionSpec(new int[0], Kind.PREVIEW,
                new HashSet<String>());
    }

    /**
     * Parses the text between the brackets of a BODY[...] or BINARY[...]
     * item.
     *
     * @param section the section text
     * @param binary true for BINARY sections, which allow only a part path
     * @return the parsed spec, or null when malformed
     */
    static MimeSectionSpec parse(String section, boolean binary) {
        String text = section.trim();
        int pos = 0;
        int count = 0;
        int[] numbers = new int[16];
        while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
            int end = pos;
            while (end < text.length() && Character.isDigit(text.charAt(end))) {
                end++;
            }
            if (end - pos > 9) {
                return null;
            }
            int value = Integer.parseInt(text.substring(pos, end));
            if (value < 1 || count == numbers.length) {
                return null;
            }
            numbers[count++] = value;
            pos = end;
            if (pos < text.length() && text.charAt(pos) == '.') {
                pos++;
                if (pos == text.length()) {
                    return null;
                }
            } else if (pos < text.length()) {
                return null;
            }
        }
        int[] path = new int[count];
        System.arraycopy(numbers, 0, path, 0, count);
        String rest = text.substring(pos);
        if (rest.isEmpty()) {
            return new MimeSectionSpec(path, Kind.FULL, new HashSet<String>());
        }
        if (binary) {
            return null;
        }
        String upper = rest.toUpperCase(Locale.ENGLISH);
        if (upper.equals("HEADER")) {
            return new MimeSectionSpec(path, Kind.HEADER, new HashSet<String>());
        }
        if (upper.equals("TEXT")) {
            return new MimeSectionSpec(path, Kind.TEXT, new HashSet<String>());
        }
        if (upper.equals("MIME") && count > 0) {
            return new MimeSectionSpec(path, Kind.MIME, new HashSet<String>());
        }
        if (upper.startsWith("HEADER.FIELDS.NOT ")) {
            Set<String> names = parseFields(rest.substring(18));
            if (names == null) {
                return null;
            }
            return new MimeSectionSpec(path, Kind.HEADER_FIELDS_NOT, names);
        }
        if (upper.startsWith("HEADER.FIELDS ")) {
            Set<String> names = parseFields(rest.substring(14));
            if (names == null) {
                return null;
            }
            return new MimeSectionSpec(path, Kind.HEADER_FIELDS, names);
        }
        return null;
    }

    private static Set<String> parseFields(String list) {
        String trimmed = list.trim();
        if (!trimmed.startsWith("(") || !trimmed.endsWith(")")) {
            return null;
        }
        String inner = trimmed.substring(1, trimmed.length() - 1);
        Set<String> names = new HashSet<String>();
        StringTokenizer tokens = new StringTokenizer(inner);
        while (tokens.hasMoreTokens()) {
            String token = tokens.nextToken();
            if (token.length() >= 2 && token.startsWith("\"")
                    && token.endsWith("\"")) {
                token = token.substring(1, token.length() - 1);
            }
            if (!token.isEmpty()) {
                names.add(token.toLowerCase(Locale.ENGLISH));
            }
        }
        return names;
    }
}
