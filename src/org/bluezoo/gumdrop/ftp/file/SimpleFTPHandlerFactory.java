/*
 * SimpleFTPHandlerFactory.java
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

package org.bluezoo.gumdrop.ftp.file;

import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.ftp.FTPConnectionHandler;
import org.bluezoo.gumdrop.ftp.FTPConnectionHandlerFactory;

import java.util.logging.Logger;

/**
 * Factory for creating SimpleFTPHandler instances with configurable parameters.
 * 
 * <p>This factory creates FTP handlers that provide basic functionality:
 * <ul>
 * <li>Realm-based authentication integration</li>
 * <li>File system access with configurable root directory</li>
 * <li>Read-write or read-only mode</li>
 * <li>Per-connection handler instances for thread safety</li>
 * </ul>
 *
 * <p><strong>Configuration Parameters:</strong>
 * <ul>
 * <li><code>rootDirectory</code>: Root directory for file system access</li>
 * <li><code>readOnly</code>: Whether to allow write operations (default: false)</li>
 * <li><code>realm</code>: Gumdrop Realm for authentication (optional)</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SimpleFTPHandlerFactory implements FTPConnectionHandlerFactory {
    
    private static final Logger LOGGER = Logger.getLogger(SimpleFTPHandlerFactory.class.getName());
    
    private String rootDirectory;
    private boolean readOnly = false;
    private Realm realm;
    
    /**
     * Sets the root directory for the file system.
     * This directory will be the "/" directory for all FTP connections.
     *
     * @param rootDirectory the root directory path
     */
    public void setRootDirectory(String rootDirectory) {
        this.rootDirectory = rootDirectory;
    }
    
    /**
     * Gets the configured root directory.
     */
    public String getRootDirectory() {
        return rootDirectory;
    }
    
    /**
     * Sets whether the file system should be read-only.
     *
     * @param readOnly true for read-only access, false for read-write
     */
    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }
    
    /**
     * Checks if the file system is configured as read-only.
     */
    public boolean isReadOnly() {
        return readOnly;
    }
    
    /**
     * Sets the Gumdrop Realm for authentication.
     * If not set, the handler will use simple password-based authentication.
     *
     * @param realm the authentication realm
     */
    public void setRealm(Realm realm) {
        this.realm = realm;
    }
    
    /**
     * Gets the configured authentication realm.
     */
    public Realm getRealm() {
        return realm;
    }
    
    @Override
    public FTPConnectionHandler createHandler() {
        if (rootDirectory == null || rootDirectory.trim().isEmpty()) {
            throw new IllegalStateException("Root directory must be configured for SimpleFTPHandlerFactory");
        }
        
        // Create file system instance
        BasicFTPFileSystem fileSystem = new BasicFTPFileSystem(rootDirectory.trim(), readOnly);
        
        // Create handler with optional realm integration
        SimpleFTPHandler handler = new SimpleFTPHandler(fileSystem, realm);
        
        if (LOGGER.isLoggable(java.util.logging.Level.FINE)) {
            LOGGER.fine("Created SimpleFTPHandler with root: " + rootDirectory + 
                       ", readOnly: " + readOnly + 
                       ", realm: " + (realm != null ? realm.getClass().getSimpleName() : "none"));
        }
        
        return handler;
    }
    
    @Override
    public String toString() {
        return "SimpleFTPHandlerFactory{" +
               "rootDirectory='" + rootDirectory + '\'' +
               ", readOnly=" + readOnly +
               ", realm=" + (realm != null ? realm.getClass().getSimpleName() : "none") +
               '}';
    }
}
