/*
 * ServerPassReplyHandler.java
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
 * Handler for PASS command response. RFC 959 §4.1.1.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientPasswordState#pass
 */
public interface ServerPassReplyHandler extends ServerReplyHandler {

    /**
     * Called when the server logs the user in (230).
     *
     * @param authenticated operations available now that the session is
     *      authenticated
     */
    void handleAuthenticated(ClientAuthenticatedState authenticated);

    /**
     * Called when the server requires account information to log in (332).
     *
     * @param acct operations to send the account information
     */
    void handleAccountRequired(ClientAccountState acct);

    /**
     * Called when the server rejects the password (530, etc).
     *
     * @param login operations to retry or try a different approach
     * @param message the server's error message
     */
    void handleAuthFailed(ClientLoginState login, String message);

}
