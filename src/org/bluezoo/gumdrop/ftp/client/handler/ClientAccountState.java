/*
 * ClientAccountState.java
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

package org.bluezoo.gumdrop.ftp.client.handler;

/**
 * Operations available after the server requests an account. RFC 959
 * §4.1.1 (332 need account for login).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerUserReplyHandler#handleAccountRequired
 * @see ServerPassReplyHandler#handleAccountRequired
 */
public interface ClientAccountState {

    /**
     * Sends an ACCT command with the account information.
     *
     * @param account the account information
     * @param callback receives the server's response
     */
    void acct(String account, ServerAcctReplyHandler callback);

    /**
     * Closes the connection without completing authentication.
     */
    void quit();

}
