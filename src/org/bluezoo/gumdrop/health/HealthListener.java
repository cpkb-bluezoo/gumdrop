/*
 * HealthListener.java
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

package org.bluezoo.gumdrop.health;

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TCPListener;

/**
 * Plaintext TCP listener that serves liveness/readiness probes via
 * {@link HealthProtocolHandler}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HealthService
 */
public class HealthListener extends TCPListener {

    /** Default health/readiness port. */
    public static final int DEFAULT_PORT = 8081;

    private int port = DEFAULT_PORT;

    @Override
    public String getDescription() {
        return "health";
    }

    @Override
    public int getPort() {
        return port;
    }

    /**
     * Sets the TCP port for the health endpoint.
     *
     * @param port the port number
     */
    public void setPort(int port) {
        this.port = port;
    }

    @Override
    protected ProtocolHandler createHandler() {
        return new HealthProtocolHandler();
    }
}
