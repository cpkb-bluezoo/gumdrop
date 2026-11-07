/*
 * ListState.java
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

package org.bluezoo.gumdrop.nntp.server;

/**
 * Responses to the commands that return a list: LIST, NEWGROUPS and the
 * article numbers of LISTGROUP (RFC 3977 sections 6.1.2, 7.3 and 7.6).
 * Entries are written as they are found, so a list of any length is sent
 * without being held in memory.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ListState {

    /**
     * Starts the list: the response line is sent.
     * @return the writer for the entries
     */
    ListWriter begin();

    /**
     * The list could not be produced.
     * @param message the text after the response code
     * @param handler the handler for the next command
     */
    void error(String message, ReaderHandler handler);

    /** Writes the entries of a list. */
    interface ListWriter {

        /**
         * An entry of LIST ACTIVE or NEWGROUPS.
         * @param name the group name
         * @param high the highest article number
         * @param low the lowest article number
         * @param status {@code y} if posting is allowed, {@code n} if not,
         * {@code m} if moderated
         */
        void group(String name, long high, long low, char status);

        /**
         * An entry of LIST NEWSGROUPS.
         * @param name the group name
         * @param text its description
         */
        void description(String name, String text);

        /**
         * An entry of LISTGROUP.
         * @param number an article number
         */
        void article(long number);

        /**
         * Ends the list.
         * @param handler the handler for the next command
         */
        void end(ReaderHandler handler);
    }

}
