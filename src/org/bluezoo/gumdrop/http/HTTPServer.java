/*
 * HTTPServer.java
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

    /**
     * Authentication provider for HTTP connections created by this server.
     */
    private HTTPAuthenticationProvider authenticationProvider;

    /**
     * Handler factory for creating request handlers.
     * If null, the default 404 behavior is used.
     */
    private HTTPRequestHandlerFactory handlerFactory;

    /**
     * Metrics for this server (null if telemetry is not enabled).
     */
    private HTTPServerMetrics metrics;

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
        // Initialize metrics if telemetry is enabled
        if (isMetricsEnabled()) {
            metrics = new HTTPServerMetrics(getTelemetryConfig());
        }
    }

    /**
     * Returns the metrics for this server, or null if telemetry is not enabled.
     *
     * @return the HTTP server metrics
     */
    public HTTPServerMetrics getMetrics() {
        return metrics;
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

    /**
     * Sets the authentication provider for this server.
     *
     * @param provider the authentication provider, or null to disable authentication
     */
    public void setAuthenticationProvider(HTTPAuthenticationProvider provider) {
        this.authenticationProvider = provider;
    }

    /**
     * Returns the authentication provider for this server.
     *
     * @return the authentication provider, or null if authentication is not configured
     */
    public HTTPAuthenticationProvider getAuthenticationProvider() {
        return authenticationProvider;
    }

    /**
     * Sets the handler factory for this server.
     *
     * <p>The factory is called once per HTTP stream when the initial request
     * headers are received. It can examine the headers (including :method,
     * :path, etc.) to decide which handler implementation to return.
     *
     * <p>If no factory is set, requests receive a 404 Not Found response.
     *
     * @param factory the handler factory, or null to use default 404 behavior
     * @see HTTPRequestHandlerFactory
     */
    public void setHandlerFactory(HTTPRequestHandlerFactory factory) {
        this.handlerFactory = factory;
    }

    /**
     * Returns the handler factory for this server.
     *
     * @return the handler factory, or null if not configured
     */
    public HTTPRequestHandlerFactory getHandlerFactory() {
        return handlerFactory;
    }

    @Override
    public Connection newConnection(SocketChannel channel, SSLEngine engine) {
        HTTPConnection connection = new HTTPConnection(channel, engine, secure, framePadding);
        connection.setAuthenticationProvider(authenticationProvider);
        connection.setHandlerFactory(handlerFactory);
        return connection;
    }

}
