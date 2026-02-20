/*
 * UidlState.java
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

package org.bluezoo.gumdrop.pop3.handler;

/**
 * Operations available when responding to a UIDL command.
 * 
 * <p>This interface is provided to {@link TransactionHandler#uidl} and
 * allows the handler to return unique identifiers for messages.
 * 
 * <p>UIDs must be unique within a mailbox and persist across sessions
 * (RFC 1939 Section 7).
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see TransactionHandler#uidl
 */
public interface UidlState {

    /**
     * Sends the UID for a single message.
     * 
     * <p>Sends a +OK response with the message number and unique ID:
     * {@code +OK nn uid}
     * 
     * @param messageNumber the message number
     * @param uid the unique identifier
     * @param handler continues receiving transaction commands
     */
    void sendUid(int messageNumber, String uid, TransactionHandler handler);

    /**
     * Begins a multi-line UID listing for all messages.
     * 
     * <p>Sends a +OK response header and returns a writer for sending
     * individual message UIDs. Call {@link UidlWriter#end(TransactionHandler)} when done.
     * 
     * @return a writer for sending message UIDs
     */
    UidlWriter beginListing();

    /**
     * The requested message does not exist.
     * 
     * @param handler continues receiving transaction commands
     */
    void noSuchMessage(TransactionHandler handler);

    /**
     * The requested message has been deleted.
     * 
     * @param handler continues receiving transaction commands
     */
    void messageDeleted(TransactionHandler handler);

    /**
     * Reports an error accessing the mailbox.
     * 
     * @param message the error message
     * @param handler continues receiving transaction commands
     */
    void error(String message, TransactionHandler handler);

    /**
     * Writer for multi-line UIDL response.
     */
    interface UidlWriter {

        /**
         * Adds a message UID to the listing.
         * 
         * @param messageNumber the message number
         * @param uid the unique identifier
         */
        void message(int messageNumber, String uid);

        /**
         * Ends the listing and returns to transaction state.
         * 
         * @param handler continues receiving transaction commands
         */
        void end(TransactionHandler handler);

    }

}

