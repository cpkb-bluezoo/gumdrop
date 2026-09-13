/*
 * HttpServer.java
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

package org.bluezoo.gumdrop.http.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.Listener;
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.http.DefaultHttpAuthenticationProvider;
import org.bluezoo.gumdrop.http.HttpAuthenticationProvider;
import org.bluezoo.gumdrop.http.HttpListener;
import org.bluezoo.gumdrop.http.HttpRequestHandler;
import org.bluezoo.gumdrop.http.HttpRequestHandlerFactory;
import org.bluezoo.gumdrop.http.h3.Http3Listener;

/**
 * Abstract base for HTTP protocol servers.
 *
 * <p>An {@code HttpServer} defines the application logic for handling
 * HTTP requests: the {@link HttpRequestHandlerFactory} that creates
 * handlers for each stream, and an optional
 * {@link HttpAuthenticationProvider} for authenticating requests.
 *
 * <p>A server owns one or more transport listeners:
 * <ul>
 *   <li>{@link HttpListener} for HTTP/1.1 and HTTP/2 over TCP</li>
 *   <li>{@link Http3Listener} for HTTP/3 over QUIC</li>
 * </ul>
 *
 * <p>During {@link #start()}, the server:
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
 * <p>During {@link #stop()}, the server stops listeners first, then
 * calls {@link #destroyService()} for subclass-specific teardown.
 *
 * <h2>Configuration Example</h2>
 * <pre>{@code
 * <service class="org.bluezoo.gumdrop.servlet.ServletServer">
 *   <listener class="org.bluezoo.gumdrop.http.HttpListener"
 *           port="8080"/>
 *   <listener class="org.bluezoo.gumdrop.http.h3.Http3Listener"
 *           port="8443" cert-file="/etc/cert.pem" key-file="/etc/key.pem"/>
 * </service>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Server
 * @see org.bluezoo.gumdrop.http.HttpServer
 * @see HttpListener
 * @see Http3Listener
 * @see HttpRequestHandlerFactory
 */
public abstract class HttpServer implements Server {

    private static final Logger LOGGER =
            Logger.getLogger(HttpServer.class.getName());

    private final List<Listener> listeners = new ArrayList<Listener>();
    private Realm realm;
    private boolean addSecurityHeaders = true;

    // ── Realm ──

    /**
     * Returns the authentication realm for this server.
     *
     * @return the realm, or null if no realm is configured
     */
    public Realm getRealm() {
        return realm;
    }

    /**
     * Sets the authentication realm for this server.
     *
     * <p>When a realm is configured and no explicit
     * {@link HttpAuthenticationProvider} is returned by
     * {@link #getAuthenticationProvider()}, a
     * {@link DefaultHttpAuthenticationProvider} will be created
     * automatically to bridge the realm to HTTP authentication.
     *
     * @param realm the realm
     */
    public void setRealm(Realm realm) {
        this.realm = realm;
    }

    /**
     * Sets whether to add default security headers (X-Frame-Options,
     * X-Content-Type-Options) to responses. Default: true.
     *
     * @param addSecurityHeaders true to add headers (unless app sets them)
     */
    public void setAddSecurityHeaders(boolean addSecurityHeaders) {
        this.addSecurityHeaders = addSecurityHeaders;
    }

    /**
     * Returns whether default security headers are added to responses.
     */
    public boolean isAddSecurityHeaders() {
        return addSecurityHeaders;
    }

    // ── Listener management ──

    /**
     * Adds a TCP (HTTP/1.1 + HTTP/2) listener to this server.
     *
     * @param endpoint the TCP listener
     */
    public void addListener(HttpListener endpoint) {
        listeners.add(endpoint);
    }

