/*
 * NotAuthenticatedHandler.java
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

import java.security.Principal;

import org.bluezoo.gumdrop.mailbox.MailboxFactory;

/**
 * Handler for IMAP NOT_AUTHENTICATED state.
 * 
 * <p>This handler receives authentication events after the Realm has
 * verified client credentials. The handler's role is to make a policy
 * decision: should this authenticated principal be allowed access?
 * 
 * <p>The actual authentication mechanics (LOGIN command, AUTHENTICATE SASL)
 * are handled internally by the IMAPConnection using the configured Realm.
 * The handler only sees the result: an authenticated principal.
 * 
 * <p>Common policy decisions:
 * <ul>
 *   <li>Does the principal have a mailbox store?</li>
 *   <li>Is the mailbox store accessible?</li>
 *   <li>Is the account enabled or locked?</li>
 *   <li>IP-based or time-based access restrictions</li>
 * </ul>
 * 
 * <p>Protocol-level commands (CAPABILITY, NOOP, LOGOUT, STARTTLS) are handled
 * automatically by the server and don't involve the handler.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see AuthenticateState
 * @see ConnectedState#acceptConnection
 */
public interface NotAuthenticatedHandler {

    /**
     * Called after the Realm has authenticated a user.
     * 
     * <p>The connection has already verified credentials using the Realm
     * (via LOGIN command or AUTHENTICATE SASL). The handler now makes a
     * policy decision on whether to allow access.
     * 
     * <p>To accept, open the user's mailbox store using the factory and call
     * {@link AuthenticateState#accept}. To reject, call one of the reject
     * methods on the state.
     * 
     * @param principal the authenticated principal from the Realm
     * @param mailboxFactory factory for opening the user's mailbox store
     * @param state operations for accepting or rejecting
     */
    void authenticate(Principal principal, MailboxFactory mailboxFactory, 
                      AuthenticateState state);

}
