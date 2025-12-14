/*
 * AuthenticateState.java
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
 * Operations available when responding to successful SASL authentication.
 * 
 * <p>This interface is provided to {@link HelloHandler#authenticated} after
 * the connection has completed SASL authentication. The handler receives
 * the authenticated Principal and decides whether to accept it.
 * 
 * <p>The SASL mechanics (challenge/response exchange) are handled by the
 * connection. The handler only deals with the policy decision of whether
 * to accept the authenticated identity.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HelloHandler#authenticated
 */
public interface AuthenticateState {

    /**
     * Accepts the authenticated principal.
     * 
     * <p>Sends a 235 success response and transitions to mail-from state.
     * 
     * @param handler receives subsequent MAIL FROM commands
     */
    void accept(MailFromHandler handler);

    /**
     * Rejects the authenticated principal.
     * 
     * <p>Sends a 535 authentication failed response. The client may
     * retry authentication.
     * 
     * @param handler receives retry or other commands
     */
    void reject(HelloHandler handler);

    /**
     * Rejects and closes the connection.
     * 
     * <p>Sends a 535 response and closes the connection. Use this for
     * repeated authentication failures or policy violations.
     */
    void rejectAndClose();

    /**
     * Server is shutting down.
     */
    void serverShuttingDown();

}

