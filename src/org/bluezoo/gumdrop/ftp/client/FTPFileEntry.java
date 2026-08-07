/*
 * FTPFileEntry.java
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

package org.bluezoo.gumdrop.ftp.client;

import java.util.HashMap;
import java.util.Map;

/**
 * A single entry from a directory listing (LIST, NLST, or MLSD).
 *
 * <p>The counterpart, server-side formatter is {@code FTPFileInfo}
 * ({@code formatAsListingLine()} / {@code formatAsMLSEntry()}); this class
 * parses the inverse of those two formats, plus NLST's bare-name lines.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc959">RFC 959</a> §4.1.3 (LIST/NLST)
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3659">RFC 3659</a> §7 (MLSD)
 */
public final class FTPFileEntry {

    private final String name;
    private final long size;
    private final boolean directory;
    private final Map<String, String> facts;
    private final String rawLine;

    private FTPFileEntry(String name, long size, boolean directory,
            Map<String, String> facts, String rawLine) {
        this.name = name;
        this.size = size;
        this.directory = directory;
        this.facts = facts;
        this.rawLine = rawLine;
    }

    /**
     * Returns the entry's filename.
     *
     * @return the filename
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the entry's size in bytes, or {@code -1} if unknown (e.g. a
     * bare NLST name, or a LIST line whose size field could not be
     * parsed).
     *
     * @return the size in bytes, or -1 if unknown
     */
    public long getSize() {
        return size;
    }

    /**
     * Returns whether this entry is a directory. Unreliable for NLST
     * (which carries no type information) and best-effort for LIST
     * (parsed from the leading {@code d}/{@code -} in the Unix-style
     * permissions field).
     *
     * @return true if this entry is a directory
     */
    public boolean isDirectory() {
        return directory;
    }

    /**
     * Returns an MLSD fact value (RFC 3659 §7.5.1), e.g. {@code "modify"},
     * {@code "perm"}, {@code "type"}. Empty for LIST/NLST entries, which
     * carry no structured facts.
     *
     * @param name the fact name, case-insensitive
     * @return the fact value, or null if not present
     */
    public String getFact(String name) {
        return facts.get(name.toLowerCase());
    }

    /**
     * Returns the original, unparsed listing line.
     *
     * @return the raw line
     */
    public String getRawLine() {
        return rawLine;
    }

    @Override
    public String toString() {
        return rawLine;
    }

    /**
     * Parses a bare NLST line (RFC 959 §4.1.3) — just a filename, with no
     * size, type, or other metadata.
     *
     * @param line the NLST line
     * @return the parsed entry
     */
    static FTPFileEntry parseNlstLine(String line) {
        return new FTPFileEntry(line, -1, false,
                java.util.Collections.<String, String>emptyMap(), line);
    }

    /**
     * Parses one MLSD entry (RFC 3659 §7.3): {@code fact=value;
     * fact=value; ... SPACE filename}.
     *
     * @param line the MLSD line
     * @return the parsed entry
     */
    static FTPFileEntry parseMlsdLine(String line) {
        int sp = line.indexOf(' ');
        String factsPart = sp >= 0 ? line.substring(0, sp) : line;
        String filename = sp >= 0 ? line.substring(sp + 1) : "";

        Map<String, String> facts = new HashMap<String, String>();
        for (String fact : factsPart.split(";")) {
            if (fact.isEmpty()) {
                continue;
            }
            int eq = fact.indexOf('=');
            if (eq > 0) {
                facts.put(fact.substring(0, eq).toLowerCase(),
                        fact.substring(eq + 1));
            }
        }

        long size = -1;
        String sizeFact = facts.get("size");
        if (sizeFact != null) {
            try {
                size = Long.parseLong(sizeFact);
            } catch (NumberFormatException e) {
                // Leave as -1
            }
        }
        String type = facts.get("type");
        boolean directory = "dir".equalsIgnoreCase(type)
                || "cdir".equalsIgnoreCase(type)
                || "pdir".equalsIgnoreCase(type);

        return new FTPFileEntry(filename, size, directory, facts, line);
    }

    /**
     * Best-effort parse of one Unix-style LIST line, e.g.:
     * {@code -rw-r--r-- 1 owner group 1234 Jan 01 00:00 name}.
     *
     * <p>LIST's format is not standardised by RFC 959 — this handles the
     * common {@code ls -l}-style output that most FTP servers (including
     * this project's own {@code FTPFileInfo.formatAsListingLine()})
     * produce, but is not guaranteed to parse every server's format.
     * Fields that cannot be located are left at their default (name is
     * the whole line, size is -1, directory is false).
     *
     * @param line the LIST line
     * @return the parsed entry
     */
    static FTPFileEntry parseListLine(String line) {
        Map<String, String> facts = java.util.Collections.emptyMap();
        if (line.isEmpty()) {
            return new FTPFileEntry(line, -1, false, facts, line);
        }

        boolean directory = line.charAt(0) == 'd';

        // Split on runs of whitespace: perms links owner group size month
        // day time/year name... (name may itself contain spaces, so once
        // 8 fields are consumed the remainder — untrimmed except for the
        // single separating space — is the name).
        String[] parts = line.trim().split("\\s+", 9);
        long size = -1;
        String name = line;
        if (parts.length == 9) {
            try {
                size = Long.parseLong(parts[4]);
            } catch (NumberFormatException e) {
                // Leave as -1
            }
            name = parts[8];
        }

        return new FTPFileEntry(name, size, directory, facts, line);
    }
}
