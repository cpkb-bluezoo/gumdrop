/*
 * SimpleFTPHandler.java
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
import org.bluezoo.gumdrop.ftp.FTPAuthenticationResult;
import org.bluezoo.gumdrop.ftp.FTPConnectionHandler;
import org.bluezoo.gumdrop.ftp.FTPConnectionMetadata;
import org.bluezoo.gumdrop.ftp.FTPFileOperationResult;
import org.bluezoo.gumdrop.ftp.FTPFileSystem;

import java.nio.ByteBuffer;
import java.util.logging.Logger;

/**
 * Simple FTP connection handler for demonstration purposes.
 * 
 * <p>This handler provides basic authentication and file system access:
 * <ul>
 * <li>Accepts any username/password combination</li>
 * <li>Provides access to the configured file system</li>
 * <li>Logs connection events and transfers</li>
 * </ul>
 *
 * <p><strong>WARNING:</strong> This is a demo implementation with no real security.
 * For production use, implement proper authentication and authorization.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SimpleFTPHandler implements FTPConnectionHandler {
    
    private static final Logger LOGGER = Logger.getLogger(SimpleFTPHandler.class.getName());
    
    private final FTPFileSystem fileSystem;
    private final Realm realm;
    
    public SimpleFTPHandler(FTPFileSystem fileSystem) {
        this(fileSystem, null);
    }
    
    public SimpleFTPHandler(FTPFileSystem fileSystem, Realm realm) {
        this.fileSystem = fileSystem;
        this.realm = realm;
    }
    
    @Override
    public String connected(FTPConnectionMetadata metadata) {
        String clientHost = metadata.getClientAddress() != null ? 
                           metadata.getClientAddress().getHostString() : "unknown";
        LOGGER.info("FTP connection from " + clientHost + 
                   (realm != null ? " (Realm authentication)" : " (Simple authentication)"));
        return null; // Use default welcome message
    }
    
    @Override
    public FTPAuthenticationResult authenticate(String username, String password, 
                                              String account, FTPConnectionMetadata metadata) {
        
        if (username == null || username.trim().isEmpty()) {
            return FTPAuthenticationResult.INVALID_USER;
        }
        
        if (password == null) {
            return FTPAuthenticationResult.NEED_PASSWORD;
        }
        
        String clientHost = metadata.getClientAddress() != null ? 
                           metadata.getClientAddress().getHostString() : "unknown";
        
        try {
            if (realm != null) {
                // Use Realm-based authentication
                boolean authenticated = realm.passwordMatch(username.trim(), password);
                
                if (authenticated) {
                    LOGGER.info("User '" + username + "' authenticated via Realm from " + clientHost);
                    return FTPAuthenticationResult.SUCCESS;
                } else {
                    LOGGER.warning("Authentication failed for user '" + username + "' from " + clientHost);
                    return FTPAuthenticationResult.INVALID_PASSWORD;
                }
            } else {
                // Simple authentication - accept any non-empty password
                if (password.trim().isEmpty()) {
                    return FTPAuthenticationResult.INVALID_PASSWORD;
                }
                
                LOGGER.info("User '" + username + "' authenticated (simple mode) from " + clientHost);
                return FTPAuthenticationResult.SUCCESS;
            }
            
        } catch (Exception e) {
            LOGGER.log(java.util.logging.Level.WARNING, 
                      "Authentication error for user '" + username + "' from " + clientHost, e);
            return FTPAuthenticationResult.INVALID_PASSWORD;
        }
    }
    
    @Override
    public FTPFileSystem getFileSystem(FTPConnectionMetadata metadata) {
        return fileSystem;
    }
    
    @Override
    public void transferStarting(String path, boolean upload, long size, 
                               FTPConnectionMetadata metadata) {
        String direction = upload ? "upload" : "download";
        String sizeStr = (size >= 0) ? " (" + size + " bytes)" : "";
        LOGGER.info("Starting " + direction + " of " + path + sizeStr + 
                   " for user " + metadata.getAuthenticatedUser());
    }
    
    @Override
    public void transferProgress(String path, boolean upload, ByteBuffer data, 
                               long totalBytesTransferred, FTPConnectionMetadata metadata) {
        // Log progress every 1MB for demo purposes
        if (totalBytesTransferred % (1024 * 1024) == 0) {
            String direction = upload ? "upload" : "download";
            LOGGER.info("Transfer progress: " + direction + " of " + path + 
                       " - " + totalBytesTransferred + " bytes");
        }
    }
    
    @Override
    public void transferCompleted(String path, boolean upload, long totalBytesTransferred, 
                                boolean success, FTPConnectionMetadata metadata) {
        String direction = upload ? "upload" : "download";
        String status = success ? "completed" : "failed";
        LOGGER.info("Transfer " + status + ": " + direction + " of " + path + 
                   " - " + totalBytesTransferred + " bytes for user " + 
                   metadata.getAuthenticatedUser());
    }
    
    @Override
    public FTPFileOperationResult handleSiteCommand(String command, FTPConnectionMetadata metadata) {
        // Demo SITE command handling
        LOGGER.info("SITE command from " + metadata.getAuthenticatedUser() + ": " + command);
        
        if (command.toUpperCase().startsWith("HELP")) {
            return FTPFileOperationResult.SUCCESS;
        }
        
        return FTPFileOperationResult.NOT_SUPPORTED;
    }
    
    @Override
    public void disconnected(FTPConnectionMetadata metadata) {
        LOGGER.info("FTP connection closed from " + metadata.getClientAddress() + 
                   " (user: " + metadata.getAuthenticatedUser() + ")");
    }
}
