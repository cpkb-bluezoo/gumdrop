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
import org.bluezoo.gumdrop.ftp.FtpAuthenticationResult;
import org.bluezoo.gumdrop.ftp.FtpConnectionHandler;
import org.bluezoo.gumdrop.ftp.FtpConnectionMetadata;
import org.bluezoo.gumdrop.ftp.FtpFileOperationResult;
import org.bluezoo.gumdrop.ftp.FtpFileSystem;
import org.bluezoo.gumdrop.telemetry.EventLogger;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;

import java.nio.ByteBuffer;
import java.util.ResourceBundle;

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
public class SimpleFTPHandler implements FtpConnectionHandler {
    

    private EventLogger events(FtpConnectionMetadata metadata) {
        // a caller with no connection description reports through a configuration of its own
        TelemetryConfig telemetry = metadata != null ? metadata.getTelemetryConfig() : new TelemetryConfig();
        return telemetry.getLogger(SimpleFTPHandler.class, L10N);
    }
    private static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.ftp.L10N");
    
    private final FtpFileSystem fileSystem;
    private final Realm realm;
    
    public SimpleFTPHandler(FtpFileSystem fileSystem) {
        this(fileSystem, null);
    }
    
    public SimpleFTPHandler(FtpFileSystem fileSystem, Realm realm) {
        this.fileSystem = fileSystem;
        this.realm = realm;
    }
    
    @Override
    public String connected(FtpConnectionMetadata metadata) {
        String clientHost = metadata.getClientAddress() != null ? 
                           metadata.getClientAddress().getHostString() : "unknown";
        events(metadata).info("info.simple_ftp_connection")
                .attr("client_host", clientHost)
                .attr("auth_mode", realm != null ? L10N.getString("info.realm_auth") : L10N.getString("info.simple_auth")).emit();
        return null; // Use default welcome message
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
        
        String clientHost = metadata.getClientAddress() != null ? 
                           metadata.getClientAddress().getHostString() : "unknown";
        
        try {
            if (realm != null) {
                // Use Realm-based authentication
                boolean authenticated = realm.passwordMatch(username.trim(), password);
                
                if (authenticated) {
                    events(metadata).info("info.simple_ftp_realm_auth_success")
                            .attr("username", username)
                            .attr("client_host", clientHost).emit();
                    return FtpAuthenticationResult.SUCCESS;
                } else {
                    events(metadata).warn("warn.simple_ftp_auth_failed")
                            .attr("client_host", clientHost).emit();
                    return FtpAuthenticationResult.INVALID_PASSWORD;
                }
            } else {
                // Simple authentication - accept any non-empty password
                if (password.trim().isEmpty()) {
                    return FtpAuthenticationResult.INVALID_PASSWORD;
                }

                events(metadata).info("info.simple_ftp_simple_auth_success")
                        .attr("username", username)
                        .attr("client_host", clientHost).emit();
                return FtpAuthenticationResult.SUCCESS;
            }

        } catch (Exception e) {
            events(metadata).warn("warn.simple_ftp_auth_error")
                    .attr("client_host", clientHost)
                    .thrown(e).emit();
            return FtpAuthenticationResult.INVALID_PASSWORD;
        }
    }
    
    @Override
    public FtpFileSystem getFileSystem(FtpConnectionMetadata metadata) {
        return fileSystem;
    }
    
    @Override
    public void transferStarting(String path, boolean upload, long size, 
                               FtpConnectionMetadata metadata) {
        String direction = upload ? "upload" : "download";
        String sizeStr = (size >= 0) ? " (" + size + " bytes)" : "";
        events(metadata).info("info.simple_ftp_transfer_starting")
                .attr("direction", direction)
                .attr("path", path)
                .attr("size_str", sizeStr)
                .attr("authenticated_user", metadata.getAuthenticatedUser()).emit();
    }
    
    @Override
    public void transferProgress(String path, boolean upload, ByteBuffer data, 
                               long totalBytesTransferred, FtpConnectionMetadata metadata) {
        // Log progress every 1MB for demo purposes
        if (totalBytesTransferred % (1024 * 1024) == 0) {
            String direction = upload ? "upload" : "download";
            events(metadata).info("info.simple_ftp_transfer_progress")
                    .attr("direction", direction)
                    .attr("path", path)
                    .attr("total_bytes_transferred", totalBytesTransferred).emit();
        }
    }
    
    @Override
    public void transferCompleted(String path, boolean upload, long totalBytesTransferred, 
                                boolean success, FtpConnectionMetadata metadata) {
        String direction = upload ? "upload" : "download";
        String status = success ? "completed" : "failed";
        events(metadata).info("info.simple_ftp_transfer_completed")
                .attr("status", status)
                .attr("direction", direction)
                .attr("path", path)
                .attr("total_bytes_transferred", totalBytesTransferred)
                .attr("authenticated_user", metadata.getAuthenticatedUser()).emit();
    }
    
    @Override
    public FtpFileOperationResult handleSiteCommand(String command, FtpConnectionMetadata metadata) {
        // Demo SITE command handling
        events(metadata).info("info.simple_ftp_site_command")
                .attr("authenticated_user", metadata.getAuthenticatedUser())
                .attr("command", command).emit();
        
        if (command.toUpperCase().startsWith("HELP")) {
            return FtpFileOperationResult.SUCCESS;
        }
        
        return FtpFileOperationResult.NOT_SUPPORTED;
    }
    
    @Override
    public void disconnected(FtpConnectionMetadata metadata) {
        events(metadata).info("info.simple_ftp_disconnected")
                .attr("client_address", String.valueOf(metadata.getClientAddress()))
                .attr("authenticated_user", metadata.getAuthenticatedUser()).emit();
    }
}
