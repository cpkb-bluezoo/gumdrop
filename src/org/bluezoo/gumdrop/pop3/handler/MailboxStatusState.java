/*
 * MailboxStatusState.java
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
 * Operations available when responding to a mailbox status request.
 * 
 * <p>This interface is provided to {@link TransactionHandler#mailboxStatus} and
 * allows the handler to return mailbox statistics.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see TransactionHandler#mailboxStatus
 */
public interface MailboxStatusState {

    /**
     * Sends mailbox statistics.
     * 
     * <p>Sends a +OK response with the message count and total size:
     * {@code +OK nn mm} where nn is message count and mm is size in octets.
     * 
     * @param messageCount number of messages (excluding deleted)
     * @param totalSize total size of all messages in octets
     * @param handler continues receiving transaction commands
     */
    void sendStatus(int messageCount, long totalSize, TransactionHandler handler);

    /**
     * Reports an error accessing the mailbox.
     * 
     * @param message the error message
     * @param handler continues receiving transaction commands
     */
    void error(String message, TransactionHandler handler);

}

