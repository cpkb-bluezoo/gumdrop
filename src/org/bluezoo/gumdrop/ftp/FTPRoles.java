/*
 * FTPRoles.java
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
 * Standard role names for FTP authorization.
 * 
 * <p>These roles provide a standard vocabulary for FTP access control.
 * Administrators define users in a {@link org.bluezoo.gumdrop.auth.Realm} and
 * assign them to these roles to control their FTP capabilities.</p>
 * 
 * <h4>Role Hierarchy</h4>
 * <p>Roles are cumulative - higher-privilege roles typically imply lower
 * privileges:</p>
 * <ul>
 *   <li>{@link #ADMIN} - Full control, implies all other roles</li>
 *   <li>{@link #DELETE} - Can delete, implies WRITE</li>
 *   <li>{@link #WRITE} - Can upload, implies READ</li>
 *   <li>{@link #READ} - Read-only access</li>
 * </ul>
 * 
 * <h4>Example Realm Configuration</h4>
 * <pre>{@code
 * <realm>
 *   <!-- Define groups with id (for XML linking) and name (the role name) -->
 *   <group id="ftpAdminGroup" name="ftp-admin"/>
 *   <group id="ftpWriteGroup" name="ftp-write"/>
 *   <group id="ftpReadGroup" name="ftp-read"/>
 *   
 *   <!-- Users reference groups by id (IDREFS syntax) -->
 *   <user name="admin" password="secret" groups="ftpAdminGroup"/>
 *   <user name="uploader" password="upload123" groups="ftpWriteGroup ftpReadGroup"/>
 *   <user name="guest" password="guest" groups="ftpReadGroup"/>
 * </realm>
 * }</pre>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see FTPOperation
 * @see FTPConnectionHandler#isAuthorized(FTPOperation, String, FTPConnectionMetadata)
 */
public final class FTPRoles {
    
    /**
     * Read-only access role.
     * <p>Grants: RETR, LIST, NLST, SIZE, MDTM, CWD, PWD</p>
     */
    public static final String READ = "ftp-read";
    
    /**
     * Write access role.
     * <p>Grants: STOR, STOU, APPE, MKD</p>
     * <p>Typically combined with {@link #READ}.</p>
     */
    public static final String WRITE = "ftp-write";
    
    /**
     * Delete access role.
     * <p>Grants: DELE, RMD, RNFR, RNTO</p>
     * <p>Typically combined with {@link #WRITE} and {@link #READ}.</p>
     */
    public static final String DELETE = "ftp-delete";
    
    /**
     * Administrative access role.
     * <p>Grants all FTP operations including SITE commands.</p>
     * <p>Users with this role bypass all other authorization checks.</p>
     */
    public static final String ADMIN = "ftp-admin";
    
    // Prevent instantiation
    private FTPRoles() {
    }
}

