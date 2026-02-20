/*
 * HTTPListener.java
 * Copyright (C) 2005, 2013, 2025, 2026 Chris Burdess
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

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TCPListener;
import org.bluezoo.gumdrop.TCPTransportFactory;
import org.bluezoo.gumdrop.TransportFactory;

/**
 * TCP transport listener for HTTP/1.1 and HTTP/2 connections.
 *
 * <p>This is the transport endpoint; application logic is provided by
 * an {@link HTTPService} which sets the handler factory and
 * authentication provider during wiring.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPListener extends TCPListener {

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
     * Authentication provider for HTTP connections created by this endpoint.
     */
    private HTTPAuthenticationProvider authenticationProvider;

    /**
     * Handler factory for creating request handlers.
     * If null, the default 404 behaviour is used.
     */
    private HTTPRequestHandlerFactory handlerFactory;

    /**
     * Alt-Svc header value to inject into responses, or null.
     * Set by the owning HTTPService when an HTTP/3 listener is also
     * configured.
     */
    private String altSvc;

    /**
     * Metrics for this endpoint (null if telemetry is not enabled).
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
        if (isMetricsEnabled()) {
            metrics = new HTTPServerMetrics(getTelemetryConfig());
        }
    }

    /**
     * Returns the metrics for this endpoint, or null if telemetry is
     * not enabled.
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
     *
     * @param framePadding padding amount in bytes (0-255)
     * @throws IllegalArgumentException if padding is outside valid range
     */
    public void setFramePadding(int framePadding) {
        if (framePadding < 0 || framePadding > 255) {
            throw new IllegalArgumentException(
                    "Frame padding must be between 0-255 bytes, got: "
                            + framePadding);
        }
        this.framePadding = framePadding;
    }

    /**
     * Sets the authentication provider for this endpoint.
     *
     * @param provider the authentication provider, or null to disable
     */
    public void setAuthenticationProvider(
            HTTPAuthenticationProvider provider) {
        this.authenticationProvider = provider;
    }

    /**
     * Returns the authentication provider for this endpoint.
     *
     * @return the authentication provider, or null if not configured
     */
    public HTTPAuthenticationProvider getAuthenticationProvider() {
        return authenticationProvider;
    }

    /**
     * Sets the handler factory for this endpoint.
     *
     * <p>The factory is called once per HTTP stream when the initial
     * request headers are received.
     *
     * @param factory the handler factory, or null for default 404
     * @see HTTPRequestHandlerFactory
     */
    public void setHandlerFactory(HTTPRequestHandlerFactory factory) {
        this.handlerFactory = factory;
    }

    /**
     * Returns the handler factory for this endpoint.
     *
     * @return the handler factory, or null if not configured
     */
    public HTTPRequestHandlerFactory getHandlerFactory() {
        return handlerFactory;
    }

    /**
     * Sets the Alt-Svc header value to inject into HTTP responses.
     * Typically set by the owning service to advertise HTTP/3.
     *
     * @param altSvc the Alt-Svc header value, or null to disable
     */
    public void setAltSvc(String altSvc) {
        this.altSvc = altSvc;
    }

    /**
     * Returns the Alt-Svc header value, or null if not set.
     *
     * @return the Alt-Svc header value
     */
    public String getAltSvc() {
        return altSvc;
    }

    @Override
    protected ProtocolHandler createHandler() {
        return new HTTPProtocolHandler(this, framePadding);
    }

    @Override
    protected void configureTransportFactory(TransportFactory factory) {
        super.configureTransportFactory(factory);
        // Configure ALPN for HTTP/2 support on secure connections
        if (secure && factory instanceof TCPTransportFactory) {
            TCPTransportFactory tcpFactory = (TCPTransportFactory) factory;
            tcpFactory.setApplicationProtocols("h2", "http/1.1");
        }
    }

}
