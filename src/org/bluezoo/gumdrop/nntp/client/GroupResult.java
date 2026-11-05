/*
 * GroupResult.java
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
 * The reply to GROUP (RFC 3977 section 6.1.1).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class GroupResult {

    private final long count;
    private final long first;
    private final long last;
    private final String name;

    GroupResult(long count, long first, long last, String name) {
        this.count = count;
        this.first = first;
        this.last = last;
        this.name = name;
    }

    /**
     * Parses the text of a 211 response.
     *
     * @param text the text after the code
     * @return the result, or null if it is malformed
     */
    static GroupResult parse(String text) {
        String[] f = text.trim().split("[ \t]+");
        if (f.length < 4) {
            return null;
        }
        try {
            return new GroupResult(Long.parseLong(f[0]), Long.parseLong(f[1]),
                    Long.parseLong(f[2]), f[3]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Returns the estimated number of articles.
     *
     * @return the count
     */
    public long getCount() {
        return count;
    }

    /**
     * Returns the lowest article number.
     *
     * @return the number
     */
    public long getFirst() {
        return first;
    }

    /**
     * Returns the highest article number.
     *
     * @return the number
     */
    public long getLast() {
        return last;
    }

    /**
     * Returns the newsgroup name.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

}
