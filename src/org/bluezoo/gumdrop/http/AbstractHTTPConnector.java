/*
 * AbstractHTTPConnector.java
 * Copyright (C) 2013 Chris Burdess
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

import org.bluezoo.gumdrop.Connector;

/**
 * Abstract connection factory for HTTP connections on a given port.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class AbstractHTTPConnector extends Connector {

    protected static final int HTTP_DEFAULT_PORT = 80;
    protected static final int HTTPS_DEFAULT_PORT = 443;

    protected int port = -1;

    public String getDescription() {
        return secure ? "https" : "http";
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void start() {
        super.start();
        if (port <= 0) {
            port = secure ? HTTPS_DEFAULT_PORT : HTTP_DEFAULT_PORT;
        }
    }

    public void stop() {
        // NOOP
    }

}
