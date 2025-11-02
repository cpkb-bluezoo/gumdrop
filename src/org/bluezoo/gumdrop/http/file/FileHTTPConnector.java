/*
 * FileHTTPConnector.java
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

package org.bluezoo.gumdrop.http.file;

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.http.HTTPConnector;

import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.net.ssl.SSLEngine;

/**
 * HTTP connector for serving files from a filesystem root.
 * This replaces the older FileBasedHTTPConnector.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FileHTTPConnector extends HTTPConnector {

    private Path rootPath = Paths.get(".");
    private boolean allowWrite = false;

    public Path getRootPath() {
        return rootPath;
    }

    public void setRootPath(Path rootPath) {
        this.rootPath = rootPath;
    }

    public void setRootPath(String rootPath) {
        this.rootPath = Paths.get(rootPath);
    }

    public boolean isAllowWrite() {
        return allowWrite;
    }

    public void setAllowWrite(boolean allowWrite) {
        this.allowWrite = allowWrite;
    }

    @Override
    public Connection newConnection(SocketChannel channel, SSLEngine engine) {
        return new FileHTTPConnection(channel, engine, secure, rootPath, allowWrite);
    }

    @Override
    public String getDescription() {
        return String.format("%s file server (root: %s, write: %s)", 
                            super.getDescription(), rootPath, allowWrite);
    }

}
