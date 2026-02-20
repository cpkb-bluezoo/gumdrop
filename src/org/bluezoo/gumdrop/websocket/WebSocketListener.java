/*
 * WebSocketListener.java
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

import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.http.DefaultHTTPRequestHandler;
import org.bluezoo.gumdrop.http.HTTPListener;
import org.bluezoo.gumdrop.http.HTTPRequestHandler;
import org.bluezoo.gumdrop.http.HTTPRequestHandlerFactory;
import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.http.Headers;

/**
 * TCP transport listener for WebSocket connections.
 *
 * <p>Extends {@link HTTPListener} and encapsulates the HTTP-to-WebSocket
 * upgrade handshake. Every incoming HTTP request is automatically checked
 * for a valid WebSocket upgrade; if valid, the connection is upgraded and
 * handed off to the owning {@link WebSocketService}'s handler. Non-WebSocket
 * requests receive a 400 Bad Request response.
 *
 * <p>The HTTP protocol machinery (request parsing, upgrade negotiation,
 * frame switching) is handled entirely within this listener and the
 * underlying {@link org.bluezoo.gumdrop.http.HTTPProtocolHandler}. The
 * {@link WebSocketService} never sees HTTP types.
 *
 * <h2>Configuration</h2>
 *
 * <pre>{@code
 * <service class="my.EchoService">
 *   <listener class="org.bluezoo.gumdrop.websocket.WebSocketListener">
 *     <property name="port">8080</property>
 *   </listener>
 * </service>
 * }</pre>
 *
 * <p>All properties inherited from {@link HTTPListener} are available
 * (port, secure, keystore-file, keystore-pass, etc.).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see WebSocketService
 * @see HTTPListener
 */
public class WebSocketListener extends HTTPListener {

    private static final Logger LOGGER =
            Logger.getLogger(WebSocketListener.class.getName());

    private WebSocketService service;

    /**
     * Sets the owning service. Called by {@link WebSocketService} during
     * wiring.
     *
     * @param service the owning service
     */
    void setService(WebSocketService service) {
        this.service = service;
    }

    /**
     * Returns the owning service, or null if not yet wired.
     *
     * @return the owning service
     */
    public WebSocketService getService() {
        return service;
    }

    @Override
    public String getDescription() {
        return secure ? "wss" : "ws";
    }

    /**
     * Starts this listener. Wires the internal WebSocket upgrade handler
     * factory before starting the underlying HTTP listener.
     */
    @Override
    public void start() {
        setHandlerFactory(new UpgradeHandlerFactory());
        super.start();
    }

    // ── Internal HTTP upgrade machinery ──

    /**
     * Handler factory that creates upgrade handlers for each incoming
     * HTTP request. All HTTP concepts are confined to this class and
     * its inner handler.
     */
    private class UpgradeHandlerFactory
            implements HTTPRequestHandlerFactory {

        @Override
        public HTTPRequestHandler createHandler(HTTPResponseState state,
                                                Headers headers) {
            return new UpgradeHandler();
        }
    }

    /**
     * HTTP request handler that validates the WebSocket upgrade and
     * delegates to the owning service's connection handler factory.
     */
    private class UpgradeHandler extends DefaultHTTPRequestHandler {

        @Override
        public void headers(HTTPResponseState state, Headers headers) {
            if (!WebSocketHandshake.isValidWebSocketUpgrade(headers)) {
                sendError(state, HTTPStatus.BAD_REQUEST);
                return;
            }

            String path = headers.getValue(":path");
            WebSocketEventHandler handler =
                    service.createConnectionHandler(path, headers);
            if (handler == null) {
                sendError(state, HTTPStatus.FORBIDDEN);
                return;
            }

            String subprotocol = service.selectSubprotocol(headers);

            try {
                state.upgradeToWebSocket(subprotocol, handler);
            } catch (IllegalStateException e) {
                LOGGER.log(Level.WARNING,
                        "WebSocket upgrade failed", e);
                sendError(state, HTTPStatus.BAD_REQUEST);
            }
        }

        private void sendError(HTTPResponseState state,
                               HTTPStatus status) {
            Headers response = new Headers();
            response.status(status);
            state.headers(response);
            state.complete();
        }
    }

}
