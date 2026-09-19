/*
 * FileSystemFtpSessionProvider.java
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

import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.ftp.file.BasicFTPFileSystem;
import org.bluezoo.gumdrop.quota.QuotaManager;

import java.nio.file.Path;

/**
 * Stock {@link FtpServerSessionProvider} for local filesystem FTP access.
 *
 * <pre>{@code
 * FtpServer server = FtpServer.compose()
 *         .listener(new FtpListener().port(21).bindWildcard())
 *         .realm(realm)
 *         .sessionProvider(FtpServerSessionProviders.fileSystem()
 *                 .rootDirectory(Path.of("/var/ftp")))
 *         .server();
 * }</pre>
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class FileSystemFtpSessionProvider implements FtpServerSessionProvider {

    private Path rootDirectory;
    private boolean readOnly;
    private Realm realm;
    private QuotaManager quotaManager;
    private String welcomeMessage;

    private BasicFTPFileSystem sharedFileSystem;

    public FileSystemFtpSessionProvider rootDirectory(Path rootDirectory) {
        this.rootDirectory = rootDirectory;
        return this;
    }

    public FileSystemFtpSessionProvider readOnly(boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    public FileSystemFtpSessionProvider realm(Realm realm) {
        this.realm = realm;
        return this;
    }

    public FileSystemFtpSessionProvider quotaManager(QuotaManager quotaManager) {
        this.quotaManager = quotaManager;
        return this;
    }

    public FileSystemFtpSessionProvider welcomeMessage(String welcomeMessage) {
        this.welcomeMessage = welcomeMessage;
        return this;
    }

    @Override
    public void start() {
        if (rootDirectory == null) {
            return;
        }
        sharedFileSystem = new BasicFTPFileSystem(rootDirectory, readOnly);
    }

    @Override
    public void stop() {
        sharedFileSystem = null;
    }

    @Override
    public ClientConnected openSession(TcpListener listener) {
        if (sharedFileSystem == null && rootDirectory != null) {
            sharedFileSystem = new BasicFTPFileSystem(rootDirectory, readOnly);
        }
        return new DefaultFtpHandler(sharedFileSystem, realm, quotaManager,
                welcomeMessage);
    }

}
