/*
 * FTPControlConnection.java
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

package org.bluezoo.gumdrop.ftp;

/**
 * Interface for FTP control connections used by {@link FTPDataConnectionCoordinator}
 * and {@link FTPDataServer} to obtain server configuration (e.g. for TLS data
 * connections).
 *
 * <p>Implemented by {@link FTPProtocolHandler}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
interface FTPControlConnection {

    /**
     * Returns the FTPListener instance for this connection.
     *
     * @return the FTP server listener
     */
    FTPListener getServer();
}
