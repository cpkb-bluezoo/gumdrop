/*
 * package-info.java
 * Copyright (C) 2005, 2025 Chris Burdess
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

/**
 * Filesystem-based FTP handler implementations.
 *
 * <p>This package provides FTP handlers that map FTP operations to the
 * local filesystem, with support for virtual paths, chroot jails, and
 * role-based access control.
 *
 * <h2>Key Components</h2>
 *
 * <h3>Services (v2)</h3>
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.ftp.file.SimpleFTPService} - Simple
 *       file-based FTP service with optional realm authentication</li>
 *   <li>{@link org.bluezoo.gumdrop.ftp.file.RoleBasedFTPService} - FTP
 *       service with role-based access control and quota support</li>
 *   <li>{@link org.bluezoo.gumdrop.ftp.file.AnonymousFTPService} - FTP
 *       service for anonymous public file distribution</li>
 * </ul>
 *
 * <h3>Handlers and Supporting Classes</h3>
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.ftp.file.SimpleFTPHandler} - Basic
 *       filesystem connection handler</li>
 *   <li>{@link org.bluezoo.gumdrop.ftp.file.RoleBasedFTPHandler} - Handler
 *       with role-based access control</li>
 *   <li>{@link org.bluezoo.gumdrop.ftp.file.AnonymousFTPHandler} - Handler
 *       for anonymous FTP access</li>
 *   <li>{@link org.bluezoo.gumdrop.ftp.file.BasicFTPFileSystem} - Local
 *       filesystem implementation with chroot</li>
 * </ul>
 *
 * <h2>Features</h2>
 *
 * <ul>
 *   <li>Chroot-style isolation per user</li>
 *   <li>Configurable home directories</li>
 *   <li>Integration with quota management</li>
 *   <li>Virtual path mapping</li>
 *   <li>Hidden file filtering</li>
 * </ul>
 *
 * <h2>Configuration Example</h2>
 *
 * <pre>{@code
 * <realm id="ftpRealm" class="org.bluezoo.gumdrop.BasicRealm">
 *   <property name="href">ftp-users.xml</property>
 * </realm>
 *
 * <service class="org.bluezoo.gumdrop.ftp.file.RoleBasedFTPService">
 *   <property name="realm" ref="#ftpRealm"/>
 *   <property name="root-directory">/var/ftp/users</property>
 *   <property name="welcome-message">Welcome to Gumdrop FTP</property>
 *   <listener class="org.bluezoo.gumdrop.ftp.FTPListener" port="21"/>
 * </service>
 * }</pre>
 *
 * <h2>Security</h2>
 *
 * <p>The handlers enforce strict path validation to prevent directory
 * traversal attacks. Users are confined to their designated directories
 * and cannot access files outside their scope.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.ftp.FTPConnectionHandler
 * @see org.bluezoo.gumdrop.ftp.file.RoleBasedFTPHandler
 */
package org.bluezoo.gumdrop.ftp.file;
