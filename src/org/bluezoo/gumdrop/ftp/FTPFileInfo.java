/*
 * FTPFileInfo.java
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

import java.time.Instant;
import java.util.ResourceBundle;

/**
 * Represents metadata about a file or directory in the FTP file system.
 * This class abstracts file system details from the FTP protocol implementation,
 * allowing handlers to work with various storage backends (filesystem, database, etc.).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FTPFileInfo {
    private static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.ftp.L10N");
    
    private final String name;
    private final boolean directory;
    private final long size;
    private final Instant lastModified;
    private final String owner;
    private final String group;
    private final String permissions;

    /**
     * Creates file information for a regular file.
     *
     * @param name the file name
     * @param size the file size in bytes
     * @param lastModified when the file was last modified
     * @param owner the file owner (can be null)
     * @param group the file group (can be null)  
     * @param permissions Unix-style permissions string (e.g., "rw-r--r--")
     */
    public FTPFileInfo(String name, long size, Instant lastModified, 
                       String owner, String group, String permissions) {
        this.name = name;
        this.directory = false;
        this.size = size;
        this.lastModified = lastModified;
        this.owner = owner;
        this.group = group;
        this.permissions = permissions;
    }

    /**
     * Creates file information for a directory.
     *
     * @param name the directory name
     * @param lastModified when the directory was last modified
     * @param owner the directory owner (can be null)
     * @param group the directory group (can be null)
     * @param permissions Unix-style permissions string (e.g., "rwxr-xr-x")
     */
    public FTPFileInfo(String name, Instant lastModified, 
                       String owner, String group, String permissions) {
        this.name = name;
        this.directory = true;
        this.size = 0; // Directories don't have meaningful sizes
        this.lastModified = lastModified;
        this.owner = owner;
        this.group = group;
        this.permissions = permissions;
    }

    /**
     * @return the file or directory name
     */
    public String getName() {
        return name;
    }

    /**
     * @return true if this represents a directory, false for a regular file
     */
    public boolean isDirectory() {
        return directory;
    }

    /**
     * @return true if this represents a regular file, false for a directory
     */
    public boolean isFile() {
        return !directory;
    }

    /**
     * @return the file size in bytes (0 for directories)
     */
    public long getSize() {
        return size;
    }

    /**
     * @return when the file or directory was last modified
     */
    public Instant getLastModified() {
        return lastModified;
    }

    /**
     * @return the owner name, or null if not available
     */
    public String getOwner() {
        return owner;
    }

    /**
     * @return the group name, or null if not available
     */
    public String getGroup() {
        return group;
    }

    /**
     * @return Unix-style permissions string (e.g., "rw-r--r--"), or null if not available
     */
    public String getPermissions() {
        return permissions;
    }

    /**
     * Formats this file info as a Unix-style directory listing line.
     * Used for FTP LIST command responses.
     *
     * @return formatted listing line (e.g., "-rw-r--r-- 1 user group 1234 Jan 15 10:30 filename.txt")
     */
    public String formatAsListingLine() {
        StringBuilder sb = new StringBuilder();
        
        // File type and permissions
        if (directory) {
            sb.append('d');
        } else {
            sb.append('-');
        }
        sb.append(permissions != null ? permissions : "rw-r--r--");
        
        // Link count (always 1 for simplicity)
        sb.append(" 1 ");
        
        // Owner and group
        sb.append(owner != null ? owner : L10N.getString("file.default_owner")).append(' ');
        sb.append(group != null ? group : L10N.getString("file.default_group")).append(' ');
        
        // File size (right-aligned, 8 characters)
        sb.append(String.format("%8d ", size));
        
        // Modification time (simplified format)
        if (lastModified != null) {
            // Format as "Jan 15 10:30" or similar
            sb.append(String.format("%tb %2$te %2$tR ", 
                java.util.Date.from(lastModified), 
                java.util.Date.from(lastModified)));
        } else {
            sb.append(L10N.getString("file.default_date")).append(' ');
        }
        
        // File name
        sb.append(name);
        
        return sb.toString();
    }

    @Override
    public String toString() {
        return String.format("FTPFileInfo{name='%s', directory=%s, size=%d, modified=%s}", 
                           name, directory, size, lastModified);
    }
}
