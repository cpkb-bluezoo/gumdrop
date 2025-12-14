/*
 * AuthorizationHandler.java
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

import java.security.Principal;

import org.bluezoo.gumdrop.mailbox.MailboxFactory;

/**
 * Handler for POP3 AUTHORIZATION state.
 * 
 * <p>This handler receives authentication events after the Realm has
 * verified client credentials. The handler's role is to make a policy
 * decision: should this authenticated principal be allowed access?
 * 
 * <p>The actual authentication mechanics (USER/PASS, APOP, SASL) are
 * handled internally by the POP3Connection using the configured Realm.
 * The handler only sees the result: an authenticated principal.
 * 
 * <p>Common policy decisions:
 * <ul>
 *   <li>Account disabled or locked</li>
 *   <li>IP-based access restrictions</li>
 *   <li>Time-based access controls</li>
 *   <li>Concurrent session limits</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ConnectedState#acceptConnection
 * @see AuthenticateState
 */
public interface AuthorizationHandler {

    /**
     * Called after the Realm has authenticated a user.
     * 
     * <p>The connection has already verified credentials using the Realm.
     * The handler now makes a policy decision on whether to allow access.
     * 
     * <p>To accept, open the user's mailbox using the factory and call
     * {@link AuthenticateState#accept}. To reject, call one of the
     * reject methods on the state.
     * 
     * @param principal the authenticated principal from the Realm
     * @param mailboxFactory factory for opening the user's mailbox
     * @param state operations for accepting or rejecting
     */
    void authenticate(Principal principal, MailboxFactory mailboxFactory, 
                      AuthenticateState state);

}
