/*
 * FTPDataConnection.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.bluezoo.gumdrop.ftp;

import org.bluezoo.gumdrop.Connection;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;

/**
 * Connection handler for the FTP protocol.
 * This manages one TCP data connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FTPDataConnection extends Connection {

    protected FTPDataConnection(SocketChannel channel, SSLEngine engine, boolean secure) {
        super(engine, secure);
        // TODO
    }

    protected synchronized void received(ByteBuffer buf) {}

    protected void disconnected() throws IOException {}

}
