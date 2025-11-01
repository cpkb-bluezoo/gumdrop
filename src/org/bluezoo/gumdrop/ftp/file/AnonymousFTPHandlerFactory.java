/*
 * AnonymousFTPHandlerFactory.java
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

package org.bluezoo.gumdrop.ftp.file;

import org.bluezoo.gumdrop.ftp.FTPConnectionHandler;
import org.bluezoo.gumdrop.ftp.FTPConnectionHandlerFactory;

import java.util.logging.Logger;

/**
 * Factory for creating AnonymousFTPHandler instances for public file access.
 * 
 * <p>This factory creates FTP handlers that provide anonymous access:
 * <ul>
 * <li>Anonymous login (username: "anonymous" or "ftp")</li>
 * <li>Email address as password (traditional anonymous FTP)</li>
 * <li>Read-only file system access</li>
 * <li>Public file distribution functionality</li>
 * </ul>
 *
 * <p><strong>Configuration Parameters:</strong>
 * <ul>
 * <li><code>rootDirectory</code>: Root directory for public files</li>
 * <li><code>welcomeMessage</code>: Custom welcome message (optional)</li>
 * </ul>
 *
 * <p><strong>Typical Use Cases:</strong>
 * <ul>
 * <li>Public software distribution servers</li>
 * <li>Document sharing portals</li>
 * <li>Open source project file repositories</li>
 * <li>Anonymous file download services</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AnonymousFTPHandlerFactory implements FTPConnectionHandlerFactory {
    
    private static final Logger LOGGER = Logger.getLogger(AnonymousFTPHandlerFactory.class.getName());
    
    private String rootDirectory;
    private String welcomeMessage;
    
    /**
     * Sets the root directory for anonymous access.
     * This directory will be the "/" directory for all anonymous FTP connections.
     *
     * @param rootDirectory the root directory path for public files
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
     * Sets a custom welcome message for anonymous users.
     * If not set, a default message will be used.
     *
     * @param welcomeMessage custom welcome message
     */
    public void setWelcomeMessage(String welcomeMessage) {
        this.welcomeMessage = welcomeMessage;
    }
    
    /**
     * Gets the configured welcome message.
     */
    public String getWelcomeMessage() {
        return welcomeMessage;
    }
    
    @Override
    public FTPConnectionHandler createHandler() {
        if (rootDirectory == null || rootDirectory.trim().isEmpty()) {
            throw new IllegalStateException("Root directory must be configured for AnonymousFTPHandlerFactory");
        }
        
        // Create read-only file system for anonymous access
        BasicFTPFileSystem fileSystem = new BasicFTPFileSystem(rootDirectory.trim(), true);
        
        // Create anonymous handler
        AnonymousFTPHandler handler = new AnonymousFTPHandler(fileSystem);
        
        // Set custom welcome message if provided
        if (welcomeMessage != null && !welcomeMessage.trim().isEmpty()) {
            handler.setWelcomeMessage(welcomeMessage.trim());
        }
        
        if (LOGGER.isLoggable(java.util.logging.Level.FINE)) {
            LOGGER.fine("Created AnonymousFTPHandler with root: " + rootDirectory + 
                       ", welcomeMessage: " + (welcomeMessage != null ? "custom" : "default"));
        }
        
        return handler;
    }
    
    @Override
    public String toString() {
        return "AnonymousFTPHandlerFactory{" +
               "rootDirectory='" + rootDirectory + '\'' +
               ", welcomeMessage='" + welcomeMessage + '\'' +
               '}';
    }
}
