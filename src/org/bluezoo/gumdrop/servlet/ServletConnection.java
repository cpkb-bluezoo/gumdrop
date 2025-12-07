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
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Stream;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;

/**
 * A servlet connection is an HTTP connection implementation for servlet streams.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServletConnection extends HTTPConnection {

    private static final Logger LOGGER = Logger.getLogger(ServletConnection.class.getName());

    final Container container;
    final ServletServer server;

    /**
     * Response queue. This holds the streams in the same order they arrive
     * in, so as to coordinate sending responses in the correct order.
     */
    final BlockingQueue<ServletStream> responseQueue = new LinkedBlockingQueue<>();

    ServletConnection(SocketChannel channel,
            SSLEngine engine,
            boolean secure,
            Container container,
            ServletServer server) {
        super(channel, engine, secure);
        this.container = container;
        this.server = server;
    }

    // Friend access for Request
    SocketChannel getChannel() {
        return channel;
    }

    protected Stream newStream(HTTPConnection connection, int streamId) {
        ServletStream stream = new ServletStream(this, streamId, bufferSize);
        try {
            responseQueue.put(stream);
        } catch (InterruptedException e) {
            throw (RuntimeException) new RuntimeException().initCause(e);
        }
        return stream;
    }

    void serviceRequest(ServletStream stream) {
        server.serviceRequest(stream);
    }

    void responseFlushed() {
        server.responseFlushed(this);
    }

}
