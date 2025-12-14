/*
 * ServerAuthAbortHandler.java
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
 * Handler for AUTH abort response.
 * 
 * <p>This handler receives the server's response when the client aborts
 * authentication by sending "*" during a SASL exchange.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientAuthExchange#abort(ServerAuthAbortHandler)
 */
public interface ServerAuthAbortHandler extends ServerReplyHandler {

    /**
     * Called when authentication abort is acknowledged.
     * 
     * <p>The handler can continue without authentication or try a different
     * authentication approach.
     * 
     * @param session operations to continue without authentication
     */
    void handleAborted(ClientSession session);

}

