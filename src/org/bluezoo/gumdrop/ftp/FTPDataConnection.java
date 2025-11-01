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
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;

/**
 * Connection handler for FTP data connections.
 * This manages one TCP data connection used for file transfers and listings.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FTPDataConnection extends Connection {

    private static final Logger LOGGER = Logger.getLogger(FTPDataConnection.class.getName());

    private final SocketChannel channel;
    private final FTPDataConnectionCoordinator coordinator;
    private boolean transferActive = false;

    protected FTPDataConnection(SocketChannel channel, SSLEngine engine, boolean secure, 
                               FTPDataConnectionCoordinator coordinator) {
        super(engine, secure);
        this.channel = channel;
        this.coordinator = coordinator;
    }

    /**
     * Gets the SocketChannel for direct I/O operations during transfers.
     */
    public SocketChannel getChannel() {
        return channel;
    }

    /**
     * Marks this connection as actively transferring data.
     */
    public void setTransferActive(boolean active) {
        this.transferActive = active;
    }

    /**
     * Checks if this connection is actively transferring data.
     */
    public boolean isTransferActive() {
        return transferActive;
    }

    protected synchronized void received(ByteBuffer buf) {
        // Data connections typically don't receive commands - they transfer data
        // This will be handled by the coordinator during transfers
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest("Received " + buf.remaining() + " bytes on data connection");
        }
    }

    protected void disconnected() throws IOException {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Data connection closed");
        }
        
        // Notify coordinator of disconnection if needed
        if (transferActive && coordinator != null) {
            // This will be expanded in Phase 2 for transfer completion handling
        }
    }

}
