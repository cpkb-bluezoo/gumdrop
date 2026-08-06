/*
 * ServerPortReplyHandler.java
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
 * Handler for PORT/EPRT command responses. RFC 959 §4.1.2 (PORT); RFC 2428
 * §2 (EPRT).
 *
 * <p>On success, a subsequent call to {@link ClientAuthenticatedState#retr},
 * {@link ClientAuthenticatedState#stor}, {@link
 * ClientAuthenticatedState#appe}, or one of the listing commands with a
 * {@code null} data address uses the listener this call opened, rather
 * than connecting out as PASV/EPSV do.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientAuthenticatedState#port
 * @see ClientAuthenticatedState#eprt
 */
public interface ServerPortReplyHandler extends ServerReplyHandler {

    /**
     * Called when the server accepts the address (2xx).
     *
     * @param authenticated operations available for further commands
     */
    void handleOk(ClientAuthenticatedState authenticated);

    /**
     * Called when the server rejects the command (4xx/5xx), or the local
     * listener could not be opened (code {@code 0}).
     *
     * @param authenticated operations available for further commands
     * @param code the FTP reply code, or {@code 0} for a local setup
     *      failure
     * @param message the error message
     */
    void handleError(ClientAuthenticatedState authenticated, int code, String message);

}
