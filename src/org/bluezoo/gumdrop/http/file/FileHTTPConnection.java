/*
 * FileHTTPConnection.java
 * Copyright (C) 2005, 2013, 2025 Chris Burdess
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

import org.bluezoo.gumdrop.SendCallback;
import org.bluezoo.gumdrop.http.HTTPConnection;
import org.bluezoo.gumdrop.http.Stream;

import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.ResourceBundle;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;

/**
 * HTTP connection that serves files from a filesystem root.
 * This is a cleaner implementation that extends HTTPConnection directly.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FileHTTPConnection extends HTTPConnection {

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.http.L10N");
    static final Logger LOGGER = Logger.getLogger(FileHTTPConnection.class.getName());

    private final Path rootPath;
    private final boolean allowWrite;
    private final String allowedOptions;
    private final String welcomeFile;

    protected FileHTTPConnection(SocketChannel channel,
            SSLEngine engine,
            boolean secure,
            Path rootPath,
            boolean allowWrite,
            String welcomeFile) {
        super(channel, engine, secure);
        this.rootPath = rootPath;
        this.allowWrite = allowWrite;
        this.welcomeFile = welcomeFile != null ? welcomeFile : "index.html";
        allowedOptions = allowWrite ? "OPTIONS, GET, HEAD, PUT, DELETE" : "OPTIONS, GET, HEAD";
    }

    @Override
    protected Stream newStream(HTTPConnection connection, int streamId) {
        return new FileStream(connection, streamId, rootPath, allowWrite, allowedOptions, welcomeFile);
    }
    
    @Override
    public void setSendCallback(SendCallback callback) {
        super.setSendCallback(callback);
    }
}
