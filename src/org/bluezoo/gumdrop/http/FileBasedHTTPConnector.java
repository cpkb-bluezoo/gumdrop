/*
 * FileBasedHTTPConnector.java
 * Copyright (C) 2005, 2013, 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.http;

import org.bluezoo.gumdrop.Connection;

import java.nio.channels.SocketChannel;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;

import javax.net.ssl.SSLEngine;

/**
 * Connection factory for HTTP connections on a given port.
 * This connection can serve static pages from a filesystem root.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FileBasedHTTPConnector extends AbstractHTTPConnector {

    protected String root;
    protected boolean allowWrite;

    protected FileSystem fileSystem;
    protected Path rootPath;

    public FileBasedHTTPConnector() {
        fileSystem = FileSystems.getDefault();
    }

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
        rootPath = fileSystem.getPath(root);
    }

    public boolean isAllowWrite() {
        return allowWrite;
    }

    public void setAllowWrite(boolean allowWrite) {
        this.allowWrite = allowWrite;
    }

    public Connection newConnection(SocketChannel sc, SSLEngine engine) {
        return new FileBasedHTTPConnection(sc, engine, isSecure(), rootPath, allowWrite);
    }

}
