/*
 * AnonymousFTPService.java
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

package org.bluezoo.gumdrop.ftp.file;

import java.nio.file.Path;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.TCPListener;
import org.bluezoo.gumdrop.ftp.FTPConnectionHandler;
import org.bluezoo.gumdrop.ftp.FTPService;

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
 * <service class="org.bluezoo.gumdrop.ftp.file.AnonymousFTPService">
 *   <property name="root-directory">/var/ftp/pub</property>
 *   <property name="welcome-message">Welcome to Anonymous FTP</property>
 *   <listener class="org.bluezoo.gumdrop.ftp.FTPListener" port="21"/>
 * </service>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see FTPService
 * @see AnonymousFTPHandler
 */
public class AnonymousFTPService extends FTPService {

    private static final Logger LOGGER =
            Logger.getLogger(AnonymousFTPService.class.getName());

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

    // ── FTPService hooks ──

    @Override
    protected void initService() {
        if (rootDirectory == null) {
            throw new IllegalStateException(
                    "rootDirectory must be configured");
        }
        fileSystem = new BasicFTPFileSystem(rootDirectory, true);
        LOGGER.info("AnonymousFTPService initialised: root="
                + rootDirectory);
    }

    @Override
    protected FTPConnectionHandler createHandler(TCPListener endpoint) {
        AnonymousFTPHandler handler = new AnonymousFTPHandler(fileSystem);
        if (welcomeMessage != null && !welcomeMessage.trim().isEmpty()) {
            handler.setWelcomeMessage(welcomeMessage.trim());
        }
        return handler;
    }

}
