/*
 * RoleBasedFTPHandlerFactory.java
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
import org.bluezoo.gumdrop.ftp.FTPConnectionHandler;
import org.bluezoo.gumdrop.ftp.FTPConnectionHandlerFactory;
import org.bluezoo.gumdrop.ftp.FTPFileSystem;
import org.bluezoo.gumdrop.quota.QuotaManager;

/**
 * Factory for creating {@link RoleBasedFTPHandler} instances.
 * 
 * <p>This factory is used with Gumdrop's DI framework to configure
 * role-based FTP access control.</p>
 * 
 * <h4>Configuration Example</h4>
 * <pre>{@code
 * <realm id="ftpRealm" class="org.bluezoo.gumdrop.BasicRealm">
 *   <property name="href">ftp-users.xml</property>
 * </realm>
 * 
 * <component id="ftpFileSystem" class="org.bluezoo.gumdrop.ftp.file.LocalFileSystem">
 *   <property name="root">/var/ftp</property>
 * </component>
 * 
 * <server id="ftp" class="org.bluezoo.gumdrop.ftp.FTPListener" port="21">
 *   <property name="handler-factory">
 *     <ftp-handler-factory class="org.bluezoo.gumdrop.ftp.file.RoleBasedFTPHandlerFactory">
 *       <property name="realm" ref="#ftpRealm"/>
 *       <property name="file-system" ref="#ftpFileSystem"/>
 *       <property name="welcome-message">Welcome to Secure FTP Server</property>
 *     </ftp-handler-factory>
 *   </property>
 * </server>
 * }</pre>
 * 
 * <h4>Realm Configuration (ftp-users.xml)</h4>
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
 * @see RoleBasedFTPHandler
 * @see org.bluezoo.gumdrop.ftp.FTPRoles
 */
public class RoleBasedFTPHandlerFactory implements FTPConnectionHandlerFactory {
    
    private Realm realm;
    private FTPFileSystem fileSystem;
    private QuotaManager quotaManager;
    private String welcomeMessage;
    
    /**
     * Creates a new factory with default settings.
     */
    public RoleBasedFTPHandlerFactory() {
    }
    
    /**
     * Creates a new factory with the specified realm and file system.
     * 
     * @param realm the realm for authentication and role checking
     * @param fileSystem the file system to use for file operations
     */
    public RoleBasedFTPHandlerFactory(Realm realm, FTPFileSystem fileSystem) {
        this.realm = realm;
        this.fileSystem = fileSystem;
    }
    
    /**
     * Sets the realm for authentication and role checking.
     * 
     * @param realm the realm
     */
    public void setRealm(Realm realm) {
        this.realm = realm;
    }
    
    /**
     * Sets the file system for file operations.
     * 
     * @param fileSystem the file system
     */
    public void setFileSystem(FTPFileSystem fileSystem) {
        this.fileSystem = fileSystem;
    }
    
    /**
     * Sets a custom welcome message for FTP connections.
     * 
     * @param welcomeMessage the welcome message
     */
    public void setWelcomeMessage(String welcomeMessage) {
        this.welcomeMessage = welcomeMessage;
    }
    
    /**
     * Sets the quota manager for quota enforcement.
     * 
     * <p>When configured, the handler will check quotas before allowing
     * file uploads and track usage after uploads and deletes.</p>
     * 
     * @param quotaManager the quota manager
     */
    public void setQuotaManager(QuotaManager quotaManager) {
        this.quotaManager = quotaManager;
    }
    
    @Override
    public FTPConnectionHandler createHandler() {
        if (realm == null) {
            throw new IllegalStateException("Realm must be configured");
        }
        if (fileSystem == null) {
            throw new IllegalStateException("FileSystem must be configured");
        }
        
        RoleBasedFTPHandler handler = new RoleBasedFTPHandler(realm, fileSystem);
        if (welcomeMessage != null) {
            handler.setWelcomeMessage(welcomeMessage);
        }
        if (quotaManager != null) {
            handler.setQuotaManager(quotaManager);
        }
        return handler;
    }
}

