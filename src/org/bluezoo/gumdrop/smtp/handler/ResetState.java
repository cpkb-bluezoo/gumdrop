/*
 * ResetState.java
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
 * Operations for responding to RSET command.
 * 
 * <p>RSET resets the mail transaction state, clearing sender and
 * recipients. The connection returns to the ready state.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ResetState {

    /**
     * Accepts the reset (250 response).
     * 
     * @param handler receives next transaction
     */
    void acceptReset(MailFromHandler handler);

    /**
     * Server is shutting down, close gracefully.
     * 
     * <p>Sends a 421 response indicating the server is shutting down
     * and closes the connection.
     */
    void serverShuttingDown();

}