    /**
     * Adds a QUIC (HTTP/3) listener to this server.
     *
     * @param endpoint the QUIC listener
     */
    public void addListener(Http3Listener endpoint) {
        listeners.add(endpoint);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns all current listeners, both TCP and QUIC.
     */
    /**
     * Sets the listeners from a configuration list. Each item must be
     * an {@link HttpListener} or {@link Http3Listener}.
     *
     * @param list the list of listener endpoints
     */
    public void setListeners(List<?> list) {
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof Http3Listener) {
                addListener((Http3Listener) item);
            } else if (item instanceof HttpListener) {
                addListener((HttpListener) item);
            }
        }
    }

    @Override
    public List<? extends Listener> getListeners() {
        return Collections.unmodifiableList(listeners);
    }

    // ── Application logic hooks ──

    /**
     * Returns the handler factory that creates
     * {@link HttpRequestHandler} instances for each request stream.
     *
     * <p>Called during {@link #start()} to wire each listener.
     * Subclasses must provide their own factory.
     *
     * @return the handler factory, never null after initialisation
     */
    protected abstract HttpRequestHandlerFactory getHandlerFactory();

    /**
     * Returns the authentication provider for this server, or null
     * if authentication is not required.
     *
     * <p>The default implementation returns null (no authentication).
     * Subclasses may override to provide an authentication provider
     * that applies to all listeners.
     *
     * @return the authentication provider, or null
     */
    protected HttpAuthenticationProvider getAuthenticationProvider() {
        return null;
    }

    /**
     * Initialises server-specific application resources.
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
     * Tears down server-specific application resources.
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
     * Starts this server: initialises application logic, wires
     * listeners, computes Alt-Svc, and starts each listener.
     */
    @Override
    public void start() {
        initService();

        HttpRequestHandlerFactory factory = getHandlerFactory();
        HttpAuthenticationProvider authProvider =
                getAuthenticationProvider();
        if (authProvider == null && realm != null) {
            authProvider = new DefaultHttpAuthenticationProvider(realm);
        }
        String altSvc = computeAltSvc();

        for (int i = 0; i < listeners.size(); i++) {
            Object listener = listeners.get(i);
            wireListener(listener, factory, authProvider, altSvc);
            startListener(listener);
        }
    }

    /**
     * Stops this server: stops all listeners first, then tears down
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
     * Wires a single listener with the server's handler factory,
     * authentication provider, and Alt-Svc header.
     */
    private void wireListener(Object listener,
                              HttpRequestHandlerFactory factory,
                              HttpAuthenticationProvider authProvider,
                              String altSvc) {
        if (listener instanceof HttpListener) {
            HttpListener tcp = (HttpListener) listener;
            tcp.setHandlerFactory(factory);
            tcp.setAuthenticationProvider(authProvider);
            tcp.setAddSecurityHeaders(addSecurityHeaders);
            if (altSvc != null) {
                tcp.setAltSvc(altSvc);
            }
        } else if (listener instanceof Http3Listener) {
            Http3Listener quic = (Http3Listener) listener;
            quic.setHandlerFactory(factory);
            quic.setAuthenticationProvider(authProvider);
            quic.setAddSecurityHeaders(addSecurityHeaders);
        }
    }

    /**
     * Starts a single listener.
     */
    private void startListener(Object listener) {
        if (listener instanceof Http3Listener) {
            Http3Listener h3 = (Http3Listener) listener;
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
     * Computes the {@code Alt-Svc} header value when this server has
     * both TCP and QUIC listeners.
     *
     * <p>If at least one {@link Http3Listener} is present, this
     * method builds an Alt-Svc value like {@code h3=":443"} so that
     * HTTP/1.1 and HTTP/2 responses advertise the availability of HTTP/3.
     *
     * @return the Alt-Svc header value, or null if there are no QUIC
     *         listeners
     */
    private String computeAltSvc() {
        boolean hasTcp = false;
        List<Integer> h3Ports = new ArrayList<Integer>();

        for (int i = 0; i < listeners.size(); i++) {
            Object listener = listeners.get(i);
            if (listener instanceof HttpListener) {
                hasTcp = true;
            } else if (listener instanceof Http3Listener) {
                Http3Listener h3 = (Http3Listener) listener;
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
