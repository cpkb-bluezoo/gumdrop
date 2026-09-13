/*
 * RoleBasedFTPHandler.java
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

import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.ftp.FtpAuthenticationResult;
import org.bluezoo.gumdrop.ftp.FtpConnectionHandler;
import org.bluezoo.gumdrop.ftp.FtpConnectionMetadata;
import org.bluezoo.gumdrop.ftp.FtpFileOperationResult;
import org.bluezoo.gumdrop.ftp.FtpFileSystem;
import org.bluezoo.gumdrop.ftp.FtpOperation;
import org.bluezoo.gumdrop.ftp.FtpRoles;
import org.bluezoo.gumdrop.quota.Quota;
import org.bluezoo.gumdrop.quota.QuotaManager;
import org.bluezoo.gumdrop.quota.QuotaPolicy;
import org.bluezoo.gumdrop.quota.QuotaSource;

/**
 * FTP connection handler with role-based access control.
 * 
 * <p>This handler uses a {@link Realm} to authenticate users and check
 * role membership for authorization decisions. Users are granted
 * permissions based on their membership in standard FTP roles.</p>
 * 
 * <h4>Role Hierarchy</h4>
 * <ul>
 *   <li><b>ftp-admin</b> - Full access to all operations</li>
 *   <li><b>ftp-delete</b> - Can delete, rename (implies ftp-write)</li>
 *   <li><b>ftp-write</b> - Can upload, create directories (implies ftp-read)</li>
 *   <li><b>ftp-read</b> - Read-only access</li>
 * </ul>
 * 
 * <h4>Configuration Example</h4>
 * <pre>{@code
 * <realm id="ftpRealm" class="org.bluezoo.gumdrop.BasicRealm">
 *   <property name="href">ftp-users.xml</property>
 * </realm>
 * 
 * <service class="org.bluezoo.gumdrop.ftp.file.RoleBasedFTPServer">
 *   <property name="realm" ref="#ftpRealm"/>
 *   <property name="root-directory">/var/ftp</property>
 *   <listener class="org.bluezoo.gumdrop.ftp.FtpListener" port="21"/>
 * </service>
 * }</pre>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see FtpRoles
 * @see FtpOperation
 */
public class RoleBasedFTPHandler implements FtpConnectionHandler {
    
    private static final Logger LOGGER = Logger.getLogger(RoleBasedFTPHandler.class.getName());
    private static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.ftp.L10N");
    
    private final Realm realm;
    private final FtpFileSystem fileSystem;
    private QuotaManager quotaManager;
    private String welcomeMessage;
    
    /**
     * Creates a new role-based FTP handler.
     * 
     * @param realm the realm for authentication and role checking
     * @param fileSystem the file system to use for file operations
     */
    public RoleBasedFTPHandler(Realm realm, FtpFileSystem fileSystem) {
        if (realm == null) {
            throw new IllegalArgumentException("Realm cannot be null");
        }
        if (fileSystem == null) {
            throw new IllegalArgumentException("FileSystem cannot be null");
        }
        this.realm = realm;
        this.fileSystem = fileSystem;
    }
    
    /**
     * Sets a custom welcome message.
     * 
     * @param welcomeMessage the welcome message to display on connection
     */
    public void setWelcomeMessage(String welcomeMessage) {
        this.welcomeMessage = welcomeMessage;
    }
    
    /**
     * Sets the quota manager for quota enforcement.
     * 
     * @param quotaManager the quota manager
     */
    public void setQuotaManager(QuotaManager quotaManager) {
        this.quotaManager = quotaManager;
    }
    
    @Override
    public QuotaManager getQuotaManager() {
        return quotaManager;
    }
    
