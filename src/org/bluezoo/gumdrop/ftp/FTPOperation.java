/*
 * FTPOperation.java
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
 * Categories of FTP operations for authorization purposes.
 * 
 * <p>FTP commands are grouped into logical operations that can be
 * authorized independently. This allows administrators to grant
 * specific capabilities to users based on their roles.</p>
 * 
 * <p>Mapping of FTP commands to operations:</p>
 * <ul>
 *   <li>{@link #READ} - RETR, LIST, NLST, STAT, SIZE, MDTM</li>
 *   <li>{@link #WRITE} - STOR, STOU, APPE</li>
 *   <li>{@link #DELETE} - DELE</li>
 *   <li>{@link #CREATE_DIR} - MKD</li>
 *   <li>{@link #DELETE_DIR} - RMD</li>
 *   <li>{@link #RENAME} - RNFR, RNTO</li>
 *   <li>{@link #NAVIGATE} - CWD, CDUP, PWD</li>
 *   <li>{@link #SITE_COMMAND} - SITE subcommands</li>
 *   <li>{@link #ADMIN} - Server administration operations</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see FTPRoles
 * @see FTPConnectionHandler#isAuthorized(FTPOperation, String, FTPConnectionMetadata)
 */
public enum FTPOperation {
    
    /**
     * Read operations: retrieving files and listing directories.
     * <p>Commands: RETR, LIST, NLST, STAT, SIZE, MDTM</p>
     */
    READ,
    
    /**
     * Write operations: uploading and appending files.
     * <p>Commands: STOR, STOU, APPE</p>
     */
    WRITE,
    
    /**
     * Delete file operations.
     * <p>Commands: DELE</p>
     */
    DELETE,
    
    /**
     * Create directory operations.
     * <p>Commands: MKD</p>
     */
    CREATE_DIR,
    
    /**
     * Delete directory operations.
     * <p>Commands: RMD</p>
     */
    DELETE_DIR,
    
    /**
     * Rename/move operations.
     * <p>Commands: RNFR, RNTO</p>
     */
    RENAME,
    
    /**
     * Directory navigation operations.
     * <p>Commands: CWD, CDUP, PWD</p>
     * <p>Note: These are typically always allowed for authenticated users,
     * but can be restricted for path-based access control.</p>
     */
    NAVIGATE,
    
    /**
     * SITE subcommand operations.
     * <p>Commands: SITE CHMOD, SITE UTIME, etc.</p>
     */
    SITE_COMMAND,
    
    /**
     * Administrative operations.
     * <p>Server control and account management functions.</p>
     */
    ADMIN
}

