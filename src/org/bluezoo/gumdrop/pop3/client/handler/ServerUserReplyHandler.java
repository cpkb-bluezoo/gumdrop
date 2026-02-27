/*
 * ServerUserReplyHandler.java
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
 * Handler for USER command response.
 *
 * <p>On success, the handler receives a {@link ClientPasswordState} to
 * continue with the PASS command. On failure, the handler returns to
 * the AUTHORIZATION state.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientAuthorizationState#user
 * @see ClientPasswordState
 */
public interface ServerUserReplyHandler extends ServerReplyHandler {

    /**
     * Called when the server accepts the username (+OK).
     *
     * <p>The handler should now send the password using
     * {@link ClientPasswordState#pass}.
     *
     * @param pass operations to send the password
     */
    void handleUserAccepted(ClientPasswordState pass);

    /**
     * Called when the server rejects the username (-ERR).
     *
     * @param auth operations to retry or try a different approach
     * @param message the server's error message
     */
    void handleRejected(ClientAuthorizationState auth, String message);

}
