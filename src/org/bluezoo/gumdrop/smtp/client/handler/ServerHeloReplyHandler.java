/*
 * ServerHeloReplyHandler.java
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

/**
 * Handler for HELO command response.
 * 
 * <p>This handler receives the server's response to a HELO command. HELO is
 * used for basic SMTP without extensions. On success, the handler receives
 * a {@link ClientSession} interface for continuing the session.
 * 
 * <p>Note that after HELO (as opposed to EHLO), no extensions are available,
 * so STARTTLS and AUTH are not possible.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientSession
 * @see ServerEhloReplyHandler
 */
public interface ServerHeloReplyHandler extends ServerReplyHandler {

    /**
     * Called when the server accepts HELO (250).
     * 
     * <p>No extensions are available with basic SMTP. The handler can proceed
     * directly to sending mail via {@code session.mailFrom()}.
     * 
     * @param session operations available in the established session
     */
    void handleHelo(ClientSession session);

    /**
     * Called when the server rejects HELO (5xx).
     * 
     * <p>This indicates the server will not accept connections from this client.
     * The connection will be closed.
     * 
     * @param message the server's rejection message
     */
    void handlePermanentFailure(String message);

}

