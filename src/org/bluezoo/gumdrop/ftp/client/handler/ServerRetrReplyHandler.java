/*
 * ServerRetrReplyHandler.java
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

import java.nio.ByteBuffer;

/**
 * Handler for a RETR (download) command. RFC 959 §4.1.3.
 *
 * <p>{@link #handleContent(ByteBuffer)} is called once per chunk of file
 * content as it arrives on the data connection — the content is not
 * buffered by the client. {@link #handleTransferComplete} fires once both
 * the data connection has reached EOF <em>and</em> the control
 * connection's final reply (226) has arrived, since either can arrive
 * first.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientAuthenticatedState#retr
 */
public interface ServerRetrReplyHandler extends ServerReplyHandler {

    /**
     * Called for each chunk of file content received on the data
     * connection. {@code data} is only valid for the duration of this
     * call.
     *
     * @param data the file content chunk
     */
    void handleContent(ByteBuffer data);

    /**
     * Called once the transfer has fully completed: the data connection
     * reached EOF and the server sent its final 226 reply.
     *
     * @param authenticated operations available for further commands
     */
    void handleTransferComplete(ClientAuthenticatedState authenticated);

    /**
     * Called when the server rejects the command before a transfer could
     * begin (4xx/5xx, e.g. no such file), or the data connection fails.
     *
     * @param authenticated operations available for further commands
     * @param code the FTP reply code, or {@code 0} for a data-connection
     *      failure with no corresponding control reply
     * @param message the error message
     */
    void handleTransferFailed(ClientAuthenticatedState authenticated, int code, String message);

}
