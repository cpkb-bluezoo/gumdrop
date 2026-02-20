/*
 * HTTPService.java
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

package org.bluezoo.gumdrop.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.Listener;
import org.bluezoo.gumdrop.Service;
import org.bluezoo.gumdrop.http.h3.HTTP3Listener;

/**
 * Abstract base for HTTP application services.
 *
 * <p>An {@code HTTPService} defines the application logic for handling
 * HTTP requests: the {@link HTTPRequestHandlerFactory} that creates
 * handlers for each stream, and an optional
 * {@link HTTPAuthenticationProvider} for authenticating requests.
 *
 * <p>A service owns one or more transport listeners:
 * <ul>
 *   <li>{@link HTTPListener} for HTTP/1.1 and HTTP/2 over TCP</li>
 *   <li>{@link HTTP3Listener} for HTTP/3 over QUIC</li>
 * </ul>
 *
 * <p>During {@link #start()}, the service:
 * <ol>
 *   <li>Calls {@link #initService()} for subclass-specific initialisation
 *       (e.g., starting a servlet container or building a handler factory).</li>
 *   <li>Wires each listener by pushing the handler factory and
 *       authentication provider into it.</li>
 *   <li>If both TCP and QUIC listeners are present, injects an
 *       {@code Alt-Svc} header on the TCP endpoints to advertise HTTP/3.</li>
 *   <li>Calls {@link Listener#start()} on each listener.</li>
 * </ol>
 *
 * <p>During {@link #stop()}, the service stops listeners first, then
 * calls {@link #destroyService()} for subclass-specific teardown.
 *
 * <h2>Configuration Example</h2>
 * <pre>{@code
 * <service class="org.bluezoo.gumdrop.servlet.ServletService">
 *   <listener class="org.bluezoo.gumdrop.http.HTTPListener"
 *           port="8080"/>
 *   <listener class="org.bluezoo.gumdrop.http.h3.HTTP3Listener"
 *           port="8443" cert-file="/etc/cert.pem" key-file="/etc/key.pem"/>
 * </service>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Service
 * @see HTTPListener
 * @see HTTP3Listener
 * @see HTTPRequestHandlerFactory
 */
public abstract class HTTPService implements Service {

    private static final Logger LOGGER =
            Logger.getLogger(HTTPService.class.getName());

    private final List listeners = new ArrayList();

    // ── Listener management ──

    /**
     * Adds a TCP (HTTP/1.1 + HTTP/2) listener to this service.
     *
     * @param endpoint the TCP listener
     */
    public void addListener(HTTPListener endpoint) {
        listeners.add(endpoint);
    }

