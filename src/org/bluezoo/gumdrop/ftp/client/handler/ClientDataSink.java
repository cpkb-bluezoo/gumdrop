/*
 * ClientDataSink.java
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
 * Sink for uploading file content over an FTP data connection (STOR/APPE).
 * RFC 959 §4.1.3.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerStorReplyHandler#handleReadyToSend
 */
public interface ClientDataSink {

    /**
     * Writes a chunk of file content to the data connection.
     *
     * @param data the content to write
     */
    void write(ByteBuffer data);

    /**
     * Signals that all content has been written. Closes the data
     * connection; the transfer completes once the server's final reply
     * (226) also arrives.
     */
    void finish();

}
