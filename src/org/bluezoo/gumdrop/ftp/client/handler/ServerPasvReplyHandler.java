/*
 * ServerPasvReplyHandler.java
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

import java.net.InetSocketAddress;

/**
 * Handler for PASV command response. RFC 959 §4.1.2.
 *
 * <p>The returned address is passed to {@link
 * ClientAuthenticatedState#retr}, {@link ClientAuthenticatedState#stor},
 * {@link ClientAuthenticatedState#appe}, or one of the listing commands to
 * open the data connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientAuthenticatedState#pasv
 */
public interface ServerPasvReplyHandler extends ServerReplyHandler {

    /**
     * Called when the server enters passive mode (227).
     *
     * @param dataAddress the address to connect the data connection to
     * @param authenticated operations available for further commands
     */
    void handlePassive(InetSocketAddress dataAddress, ClientAuthenticatedState authenticated);

    /**
     * Called when the server rejects the command (4xx/5xx).
     *
     * @param authenticated operations available for further commands
     * @param code the FTP reply code
     * @param message the server's error message
     */
    void handleError(ClientAuthenticatedState authenticated, int code, String message);

}