    /**
     * Adds a QUIC (HTTP/3) listener to this service.
     *
     * @param endpoint the QUIC listener
     */
    public void addListener(HTTP3Listener endpoint) {
        listeners.add(endpoint);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns all current listeners, both TCP and QUIC.
     */
    /**
     * Sets the listeners from a configuration list. Each item must be
     * an {@link HTTPListener} or {@link HTTP3Listener}.
     *
     * @param list the list of listener endpoints
     */
    public void setListeners(List list) {
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof HTTP3Listener) {
                addListener((HTTP3Listener) item);
            } else if (item instanceof HTTPListener) {
                addListener((HTTPListener) item);
            }
        }
    }

    @Override
    public List getListeners() {
        return Collections.unmodifiableList(listeners);
    }

    // ── Application logic hooks ──

    /**
     * Returns the handler factory that creates
     * {@link HTTPRequestHandler} instances for each request stream.
     *
     * <p>Called during {@link #start()} to wire each listener.
     * Subclasses must provide their own factory.
     *
     * @return the handler factory, never null after initialisation
     */
    protected abstract HTTPRequestHandlerFactory getHandlerFactory();

    /**
     * Returns the authentication provider for this service, or null
     * if authentication is not required.
     *
     * <p>The default implementation returns null (no authentication).
     * Subclasses may override to provide an authentication provider
     * that applies to all listeners.
     *
     * @return the authentication provider, or null
     */
    protected HTTPAuthenticationProvider getAuthenticationProvider() {
        return null;
    }

    /**
     * Initialises service-specific application resources.
     *
     * <p>Called at the beginning of {@link #start()}, before listeners
     * are wired and started. Subclasses should initialise containers,
     * thread pools, caches, or any other application-level resources
     * here.
     *
     * <p>The default implementation does nothing.
     */
    protected void initService() {
        // Default: no-op
    }

    /**
     * Tears down service-specific application resources.
     *
     * <p>Called at the end of {@link #stop()}, after all listeners have
     * been stopped. Subclasses should shut down containers, thread
     * pools, and other application-level resources here.
     *
     * <p>The default implementation does nothing.
     */
    protected void destroyService() {
        // Default: no-op
    }

    // ── Lifecycle ──

    /**
     * Starts this service: initialises application logic, wires
     * listeners, computes Alt-Svc, and starts each listener.
     */
    @Override
    public void start() {
        initService();

        HTTPRequestHandlerFactory factory = getHandlerFactory();
        HTTPAuthenticationProvider authProvider =
                getAuthenticationProvider();
        String altSvc = computeAltSvc();

        for (int i = 0; i < listeners.size(); i++) {
            Object listener = listeners.get(i);
            wireListener(listener, factory, authProvider, altSvc);
            startListener(listener);
        }
    }

    /**
     * Stops this service: stops all listeners first, then tears down
     * application logic.
     */
    @Override
    public void stop() {
        for (int i = 0; i < listeners.size(); i++) {
            stopListener(listeners.get(i));
        }
        destroyService();
    }

    // ── Internal wiring ──

    /**
     * Wires a single listener with the service's handler factory,
     * authentication provider, and Alt-Svc header.
     */
    private void wireListener(Object listener,
                              HTTPRequestHandlerFactory factory,
                              HTTPAuthenticationProvider authProvider,
                              String altSvc) {
        if (listener instanceof HTTPListener) {
            HTTPListener tcp = (HTTPListener) listener;
            tcp.setHandlerFactory(factory);
            tcp.setAuthenticationProvider(authProvider);
            if (altSvc != null) {
                tcp.setAltSvc(altSvc);
            }
        } else if (listener instanceof HTTP3Listener) {
            HTTP3Listener quic = (HTTP3Listener) listener;
            quic.setHandlerFactory(factory);
        }
    }

    /**
     * Starts a single listener.
     */
    private void startListener(Object listener) {
        if (listener instanceof HTTP3Listener) {
            HTTP3Listener h3 = (HTTP3Listener) listener;
            if (h3.getSelectorLoop() == null) {
                h3.setSelectorLoop(
                        Gumdrop.getInstance().nextWorkerLoop());
            }
        }
        if (listener instanceof Listener) {
            try {
                ((Listener) listener).start();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE,
                        "Failed to start listener: " + listener, e);
            }
        }
    }

    /**
     * Stops a single listener.
     */
    private void stopListener(Object listener) {
        if (listener instanceof Listener) {
            try {
                ((Listener) listener).stop();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        "Error stopping listener: " + listener, e);
            }
        }
    }

    /**
     * Computes the {@code Alt-Svc} header value when this service has
     * both TCP and QUIC listeners.
     *
     * <p>If at least one {@link HTTP3Listener} is present, this
     * method builds an Alt-Svc value like {@code h3=":443"} so that
     * HTTP/1.1 and HTTP/2 responses advertise the availability of HTTP/3.
     *
     * @return the Alt-Svc header value, or null if there are no QUIC
     *         listeners
     */
    private String computeAltSvc() {
        boolean hasTcp = false;
        List h3Ports = new ArrayList();

        for (int i = 0; i < listeners.size(); i++) {
            Object listener = listeners.get(i);
            if (listener instanceof HTTPListener) {
                hasTcp = true;
            } else if (listener instanceof HTTP3Listener) {
                HTTP3Listener h3 = (HTTP3Listener) listener;
                int port = h3.getPort();
                if (port > 0) {
                    h3Ports.add(Integer.valueOf(port));
                }
            }
        }

        if (!hasTcp || h3Ports.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < h3Ports.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("h3=\":");
            sb.append(h3Ports.get(i));
            sb.append("\"; ma=86400");
        }
        return sb.toString();
    }

}
