/*
 * NewsgroupEntry.java
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
 * A line of LIST ACTIVE (RFC 3977 section 7.6.3): a newsgroup, its highest
 * and lowest article numbers and its posting status.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class NewsgroupEntry {

    private final String name;
    private final long high;
    private final long low;
    private final char status;

    NewsgroupEntry(String name, long high, long low, char status) {
        this.name = name;
        this.high = high;
        this.low = low;
        this.status = status;
    }

    /**
     * Parses a LIST ACTIVE line.
     *
     * @param line the line, without line ending
     * @return the entry, or null if the line is malformed
     */
    static NewsgroupEntry parse(String line) {
        String[] f = line.trim().split("[ \t]+");
        if (f.length < 4 || f[3].isEmpty()) {
            return null;
        }
        try {
            return new NewsgroupEntry(f[0], Long.parseLong(f[1]), Long.parseLong(f[2]), f[3].charAt(0));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Returns the newsgroup name.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the highest article number.
     *
     * @return the number
     */
    public long getHigh() {
        return high;
    }

    /**
     * Returns the lowest article number.
     *
     * @return the number
     */
    public long getLow() {
        return low;
    }

    /**
     * Returns the posting status: {@code y} (posting allowed), {@code n}
     * (not allowed), {@code m} (moderated), or another server-defined
     * character.
     *
     * @return the status
     */
    public char getStatus() {
        return status;
    }

}
