/*
 * OverState.java
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
 * Responses to OVER and HDR (RFC 3977 sections 8.3 and 8.5). Entries are
 * written as they are found.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface OverState {

    /**
     * Starts an overview list.
     * @return the writer for the entries
     */
    OverviewWriter beginOverview();

    /**
     * Starts a header list.
     * @return the writer for the entries
     */
    HeaderWriter beginHeaders();

    /**
     * There are no such articles (423 or 430).
     * @param handler the handler for the next command
     */
    void notFound(ReaderHandler handler);

    /**
     * The listing could not be produced.
     * @param message the text after the response code
     * @param handler the handler for the next command
     */
    void error(String message, ReaderHandler handler);

    /** Writes the entries of an overview list. */
    interface OverviewWriter {

        /**
         * One article. Null header values are sent empty; tabs and line
         * breaks in values are replaced by spaces.
         *
         * @param number the article number, 0 when it was asked for by
         * message-id and is not known in the current group
         * @param subject the Subject header
         * @param from the From header
         * @param date the Date header
         * @param messageId the Message-ID header
         * @param references the References header
         * @param bytes the size of the article in octets
         * @param lines the number of lines in the body, negative if unknown
         */
        void entry(long number, String subject, String from, String date,
                   String messageId, String references, long bytes, long lines);

        /**
         * Ends the list.
         * @param handler the handler for the next command
         */
        void end(ReaderHandler handler);
    }

    /** Writes the entries of a header list. */
    interface HeaderWriter {

        /**
         * One article.
         * @param number the article number, 0 when asked for by message-id
         * @param value the header value, null if the article has no such header
         */
        void entry(long number, String value);

        /**
         * Ends the list.
         * @param handler the handler for the next command
         */
        void end(ReaderHandler handler);
    }

}
