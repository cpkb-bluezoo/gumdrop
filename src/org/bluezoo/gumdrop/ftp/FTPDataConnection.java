/*
 * FTPDataConnection.java
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

package org.bluezoo.gumdrop.ftp;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Represents an FTP data connection for file transfers and listings.
 * This is a lightweight wrapper around a SocketChannel used for
 * blocking I/O during data transfers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class FTPDataConnection {

    private static final Logger LOGGER = Logger.getLogger(FTPDataConnection.class.getName());

    private final SocketChannel channel;
    private final FTPDataConnectionCoordinator coordinator;
    private boolean transferActive = false;

    FTPDataConnection(SocketChannel channel, FTPDataConnectionCoordinator coordinator) {
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

    /**
     * Closes the data connection.
     */
    public void close() {
        try {
            if (channel.isOpen()) {
                channel.close();
            }
        } catch (IOException e) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "Error closing data connection", e);
            }
        }
    }
}
