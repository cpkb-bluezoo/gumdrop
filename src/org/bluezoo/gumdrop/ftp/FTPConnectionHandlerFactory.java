/*
 * FTPConnectionHandlerFactory.java
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

/**
 * A factory interface for creating {@link FTPConnectionHandler} instances.
 *
 * <p>This functional interface is used by {@link FTPConnector} to create a new,
 * dedicated {@link FTPConnectionHandler} instance for each incoming FTP connection.
 * This ensures proper thread safety and state isolation, as handler implementations
 * are typically stateful and not designed to be shared across multiple concurrent connections.
 *
 * <p>Implementations can be provided as a lambda expression:
 * <pre><code>
 * // Example: A factory that creates a new MyFTPHandler for each connection
 * ftpConnector.setHandlerFactory(() -> new MyFTPHandler(fileSystem, userDatabase));
 * </code></pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see FTPConnectionHandler
 * @see FTPConnector
 */
@FunctionalInterface
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
