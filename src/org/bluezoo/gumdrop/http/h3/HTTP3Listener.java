/*
 * HTTP3Listener.java
 * Copyright (C) 2026 Chris Burdess
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

package org.bluezoo.gumdrop.http.h3;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TCPListener;
import org.bluezoo.gumdrop.TransportFactory;
import org.bluezoo.gumdrop.http.HTTPRequestHandlerFactory;
import org.bluezoo.gumdrop.http.HTTPServerMetrics;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.QuicEngine;
import org.bluezoo.gumdrop.quic.QuicTransportFactory;

/**
 * QUIC transport listener for HTTP/3 connections.
 *
 * <p>This is the HTTP/3 equivalent of
 * {@link org.bluezoo.gumdrop.http.HTTPListener}. It creates a
 * {@link QuicTransportFactory} with ALPN "h3", binds to the configured
 * port, and installs an {@link HTTP3ServerHandler} on each new QUIC
 * connection to bridge between the quiche h3 module and the gumdrop
 * {@link org.bluezoo.gumdrop.http.HTTPRequestHandler} API.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTP3ServerHandler
 * @see H3Stream
 */
public class HTTP3Listener extends TCPListener
        implements QuicEngine.ConnectionAcceptedHandler {

    private static final Logger LOGGER =
            Logger.getLogger(HTTP3Listener.class.getName());

    private static final int HTTP3_DEFAULT_PORT = 443;

    private int port = -1;

    private HTTPRequestHandlerFactory handlerFactory;
    private HTTPServerMetrics metrics;
    private SelectorLoop selectorLoop;

    private String certFile;
    private String keyFile;

    private final List<QuicEngine> engines =
            new ArrayList<QuicEngine>();

    @Override
    public String getDescription() {
        return "h3";
    }

    @Override
    public int getPort() {
        return port;
    }

    /**
     * Sets the port to listen on.
     *
     * @param port the UDP port number
     */
    public void setPort(int port) {
        this.port = port;
    }

    /**
     * Sets the PEM certificate chain file for QUIC TLS 1.3.
     *
     * @param path the PEM file path
     */
    public void setCertFile(String path) {
        this.certFile = path;
    }

    /**
     * Sets the PEM private key file for QUIC TLS 1.3.
     *
     * @param path the PEM file path
     */
    public void setKeyFile(String path) {
        this.keyFile = path;
    }

    /**
     * Sets the handler factory for this endpoint.
     *
     * @param factory the handler factory, or null
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
     * Returns the metrics for this endpoint, or null if telemetry is
     * not enabled.
     *
     * @return the HTTP server metrics
     */
    public HTTPServerMetrics getMetrics() {
        return metrics;
    }

    /**
     * Returns the SelectorLoop used for QUIC datagram I/O.
     *
     * @return the selector loop, or null if not yet assigned
     */
    public SelectorLoop getSelectorLoop() {
        return selectorLoop;
    }

    /**
     * Sets the SelectorLoop used for QUIC datagram I/O.
     *
     * @param loop the selector loop
     */
    public void setSelectorLoop(SelectorLoop loop) {
        this.selectorLoop = loop;
    }

    // ── Lifecycle ──

    @Override
    public boolean requiresTcpAccept() {
        return false;
    }

    @Override
    protected TransportFactory createTransportFactory() {
        QuicTransportFactory factory = new QuicTransportFactory();
        factory.setApplicationProtocols("h3");
        if (certFile != null) {
            factory.setCertFile(certFile);
        }
        if (keyFile != null) {
            factory.setKeyFile(keyFile);
        }
        return factory;
    }

    @Override
    public void start() {
        super.start();
        if (port <= 0) {
            port = HTTP3_DEFAULT_PORT;
        }
        if (isMetricsEnabled()) {
            metrics = new HTTPServerMetrics(getTelemetryConfig());
        }
        if (selectorLoop == null) {
            throw new IllegalStateException(
                    "SelectorLoop must be set before starting "
                            + "HTTP3Listener");
        }
        bindEngines();
    }

    /**
     * Creates and binds a QuicEngine for each configured address.
     */
    private void bindEngines() {
        QuicTransportFactory factory =
                (QuicTransportFactory) getTransportFactory();

        Set<InetAddress> addrs = getAddresses();
        for (Iterator<InetAddress> it = addrs.iterator();
             it.hasNext(); ) {
            InetAddress addr = it.next();
            try {
                QuicEngine engine = factory.createServerEngine(
                        addr, port, this, selectorLoop);
                engines.add(engine);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to bind HTTP/3 on "
                                + addr.getHostAddress() + ":" + port, e);
            }
        }

        if (engines.isEmpty()) {
            LOGGER.warning(
                    "HTTP/3 server could not bind to any address");
        }
    }

    @Override
    public void stop() {
        for (int i = 0; i < engines.size(); i++) {
            engines.get(i).close();
        }
        engines.clear();
    }

    // ── ConnectionAcceptedHandler ──

    @Override
    public void connectionAccepted(QuicConnection connection) {
        new HTTP3ServerHandler(connection, handlerFactory, metrics,
                getTelemetryConfig());
    }

    /**
     * Not used for HTTP/3. QUIC connections are handled at the
     * connection level by {@link HTTP3ServerHandler}.
     *
     * @return null
     */
    @Override
    protected ProtocolHandler createHandler() {
        return null;
    }

}
