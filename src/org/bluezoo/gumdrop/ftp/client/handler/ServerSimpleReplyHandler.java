/*
 * ServerSimpleReplyHandler.java
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
 * Handler for commands whose reply is a plain success/failure with no
 * further structured data: CDUP, TYPE, STRU, MODE, DELE, RMD (RFC 959
 * §4.1.2, §4.1.3).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientAuthenticatedState
 */
public interface ServerSimpleReplyHandler extends ServerReplyHandler {

    /**
     * Called when the server accepts the command (2xx).
     *
     * @param authenticated operations available for further commands
     */
    void handleOk(ClientAuthenticatedState authenticated);

    /**
     * Called when the server rejects the command (4xx/5xx).
     *
     * @param authenticated operations available for further commands
     * @param code the FTP reply code
     * @param message the server's error message
     */
    void handleError(ClientAuthenticatedState authenticated, int code, String message);

}
