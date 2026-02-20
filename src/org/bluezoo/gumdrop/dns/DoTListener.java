/*
 * DoTListener.java
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

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TCPListener;

/**
 * TCP/TLS transport listener for DNS-over-TLS (DoT) queries.
 *
 * <p>DoT (RFC 7858) transports DNS messages over a TLS-encrypted TCP
 * connection on port 853. Each DNS message is prefixed with a two-byte
 * length field (DNS-over-TCP framing, RFC 1035 section 4.2.2).
 *
 * <p>A single TCP connection may carry multiple sequential
 * query-response pairs. Each accepted connection gets its own
 * {@link DoTProtocolHandler} that accumulates, parses, and responds
 * to DNS messages.
 *
 * <p>The listener is always secure (TLS is mandatory for DoT).
 * Configure a keystore or certificate chain via the standard
 * {@link org.bluezoo.gumdrop.Listener} TLS properties.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DoTProtocolHandler
 * @see DNSService
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7858">RFC 7858 - DNS over TLS</a>
 */
public class DoTListener extends TCPListener {

    private static final int DEFAULT_PORT = 853;

    private int port = DEFAULT_PORT;
    private DNSService service;

    /**
     * Creates a new DoT listener. TLS is enabled by default.
     */
    public DoTListener() {
        secure = true;
    }

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
        return "dns-tls";
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

    @Override
    protected ProtocolHandler createHandler() {
        return new DoTProtocolHandler(service);
    }

}
