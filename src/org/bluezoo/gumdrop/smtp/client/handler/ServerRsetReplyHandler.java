/*
 * ServerRsetReplyHandler.java
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
 * Handler for RSET command response.
 * 
 * <p>This handler receives the server's response to a RSET command. RSET
 * aborts the current mail transaction and returns to the session state,
 * ready for a new MAIL FROM command.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientEnvelope#rset
 * @see ClientEnvelopeReady#rset
 */
public interface ServerRsetReplyHandler extends ServerReplyHandler {

    /**
     * Called when the reset is acknowledged (250).
     * 
     * <p>The current transaction has been aborted. The handler can start
     * a new transaction or quit.
     * 
     * @param session operations to start a new transaction or quit
     */
    void handleResetOk(ClientSession session);

}