    @Override
    public String connected(FtpConnectionMetadata metadata) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("FTP connection from " + metadata.getClientAddress());
        }
        return welcomeMessage;
    }
    
    @Override
    public FtpAuthenticationResult authenticate(String username, String password,
                                                 String account, FtpConnectionMetadata metadata) {
        if (username == null || username.trim().isEmpty()) {
            return FtpAuthenticationResult.INVALID_USER;
        }
        
        if (password == null) {
            return FtpAuthenticationResult.NEED_PASSWORD;
        }
        
        String trimmedUsername = username.trim();
        
        // Check if user exists
        if (!realm.userExists(trimmedUsername)) {
            logAuthFailure(trimmedUsername, metadata, "user not found");
            return FtpAuthenticationResult.INVALID_USER;
        }
        
        // Verify password
        if (!realm.passwordMatch(trimmedUsername, password)) {
            logAuthFailure(trimmedUsername, metadata, "invalid password");
            return FtpAuthenticationResult.INVALID_PASSWORD;
        }
        
        // Check that user has at least read access
        if (!hasAnyFTPRole(trimmedUsername)) {
            logAuthFailure(trimmedUsername, metadata, "no FTP roles assigned");
            return FtpAuthenticationResult.INVALID_USER;
        }
        
        logAuthSuccess(trimmedUsername, metadata);
        return FtpAuthenticationResult.SUCCESS;
    }
    
    /**
     * Checks if a user has any FTP role assigned.
     */
    private boolean hasAnyFTPRole(String username) {
        return realm.isUserInRole(username, FtpRoles.ADMIN) ||
               realm.isUserInRole(username, FtpRoles.DELETE) ||
               realm.isUserInRole(username, FtpRoles.WRITE) ||
               realm.isUserInRole(username, FtpRoles.READ);
    }
    
    @Override
    public FtpFileSystem getFileSystem(FtpConnectionMetadata metadata) {
        return fileSystem;
    }
    
    @Override
    public boolean isAuthorized(FtpOperation operation, String path,
                                FtpConnectionMetadata metadata) {
        String username = metadata.getAuthenticatedUser();
        if (username == null) {
            return false;
        }
        
        // Admins can do anything
        if (realm.isUserInRole(username, FtpRoles.ADMIN)) {
            return true;
        }
        
        boolean authorized;
        switch (operation) {
            case READ:
            case NAVIGATE:
                // READ or higher required
                authorized = realm.isUserInRole(username, FtpRoles.READ) ||
                             realm.isUserInRole(username, FtpRoles.WRITE) ||
                             realm.isUserInRole(username, FtpRoles.DELETE);
                break;
                
            case WRITE:
            case CREATE_DIR:
                // WRITE or higher required
                authorized = realm.isUserInRole(username, FtpRoles.WRITE) ||
                             realm.isUserInRole(username, FtpRoles.DELETE);
                break;
                
            case DELETE:
            case DELETE_DIR:
            case RENAME:
                // DELETE required
                authorized = realm.isUserInRole(username, FtpRoles.DELETE);
                break;
                
            case SITE_COMMAND:
            case ADMIN:
                // Only ADMIN can do these
                authorized = false;
                break;
                
            default:
                authorized = false;
        }
        
        if (!authorized && LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Authorization denied: user=" + username + 
                       ", operation=" + operation + ", path=" + path);
        }
        
        return authorized;
    }
    
    @Override
    public void transferStarting(String path, boolean upload, long size,
                                 FtpConnectionMetadata metadata) {
        if (LOGGER.isLoggable(Level.FINE)) {
            String direction = upload ? "upload" : "download";
            LOGGER.fine("Transfer starting: " + direction + " " + path + 
                       " (size=" + size + ") by " + metadata.getAuthenticatedUser());
        }
    }
    
    @Override
    public void transferProgress(String path, boolean upload, ByteBuffer data,
                                 long totalBytesTransferred, FtpConnectionMetadata metadata) {
        // Default: no progress tracking
    }
    
    @Override
    public void transferCompleted(String path, boolean upload, long totalBytesTransferred,
                                  boolean success, FtpConnectionMetadata metadata) {
        if (LOGGER.isLoggable(Level.INFO)) {
            String direction = upload ? "uploaded" : "downloaded";
            String status = success ? "completed" : "failed";
            LOGGER.info(MessageFormat.format(
                    L10N.getString("info.role_based_transfer_completed"),
                    status, direction, path, totalBytesTransferred, metadata.getAuthenticatedUser()));
        }
    }
    
    @Override
    public FtpFileOperationResult handleSiteCommand(String command, FtpConnectionMetadata metadata) {
        String upperCommand = command.toUpperCase().trim();
        
        // SITE QUOTA - any authenticated user can check their own quota
        if (upperCommand.equals("QUOTA") || upperCommand.startsWith("QUOTA ")) {
            return handleSiteQuota(command, metadata);
        }
        
        // SITE SETQUOTA - only admins can set quotas
        if (upperCommand.startsWith("SETQUOTA ")) {
            if (!realm.isUserInRole(metadata.getAuthenticatedUser(), FtpRoles.ADMIN)) {
                return FtpFileOperationResult.ACCESS_DENIED;
            }
            return handleSiteSetQuota(command, metadata);
        }
        
        // Other SITE commands require ADMIN role
        if (!realm.isUserInRole(metadata.getAuthenticatedUser(), FtpRoles.ADMIN)) {
            return FtpFileOperationResult.ACCESS_DENIED;
        }
        return FtpFileOperationResult.NOT_SUPPORTED;
    }
    
    /**
     * Handles SITE QUOTA command.
     */
    private FtpFileOperationResult handleSiteQuota(String command, FtpConnectionMetadata metadata) {
        if (quotaManager == null) {
            // Quota not configured
            return FtpFileOperationResult.NOT_SUPPORTED;
        }
        
        String targetUser = metadata.getAuthenticatedUser();
        
        // Admin can check other users' quotas: SITE QUOTA username
        String args = command.substring(5).trim(); // Remove "QUOTA"
        if (!args.isEmpty() && realm.isUserInRole(metadata.getAuthenticatedUser(), FtpRoles.ADMIN)) {
            targetUser = args;
        }
        
        Quota quota = quotaManager.getQuota(targetUser);
        metadata.setSiteCommandResponse(formatQuotaStatus(targetUser, quota));
        
        return FtpFileOperationResult.SUCCESS;
    }
    
    /**
     * Formats quota status for SITE QUOTA response.
     */
    private String formatQuotaStatus(String username, Quota quota) {
        StringBuilder sb = new StringBuilder();
        sb.append(MessageFormat.format(L10N.getString("ftp.quota.status_for"), username)).append("\r\n");
        
        // Source
        QuotaSource source = quota.getSource();
        String sourceDetail = quota.getSourceDetail();
        switch (source) {
            case USER:
                sb.append(L10N.getString("ftp.quota.source_user")).append("\r\n");
                break;
            case ROLE:
                sb.append(MessageFormat.format(L10N.getString("ftp.quota.source_role"), sourceDetail)).append("\r\n");
                break;
            case DEFAULT:
                sb.append(L10N.getString("ftp.quota.source_default")).append("\r\n");
                break;
            default:
                sb.append(L10N.getString("ftp.quota.source_none")).append("\r\n");
        }
        
        // Storage
        if (quota.isStorageUnlimited()) {
            sb.append(L10N.getString("ftp.quota.storage_unlimited")).append("\r\n");
        } else {
            String used = QuotaPolicy.formatSize(quota.getStorageUsed());
            String limit = QuotaPolicy.formatSize(quota.getStorageLimit());
            int percent = quota.getStoragePercentUsed();
            sb.append(MessageFormat.format(L10N.getString("ftp.quota.storage_status"), 
                used, limit, String.valueOf(percent))).append("\r\n");
        }
        
        return sb.toString();
    }
    
    /**
     * Handles SITE SETQUOTA command.
     * Syntax: SITE SETQUOTA username storageLimit [messageLimit]
     */
    private FtpFileOperationResult handleSiteSetQuota(String command, FtpConnectionMetadata metadata) {
        if (quotaManager == null) {
            return FtpFileOperationResult.NOT_SUPPORTED;
        }
        
        String args = command.substring(8).trim(); // Remove "SETQUOTA"
        
        // Parse whitespace-separated arguments: username storageLimit [messageLimit]
        String targetUser = null;
        String storageStr = null;
        String messageStr = "-1";
        int argIndex = 0;
        int start = 0;
        int length = args.length();
        while (start < length && argIndex < 3) {
            // Skip whitespace
            while (start < length && Character.isWhitespace(args.charAt(start))) {
                start++;
            }
            if (start >= length) {
                break;
            }
            // Find end of token
            int end = start;
            while (end < length && !Character.isWhitespace(args.charAt(end))) {
                end++;
            }
            String token = args.substring(start, end);
            if (argIndex == 0) {
                targetUser = token;
            } else if (argIndex == 1) {
                storageStr = token;
            } else {
                messageStr = token;
            }
            argIndex++;
            start = end;
        }
        
        if (targetUser == null || storageStr == null) {
            return FtpFileOperationResult.NOT_SUPPORTED;
        }
        
        try {
            long storageLimit = QuotaPolicy.parseSize(storageStr);
            long messageLimit = Long.parseLong(messageStr);
            
            quotaManager.setUserQuota(targetUser, storageLimit, messageLimit);
            
            metadata.setSiteCommandResponse(
                MessageFormat.format(L10N.getString("ftp.quota.set_success"), 
                    targetUser, QuotaPolicy.formatSize(storageLimit)));
            
            return FtpFileOperationResult.SUCCESS;
        } catch (IllegalArgumentException e) {
            return FtpFileOperationResult.NOT_SUPPORTED;
        }
    }
    
    @Override
    public void disconnected(FtpConnectionMetadata metadata) {
        if (LOGGER.isLoggable(Level.FINE)) {
            long duration = metadata.getConnectionDurationMillis();
            LOGGER.fine("FTP disconnected: user=" + metadata.getAuthenticatedUser() +
                       ", duration=" + duration + "ms");
        }
    }
    
    private void logAuthSuccess(String username, FtpConnectionMetadata metadata) {
        if (LOGGER.isLoggable(Level.INFO)) {
            String clientHost = metadata.getClientAddress() != null ?
                               metadata.getClientAddress().getHostString() : "unknown";
            LOGGER.info(MessageFormat.format(
                    L10N.getString("info.role_based_auth_success"), username, clientHost));
        }
    }
    
    private void logAuthFailure(String username, FtpConnectionMetadata metadata, String reason) {
        if (LOGGER.isLoggable(Level.WARNING)) {
            String clientHost = metadata.getClientAddress() != null ?
                               metadata.getClientAddress().getHostString() : "unknown";
            LOGGER.warning(MessageFormat.format(
                    L10N.getString("warn.role_based_auth_failed"), clientHost, reason));
        }
    }
}

