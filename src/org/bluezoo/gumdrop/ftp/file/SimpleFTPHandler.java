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
import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.logging.Level;
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
    private static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.ftp.L10N");
    
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
        LOGGER.info(MessageFormat.format(
                L10N.getString("info.simple_ftp_connection"),
                clientHost,
                realm != null ? L10N.getString("info.realm_auth") : L10N.getString("info.simple_auth")));
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
                    LOGGER.info(MessageFormat.format(
                            L10N.getString("info.simple_ftp_realm_auth_success"), username, clientHost));
                    return FTPAuthenticationResult.SUCCESS;
                } else {
                    LOGGER.warning(MessageFormat.format(
                            L10N.getString("warn.simple_ftp_auth_failed"), clientHost));
                    return FTPAuthenticationResult.INVALID_PASSWORD;
                }
            } else {
                // Simple authentication - accept any non-empty password
                if (password.trim().isEmpty()) {
                    return FTPAuthenticationResult.INVALID_PASSWORD;
                }

                LOGGER.info(MessageFormat.format(
                        L10N.getString("info.simple_ftp_simple_auth_success"), username, clientHost));
                return FTPAuthenticationResult.SUCCESS;
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, MessageFormat.format(
                    L10N.getString("warn.simple_ftp_auth_error"), clientHost), e);
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
        LOGGER.info(MessageFormat.format(
                L10N.getString("info.simple_ftp_transfer_starting"),
                direction, path, sizeStr, metadata.getAuthenticatedUser()));
    }
    
    @Override
    public void transferProgress(String path, boolean upload, ByteBuffer data, 
                               long totalBytesTransferred, FTPConnectionMetadata metadata) {
        // Log progress every 1MB for demo purposes
        if (totalBytesTransferred % (1024 * 1024) == 0) {
            String direction = upload ? "upload" : "download";
            LOGGER.info(MessageFormat.format(
                    L10N.getString("info.simple_ftp_transfer_progress"),
                    direction, path, totalBytesTransferred));
        }
    }
    
    @Override
    public void transferCompleted(String path, boolean upload, long totalBytesTransferred, 
                                boolean success, FTPConnectionMetadata metadata) {
        String direction = upload ? "upload" : "download";
        String status = success ? "completed" : "failed";
        LOGGER.info(MessageFormat.format(
                L10N.getString("info.simple_ftp_transfer_completed"),
                status, direction, path, totalBytesTransferred, metadata.getAuthenticatedUser()));
    }
    
    @Override
    public FTPFileOperationResult handleSiteCommand(String command, FTPConnectionMetadata metadata) {
        // Demo SITE command handling
        LOGGER.info(MessageFormat.format(
                L10N.getString("info.simple_ftp_site_command"), metadata.getAuthenticatedUser(), command));
        
        if (command.toUpperCase().startsWith("HELP")) {
            return FTPFileOperationResult.SUCCESS;
        }
        
        return FTPFileOperationResult.NOT_SUPPORTED;
    }
    
    @Override
    public void disconnected(FTPConnectionMetadata metadata) {
        LOGGER.info(MessageFormat.format(
                L10N.getString("info.simple_ftp_disconnected"),
                metadata.getClientAddress(), metadata.getAuthenticatedUser()));
    }
}
