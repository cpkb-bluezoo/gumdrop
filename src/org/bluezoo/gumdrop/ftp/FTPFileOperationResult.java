/*
 * FTPFileOperationResult.java
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

/**
 * Defines the possible outcomes for FTP file and directory operations.
 * Each result corresponds to specific FTP response codes and indicates how the
 * server should respond to file system commands like RETR, STOR, DELE, etc.
 * <p>
 * This abstraction allows handler implementations to focus on file system
 * logic without needing to know FTP protocol response codes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum FTPFileOperationResult {
	
    /**
     * Operation completed successfully (250, 226).
     * The requested file system operation was successful.
     */
    SUCCESS,

    /**
     * File transfer starting (150).
     * Used to indicate that a data transfer is about to begin.
     */
    TRANSFER_STARTING,

    /**
     * File or directory not found (550).
     * The specified path does not exist in the file system.
     */
    NOT_FOUND,

    /**
     * Access denied (550).
     * The user does not have permission to perform this operation.
     */
    ACCESS_DENIED,

    /**
     * File already exists (550).
     * Attempted to create a file or directory that already exists.
     */
    ALREADY_EXISTS,

    /**
     * Directory not empty (550).
     * Attempted to delete a directory that contains files or subdirectories.
     */
    DIRECTORY_NOT_EMPTY,

    /**
     * Insufficient storage space (552).
     * The file system does not have enough space for the operation.
     */
    INSUFFICIENT_SPACE,

    /**
     * Invalid file name (553).
     * The specified file or directory name contains invalid characters.
     */
    INVALID_NAME,

    /**
     * File system error (550).
     * A general file system error occurred during the operation.
     */
    FILE_SYSTEM_ERROR,

    /**
     * Operation not supported (502).
     * The requested operation is not supported by this implementation.
     */
    NOT_SUPPORTED,

    /**
     * File is locked or in use (550).
     * The file cannot be accessed because it is locked by another process.
     */
    FILE_LOCKED,

    /**
     * Path is a directory, not a file (550).
     * Attempted a file operation on a directory path.
     */
    IS_DIRECTORY,

    /**
     * Path is a file, not a directory (550).
     * Attempted a directory operation on a file path.
     */
    IS_FILE,

    /**
     * Quota exceeded (552).
     * The user has exceeded their disk quota.
     */
    QUOTA_EXCEEDED,

    /**
     * Rename operation needs target (350).
    * Used for RNFR command - source is valid, waiting for RNTO.
     */
    RENAME_PENDING;

}
