/*
 * HTTPServer.java
 * Copyright (C) 2013, 2025 Chris Burdess
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
import org.bluezoo.gumdrop.Server;

import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;

/**
 * Connection factory for HTTP connections on a given port.
 * Provides default HTTPConnection instances with 404 behavior.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPServer extends Server {

    protected static final int HTTP_DEFAULT_PORT = 80;
    protected static final int HTTPS_DEFAULT_PORT = 443;

    protected int port = -1;
    
    /**
     * HTTP/2 frame padding amount for server-originated frames.
     * Padding can be used to obscure the actual size of DATA, HEADERS,
     * and PUSH_PROMISE frames for security purposes (RFC 7540 Section 6.1).
     * Value must be between 0-255 bytes.
     */
    protected int framePadding = 0;

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

    /**
     * Gets the HTTP/2 frame padding amount for server-originated frames.
     * 
     * @return padding amount in bytes (0-255)
     */
    public int getFramePadding() {
        return framePadding;
    }
    
    /**
     * Sets the HTTP/2 frame padding amount for server-originated frames.
     * Padding adds random-length padding to DATA, HEADERS, and PUSH_PROMISE
     * frames to obscure actual message sizes for security purposes.
     * 
     * <p>From RFC 7540 Section 6.1: "Padding can be added to DATA, HEADERS, 
     * PUSH_PROMISE frames to obscure the size of messages and to provide 
     * protection against specific attacks."
     * 
     * @param framePadding padding amount in bytes (0-255)
     * @throws IllegalArgumentException if padding is outside valid range
     */
    public void setFramePadding(int framePadding) {
        if (framePadding < 0 || framePadding > 255) {
            throw new IllegalArgumentException("Frame padding must be between 0-255 bytes, got: " + framePadding);
        }
        this.framePadding = framePadding;
    }

    @Override
    public Connection newConnection(SocketChannel channel, SSLEngine engine) {
        return new HTTPConnection(channel, engine, secure, framePadding);
    }

}
