/*
 * Client.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop;

import java.net.InetAddress;
import java.net.UnknownHostException;
import javax.net.ssl.SSLEngine;

/**
 * Abstract base class for client-side connection factories.
 * This class extends {@link Connector} and adds client-specific
 * functionality such as target host and port configuration
 * and client-mode SSL engine setup.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class Client extends Connector {

    protected InetAddress host;
    protected int port;

    /**
     * Creates a client that will connect to the specified host and port.
     *
     * @param host the target host as a String
     * @param port the target port number
     * @throws UnknownHostException if the host cannot be resolved
     */
    public Client(String host, int port) throws UnknownHostException {
        this(InetAddress.getByName(host), port);
    }

    /**
     * Creates a client that will connect to the specified host and port.
     *
     * @param host the target host as an InetAddress
     * @param port the target port number
     */
    public Client(InetAddress host, int port) {
        super();
        this.host = host;
        this.port = port;
    }

    /**
     * Returns the target host address.
     */
    public InetAddress getHost() {
        return host;
    }

    /**
     * Returns the target port number.
     */
    public int getPort() {
        return port;
    }

    @Override
    protected void configureSSLEngine(SSLEngine engine) {
        super.configureSSLEngine(engine); // Call parent for common configuration
        engine.setUseClientMode(true); // we are a client
    }
}
