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
 * FTP (File Transfer Protocol) server implementation.
 *
 * <p>This package provides a full-featured FTP server supporting both
 * active and passive modes, with optional SSL/TLS security (FTPS).
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.ftp.FTPService} - Abstract base for
 *       FTP application services; owns configuration, creates per-connection
 *       handlers, and manages dynamic data-connection listeners</li>
 *   <li>{@link org.bluezoo.gumdrop.ftp.FTPListener} - TCP transport
 *       listener for FTP control connections</li>
 *   <li>{@link org.bluezoo.gumdrop.ftp.FTPProtocolHandler} - Handles
 *       the FTP control session and command processing</li>
 *   <li>{@link org.bluezoo.gumdrop.ftp.FTPConnectionHandler} - Interface for
 *       handling FTP commands and filesystem operations</li>
 *   <li>{@link org.bluezoo.gumdrop.ftp.FTPConnectionHandlerFactory} - Factory
 *       for creating per-session FTP handlers</li>
 *   <li>{@link org.bluezoo.gumdrop.ftp.FTPFileSystem} - Interface for
 *       filesystem operations</li>
 *   <li>{@link org.bluezoo.gumdrop.ftp.FTPDataConnection} - Handles the
 *       data connection for file transfers</li>
 * </ul>
 *
 * <h2>FTP Commands Supported</h2>
 *
 * <ul>
 *   <li>USER, PASS - Authentication</li>
 *   <li>PWD, CWD, CDUP - Directory navigation</li>
 *   <li>LIST, NLST, MLSD, MLST - Directory listing</li>
 *   <li>RETR, STOR, APPE - File transfer</li>
 *   <li>DELE, MKD, RMD, RNFR, RNTO - File management</li>
 *   <li>PORT, PASV, EPRT, EPSV - Data connection modes</li>
 *   <li>TYPE, MODE, STRU - Transfer parameters</li>
 *   <li>AUTH, PBSZ, PROT - SSL/TLS security</li>
 *   <li>SIZE, MDTM, REST - Extended features</li>
 *   <li>FEAT, OPTS - Feature negotiation</li>
 * </ul>
 *
 * <h2>Configuration Example</h2>
 *
 * <pre>{@code
 * <realm id="ftpRealm" class="org.bluezoo.gumdrop.BasicRealm">
 *   <property name="href">ftp-users.xml</property>
 * </realm>
 *
 * <server id="ftp" class="org.bluezoo.gumdrop.ftp.FTPListener">
 *   <property name="port">21</property>
 *   <property name="handler-factory">
 *     <ftp-handler-factory class="org.bluezoo.gumdrop.ftp.file.SimpleFTPHandlerFactory">
 *       <property name="realm" ref="#ftpRealm"/>
 *       <property name="file-system">
 *         <component class="org.bluezoo.gumdrop.ftp.file.BasicFTPFileSystem">
 *           <property name="root">/var/ftp</property>
 *         </component>
 *       </property>
 *     </ftp-handler-factory>
 *   </property>
 * </server>
 * }</pre>
 *
 * <h2>Security</h2>
 *
 * <p>For secure file transfer, FTPS (FTP over SSL/TLS) is supported:
 * <ul>
 *   <li>Implicit FTPS on port 990</li>
 *   <li>Explicit FTPS via AUTH TLS command</li>
 *   <li>Data channel protection via PROT command</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.ftp.FTPListener
 * @see org.bluezoo.gumdrop.ftp.FTPConnectionHandler
 * @see org.bluezoo.gumdrop.ftp.file
 */
package org.bluezoo.gumdrop.ftp;
