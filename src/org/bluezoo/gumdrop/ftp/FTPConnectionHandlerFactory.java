/*
 * FTPConnectionHandlerFactory.java
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

/**
 * A factory interface for creating {@link FTPConnectionHandler} instances.
 *
 * <p>This functional interface is used by {@link FTPListener} to create a new,
 * dedicated {@link FTPConnectionHandler} instance for each incoming FTP connection.
 * This ensures proper thread safety and state isolation, as handler implementations
 * are typically stateful and not designed to be shared across multiple concurrent connections.
 *
 * <p>Example implementation:
 * <pre><code>
 * // Example: A factory that creates a new MyFTPHandler for each connection
 * ftpConnector.setHandlerFactory(new FTPConnectionHandlerFactory() {
 *     public FTPConnectionHandler createHandler() {
 *         return new MyFTPHandler(fileSystem, userDatabase);
 *     }
 * });
 * </code></pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see FTPConnectionHandler
 * @see FTPListener
 */
public interface FTPConnectionHandlerFactory {

    /**
     * Creates a new instance of {@link FTPConnectionHandler}.
     * This method is called for every new FTP connection.
     *
     * @return a new, uninitialized {@link FTPConnectionHandler} instance
     * @throws Exception if an error occurs during handler creation
     */
    FTPConnectionHandler createHandler() throws Exception;

}
