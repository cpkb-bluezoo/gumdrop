/*
 * MailFromState.java
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

/**
 * Operations for responding to MAIL FROM.
 * 
 * <p>This interface is provided to {@link MailFromHandler#mailFrom} and
 * allows the handler to accept or reject the sender address.
 * 
 * <p>Accepting transitions to the recipient state where RCPT TO commands
 * are expected. Various rejection methods are available with appropriate
 * SMTP response codes for different scenarios.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see MailFromHandler#mailFrom
 * @see RecipientHandler
 */
public interface MailFromState {

    /**
     * Accepts the sender (250 response).
     * 
     * @param handler receives RCPT TO commands
     */
    void acceptSender(RecipientHandler handler);

    /**
     * Temporarily rejects - greylisting (450 response).
     * 
     * <p>Used to implement greylisting. The client should retry later.
     * 
     * @param handler receives retry
     */
    void rejectSenderGreylist(MailFromHandler handler);

    /**
     * Temporarily rejects - rate limit exceeded (450 response).
     * 
     * @param handler receives retry
     */
    void rejectSenderRateLimit(MailFromHandler handler);

    /**
     * Temporarily rejects - storage full (452 response).
     * 
     * @param handler receives retry
     */
    void rejectSenderStorageFull(MailFromHandler handler);

    /**
     * Permanently rejects - blocked domain (550 response).
     * 
     * @param handler receives next attempt
     */
    void rejectSenderBlockedDomain(MailFromHandler handler);

    /**
     * Permanently rejects - invalid domain (550 response).
     * 
     * @param handler receives next attempt
     */
    void rejectSenderInvalidDomain(MailFromHandler handler);

    /**
     * Permanently rejects - policy violation (553 response).
     * 
     * @param message custom rejection message
     * @param handler receives next attempt
     */
    void rejectSenderPolicy(String message, MailFromHandler handler);

    /**
     * Permanently rejects - known spam source (554 response).
     * 
     * @param handler receives next attempt
     */
    void rejectSenderSpam(MailFromHandler handler);

    /**
     * Permanently rejects - syntax error (501 response).
     * 
     * @param handler receives next attempt
     */
    void rejectSenderSyntax(MailFromHandler handler);

    /**
     * Server is shutting down (421 response).
     * 
     * <p>Closes the connection after sending the response.
     */
    void serverShuttingDown();

}

