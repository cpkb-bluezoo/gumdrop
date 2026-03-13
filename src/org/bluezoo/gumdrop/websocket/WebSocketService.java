/*
 * WebSocketService.java
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

package org.bluezoo.gumdrop.websocket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Listener;
import org.bluezoo.gumdrop.Service;
import org.bluezoo.gumdrop.http.Headers;

/**
 * Abstract base for WebSocket application services.
 *
 * <p>A {@code WebSocketService} provides a pure WebSocket programming
 * model: subclasses implement {@link #createConnectionHandler} to
 * receive WebSocket connections and exchange messages. The HTTP
 * upgrade handshake is handled automatically by the transport layer
 * ({@link WebSocketListener} for HTTP/1.1 and HTTP/2, or
 * {@link HTTP3WebSocketListener} for HTTP/3 via RFC 9220) and is
 * invisible to the service.
 *
 * <p>After upgrade, the connection is a raw bidirectional message
 * channel &mdash; no HTTP requests, verbs, status codes, or streams.
 * The user's {@link WebSocketEventHandler} receives
 * {@code opened}, {@code textMessageReceived},
 * {@code binaryMessageReceived}, {@code closed}, and {@code error}
 * callbacks.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * public class EchoService extends WebSocketService {
 *
 *     &#64;Override
 *     protected WebSocketEventHandler createConnectionHandler(
 *             String requestPath, Headers upgradeHeaders) {
 *         return new DefaultWebSocketEventHandler() {
 *
 *             &#64;Override
 *             public void textMessageReceived(WebSocketSession session,
 *                                             String message) {
 *                 try {
 *                     session.sendText("Echo: " + message);
 *                 } catch (java.io.IOException e) {
 *                     // handle error
 *                 }
 *             }
 *         };
 *     }
 * }
 * </pre>
 *
 * <h2>Configuration</h2>
 *
 * <pre>{@code
 * <service class="my.EchoService">
 *   <listener class="org.bluezoo.gumdrop.websocket.WebSocketListener">
 *     <property name="port">8080</property>
 *   </listener>
 *   <!-- Optional HTTP/3 WebSocket transport (RFC 9220) -->
 *   <listener class="org.bluezoo.gumdrop.websocket.HTTP3WebSocketListener">
 *     <property name="port">443</property>
 *     <property name="cert-file">/path/to/cert.pem</property>
 *     <property name="key-file">/path/to/key.pem</property>
 *   </listener>
 * </service>
 * }</pre>
 *
 * <h2>Servlet Integration</h2>
 *
 * <p>The servlet container ({@code ServletService}) provides its own
 * WebSocket upgrade path via {@code HttpServletRequest.upgrade()}.
 * Both this service and the servlet container share the same
 * {@link WebSocketEventHandler} interface and converge on the same
 * internal upgrade mechanism, so the WebSocket frame engine is
 * shared.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://tools.ietf.org/html/rfc6455">RFC 6455: The WebSocket Protocol</a>
 * @see WebSocketEventHandler
 * @see DefaultWebSocketEventHandler
 * @see WebSocketSession
 * @see WebSocketListener
 * @see HTTP3WebSocketListener
 */
public abstract class WebSocketService implements Service {

    private static final Logger LOGGER =
            Logger.getLogger(WebSocketService.class.getName());

    private final List listeners = new ArrayList();

    // ── Listener management ──

    /**
     * Adds a TCP WebSocket listener to this service.
     *
     * @param listener the WebSocket listener
     */
    public void addListener(WebSocketListener listener) {
        listeners.add(listener);
    }

    /**
     * Adds an HTTP/3 WebSocket listener to this service (RFC 9220).
     *
     * @param listener the HTTP/3 WebSocket listener
     */
    public void addListener(HTTP3WebSocketListener listener) {
        listeners.add(listener);
    }

    /**
     * Sets the listeners from a configuration list. Each item must
     * be a {@link WebSocketListener} or {@link HTTP3WebSocketListener}.
     *
     * @param list the list of listener endpoints
     */
    public void setListeners(List list) {
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof WebSocketListener) {
                addListener((WebSocketListener) item);
            } else if (item instanceof HTTP3WebSocketListener) {
                addListener((HTTP3WebSocketListener) item);
            }
        }
    }

    @Override
    public List getListeners() {
        return Collections.unmodifiableList(listeners);
    }

    // ── Application logic hooks ──

    /**
     * Creates a {@link WebSocketEventHandler} for an incoming WebSocket
     * connection.
     *
     * <p>Called when a valid WebSocket upgrade request is received. The
     * implementation should return a handler that will receive lifecycle
     * events for the connection. Return {@code null} to reject the
     * connection (a 403 Forbidden response will be sent by the
     * listener).
     *
     * @param requestPath the request path from the HTTP upgrade request
     * @param upgradeHeaders the full HTTP headers of the upgrade request
     * @return a handler for this connection, or null to reject
     */
    protected abstract WebSocketEventHandler createConnectionHandler(
            String requestPath, Headers upgradeHeaders);

    /**
     * RFC 6455 §4.2.2 — selects a WebSocket subprotocol from the client's
     * {@code Sec-WebSocket-Protocol} header. Return the selected name,
     * or {@code null} for no subprotocol negotiation.
     *
     * <p>The default implementation returns {@code null}.
     *
     * @param upgradeHeaders the HTTP headers of the upgrade request
     * @return the selected subprotocol, or null
     */
    protected String selectSubprotocol(Headers upgradeHeaders) {
        return null;
    }

    /**
     * Initialises service-specific application resources.
     *
     * <p>Called at the beginning of {@link #start()}, before listeners
     * are wired and started. Subclasses should initialise thread pools,
     * caches, or any other application-level resources here.
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
     * been stopped. Subclasses should shut down thread pools and other
     * application-level resources here.
     *
     * <p>The default implementation does nothing.
     */
    protected void destroyService() {
        // Default: no-op
    }

    // ── Lifecycle ──

    /**
     * Starts this service: initialises application logic, wires
     * listeners, and starts each listener.
     */
    @Override
    public void start() {
        initService();

        for (int i = 0; i < listeners.size(); i++) {
            Object listener = listeners.get(i);
            if (listener instanceof WebSocketListener) {
                ((WebSocketListener) listener).setService(this);
            } else if (listener instanceof HTTP3WebSocketListener) {
                ((HTTP3WebSocketListener) listener).setService(this);
            }
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

    // ── Internal helpers ──

    private void startListener(Object listener) {
        if (listener instanceof Listener) {
            try {
                ((Listener) listener).start();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE,
                        "Failed to start listener: " + listener, e);
            }
        }
    }

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

}
