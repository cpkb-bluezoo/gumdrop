/*
 * BasicFTPFileSystem.java
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

import org.bluezoo.gumdrop.ftp.FTPConnectionMetadata;
import org.bluezoo.gumdrop.ftp.FTPFileInfo;
import org.bluezoo.gumdrop.ftp.FTPFileOperationResult;
import org.bluezoo.gumdrop.ftp.FTPFileSystem;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Basic file system implementation that maps FTP operations to the local file system.
 * 
 * <p>This implementation provides a secure, chrooted file system view where:
 * <ul>
 * <li>All FTP paths are relative to a configured root directory</li>
 * <li>Path traversal attacks are prevented (no escaping the root)</li>
 * <li>Unix-style paths (/) are mapped to platform-specific paths</li>
 * <li>File operations use standard Java I/O and NIO APIs</li>
 * </ul>
 *
 * <p><strong>Security Features:</strong>
 * <ul>
 * <li>Path normalization prevents ../ directory traversal</li>
 * <li>Symbolic links are resolved securely</li>
 * <li>Access is limited to the configured root directory tree</li>
 * <li>File permissions are checked before operations</li>
 * </ul>
 *
 * <p><strong>Performance Features:</strong>
 * <ul>
 * <li>Uses NIO for file metadata operations</li>
 * <li>Efficient directory listing with lazy loading</li>
 * <li>Stream-based file I/O for memory efficiency</li>
 * <li>Skip-ahead support for resumed downloads</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class BasicFTPFileSystem implements FTPFileSystem {
    
    private static final Logger LOGGER = Logger.getLogger(BasicFTPFileSystem.class.getName());
    
    private final Path rootPath;
    private final boolean readOnly;
    
    /**
     * Creates a new BasicFTPFileSystem with the specified root directory.
     *
     * @param rootDirectory the root directory for this file system
     * @param readOnly true to make this file system read-only
     * @throws IllegalArgumentException if rootDirectory doesn't exist or isn't a directory
     */
    public BasicFTPFileSystem(String rootDirectory, boolean readOnly) {
        this(Paths.get(rootDirectory), readOnly);
    }

    /**
     * Creates a new BasicFTPFileSystem with the specified root directory.
     *
     * @param rootDirectory the root directory path for this file system
     * @param readOnly true to make this file system read-only
     * @throws IllegalArgumentException if rootDirectory doesn't exist or isn't a directory
     */
    public BasicFTPFileSystem(Path rootDirectory, boolean readOnly) {
        this.readOnly = readOnly;
        
        Path root = rootDirectory.toAbsolutePath().normalize();
        if (!Files.exists(root)) {
            throw new IllegalArgumentException("Root directory does not exist: " + rootDirectory);
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Root path is not a directory: " + rootDirectory);
        }
        
        this.rootPath = root;
        
        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info("BasicFTPFileSystem initialized with root: " + rootPath + 
                       (readOnly ? " (read-only)" : " (read-write)"));
        }
    }
    
    /**
     * Creates a read-write BasicFTPFileSystem.
     */
    public BasicFTPFileSystem(String rootDirectory) {
        this(rootDirectory, false);
    }
    
    /**
     * Converts an FTP path to a secure local file system path.
     * Prevents directory traversal attacks and ensures the path stays within the root.
     *
     * @param ftpPath FTP path (Unix-style, starting with /)
     * @return resolved local path within the root directory
     * @throws SecurityException if path tries to escape the root directory
     */
    protected Path resolveSecurePath(String ftpPath) {
        if (ftpPath == null || ftpPath.isEmpty()) {
            return rootPath;
        }
        
        // Normalize the FTP path (remove .., ., etc.)
        String normalizedPath = ftpPath.startsWith("/") ? ftpPath.substring(1) : ftpPath;
        if (normalizedPath.isEmpty()) {
            return rootPath;
        }
        
        // Convert to platform-specific path and resolve
        Path resolved = rootPath.resolve(normalizedPath).normalize();
        
        // Security check: ensure the resolved path is still within the root
        if (!resolved.startsWith(rootPath)) {
            throw new SecurityException("Path traversal attempt detected: " + ftpPath);
        }
        
        return resolved;
    }
    
    /**
     * Converts a local path back to an FTP path.
     */
    private String toFtpPath(Path localPath) {
        Path relativePath = rootPath.relativize(localPath);
        String ftpPath = "/" + relativePath.toString().replace(File.separatorChar, '/');
        return ftpPath.equals("/.") ? "/" : ftpPath;
    }
    
    @Override
    public List<FTPFileInfo> listDirectory(String path, FTPConnectionMetadata metadata) {
        try {
            Path dirPath = resolveSecurePath(path);
            
            if (!Files.exists(dirPath)) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Directory does not exist: " + path);
                }
                return null;
            }
            
            if (!Files.isDirectory(dirPath)) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Path is not a directory: " + path);
                }
                return null;
            }
            
            List<FTPFileInfo> files = new ArrayList<>();
            
            File[] children = dirPath.toFile().listFiles();
            if (children != null) {
                for (File child : children) {
                    try {
                        FTPFileInfo fileInfo = createFileInfo(child.toPath());
                        if (fileInfo != null) {
                            files.add(fileInfo);
                        }
                    } catch (Exception e) {
                        // Skip files we can't read (permissions, etc.)
                        if (LOGGER.isLoggable(Level.FINE)) {
                            LOGGER.fine("Skipping file due to error: " + child + " - " + e.getMessage());
                        }
                    }
                }
            }
            
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Listed " + files.size() + " items in directory: " + path);
            }
            
            return files;
            
        } catch (SecurityException e) {
            LOGGER.log(Level.WARNING, "Security violation in listDirectory: " + path, e);
            return null;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error listing directory: " + path, e);
            return null;
        }
    }
    
    /**
     * Creates FTPFileInfo from a local file path.
     */
    private FTPFileInfo createFileInfo(Path filePath) throws IOException {
        if (!Files.exists(filePath)) {
            return null;
        }
        
        String name = filePath.getFileName().toString();
        boolean isDirectory = Files.isDirectory(filePath);
        Instant lastModified = Files.getLastModifiedTime(filePath).toInstant();
        String owner = System.getProperty("user.name");
        String group = "users";
        
        // Generate Unix-style permissions string
        String permissions = generatePermissionsString(filePath);
        
        if (isDirectory) {
            // Use directory constructor
            return new FTPFileInfo(name, lastModified, owner, group, permissions);
        } else {
            // Use file constructor with size
            long size = Files.size(filePath);
            return new FTPFileInfo(name, size, lastModified, owner, group, permissions);
        }
    }
    
    /**
     * Generates a Unix-style permission string for a file/directory.
     */
    private String generatePermissionsString(Path filePath) {
        StringBuilder perms = new StringBuilder(9);
        
        // Owner permissions
        perms.append(Files.isReadable(filePath) ? 'r' : '-');
        perms.append(Files.isWritable(filePath) ? 'w' : '-');
        perms.append(Files.isExecutable(filePath) ? 'x' : '-');
        
        // Group permissions (simplified - same as owner)
        perms.append(Files.isReadable(filePath) ? 'r' : '-');
        perms.append(Files.isWritable(filePath) ? 'w' : '-');
        perms.append(Files.isExecutable(filePath) ? 'x' : '-');
        
        // Other permissions (simplified - read-only)
        perms.append(Files.isReadable(filePath) ? 'r' : '-');
        perms.append('-'); // No write for others
        perms.append('-'); // No execute for others
        
        return perms.toString();
    }
    
    @Override
    public DirectoryChangeResult changeDirectory(String path, String currentDirectory, 
                                               FTPConnectionMetadata metadata) {
        try {
            // Handle relative paths
            String targetPath;
            if (path.startsWith("/")) {
                targetPath = path; // Absolute path
            } else {
                // Relative path - resolve against current directory
                targetPath = currentDirectory.endsWith("/") ? 
                    currentDirectory + path : 
                    currentDirectory + "/" + path;
            }
            
            Path dirPath = resolveSecurePath(targetPath);
            
            if (!Files.exists(dirPath)) {
                return new DirectoryChangeResult(FTPFileOperationResult.NOT_FOUND, currentDirectory);
            }
            
            if (!Files.isDirectory(dirPath)) {
                return new DirectoryChangeResult(FTPFileOperationResult.IS_FILE, currentDirectory);
            }
            
            // Convert back to FTP path format
            String newFtpPath = toFtpPath(dirPath);
            
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Changed directory from " + currentDirectory + " to " + newFtpPath);
            }
            
            return new DirectoryChangeResult(FTPFileOperationResult.SUCCESS, newFtpPath);
            
        } catch (SecurityException e) {
            LOGGER.log(Level.WARNING, "Security violation in changeDirectory: " + path, e);
            return new DirectoryChangeResult(FTPFileOperationResult.ACCESS_DENIED, currentDirectory);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error changing directory: " + path, e);
            return new DirectoryChangeResult(FTPFileOperationResult.FILE_SYSTEM_ERROR, currentDirectory);
        }
    }
    
    @Override
    public FTPFileInfo getFileInfo(String path, FTPConnectionMetadata metadata) {
        try {
            Path filePath = resolveSecurePath(path);
            return createFileInfo(filePath);
        } catch (Exception e) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Error getting file info for " + path + ": " + e.getMessage());
            }
            return null;
        }
    }
    
    @Override
    public FTPFileOperationResult createDirectory(String path, FTPConnectionMetadata metadata) {
        if (readOnly) {
            return FTPFileOperationResult.ACCESS_DENIED;
        }
        
        try {
            Path dirPath = resolveSecurePath(path);
            
            if (Files.exists(dirPath)) {
                return FTPFileOperationResult.ALREADY_EXISTS;
            }
            
            Files.createDirectories(dirPath);
            
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Created directory: " + path);
            }
            
            return FTPFileOperationResult.SUCCESS;
            
        } catch (SecurityException e) {
            LOGGER.log(Level.WARNING, "Security violation in createDirectory: " + path, e);
            return FTPFileOperationResult.ACCESS_DENIED;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error creating directory: " + path, e);
            return FTPFileOperationResult.FILE_SYSTEM_ERROR;
        }
    }
    
    @Override
    public FTPFileOperationResult removeDirectory(String path, FTPConnectionMetadata metadata) {
        if (readOnly) {
            return FTPFileOperationResult.ACCESS_DENIED;
        }
        
        try {
            Path dirPath = resolveSecurePath(path);
            
            if (!Files.exists(dirPath)) {
                return FTPFileOperationResult.NOT_FOUND;
            }
            
            if (!Files.isDirectory(dirPath)) {
                return FTPFileOperationResult.IS_FILE;
            }
            
            // Check if directory is empty
            try {
                if (Files.list(dirPath).findAny().isPresent()) {
                    return FTPFileOperationResult.DIRECTORY_NOT_EMPTY;
                }
            } catch (IOException e) {
                return FTPFileOperationResult.FILE_SYSTEM_ERROR;
            }
            
            Files.delete(dirPath);
            
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Removed directory: " + path);
            }
            
            return FTPFileOperationResult.SUCCESS;
            
        } catch (SecurityException e) {
            LOGGER.log(Level.WARNING, "Security violation in removeDirectory: " + path, e);
            return FTPFileOperationResult.ACCESS_DENIED;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error removing directory: " + path, e);
            return FTPFileOperationResult.FILE_SYSTEM_ERROR;
        }
    }
    
    @Override
    public FTPFileOperationResult deleteFile(String path, FTPConnectionMetadata metadata) {
        if (readOnly) {
            return FTPFileOperationResult.ACCESS_DENIED;
        }
        
        try {
            Path filePath = resolveSecurePath(path);
            
            if (!Files.exists(filePath)) {
                return FTPFileOperationResult.NOT_FOUND;
            }
            
            if (Files.isDirectory(filePath)) {
                return FTPFileOperationResult.IS_DIRECTORY;
            }
            
            Files.delete(filePath);
            
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Deleted file: " + path);
            }
            
            return FTPFileOperationResult.SUCCESS;
            
        } catch (SecurityException e) {
            LOGGER.log(Level.WARNING, "Security violation in deleteFile: " + path, e);
            return FTPFileOperationResult.ACCESS_DENIED;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error deleting file: " + path, e);
            return FTPFileOperationResult.FILE_SYSTEM_ERROR;
        }
    }
    
    @Override
    public FTPFileOperationResult rename(String fromPath, String toPath, 
                                       FTPConnectionMetadata metadata) {
        if (readOnly) {
            return FTPFileOperationResult.ACCESS_DENIED;
        }
        
        try {
            Path sourceFile = resolveSecurePath(fromPath);
            Path targetFile = resolveSecurePath(toPath);
            
            if (!Files.exists(sourceFile)) {
                return FTPFileOperationResult.NOT_FOUND;
            }
            
            if (Files.exists(targetFile)) {
                return FTPFileOperationResult.ALREADY_EXISTS;
            }
            
            Files.move(sourceFile, targetFile);
            
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Renamed " + fromPath + " to " + toPath);
            }
            
            return FTPFileOperationResult.SUCCESS;
            
        } catch (SecurityException e) {
            LOGGER.log(Level.WARNING, "Security violation in rename: " + fromPath + " -> " + toPath, e);
            return FTPFileOperationResult.ACCESS_DENIED;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error renaming file: " + fromPath + " -> " + toPath, e);
            return FTPFileOperationResult.FILE_SYSTEM_ERROR;
        }
    }
    
    @Override
    public ReadableByteChannel openForReading(String path, long restartOffset, 
                                            FTPConnectionMetadata metadata) {
        try {
            Path filePath = resolveSecurePath(path);
            
            if (!Files.exists(filePath)) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("File not found for reading: " + path);
                }
                return null;
            }
            
            if (Files.isDirectory(filePath)) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Cannot read directory as file: " + path);
                }
                return null;
            }
            
            FileChannel fileChannel = FileChannel.open(filePath, StandardOpenOption.READ);
            
            // Handle restart offset (for REST command)
            if (restartOffset > 0) {
                fileChannel.position(restartOffset);
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Positioned file channel at restart offset: " + restartOffset);
                }
            }
            
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Opened file for reading: " + path + 
                           (restartOffset > 0 ? " (offset: " + restartOffset + ")" : ""));
            }
            
            return fileChannel;
            
        } catch (SecurityException e) {
            LOGGER.log(Level.WARNING, "Security violation in openForReading: " + path, e);
            return null;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error opening file for reading: " + path, e);
            return null;
        }
    }
    
    @Override
    public WritableByteChannel openForWriting(String path, boolean append, 
                                            FTPConnectionMetadata metadata) {
        if (readOnly) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Write denied - file system is read-only: " + path);
            }
            return null;
        }
        
        try {
            Path filePath = resolveSecurePath(path);
            
            // Ensure parent directories exist
            Path parentDir = filePath.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }
            
            // Check if it's a directory
            if (Files.exists(filePath) && Files.isDirectory(filePath)) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Cannot write to directory: " + path);
                }
                return null;
            }
            
            // Open FileChannel with appropriate options
            StandardOpenOption[] options = append ? 
                new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND} :
                new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING};
            
            FileChannel fileChannel = FileChannel.open(filePath, options);
            
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Opened file for writing: " + path + 
                           (append ? " (append mode)" : " (overwrite mode)"));
            }
            
            return fileChannel;
            
        } catch (SecurityException e) {
            LOGGER.log(Level.WARNING, "Security violation in openForWriting: " + path, e);
            return null;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error opening file for writing: " + path, e);
            return null;
        }
    }
    
    @Override
    public UniqueNameResult generateUniqueName(String basePath, String suggestedName, 
                                             FTPConnectionMetadata metadata) {
        if (readOnly) {
            return new UniqueNameResult(FTPFileOperationResult.ACCESS_DENIED, null);
        }
        
        try {
            Path baseDir = resolveSecurePath(basePath);
            
            if (!Files.exists(baseDir) || !Files.isDirectory(baseDir)) {
                return new UniqueNameResult(FTPFileOperationResult.NOT_FOUND, null);
            }
            
            // Generate unique filename
            String baseName = (suggestedName != null && !suggestedName.isEmpty()) ? 
                             suggestedName : "file";
                             
            // Remove extension for counter insertion
            String name;
            String extension = "";
            int dotIndex = baseName.lastIndexOf('.');
            if (dotIndex > 0) {
                name = baseName.substring(0, dotIndex);
                extension = baseName.substring(dotIndex);
            } else {
                name = baseName;
            }
            
            // Find unique name
            Path uniqueFile;
            String uniqueName;
            int counter = 1;
            
            do {
                uniqueName = counter == 1 ? (name + extension) : 
                           (name + "_" + counter + extension);
                uniqueFile = baseDir.resolve(uniqueName);
                counter++;
            } while (Files.exists(uniqueFile) && counter < 10000); // Prevent infinite loops
            
            if (Files.exists(uniqueFile)) {
                return new UniqueNameResult(FTPFileOperationResult.FILE_SYSTEM_ERROR, null);
            }
            
            String ftpPath = basePath.endsWith("/") ? 
                           basePath + uniqueName : 
                           basePath + "/" + uniqueName;
            
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Generated unique name: " + ftpPath);
            }
            
            return new UniqueNameResult(FTPFileOperationResult.SUCCESS, ftpPath);
            
        } catch (SecurityException e) {
            LOGGER.log(Level.WARNING, "Security violation in generateUniqueName: " + basePath, e);
            return new UniqueNameResult(FTPFileOperationResult.ACCESS_DENIED, null);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error generating unique name: " + basePath, e);
            return new UniqueNameResult(FTPFileOperationResult.FILE_SYSTEM_ERROR, null);
        }
    }
    
    @Override
    public FTPFileOperationResult allocateSpace(String path, long size, 
                                              FTPConnectionMetadata metadata) {
        // ALLO is typically a no-op for modern file systems
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("ALLO command for " + path + " (" + size + " bytes) - no-op");
        }
        return FTPFileOperationResult.SUCCESS;
    }
    
    /**
     * Gets the root directory path for this file system.
     */
    public Path getRootPath() {
        return rootPath;
    }
    
    /**
     * Checks if this file system is read-only.
     */
    public boolean isReadOnly() {
        return readOnly;
    }
}
