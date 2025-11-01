/*
 * FTPAbstractionDemo.java
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

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Demonstration of the FTP abstraction layer design.
 * This shows how to implement a complete FTP server using the high-level
 * interfaces without knowing anything about FTP protocol details.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FTPAbstractionDemo {

    private static final Logger LOGGER = Logger.getLogger(FTPAbstractionDemo.class.getName());

    /**
     * Example implementation of FTPConnectionHandler that provides
     * a simple in-memory file system with basic authentication.
     */
    static class DemoFTPHandler implements FTPConnectionHandler {
        
        private final Map<String, String> users; // username -> password
        private final InMemoryFileSystem fileSystem;

        public DemoFTPHandler() {
            // Set up demo users
            users = new HashMap<>();
            users.put("admin", "secret");
            users.put("user", "password"); 
            users.put("anonymous", ""); // Anonymous FTP
            
            // Create demo file system
            fileSystem = new InMemoryFileSystem();
        }

        @Override
        public String connected(FTPConnectionMetadata metadata) {
            String clientIP = metadata.getClientAddress().getAddress().getHostAddress();
            LOGGER.info("New FTP connection from " + clientIP);
            return "Welcome to Demo FTP Server";
        }

        @Override
        public FTPAuthenticationResult authenticate(String username, String password, 
                                                  String account, FTPConnectionMetadata metadata) {
            LOGGER.info("Authentication attempt for user: " + username);
            
            if (username == null) {
                return FTPAuthenticationResult.INVALID_USER;
            }
            
            // Handle anonymous FTP
            if ("anonymous".equals(username) || "ftp".equals(username)) {
                return FTPAuthenticationResult.SUCCESS;
            }
            
            // Check if user exists
            if (!users.containsKey(username)) {
                return FTPAuthenticationResult.INVALID_USER;
            }
            
            // If password not provided yet, request it
            if (password == null) {
                return FTPAuthenticationResult.NEED_PASSWORD;
            }
            
            // Validate password
            String expectedPassword = users.get(username);
            if (expectedPassword.equals(password)) {
                LOGGER.info("User " + username + " authenticated successfully");
                return FTPAuthenticationResult.SUCCESS;
            } else {
                LOGGER.warning("Invalid password for user: " + username);
                return FTPAuthenticationResult.INVALID_PASSWORD;
            }
        }

        @Override
        public FTPFileSystem getFileSystem(FTPConnectionMetadata metadata) {
            // Could return different file systems based on user
            String user = metadata.getAuthenticatedUser();
            LOGGER.info("Providing file system for user: " + user);
            return fileSystem;
        }

        @Override
        public void transferStarting(String path, boolean upload, long size, 
                                   FTPConnectionMetadata metadata) {
            String operation = upload ? "upload" : "download";
            LOGGER.info("Starting " + operation + " of " + path + " (" + size + " bytes)");
        }

        @Override
        public void transferProgress(String path, boolean upload, ByteBuffer data, 
                                   long totalBytesTransferred, FTPConnectionMetadata metadata) {
            // Could implement progress tracking, virus scanning, etc.
            if (totalBytesTransferred % 1024 == 0) {
                LOGGER.fine("Transfer progress: " + totalBytesTransferred + " bytes");
            }
        }

        @Override
        public void transferCompleted(String path, boolean upload, long totalBytesTransferred, 
                                    boolean success, FTPConnectionMetadata metadata) {
            String operation = upload ? "upload" : "download";
            String result = success ? "completed" : "failed";
            LOGGER.info("Transfer " + result + ": " + operation + " of " + path + 
                       " (" + totalBytesTransferred + " bytes)");
        }

        @Override
        public FTPFileOperationResult handleSiteCommand(String command, FTPConnectionMetadata metadata) {
            LOGGER.info("SITE command: " + command);
            
            if (command.startsWith("HELP")) {
                // Could send custom help text
                return FTPFileOperationResult.SUCCESS;
            }
            
            return FTPFileOperationResult.NOT_SUPPORTED;
        }

        @Override
        public void disconnected(FTPConnectionMetadata metadata) {
            String clientIP = metadata.getClientAddress().getAddress().getHostAddress();
            long duration = metadata.getConnectionDurationMillis();
            LOGGER.info("FTP connection closed from " + clientIP + " (duration: " + duration + "ms)");
        }
    }

    /**
     * Example implementation of FTPFileSystem using in-memory storage.
     * In real applications, this would be backed by actual file systems,
     * databases, cloud storage, etc.
     */
    static class InMemoryFileSystem implements FTPFileSystem {

        private final Map<String, FTPFileInfo> files;

        public InMemoryFileSystem() {
            files = new HashMap<>();
            
            // Create some demo files and directories
            files.put("/", new FTPFileInfo("", Instant.now(), "root", "root", "rwxr-xr-x"));
            files.put("/readme.txt", new FTPFileInfo("readme.txt", 1024, Instant.now(), 
                                                   "admin", "users", "rw-r--r--"));
            files.put("/pub", new FTPFileInfo("pub", Instant.now(), "admin", "users", "rwxr-xr-x"));
            files.put("/pub/demo.txt", new FTPFileInfo("demo.txt", 512, Instant.now(), 
                                                     "admin", "users", "rw-r--r--"));
        }

        @Override
        public List<FTPFileInfo> listDirectory(String path, FTPConnectionMetadata metadata) {
            List<FTPFileInfo> result = new ArrayList<>();
            
            // Find all files in the specified directory
            for (Map.Entry<String, FTPFileInfo> entry : files.entrySet()) {
                String filePath = entry.getKey();
                
                // Skip the directory itself
                if (filePath.equals(path)) {
                    continue;
                }
                
                // Check if file is in this directory
                if (filePath.startsWith(path) && !path.equals("/")) {
                    String relativePath = filePath.substring(path.length());
                    if (!relativePath.contains("/") || (relativePath.startsWith("/") && 
                        relativePath.substring(1).indexOf("/") == -1)) {
                        result.add(entry.getValue());
                    }
                } else if (path.equals("/")) {
                    // Root directory - only show immediate children
                    if (!filePath.equals("/") && filePath.substring(1).indexOf("/") == -1) {
                        result.add(entry.getValue());
                    }
                }
            }
            
            return result;
        }

        @Override
        public DirectoryChangeResult changeDirectory(String path, String currentDirectory, 
                                                   FTPConnectionMetadata metadata) {
            // Resolve relative paths
            String newPath;
            if (path.startsWith("/")) {
                newPath = path;
            } else if ("..".equals(path)) {
                int lastSlash = currentDirectory.lastIndexOf("/");
                newPath = lastSlash > 0 ? currentDirectory.substring(0, lastSlash) : "/";
            } else {
                newPath = currentDirectory.equals("/") ? "/" + path : currentDirectory + "/" + path;
            }
            
            // Check if directory exists
            FTPFileInfo info = files.get(newPath);
            if (info != null && info.isDirectory()) {
                return new DirectoryChangeResult(FTPFileOperationResult.SUCCESS, newPath);
            } else {
                return new DirectoryChangeResult(FTPFileOperationResult.NOT_FOUND, currentDirectory);
            }
        }

        @Override
        public FTPFileInfo getFileInfo(String path, FTPConnectionMetadata metadata) {
            return files.get(path);
        }

        @Override
        public FTPFileOperationResult createDirectory(String path, FTPConnectionMetadata metadata) {
            if (files.containsKey(path)) {
                return FTPFileOperationResult.ALREADY_EXISTS;
            }
            
            files.put(path, new FTPFileInfo(getBaseName(path), Instant.now(), 
                                          metadata.getAuthenticatedUser(), "users", "rwxr-xr-x"));
            return FTPFileOperationResult.SUCCESS;
        }

        @Override
        public FTPFileOperationResult removeDirectory(String path, FTPConnectionMetadata metadata) {
            FTPFileInfo info = files.get(path);
            if (info == null) {
                return FTPFileOperationResult.NOT_FOUND;
            }
            if (!info.isDirectory()) {
                return FTPFileOperationResult.IS_FILE;
            }
            
            // Check if directory is empty
            for (String filePath : files.keySet()) {
                if (!filePath.equals(path) && filePath.startsWith(path + "/")) {
                    return FTPFileOperationResult.DIRECTORY_NOT_EMPTY;
                }
            }
            
            files.remove(path);
            return FTPFileOperationResult.SUCCESS;
        }

        @Override
        public FTPFileOperationResult deleteFile(String path, FTPConnectionMetadata metadata) {
            FTPFileInfo info = files.get(path);
            if (info == null) {
                return FTPFileOperationResult.NOT_FOUND;
            }
            if (info.isDirectory()) {
                return FTPFileOperationResult.IS_DIRECTORY;
            }
            
            files.remove(path);
            return FTPFileOperationResult.SUCCESS;
        }

        @Override
        public FTPFileOperationResult rename(String fromPath, String toPath, 
                                           FTPConnectionMetadata metadata) {
            FTPFileInfo info = files.get(fromPath);
            if (info == null) {
                return FTPFileOperationResult.NOT_FOUND;
            }
            if (files.containsKey(toPath)) {
                return FTPFileOperationResult.ALREADY_EXISTS;
            }
            
            // Create new entry with new name
            String newBaseName = getBaseName(toPath);
            FTPFileInfo newInfo;
            if (info.isDirectory()) {
                newInfo = new FTPFileInfo(newBaseName, info.getLastModified(), 
                                        info.getOwner(), info.getGroup(), info.getPermissions());
            } else {
                newInfo = new FTPFileInfo(newBaseName, info.getSize(), info.getLastModified(),
                                        info.getOwner(), info.getGroup(), info.getPermissions());
            }
            
            files.remove(fromPath);
            files.put(toPath, newInfo);
            return FTPFileOperationResult.SUCCESS;
        }

        @Override
        public InputStream openForReading(String path, long restartOffset, 
                                        FTPConnectionMetadata metadata) {
            // In a real implementation, this would return an InputStream for the file
            return null; // Placeholder
        }

        @Override
        public OutputStream openForWriting(String path, boolean append, 
                                         FTPConnectionMetadata metadata) {
            // In a real implementation, this would return an OutputStream for the file
            return null; // Placeholder
        }

        @Override
        public UniqueNameResult generateUniqueName(String basePath, String suggestedName, 
                                                 FTPConnectionMetadata metadata) {
            String uniquePath = basePath + "/" + System.currentTimeMillis() + ".tmp";
            return new UniqueNameResult(FTPFileOperationResult.SUCCESS, uniquePath);
        }

        private String getBaseName(String path) {
            int lastSlash = path.lastIndexOf("/");
            return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        }
    }

    /**
     * Demonstrates how to configure an FTP connector with the abstraction layer.
     */
    public static void demonstrateConfiguration() {
        System.out.println("FTP Abstraction Layer Design Demo");
        System.out.println("================================");
        
        // This shows how simple it is to configure FTP with the new abstraction:
        /*
        FTPConnector ftpConnector = new FTPConnector();
        ftpConnector.setPort(21);
        
        // Set up handler factory - each connection gets its own handler instance
        ftpConnector.setHandlerFactory(() -> new DemoFTPHandler());
        
        // That's it! The FTP server is ready with:
        // - Complete FTP protocol implementation
        // - Authentication handling
        // - File system operations
        // - Data transfer management
        // - All without knowing any FTP protocol details!
        */
        
        System.out.println("✓ FTPConnectionHandler - Business logic abstraction");
        System.out.println("✓ FTPFileSystem - Storage abstraction");
        System.out.println("✓ FTPAuthenticationResult - Simple auth outcomes");
        System.out.println("✓ FTPFileOperationResult - Simple operation results");
        System.out.println("✓ FTPConnectionMetadata - Rich connection context");
        System.out.println("✓ FTPFileInfo - File/directory metadata");
        
        System.out.println("\nKey Benefits:");
        System.out.println("• No FTP protocol knowledge required");
        System.out.println("• Work with any storage backend (files, DB, cloud)");
        System.out.println("• Focus on business logic, not protocol details");
        System.out.println("• Thread-safe per-connection handlers");
        System.out.println("• Rich metadata for authorization and logging");
        System.out.println("• Similar design to SMTPConnectionHandler");
        
        System.out.println("\nExample use cases:");
        System.out.println("• Traditional file server with local filesystem");
        System.out.println("• Virtual file system backed by database");
        System.out.println("• Cloud storage proxy (S3, Azure, etc.)");
        System.out.println("• Custom business applications over FTP");
    }

    public static void main(String[] args) {
        demonstrateConfiguration();
    }
}
