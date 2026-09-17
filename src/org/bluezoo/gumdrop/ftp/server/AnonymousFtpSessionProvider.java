/*
 * AnonymousFtpSessionProvider.java
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
import org.bluezoo.gumdrop.ftp.file.AnonymousFTPHandler;
import org.bluezoo.gumdrop.ftp.file.BasicFTPFileSystem;

import java.nio.file.Path;

/**
 * Stock {@link FtpServerSessionProvider} for anonymous, read-only public
 * file distribution (username "anonymous" or "ftp").
 *
 * <pre>{@code
 * FtpServer server = FtpServer.compose()
 *         .listener(new FtpListener().port(21).bindWildcard())
 *         .sessionProvider(FtpServerSessionProviders.anonymous()
 *                 .rootDirectory(Path.of("/var/ftp/pub"))
 *                 .welcomeMessage("Welcome to Anonymous FTP"))
 *         .server();
 * }</pre>
 */
public final class AnonymousFtpSessionProvider implements FtpServerSessionProvider {

    private Path rootDirectory;
    private String welcomeMessage;

    private BasicFTPFileSystem sharedFileSystem;

    public AnonymousFtpSessionProvider rootDirectory(Path rootDirectory) {
        this.rootDirectory = rootDirectory;
        return this;
    }

    public AnonymousFtpSessionProvider welcomeMessage(String welcomeMessage) {
        this.welcomeMessage = welcomeMessage;
        return this;
    }

    @Override
    public void start() {
        if (rootDirectory == null) {
            return;
        }
        sharedFileSystem = new BasicFTPFileSystem(rootDirectory, true);
    }

    @Override
    public void stop() {
        sharedFileSystem = null;
    }

    @Override
    public ClientConnected openSession(TcpListener listener) {
        if (sharedFileSystem == null && rootDirectory != null) {
            sharedFileSystem = new BasicFTPFileSystem(rootDirectory, true);
        }
        AnonymousFTPHandler handler = new AnonymousFTPHandler(sharedFileSystem);
        if (welcomeMessage != null && !welcomeMessage.trim().isEmpty()) {
            handler.setWelcomeMessage(welcomeMessage.trim());
        }
        return new LegacyConnectionHandlerAdapter(handler);
    }

}
