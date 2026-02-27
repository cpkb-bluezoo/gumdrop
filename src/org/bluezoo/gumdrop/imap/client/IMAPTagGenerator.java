/*
 * IMAPTagGenerator.java
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

package org.bluezoo.gumdrop.imap.client;

/**
 * Generates unique command tags for IMAP client commands.
 * Tags follow the format A001, A002, ..., A999, B000, etc.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class IMAPTagGenerator {

    private char prefix = 'A';
    private int counter = 0;

    String next() {
        String tag = String.format("%c%03d", prefix, counter);
        counter++;
        if (counter > 999) {
            counter = 0;
            prefix++;
            if (prefix > 'Z') {
                prefix = 'A';
            }
        }
        return tag;
    }

    void reset() {
        prefix = 'A';
        counter = 0;
    }
}
