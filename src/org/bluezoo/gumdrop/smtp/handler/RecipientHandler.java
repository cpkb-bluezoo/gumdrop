/*
 * RecipientHandler.java
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

package org.bluezoo.gumdrop.smtp.handler;

import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;

/**
 * Handler for RCPT TO commands and message initiation.
 * 
 * <p>This handler is active after a sender has been accepted via MAIL FROM.
 * It receives recipient addresses and, once at least one recipient is
 * accepted, can receive message content via DATA or BDAT.
 * 
 * <p>The handler does not need to distinguish between DATA and BDAT
 * transports - this is handled transparently by the connection.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see RecipientState
 * @see MessageStartState
 */
public interface RecipientHandler {

    /**
     * Called when client sends RCPT TO.
     * 
     * <p>The handler should validate the recipient (mailbox exists, quotas,
     * relay permissions, etc.) and call the appropriate method on the
     * state interface.
     * 
     * <p>If the server is configured with a MailboxFactory for local delivery,
     * the handler can check if a mailbox exists for this recipient to make
     * appropriate policy decisions.
     * 
     * @param state operations for responding
     * @param recipient the recipient address
     * @param factory the mailbox factory for local delivery (may be null)
     */
    void rcptTo(RecipientState state, EmailAddress recipient, MailboxFactory factory);

    /**
     * Called when client sends DATA or BDAT to begin message transfer.
     * 
     * <p>This is only called when at least one recipient has been accepted.
     * The handler does not need to know whether the client used DATA or
     * BDAT - the transport difference is handled by the connection.
     * 
     * @param state operations for message transfer
     */
    void startMessage(MessageStartState state);

    /**
     * Called when client sends RSET.
     * 
     * @param state operations for responding
     */
    void reset(ResetState state);

    /**
     * Called when client sends QUIT.
     */
    void quit();

}

