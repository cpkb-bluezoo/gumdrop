/*
 * ListState.java
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

package org.bluezoo.gumdrop.imap.handler;

import org.bluezoo.gumdrop.mailbox.MailboxAttribute;

import java.util.Set;

/**
 * Operations available when responding to LIST or LSUB commands.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see AuthenticatedHandler#list
 * @see AuthenticatedHandler#lsub
 */
public interface ListState {

    /**
     * Begins a list response and returns a writer.
     * 
     * @return a writer for sending list entries
     */
    ListWriter beginList();

    /**
     * Sends a mailbox entry in the list.
     * 
     * <p>Call this for each mailbox matching the pattern, then call
     * {@link #listComplete} to complete.
     * 
     * @param attributes the mailbox attributes
     * @param delimiter the hierarchy delimiter character
     * @param name the mailbox name
     */
    void listEntry(Set<MailboxAttribute> attributes, String delimiter, String name);

    /**
     * Completes the list successfully.
     * 
     * @param handler continues receiving authenticated commands
     */
    void listComplete(AuthenticatedHandler handler);

    /**
     * Writer for list entries.
     */
    interface ListWriter {

        /**
         * Adds a mailbox entry to the list.
         * 
         * @param attributes the mailbox attributes
         * @param delimiter the hierarchy delimiter character
         * @param name the mailbox name
         */
        void mailbox(Set<MailboxAttribute> attributes, String delimiter, String name);

        /**
         * Ends the list and completes successfully.
         * 
         * @param handler continues receiving authenticated commands
         */
        void end(AuthenticatedHandler handler);
    }

    /**
     * List failed.
     * 
     * @param message the error message
     * @param handler continues receiving authenticated commands
     */
    void listFailed(String message, AuthenticatedHandler handler);

    /**
     * Server is shutting down, close gracefully.
     * 
     * <p>Sends an untagged BYE response indicating the server is shutting
     * down and closes the connection.
     */
    void serverShuttingDown();

}
