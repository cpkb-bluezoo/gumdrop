/*
 * FileHTTPConnection.java
 * Copyright (C) 2005, 2013, 2025, 2026 Chris Burdess
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

package org.bluezoo.gumdrop.http.file;

import org.bluezoo.gumdrop.http.HTTPConnection;

import java.nio.channels.SocketChannel;
import java.nio.file.Path;

import javax.net.ssl.SSLEngine;

/**
 * HTTP connection that serves files from a filesystem root.
 * Uses the {@link FileHandlerFactory} to create handlers for each request.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FileHTTPConnection extends HTTPConnection {

    protected FileHTTPConnection(SocketChannel channel,
            SSLEngine engine,
            boolean secure,
            Path rootPath,
            boolean allowWrite,
            String welcomeFile) {
        this(channel, engine, secure, rootPath, allowWrite, welcomeFile, false);
    }

    protected FileHTTPConnection(SocketChannel channel,
            SSLEngine engine,
            boolean secure,
            Path rootPath,
            boolean allowWrite,
            String welcomeFile,
            boolean webdavEnabled) {
        super(channel, engine, secure);
        setHandlerFactory(new FileHandlerFactory(rootPath, allowWrite, welcomeFile, webdavEnabled));
    }
}
