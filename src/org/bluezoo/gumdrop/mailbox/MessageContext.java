/*
 * MessageContext.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.mailbox;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.Collections;

/**
 * Context providing access to message data during search evaluation.
 * 
 * <p>This interface allows {@link SearchCriteria} implementations to
 * access message attributes, headers, and content without requiring
 * the full message to be loaded into memory.
 * 
 * <p>Implementations should load data lazily where possible to
 * optimize search performance for large mailboxes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SearchCriteria
 */
public interface MessageContext {

    /**
     * Returns the message sequence number (1-based).
     * 
     * @return the message sequence number
     */
    int getMessageNumber();

    /**
     * Returns the message UID.
     * 
     * @return the unique identifier
     */
    long getUID();

    /**
     * Returns the message size in octets.
     * 
     * @return the message size
     */
    long getSize();

    /**
     * Returns the message system flags.
     * 
     * @return set of system flags
     */
    Set<Flag> getFlags();

    /**
     * Returns the message keyword flags.
     * Keywords are user-defined flags (non-system flags).
     * 
     * @return set of keyword strings, empty if none
     */
    default Set<String> getKeywords() {
        return Collections.emptySet();
    }

    /**
     * Returns the internal date of the message.
     * This is the date the message was received by the server.
     * 
     * @return the internal date, or null if not available
     */
    OffsetDateTime getInternalDate();

    /**
     * Returns the value of the first header with the given name.
     * Header names are case-insensitive.
     * 
     * @param name the header name (e.g., "Subject", "From")
     * @return the header value, or null if not present
     * @throws IOException if headers cannot be read
     */
    String getHeader(String name) throws IOException;

    /**
     * Returns all values for headers with the given name.
     * Header names are case-insensitive.
     * 
     * @param name the header name
     * @return list of header values, empty if none present
     * @throws IOException if headers cannot be read
     */
    List<String> getHeaders(String name) throws IOException;

    /**
     * Returns the sent date from the Date header.
     * 
     * @return the sent date, or null if not available or unparseable
     * @throws IOException if headers cannot be read
     */
    OffsetDateTime getSentDate() throws IOException;

    /**
     * Returns the internal date as a LocalDate for date comparisons.
     * The date is extracted in the original timezone.
     * 
     * @return the internal date as LocalDate, or null if not available
     */
    default LocalDate getInternalLocalDate() {
        OffsetDateTime dateTime = getInternalDate();
        if (dateTime == null) {
            return null;
        }
        return dateTime.toLocalDate();
    }

    /**
     * Returns the sent date as a LocalDate for date comparisons.
     * The date is extracted in the original timezone.
     * 
     * @return the sent date as LocalDate, or null if not available
     * @throws IOException if headers cannot be read
     */
    default LocalDate getSentLocalDate() throws IOException {
        OffsetDateTime dateTime = getSentDate();
        if (dateTime == null) {
            return null;
        }
        return dateTime.toLocalDate();
    }

    /**
     * Returns the message headers as text for TEXT search.
     * 
     * @return the headers as a character sequence
     * @throws IOException if headers cannot be read
     */
    CharSequence getHeadersText() throws IOException;

    /**
     * Returns the message body as text for BODY/TEXT search.
     * 
     * <p>For MIME messages, this should return the decoded text content
     * of all text parts concatenated.
     * 
     * @return the body text as a character sequence
     * @throws IOException if body cannot be read
     */
    CharSequence getBodyText() throws IOException;

}

