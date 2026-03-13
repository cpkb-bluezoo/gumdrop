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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ResourceBundle;

/**
 * Represents metadata about a file or directory in the FTP file system.
 * Used to format directory listings for the LIST command (RFC 959 section 4.1.3).
 * The listing format follows the conventional Unix {@code ls -l} style used
 * by virtually all modern FTP servers.
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

    /**
     * RFC 3659 section 7.2 date/time format: YYYYMMDDHHMMSS[.sss]
     */
    private static final DateTimeFormatter MLST_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    /**
     * Formats this file info as an RFC 3659 machine-readable listing entry.
     * Used for MLST and MLSD command responses (RFC 3659 section 7).
     *
     * <p>The format is {@code fact=value;fact=value; filename} with a
     * space separating the facts from the filename.
     *
     * @return formatted MLS entry (e.g., "type=file;size=1234;modify=20250115103000;perm=r; filename.txt")
     */
    public String formatAsMLSEntry() {
        StringBuilder sb = new StringBuilder();
        sb.append("type=").append(directory ? "dir" : "file").append(';');
        sb.append("size=").append(size).append(';');
        if (lastModified != null) {
            sb.append("modify=").append(MLST_TIME_FORMAT.format(lastModified)).append(';');
        }
        sb.append("perm=").append(deriveMLSPermissions()).append(';');
        sb.append(' ').append(name);
        return sb.toString();
    }

    /**
     * Derives RFC 3659 section 7.5.5 permission facts from the Unix permission string.
     */
    private String deriveMLSPermissions() {
        StringBuilder perm = new StringBuilder();
        if (directory) {
            perm.append('e'); // enter (CWD)
            perm.append('l'); // list (LIST/NLST/MLSD)
            if (permissions != null && permissions.length() >= 2 && permissions.charAt(1) == 'w') {
                perm.append('c'); // create files
                perm.append('m'); // create subdirectories (MKDIR)
                perm.append('p'); // purge (RMD)
            }
        } else {
            perm.append('r'); // read (RETR)
            if (permissions != null && permissions.length() >= 2 && permissions.charAt(1) == 'w') {
                perm.append('w'); // write (STOR)
                perm.append('a'); // append (APPE)
                perm.append('d'); // delete (DELE)
                perm.append('f'); // rename (RNFR)
            }
        }
        return perm.toString();
    }

    @Override
    public String toString() {
        return String.format("FTPFileInfo{name='%s', directory=%s, size=%d, modified=%s}", 
                           name, directory, size, lastModified);
    }
}
