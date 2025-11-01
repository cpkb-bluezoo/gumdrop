/*
 * FileBasedHTTPConnector.java
 * Copyright (C) 2005, 2013, 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
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
