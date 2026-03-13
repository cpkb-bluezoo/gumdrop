/*
 * ClientEnvelopeState.java
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

package org.bluezoo.gumdrop.smtp.client.handler;

import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;

/**
 * Common operations available during envelope construction.
 * RFC 5321 §4.1.1.3 (RCPT TO and RSET).
 *
 * <p>This interface provides the operations common to both
 * {@link ClientEnvelope} (before any recipients accepted) and
 * {@link ClientEnvelopeReady} (after recipients accepted).
 * 
 * <p>This interface is passed to {@link ServerRcptToReplyHandler} failure
 * handlers where the caller may or may not have accepted recipients, and
 * can check via {@link #hasAcceptedRecipients()}.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientEnvelope
 * @see ClientEnvelopeReady
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5321">RFC 5321</a>
 */
public interface ClientEnvelopeState {

    /**
     * Adds a recipient to the envelope.
     * 
     * @param recipient the envelope recipient address
     * @param callback receives the server's response
     */
    void rcptTo(EmailAddress recipient, ServerRcptToReplyHandler callback);

    /**
     * RFC 3461 §4.1–4.2 — adds a recipient with DSN parameters.
     *
     * @param recipient the envelope recipient address
     * @param notify NOTIFY value (e.g. "NEVER" or "SUCCESS,FAILURE,DELAY"), or null
     * @param orcpt ORCPT value (e.g. "rfc822;user@example.com"), or null
     * @param callback receives the server's response
     */
    void rcptTo(EmailAddress recipient, String notify, String orcpt,
                ServerRcptToReplyHandler callback);

    /**
     * Aborts the current mail transaction.
     * 
     * <p>Sends RSET to clear the transaction and return to the session state.
     * 
     * @param callback receives the server's response
     */
    void rset(ServerRsetReplyHandler callback);

    /**
     * Closes the connection.
     */
    void quit();

    /**
     * Checks if any recipients have been accepted.
     * 
     * <p>This is useful for handlers that receive this interface after a
     * recipient rejection - they can check if previous recipients were
     * accepted and thus whether {@code data()} would be available after
     * casting to {@link ClientEnvelopeReady}.
     * 
     * @return true if at least one recipient has been accepted
     */
    boolean hasAcceptedRecipients();

}

