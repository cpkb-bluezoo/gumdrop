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

import org.bluezoo.gumdrop.ftp.FtpAuthenticationResult;
import org.bluezoo.gumdrop.ftp.FtpConnectionHandler;
import org.bluezoo.gumdrop.ftp.FtpConnectionMetadata;
import org.bluezoo.gumdrop.ftp.FtpFileOperationResult;
import org.bluezoo.gumdrop.ftp.FtpFileSystem;

import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.ResourceBundle;
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
public class AnonymousFTPHandler implements FtpConnectionHandler {
    
    private static final Logger LOGGER = Logger.getLogger(AnonymousFTPHandler.class.getName());
    private static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.ftp.L10N");
    
    private final FtpFileSystem fileSystem;
    private String welcomeMessage;
    
    public AnonymousFTPHandler(FtpFileSystem fileSystem) {
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
    public String connected(FtpConnectionMetadata metadata) {
        LOGGER.info(MessageFormat.format(
                L10N.getString("info.anon_ftp_connection"), metadata.getClientAddress()));
        return welcomeMessage != null ? welcomeMessage : 
               "Welcome to Anonymous FTP Server - Login with 'anonymous' and your email address";
    }
    
    @Override
    public FtpAuthenticationResult authenticate(String username, String password, 
                                              String account, FtpConnectionMetadata metadata) {
        
        // Check for anonymous username
        if (username == null || 
            (!username.equalsIgnoreCase("anonymous") && !username.equalsIgnoreCase("ftp"))) {
            return FtpAuthenticationResult.INVALID_USER;
        }
        
        if (password == null) {
            return FtpAuthenticationResult.NEED_PASSWORD;
        }
        
        // Traditional anonymous FTP expects email address as password
        // We'll be lenient and accept any non-empty password
        if (password.trim().isEmpty()) {
            return FtpAuthenticationResult.INVALID_PASSWORD;
        }
        
        LOGGER.info(MessageFormat.format(
                L10N.getString("info.anon_ftp_authenticated"), metadata.getClientAddress(), password));
        return FtpAuthenticationResult.SUCCESS;
    }
    
    @Override
    public FtpFileSystem getFileSystem(FtpConnectionMetadata metadata) {
        return fileSystem;
    }
    
    @Override
    public void transferStarting(String path, boolean upload, long size, 
                               FtpConnectionMetadata metadata) {
        if (upload) {
            // Anonymous FTP typically doesn't allow uploads
            LOGGER.warning(MessageFormat.format(
                    L10N.getString("warn.anon_upload_attempt_blocked"), metadata.getClientAddress(), path));
        } else {
            String sizeStr = (size >= 0) ? " (" + size + " bytes)" : "";
            LOGGER.info(MessageFormat.format(
                    L10N.getString("info.anon_download_starting"),
                    path, sizeStr, metadata.getClientAddress()));
        }
    }
    
    @Override
    public void transferProgress(String path, boolean upload, ByteBuffer data, 
                               long totalBytesTransferred, FtpConnectionMetadata metadata) {
        // Log significant download progress for statistics
        if (!upload && totalBytesTransferred % (10 * 1024 * 1024) == 0) { // Every 10MB
            LOGGER.info(MessageFormat.format(
                    L10N.getString("info.anon_download_progress"),
                    path, totalBytesTransferred / (1024 * 1024), metadata.getClientAddress()));
        }
    }
    
    @Override
    public void transferCompleted(String path, boolean upload, long totalBytesTransferred, 
                                boolean success, FtpConnectionMetadata metadata) {
        if (upload) {
            LOGGER.warning(MessageFormat.format(
                    L10N.getString("warn.anon_upload_blocked"), metadata.getClientAddress(), path));
        } else {
            String status = success ? "completed" : "failed";
            LOGGER.info(MessageFormat.format(
                    L10N.getString("info.anon_download_finished"),
                    status, path, totalBytesTransferred / 1024, metadata.getClientAddress()));
        }
    }
    
    @Override
    public FtpFileOperationResult handleSiteCommand(String command, FtpConnectionMetadata metadata) {
        // Anonymous users typically don't get SITE commands
        LOGGER.info(MessageFormat.format(
                L10N.getString("info.anon_site_denied"), metadata.getClientAddress(), command));
        return FtpFileOperationResult.ACCESS_DENIED;
    }
    
    @Override
    public void disconnected(FtpConnectionMetadata metadata) {
        LOGGER.info(MessageFormat.format(
                L10N.getString("info.anon_ftp_disconnected"), metadata.getClientAddress()));
    }
}
