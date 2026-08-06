/*
 * ServerListReplyHandler.java
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

import java.util.List;

import org.bluezoo.gumdrop.ftp.client.FTPFileEntry;

/**
 * Handler for a LIST/NLST/MLSD (directory listing) command. RFC 959
 * §4.1.3; RFC 3659 §7 (MLSD).
 *
 * <p>Unlike {@link ServerRetrReplyHandler}, listing data is small and
 * line-oriented, so it is buffered and delivered as a single parsed list
 * once the transfer completes, rather than streamed chunk-by-chunk.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientAuthenticatedState#list
 * @see ClientAuthenticatedState#nlst
 * @see ClientAuthenticatedState#mlsd
 */
public interface ServerListReplyHandler extends ServerReplyHandler {

    /**
     * Called once the listing has been fully received and parsed.
     *
     * @param entries the parsed directory entries
     * @param authenticated operations available for further commands
     */
    void handleEntries(List<FTPFileEntry> entries, ClientAuthenticatedState authenticated);

    /**
     * Called when the server rejects the command before a transfer could
     * begin (4xx/5xx), or the data connection fails.
     *
     * @param authenticated operations available for further commands
     * @param code the FTP reply code, or {@code 0} for a data-connection
     *      failure with no corresponding control reply
     * @param message the error message
     */
    void handleTransferFailed(ClientAuthenticatedState authenticated, int code, String message);

}
