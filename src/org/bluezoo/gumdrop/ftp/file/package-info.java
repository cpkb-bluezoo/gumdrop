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
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.ftp.file.SimpleFTPHandler} - Basic
 *       filesystem handler</li>
 *   <li>{@link org.bluezoo.gumdrop.ftp.file.SimpleFTPHandlerFactory} - Factory
 *       for creating simple file-based handlers</li>
 *   <li>{@link org.bluezoo.gumdrop.ftp.file.RoleBasedFTPHandler} - Handler
 *       with per-user directory isolation</li>
 *   <li>{@link org.bluezoo.gumdrop.ftp.file.RoleBasedFTPHandlerFactory} -
 *       Factory for role-based handlers</li>
 *   <li>{@link org.bluezoo.gumdrop.ftp.file.AnonymousFTPHandler} - Handler
 *       for anonymous FTP access</li>
 *   <li>{@link org.bluezoo.gumdrop.ftp.file.AnonymousFTPHandlerFactory} -
 *       Factory for anonymous handlers</li>
 *   <li>{@link org.bluezoo.gumdrop.ftp.file.BasicFTPFileSystem} - Basic
 *       filesystem implementation</li>
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
 * <ftp-handler-factory id="ftpHandler"
 *     class="org.bluezoo.gumdrop.ftp.file.RoleBasedFTPHandlerFactory">
 *   <property name="realm" ref="#ftpRealm"/>
 *   <property name="file-system">
 *     <component class="org.bluezoo.gumdrop.ftp.file.BasicFTPFileSystem">
 *       <property name="root">/var/ftp/users</property>
 *     </component>
 *   </property>
 *   <property name="welcome-message">Welcome to Gumdrop FTP</property>
 * </ftp-handler-factory>
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
