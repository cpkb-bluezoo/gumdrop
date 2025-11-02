/*
 * FileHTTPConnection.java
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

    protected FileHTTPConnection(SocketChannel channel,
            SSLEngine engine,
            boolean secure,
            Path rootPath,
            boolean allowWrite) {
        super(channel, engine, secure);
        this.rootPath = rootPath;
        this.allowWrite = allowWrite;
        allowedOptions = allowWrite ? "OPTIONS, GET, HEAD, PUT, DELETE" : "OPTIONS, GET, HEAD";
    }

    @Override
    protected Stream newStream(HTTPConnection connection, int streamId) {
        return new FileStream(connection, streamId, rootPath, allowWrite, allowedOptions);
    }
}
