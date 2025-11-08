/*
 * FTPConnectionHandler.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.ftp;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Handler interface for FTP connection events and business logic.
 * <p>
 * This interface abstracts away all low-level FTP protocol details and allows
 * implementers to focus purely on the business logic of file operations and
 * user management. The handler receives high-level events (authentication,
 * file operations) along with rich metadata about the connection context.
 * <p>
 * Key benefits of this abstraction:
 * <ul>
 * <li><em>Protocol Independence</em> - No need to understand FTP response codes</li>
 * <li><em>Rich Context</em> - Access to client IP, certificates, authentication status</li>
 * <li><em>Business Focused</em> - Simple enum returns for operation results</li>
 * <li><em>File System Abstraction</em> - Work with any storage backend</li>
 * <li><em>Connection Lifecycle</em> - Notifications for connection events</li>
 * </ul>
 * <p>
 * Handler instances should be created per-connection to ensure thread safety
 * and state isolation between connections:
 * <pre><code>
 * // Example implementation
 * public class MyFTPHandler implements FTPConnectionHandler {
 *     private final FTPFileSystem fileSystem;
 *     private final UserDatabase userDb;
 *
 *     public MyFTPHandler(FTPFileSystem fs, UserDatabase db) {
 *         this.fileSystem = fs;
 *         this.userDb = db;
 *     }
 *
 *     &#64;Override
 *     public FTPAuthenticationResult authenticate(String user, String password, 
 *                                               String account, FTPConnectionMetadata metadata) {
 *         if (userDb.validateCredentials(user, password)) {
 *             return FTPAuthenticationResult.SUCCESS;
 *         }
 *         return FTPAuthenticationResult.INVALID_PASSWORD;
 *     }
 *
 *     &#64;Override
 *     public FTPFileSystem getFileSystem(FTPConnectionMetadata metadata) {
 *         // Return file system scoped to authenticated user
 *         return new UserScopedFileSystem(fileSystem, metadata.getAuthenticatedUser());
 *     }
 * }
 * </code></pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see FTPAuthenticationResult
 * @see FTPFileOperationResult
 * @see FTPFileSystem
 * @see FTPConnectionMetadata
 */
public interface FTPConnectionHandler {

    /**
     * Notifies that a new client connection has been established.
     * <p>
	 * This is called immediately after the TCP connection is accepted and any
     * SSL/TLS handshake is completed. It provides an opportunity to perform
     * early connection-level filtering, logging, or initialization.
     * <p>
	 * The handler can send a custom welcome banner by returning a string,
     * or return null to use the default welcome message.
     *
     * @param metadata connection metadata with client and security information
     * @return custom welcome banner, or null for default
     */
    String connected(FTPConnectionMetadata metadata);

    /**
     * Handles FTP authentication (USER/PASS/ACCT command sequence).
     * <p>
	 * This method is called to authenticate a user with the provided credentials.
     * The implementation should validate the user against whatever authentication
     * backend is appropriate (database, LDAP, etc.).
     * <p>
	 * Authentication considerations:
     * <ul>
     * <li>Anonymous FTP (user="anonymous" or "ftp")</li>
     * <li>Standard username/password authentication</li>
     * <li>Account information for systems that require it</li>
     * <li>Rate limiting for failed attempts</li>
     * <li>Account status checking (enabled, expired, etc.)</li>
     * </ul>
     *
     * @param username the username from USER command
     * @param password the password from PASS command (can be null if not provided yet)
     * @param account the account from ACCT command (can be null if not provided)
     * @param metadata complete connection context
     * @return authentication result indicating success, failure, or need for more information
     */
    FTPAuthenticationResult authenticate(String username, String password, 
                                       String account, FTPConnectionMetadata metadata);

    /**
     * Provides the file system implementation for this connection.
     * <p>
	 * This method is called after successful authentication to get the
     * file system that should be used for this user's session. Different
     * users may get different file system implementations with different
     * access rights, root directories, etc.
     * <p>
	 * Examples:
     * <ul>
     * <li>Anonymous users get read-only access to public directory</li>
     * <li>Regular users get access to their home directory</li>
     * <li>Admin users get full file system access</li>
     * <li>Virtual users get database-backed file systems</li>
     * </ul>
     *
     * @param metadata connection context including authenticated user
     * @return file system implementation for this user
     */
    FTPFileSystem getFileSystem(FTPConnectionMetadata metadata);

    /**
     * Called when a data transfer (upload/download) is starting.
     * <p>
	 * This provides an opportunity to set up logging, progress tracking,
     * virus scanning, or other transfer-related processing.
     *
     * @param path the file path being transferred
     * @param upload true for upload (STOR), false for download (RETR)
     * @param size expected transfer size in bytes (-1 if unknown)
     * @param metadata connection context
     */
    void transferStarting(String path, boolean upload, long size, 
                         FTPConnectionMetadata metadata);

    /**
     * Called during data transfer to provide transfer progress updates.
     * <p>
	 * This method is called periodically during file transfers with
     * chunks of data. It can be used for:
     * <ul>
     * <li>Progress tracking and reporting</li>
     * <li>Real-time virus scanning</li>
     * <li>Content filtering</li>
     * <li>Transfer rate limiting</li>
     * <li>Custom logging</li>
     * </ul>
     * <p>
	 * The handler should process the data quickly to avoid slowing
     * down the transfer.
     *
     * @param path the file path being transferred
     * @param upload true for upload, false for download
     * @param data the data chunk being transferred
     * @param totalBytesTransferred total bytes transferred so far
     * @param metadata connection context
     */
    void transferProgress(String path, boolean upload, ByteBuffer data, 
                         long totalBytesTransferred, FTPConnectionMetadata metadata);

    /**
     * Called when a data transfer has completed.
     * <p>
	 * This provides an opportunity for cleanup, final processing,
     * statistics recording, etc.
     *
     * @param path the file path that was transferred
     * @param upload true for upload, false for download
     * @param totalBytesTransferred total bytes successfully transferred
     * @param success true if transfer completed successfully, false if aborted/failed
     * @param metadata connection context
     */
    void transferCompleted(String path, boolean upload, long totalBytesTransferred, 
                          boolean success, FTPConnectionMetadata metadata);

    /**
     * Handles SITE-specific commands.
     * <p>
	 * The SITE command allows servers to implement custom, non-standard
     * commands. This method receives the SITE command arguments and can
     * implement whatever custom functionality is needed.
     * <p>
	 * Examples:
     * <ul>
     * <li>SITE CHMOD - change file permissions</li>
     * <li>SITE DISK - show disk usage</li>
     * <li>SITE HELP - show custom help</li>
     * </ul>
     *
     * @param command the SITE command and arguments
     * @param metadata connection context
     * @return operation result, or NOT_SUPPORTED if command is not recognized
     */
    FTPFileOperationResult handleSiteCommand(String command, FTPConnectionMetadata metadata);

    /**
     * Notifies that the client connection has been closed.
     * <p>
	 * This is called when the client disconnects (gracefully with QUIT or
     * abruptly due to network issues). The handler should perform final cleanup
     * and resource management.
     * <p>
	 * Cleanup actions might include:
     * <ul>
     * <li>Closing files or streams</li>
     * <li>Updating connection statistics</li>
     * <li>Logging connection summary</li>
     * <li>Releasing any held resources</li>
     * </ul>
     *
     * @param metadata final connection context with duration and statistics
     */
    void disconnected(FTPConnectionMetadata metadata);

}
