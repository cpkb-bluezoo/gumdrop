/*
 * ServletConnection.java
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

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.http.AbstractHTTPConnection;
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
public class ServletConnection extends AbstractHTTPConnection {

    private static final Logger LOGGER = Logger.getLogger(ServletConnection.class.getName());

    final Container container;
    final ServletConnector connector;

    /**
     * Response queue. This holds the streams in the same order they arrive
     * in, so as to coordinate sending responses in the correct order.
     */
    final BlockingQueue<ServletStream> responseQueue = new LinkedBlockingQueue<>();

    ServletConnection(SocketChannel channel,
            SSLEngine engine,
            boolean secure,
            Container container,
            ServletConnector connector) {
        super(channel, engine, secure);
        this.container = container;
        this.connector = connector;
    }

    // Friend access for Request
    SocketChannel getChannel() {
        return channel;
    }

    protected Stream newStream(AbstractHTTPConnection connection, int streamId) {
        ServletStream stream = new ServletStream(this, streamId, bufferSize);
        try {
            responseQueue.put(stream);
        } catch (InterruptedException e) {
            throw (RuntimeException) new RuntimeException().initCause(e);
        }
        return stream;
    }

    void serviceRequest(ServletStream stream) {
        connector.serviceRequest(stream);
    }

    void responseFlushed() {
        connector.responseFlushed(this);
    }

}
