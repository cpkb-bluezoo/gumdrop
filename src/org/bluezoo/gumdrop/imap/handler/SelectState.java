/*
 * SelectState.java
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

import org.bluezoo.gumdrop.mailbox.Flag;
import org.bluezoo.gumdrop.mailbox.Mailbox;

import java.util.Set;

/**
 * Operations available when responding to SELECT or EXAMINE commands.
 * 
 * <p>This interface is provided to {@link AuthenticatedHandler#select} and
 * {@link AuthenticatedHandler#examine} for opening mailboxes.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see AuthenticatedHandler#select
 * @see SelectedHandler
 */
public interface SelectState {

    /**
     * Mailbox selected successfully.
     * 
     * <p>Sends the required untagged responses (FLAGS, EXISTS, RECENT,
     * UIDVALIDITY, UIDNEXT) and a tagged OK response.
     * 
     * @param mailbox the selected mailbox
     * @param readOnly true if the mailbox was opened read-only (EXAMINE)
     * @param flags the defined flags for this mailbox
     * @param permanentFlags the flags that can be permanently stored
     * @param exists the number of messages in the mailbox
     * @param recent the number of recent messages
     * @param uidValidity the UID validity value
     * @param uidNext the predicted next UID
     * @param handler receives commands for the selected mailbox
     */
    void selectOk(Mailbox mailbox, boolean readOnly, Set<Flag> flags, 
                  Set<Flag> permanentFlags, int exists, int recent,
                  long uidValidity, long uidNext, SelectedHandler handler);

    /**
     * Mailbox selected successfully, using mailbox API for details.
     * 
     * <p>Convenience method that queries the mailbox for EXISTS, RECENT,
     * UIDVALIDITY, and UIDNEXT.
     * 
     * @param mailbox the selected mailbox
     * @param readOnly true if the mailbox was opened read-only (EXAMINE)
     * @param flags the defined flags for this mailbox
     * @param handler receives commands for the selected mailbox
     */
    void selectOk(Mailbox mailbox, boolean readOnly, Set<Flag> flags, SelectedHandler handler);

    /**
     * Select failed for some reason other than mailbox not found.
     * 
     * @param message the error message
     * @param handler continues receiving authenticated commands
     */
    void selectFailed(String message, AuthenticatedHandler handler);

    /**
     * Mailbox does not exist.
     * 
     * @param message the error message
     * @param handler continues receiving authenticated commands
     */
    void mailboxNotFound(String message, AuthenticatedHandler handler);

    /**
     * Access denied to the mailbox.
     * 
     * @param message the error message
     * @param handler continues receiving authenticated commands
     */
    void accessDenied(String message, AuthenticatedHandler handler);

    /**
     * A NO response for other failures.
     * 
     * @param message the error message
     * @param handler continues receiving authenticated commands
     */
    void no(String message, AuthenticatedHandler handler);

    /**
     * Server is shutting down, close gracefully.
     * 
     * <p>Sends an untagged BYE response indicating the server is shutting
     * down and closes the connection.
     */
    void serverShuttingDown();

}
