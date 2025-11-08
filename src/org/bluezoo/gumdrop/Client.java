/*
 * Client.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
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
