/*
 * ServletConnection.java
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

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.http.HTTPConnection;

import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;

/**
 * A servlet connection is an HTTP connection implementation for the servlet container.
 * Uses the {@link ServletHandlerFactory} from {@link ServletServer} to create
 * request handlers for each incoming request.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServletConnection extends HTTPConnection {

    final ServletServer server;

    ServletConnection(SocketChannel channel,
            SSLEngine engine,
            boolean secure,
            ServletServer server) {
        super(channel, engine, secure);
        this.server = server;
        setHandlerFactory(server.getServletHandlerFactory());
    }

    // Friend access for Request
    SocketChannel getChannel() {
        return channel;
    }

}
