/*
 * FTPFileSystem.java
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

package org.bluezoo.gumdrop.ftp;

import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;

/**
 * Abstract interface for FTP file system operations.
 * 
 * <p>This interface abstracts all file system details from the FTP protocol
 * implementation, allowing handlers to work with various storage backends:
 * <ul>
 * <li>Local file system</li>
 * <li>Database-backed virtual file systems</li>
 * <li>Cloud storage (S3, Azure, etc.)</li>
 * <li>In-memory file systems</li>
 * <li>Custom business logic file systems</li>
 * </ul>
 *
 * <p>All paths are normalized Unix-style paths starting with "/" regardless
 * of the underlying storage implementation. The file system is responsible
 * for any necessary path translation.
 *
 * <p>Methods return {@link FTPFileOperationResult} enums that abstract away
 * the need to know FTP response codes - the FTP connection will handle
 * translating these results into proper protocol responses.
 *
 * <p><strong>Performance:</strong> All I/O operations use NIO channels for 
 * optimal performance with zero-copy transfers when possible.
 *
 * <p><strong>TODO:</strong> Add role-based access restrictions. File operations 
 * should check user roles/permissions from the connection metadata to enforce 
 * fine-grained access control (e.g., read-only users, directory restrictions, 
 * file type filtering, quota limits).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface FTPFileSystem {

    /**
     * Lists the contents of a directory.
     * Used for FTP LIST and NLST commands.
     *
     * @param path the directory path to list (Unix-style, starting with "/")
     * @param metadata connection metadata for authorization context
     * @return list of file information, or null if the operation failed
     */
    List<FTPFileInfo> listDirectory(String path, FTPConnectionMetadata metadata);

    /**
     * Changes the current working directory.
     * Used for FTP CWD command.
     *
     * @param path the new directory path (can be relative or absolute)
     * @param currentDirectory the current directory before the change
     * @param metadata connection metadata for authorization context
     * @return operation result and the new absolute directory path
     */
    DirectoryChangeResult changeDirectory(String path, String currentDirectory, 
                                        FTPConnectionMetadata metadata);

    /**
     * Gets information about a specific file or directory.
     * Used for various FTP commands that need to check file existence/properties.
     *
     * @param path the file or directory path
     * @param metadata connection metadata for authorization context
     * @return file information, or null if the file/directory does not exist
     */
    FTPFileInfo getFileInfo(String path, FTPConnectionMetadata metadata);

    /**
     * Creates a new directory.
     * Used for FTP MKD command.
     *
     * @param path the directory path to create
     * @param metadata connection metadata for authorization context
     * @return operation result
     */
    FTPFileOperationResult createDirectory(String path, FTPConnectionMetadata metadata);

    /**
     * Removes an empty directory.
     * Used for FTP RMD command.
     *
     * @param path the directory path to remove
     * @param metadata connection metadata for authorization context
     * @return operation result
     */
    FTPFileOperationResult removeDirectory(String path, FTPConnectionMetadata metadata);

    /**
     * Deletes a file.
     * Used for FTP DELE command.
     *
     * @param path the file path to delete
     * @param metadata connection metadata for authorization context
     * @return operation result
     */
    FTPFileOperationResult deleteFile(String path, FTPConnectionMetadata metadata);

    /**
     * Renames or moves a file or directory.
     * Used for FTP RNFR/RNTO command sequence.
     *
     * @param fromPath the current path of the file/directory
     * @param toPath the new path for the file/directory
     * @param metadata connection metadata for authorization context
     * @return operation result
     */
    FTPFileOperationResult rename(String fromPath, String toPath, 
                                FTPConnectionMetadata metadata);

    /**
     * Opens a file for reading (download) using NIO channels.
     * Used for FTP RETR command.
     *
     * <p>This method provides high-performance file reading with:
     * <ul>
     * <li>Zero-copy transfers when possible</li>
     * <li>Direct integration with SocketChannel</li>
     * <li>Memory-efficient streaming for large files</li>
     * <li>Restart offset support for resumed downloads</li>
     * </ul>
     *
     * @param path the file path to read
     * @param restartOffset byte offset to start reading from (for REST command)
     * @param metadata connection metadata for authorization context
     * @return readable channel for the file data, or null if the operation failed
     */
    ReadableByteChannel openForReading(String path, long restartOffset, 
                                     FTPConnectionMetadata metadata);

    /**
     * Opens a file for writing (upload) using NIO channels.
     * Used for FTP STOR command.
     *
     * <p>This method provides high-performance file writing with:
     * <ul>
     * <li>Zero-copy transfers when possible</li>
     * <li>Direct integration with SocketChannel</li>
     * <li>Memory-efficient streaming for large files</li>
     * <li>Append mode support</li>
     * </ul>
     *
     * @param path the file path to write
     * @param append true to append to existing file, false to overwrite
     * @param metadata connection metadata for authorization context
     * @return writable channel for writing file data, or null if the operation failed
     */
    WritableByteChannel openForWriting(String path, boolean append, 
                                     FTPConnectionMetadata metadata);

    /**
     * Generates a unique file name for store-unique operations.
     * Used for FTP STOU command.
     *
     * @param basePath the base directory path
     * @param suggestedName suggested file name (can be null)
     * @param metadata connection metadata for authorization context
     * @return unique file name result
     */
    UniqueNameResult generateUniqueName(String basePath, String suggestedName, 
                                      FTPConnectionMetadata metadata);

    /**
     * Allocates space for a file (optional operation).
     * Used for FTP ALLO command - many implementations can ignore this.
     *
     * @param path the file path that will need space
     * @param size the number of bytes to allocate
     * @param metadata connection metadata for authorization context
     * @return operation result
     */
    default FTPFileOperationResult allocateSpace(String path, long size, 
                                               FTPConnectionMetadata metadata) {
        // Default implementation treats ALLO as successful no-op
        return FTPFileOperationResult.SUCCESS;
    }

    /**
     * Result of a directory change operation.
     */
    class DirectoryChangeResult {
        private final FTPFileOperationResult result;
        private final String newDirectory;

        public DirectoryChangeResult(FTPFileOperationResult result, String newDirectory) {
            this.result = result;
            this.newDirectory = newDirectory;
        }

        public FTPFileOperationResult getResult() {
            return result;
        }

        public String getNewDirectory() {
            return newDirectory;
        }
    }

    /**
     * Result of a unique name generation operation.
     */
    class UniqueNameResult {
        private final FTPFileOperationResult result;
        private final String uniquePath;

        public UniqueNameResult(FTPFileOperationResult result, String uniquePath) {
            this.result = result;
            this.uniquePath = uniquePath;
        }

        public FTPFileOperationResult getResult() {
            return result;
        }

        public String getUniquePath() {
            return uniquePath;
        }
    }
}
