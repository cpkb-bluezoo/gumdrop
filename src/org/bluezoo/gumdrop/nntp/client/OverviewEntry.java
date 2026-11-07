/*
 * OverviewEntry.java
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

package org.bluezoo.gumdrop.nntp.client;

/**
 * A line of OVER output (RFC 3977 section 8.3): the standard overview
 * fields of one article. A field the server left empty is an empty string
 * (a number, 0).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class OverviewEntry {

    private final long number;
    private final String subject;
    private final String from;
    private final String date;
    private final String messageId;
    private final String references;
    private final long bytes;
    private final long lines;

    private OverviewEntry(String[] f) {
        number = Long.parseLong(f[0].trim());
        subject = field(f, 1);
        from = field(f, 2);
        date = field(f, 3);
        messageId = field(f, 4);
        references = field(f, 5);
        bytes = number(f, 6);
        lines = number(f, 7);
    }

    /**
     * Parses a line of OVER output. Fields beyond the standard ones are
     * ignored.
     *
     * @param line the line, without line ending
     * @return the entry, or null if the line is malformed
     */
    static OverviewEntry parse(String line) {
        String[] f = line.split("\t", -1);
        if (f.length < 2) {
            return null;
        }
        try {
            return new OverviewEntry(f);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String field(String[] f, int i) {
        return i < f.length ? f[i] : "";
    }

    private static long number(String[] f, int i) {
        String s = field(f, i).trim();
        if (s.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** @return the article number */
    public long getNumber() {
        return number;
    }

    /** @return the Subject header */
    public String getSubject() {
        return subject;
    }

    /** @return the From header */
    public String getFrom() {
        return from;
    }

    /** @return the Date header */
    public String getDate() {
        return date;
    }

    /** @return the Message-ID header */
    public String getMessageId() {
        return messageId;
    }

    /** @return the References header */
    public String getReferences() {
        return references;
    }

    /** @return the size in octets (the :bytes metadata item) */
    public long getBytes() {
        return bytes;
    }

    /** @return the number of lines in the body (the :lines item) */
    public long getLines() {
        return lines;
    }

}
