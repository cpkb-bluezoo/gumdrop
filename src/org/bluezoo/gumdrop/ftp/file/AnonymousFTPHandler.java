/*
 * AnonymousFTPHandler.java
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

import org.bluezoo.gumdrop.ftp.FTPAuthenticationResult;
import org.bluezoo.gumdrop.ftp.FTPConnectionHandler;
import org.bluezoo.gumdrop.ftp.FTPConnectionMetadata;
import org.bluezoo.gumdrop.ftp.FTPFileOperationResult;
import org.bluezoo.gumdrop.ftp.FTPFileSystem;

import java.nio.ByteBuffer;
import java.util.logging.Logger;

/**
 * Anonymous FTP connection handler for public file distribution.
 * 
 * <p>This handler provides anonymous FTP access:
 * <ul>
 * <li>Accepts "anonymous" or "ftp" as username</li>
 * <li>Uses email address as password (traditional anonymous FTP)</li>
 * <li>Provides read-only access to the file system</li>
 * <li>Logs anonymous downloads for statistics</li>
 * </ul>
 *
 * <p>Typical use case: Public file distribution servers where users can
 * download files without creating accounts.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AnonymousFTPHandler implements FTPConnectionHandler {
    
    private static final Logger LOGGER = Logger.getLogger(AnonymousFTPHandler.class.getName());
    
    private final FTPFileSystem fileSystem;
    private String welcomeMessage;
    
    public AnonymousFTPHandler(FTPFileSystem fileSystem) {
        this.fileSystem = fileSystem;
    }
    
    /**
     * Sets a custom welcome message for anonymous users.
     *
     * @param welcomeMessage the custom welcome message
     */
    public void setWelcomeMessage(String welcomeMessage) {
        this.welcomeMessage = welcomeMessage;
    }
    
    @Override
    public String connected(FTPConnectionMetadata metadata) {
        LOGGER.info("Anonymous FTP connection from " + metadata.getClientAddress());
        return welcomeMessage != null ? welcomeMessage : 
               "Welcome to Anonymous FTP Server - Login with 'anonymous' and your email address";
    }
    
    @Override
    public FTPAuthenticationResult authenticate(String username, String password, 
                                              String account, FTPConnectionMetadata metadata) {
        
        // Check for anonymous username
        if (username == null || 
            (!username.equalsIgnoreCase("anonymous") && !username.equalsIgnoreCase("ftp"))) {
            return FTPAuthenticationResult.INVALID_USER;
        }
        
        if (password == null) {
            return FTPAuthenticationResult.NEED_PASSWORD;
        }
        
        // Traditional anonymous FTP expects email address as password
        // We'll be lenient and accept any non-empty password
        if (password.trim().isEmpty()) {
            return FTPAuthenticationResult.INVALID_PASSWORD;
        }
        
        LOGGER.info("Anonymous user authenticated from " + metadata.getClientAddress() + 
                   " (email: " + password + ")");
        return FTPAuthenticationResult.SUCCESS;
    }
    
    @Override
    public FTPFileSystem getFileSystem(FTPConnectionMetadata metadata) {
        return fileSystem;
    }
    
    @Override
    public void transferStarting(String path, boolean upload, long size, 
                               FTPConnectionMetadata metadata) {
        if (upload) {
            // Anonymous FTP typically doesn't allow uploads
            LOGGER.warning("Upload attempt blocked for anonymous user from " + 
                          metadata.getClientAddress() + ": " + path);
        } else {
            String sizeStr = (size >= 0) ? " (" + size + " bytes)" : "";
            LOGGER.info("Anonymous download starting: " + path + sizeStr + 
                       " from " + metadata.getClientAddress());
        }
    }
    
    @Override
    public void transferProgress(String path, boolean upload, ByteBuffer data, 
                               long totalBytesTransferred, FTPConnectionMetadata metadata) {
        // Log significant download progress for statistics
        if (!upload && totalBytesTransferred % (10 * 1024 * 1024) == 0) { // Every 10MB
            LOGGER.info("Anonymous download progress: " + path + 
                       " - " + (totalBytesTransferred / (1024 * 1024)) + " MB from " + 
                       metadata.getClientAddress());
        }
    }
    
    @Override
    public void transferCompleted(String path, boolean upload, long totalBytesTransferred, 
                                boolean success, FTPConnectionMetadata metadata) {
        if (upload) {
            LOGGER.warning("Upload blocked for anonymous user from " + 
                          metadata.getClientAddress() + ": " + path);
        } else {
            String status = success ? "completed" : "failed";
            LOGGER.info("Anonymous download " + status + ": " + path + 
                       " - " + (totalBytesTransferred / 1024) + " KB from " + 
                       metadata.getClientAddress());
        }
    }
    
    @Override
    public FTPFileOperationResult handleSiteCommand(String command, FTPConnectionMetadata metadata) {
        // Anonymous users typically don't get SITE commands
        LOGGER.info("SITE command denied for anonymous user from " + 
                   metadata.getClientAddress() + ": " + command);
        return FTPFileOperationResult.ACCESS_DENIED;
    }
    
    @Override
    public void disconnected(FTPConnectionMetadata metadata) {
        LOGGER.info("Anonymous FTP connection closed from " + metadata.getClientAddress());
    }
}
