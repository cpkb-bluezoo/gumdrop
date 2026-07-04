/*
 * HealthService.java
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

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Service;

/**
 * A tiny built-in service that exposes an HTTP liveness/readiness endpoint for
 * orchestrators (Kubernetes probes, load-balancer health checks).
 *
 * <p>Configure it alongside the application services:
 * <pre>{@code
 * <service class="org.bluezoo.gumdrop.health.HealthService">
 *   <property name="port" value="8081"/>
 * </service>
 * }</pre>
 *
 * <p>The endpoint answers {@code GET /readyz} (readiness) and {@code GET
 * /livez} (liveness); see {@link HealthProtocolHandler} for the exact
 * semantics. It is deliberately independent of the main HTTP stack so it keeps
 * responding while the application listeners are starting up or draining.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HealthService implements Service {

    private static final Logger LOGGER =
            Logger.getLogger(HealthService.class.getName());

    private int port = HealthListener.DEFAULT_PORT;
    private String addresses;
    private final List<HealthListener> listeners =
            new ArrayList<HealthListener>();

    /**
     * Sets the TCP port for the health endpoint.
     *
     * @param port the port number
     */
    public void setPort(int port) {
        this.port = port;
    }

    /**
     * Returns the configured health-endpoint port.
     *
     * @return the port number
     */
    public int getPort() {
        return port;
    }

    /**
     * Sets the bind addresses for the health endpoint (comma/space separated).
     * Defaults to all local addresses when unset.
     *
     * @param addresses the addresses to bind
     */
    public void setAddresses(String addresses) {
        this.addresses = addresses;
    }

    @Override
    public List getListeners() {
        return listeners;
    }

    @Override
    public void start() {
        HealthListener listener = new HealthListener();
        listener.setName("health");
        listener.setPort(port);
        if (addresses != null && !addresses.isEmpty()) {
            listener.setAddresses(addresses);
        }
        listener.start();
        listeners.add(listener);
        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info("Health/readiness endpoint listening on port " + port);
        }
    }

    @Override
    public void stop() {
        for (HealthListener listener : listeners) {
            listener.stop();
            listener.closeServerChannels();
        }
        listeners.clear();
    }
}
