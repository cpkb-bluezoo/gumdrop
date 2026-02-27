/*
 * ClientPasswordState.java
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

package org.bluezoo.gumdrop.pop3.client.handler;

/**
 * Operations available after a USER command has been accepted.
 *
 * <p>This interface is provided to the handler in
 * {@link ServerUserReplyHandler#handleUserAccepted} and allows the
 * handler to complete authentication by sending the PASS command.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerUserReplyHandler#handleUserAccepted
 */
public interface ClientPasswordState {

    /**
     * Sends a PASS command with the user's password.
     *
     * <p>On success, the handler receives a {@link ClientTransactionState}
     * for mailbox operations. On failure, the handler returns to the
     * {@link ClientAuthorizationState}.
     *
     * @param password the password
     * @param callback receives the server's response
     */
    void pass(String password, ServerPassReplyHandler callback);

    /**
     * Closes the connection without completing authentication.
     */
    void quit();

}
