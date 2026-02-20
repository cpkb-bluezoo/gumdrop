/*
 * DoQListener.java
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

package org.bluezoo.gumdrop.dns;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.StreamAcceptHandler;
import org.bluezoo.gumdrop.TCPListener;
import org.bluezoo.gumdrop.TransportFactory;
import org.bluezoo.gumdrop.quic.QuicEngine;
import org.bluezoo.gumdrop.quic.QuicTransportFactory;

/**
 * QUIC transport listener for DNS-over-QUIC (DoQ) queries.
 *
 * <p>DoQ (RFC 9250) transports DNS messages over QUIC on port 853.
 * Each query-response pair is carried in its own bidirectional QUIC
 * stream. Unlike DNS-over-TCP/TLS, no two-byte length prefix is used;
 * each stream carries exactly one DNS message delimited by the
 * stream FIN.
 *
 * <p>The ALPN protocol identifier is {@code "doq"}.
 *
 * <p>This listener follows the same architectural pattern as the
 * HTTP/3 listener: it extends {@link TCPListener} but overrides
 * {@link #requiresTcpAccept()} to return {@code false}, creates a
 * {@link QuicTransportFactory}, and manages {@link QuicEngine}
 * instances directly.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DoQStreamHandler
 * @see DNSService
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9250">RFC 9250 - DNS over QUIC</a>
 */
public class DoQListener extends TCPListener
        implements StreamAcceptHandler {

    private static final Logger LOGGER =
            Logger.getLogger(DoQListener.class.getName());

    private static final int DEFAULT_PORT = 853;

    private int port = DEFAULT_PORT;
    private DNSService service;

    private String certFile;
    private String keyFile;

    private SelectorLoop selectorLoop;
    private final List engines = new ArrayList();

    @Override
    public int getPort() {
        return port;
    }

    /**
     * Sets the port number this endpoint should bind to.
     *
     * @param port the port number (default 853)
     */
    public void setPort(int port) {
        this.port = port;
    }

    @Override
    public String getDescription() {
        return "dns-quic";
    }

    /**
     * Sets the owning DNS service.
     *
     * @param service the owning service
     */
    void setService(DNSService service) {
        this.service = service;
    }

    /**
     * Returns the owning service, or null if used standalone.
     *
     * @return the owning service
     */
    public DNSService getService() {
        return service;
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
        factory.setApplicationProtocols("doq");
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
        if (selectorLoop == null) {
            throw new IllegalStateException(
                    "SelectorLoop must be set before starting "
                            + "DoQListener");
        }
        bindEngines();
    }

    @Override
    public void stop() {
        for (int i = 0; i < engines.size(); i++) {
            ((QuicEngine) engines.get(i)).close();
        }
        engines.clear();
    }

    /**
     * Creates and binds a QuicEngine for each configured address.
     */
    private void bindEngines() {
        QuicTransportFactory factory =
                (QuicTransportFactory) getTransportFactory();

        Set addresses = getAddresses();
        for (Iterator it = addresses.iterator(); it.hasNext(); ) {
            InetAddress addr = (InetAddress) it.next();
            try {
                QuicEngine engine = factory.createServerEngine(
                        addr, port, this, selectorLoop);
                engines.add(engine);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to bind DoQ on "
                                + addr.getHostAddress() + ":" + port, e);
            }
        }

        if (engines.isEmpty()) {
            LOGGER.warning(
                    "DoQ server could not bind to any address");
        }
    }

    // ── StreamAcceptHandler ──

    @Override
    public ProtocolHandler acceptStream(Endpoint stream) {
        return new DoQStreamHandler(service);
    }

    /**
     * Not used for DoQ. QUIC streams are handled individually by
     * {@link DoQStreamHandler} via the {@link StreamAcceptHandler}.
     *
     * @return null
     */
    @Override
    protected ProtocolHandler createHandler() {
        return null;
    }

}
