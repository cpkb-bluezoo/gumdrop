/*
 * AnonymousFTPServer.java
 * Copyright (C) 2026 Chris Burdess
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

package org.bluezoo.gumdrop.ftp.server;

import org.bluezoo.gumdrop.ftp.FtpListener;
import org.bluezoo.gumdrop.ftp.file.AnonymousFTPHandler;
import org.bluezoo.gumdrop.ftp.file.BasicFTPFileSystem;

import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.ftp.FtpConnectionHandler;
import org.bluezoo.gumdrop.ftp.FtpServer;

/**
 * FTP service for anonymous public file distribution.
 *
 * <p>This service accepts anonymous logins (username "anonymous" or
 * "ftp") and provides read-only access to a configured root directory.
 * It is suitable for public software distribution servers, document
 * sharing portals, and open-source project file repositories.
 *
 * <h2>Configuration Example</h2>
 * <pre>{@code
 * <service class="org.bluezoo.gumdrop.ftp.file.AnonymousFTPServer">
 *   <property name="root-directory">/var/ftp/pub</property>
 *   <property name="welcome-message">Welcome to Anonymous FTP</property>
 *   <listener class="org.bluezoo.gumdrop.ftp.FtpListener" port="21"/>
 * </service>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see FtpServer
 * @see AnonymousFTPHandler
 */
public class AnonymousFTPServer extends FtpServer {

    private static final Logger LOGGER =
            Logger.getLogger(AnonymousFTPServer.class.getName());
    private static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.ftp.L10N");

    private Path rootDirectory;
    private String welcomeMessage;

    private BasicFTPFileSystem fileSystem;

    // ── Configuration ──

    public Path getRootDirectory() {
        return rootDirectory;
    }

    public void setRootDirectory(Path rootDirectory) {
        this.rootDirectory = rootDirectory;
    }

    public void setRootDirectory(String rootDirectory) {
        this.rootDirectory = Path.of(rootDirectory);
    }

    public String getWelcomeMessage() {
        return welcomeMessage;
    }

    public void setWelcomeMessage(String welcomeMessage) {
        this.welcomeMessage = welcomeMessage;
    }

    // ── FtpServer hooks ──

    @Override
    protected void initService() {
        if (rootDirectory == null) {
            throw new IllegalStateException(
                    "rootDirectory must be configured");
        }
        fileSystem = new BasicFTPFileSystem(rootDirectory, true);
        LOGGER.info(MessageFormat.format(
                L10N.getString("info.anon_ftp_service_initialised"), rootDirectory));
    }

    @Override
    public FtpConnectionHandler createHandler(TcpListener endpoint) {
        AnonymousFTPHandler handler = new AnonymousFTPHandler(fileSystem);
        if (welcomeMessage != null && !welcomeMessage.trim().isEmpty()) {
            handler.setWelcomeMessage(welcomeMessage.trim());
        }
        return handler;
    }

}
