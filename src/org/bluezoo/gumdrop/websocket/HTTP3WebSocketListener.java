/*
 * HTTP3WebSocketListener.java
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
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.http.DefaultHTTPRequestHandler;
import org.bluezoo.gumdrop.http.HTTPRequestHandler;
import org.bluezoo.gumdrop.http.HTTPRequestHandlerFactory;
import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.h3.HTTP3Listener;
import org.bluezoo.gumdrop.http.h3.HTTP3ServerHandler;
import org.bluezoo.gumdrop.quic.QuicConnection;

/**
 * QUIC transport listener for WebSocket connections over HTTP/3 (RFC 9220).
 *
 * <p>This is the HTTP/3 equivalent of {@link WebSocketListener}. It extends
 * {@link HTTP3Listener} and installs a handler factory that detects
 * Extended CONNECT requests with {@code :protocol = "websocket"}
 * (RFC 9220 section 3) and upgrades them to WebSocket connections.
 *
 * <p>The HTTP/3 layer advertises {@code SETTINGS_ENABLE_CONNECT_PROTOCOL = 1}
 * in the SETTINGS frame (handled by {@link HTTP3ServerHandler}), enabling
 * clients to send Extended CONNECT requests. Unlike HTTP/1.1 WebSocket
 * upgrades (RFC 6455), there is no {@code Sec-WebSocket-Key} exchange;
 * the server responds with {@code :status 200} to accept.
 *
 * <h2>Configuration</h2>
 *
 * <pre>{@code
 * <service class="my.EchoService">
 *   <listener class="org.bluezoo.gumdrop.websocket.HTTP3WebSocketListener">
 *     <property name="port">443</property>
 *     <property name="cert-file">/path/to/cert.pem</property>
 *     <property name="key-file">/path/to/key.pem</property>
 *   </listener>
 * </service>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9220">RFC 9220</a>
 * @see WebSocketService
 * @see HTTP3Listener
 */
public class HTTP3WebSocketListener extends HTTP3Listener {

    private static final Logger LOGGER =
            Logger.getLogger(HTTP3WebSocketListener.class.getName());

    private WebSocketService service;
    private WebSocketServerMetrics wsMetrics;

    private List<WebSocketExtension> supportedExtensions = new ArrayList<>();
    private boolean deflateEnabled = true;

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
        return "wss+h3";
    }

    /**
     * Returns the WebSocket server metrics, or null if metrics are
     * not enabled.
     *
     * @return the metrics instance, or null
     */
    public WebSocketServerMetrics getWebSocketMetrics() {
        return wsMetrics;
    }

    /**
     * RFC 7692 — enables or disables permessage-deflate compression.
     * Enabled by default.
     *
     * @param enabled true to enable permessage-deflate
     */
    public void setDeflateEnabled(boolean enabled) {
        this.deflateEnabled = enabled;
    }

    @Override
    public void start() {
        if (deflateEnabled) {
            supportedExtensions.add(new PerMessageDeflateExtension());
        }
        setHandlerFactory(new ExtendedConnectHandlerFactory());
        super.start();
        if (isMetricsEnabled()) {
            wsMetrics = new WebSocketServerMetrics(getTelemetryConfig());
        }
    }

    @Override
    public void connectionAccepted(QuicConnection connection) {
        HTTP3ServerHandler handler = new HTTP3ServerHandler(
                connection, getHandlerFactory(),
                getAuthenticationProvider(), getMetrics(),
                getTelemetryConfig());
        handler.setWebSocketMetrics(wsMetrics);
    }

    // ── Internal Extended CONNECT upgrade machinery ──

    /**
     * Handler factory that creates upgrade handlers for each incoming
     * HTTP/3 request.
     */
    private class ExtendedConnectHandlerFactory
            implements HTTPRequestHandlerFactory {

        @Override
        public HTTPRequestHandler createHandler(HTTPResponseState state,
                                                Headers headers) {
            return new ExtendedConnectUpgradeHandler();
        }
    }

    /**
     * RFC 9220 section 3 — HTTP/3 request handler that detects Extended
     * CONNECT with {@code :protocol = "websocket"}, negotiates extensions,
     * and delegates to the owning service's connection handler factory.
     */
    private class ExtendedConnectUpgradeHandler
            extends DefaultHTTPRequestHandler {

        @Override
        public void headers(HTTPResponseState state, Headers headers) {
            String method = headers.getValue(":method");
            String proto = headers.getValue(":protocol");

            if (!"CONNECT".equals(method)
                    || !"websocket".equalsIgnoreCase(proto)) {
                sendError(state, HTTPStatus.BAD_REQUEST);
                return;
            }

            String path = headers.getValue(":path");
            if (path == null) {
                path = headers.getValue(":authority");
            }

            WebSocketEventHandler handler =
                    service.createConnectionHandler(path, headers);
            if (handler == null) {
                sendError(state, HTTPStatus.FORBIDDEN);
                return;
            }

            String subprotocol = service.selectSubprotocol(headers);

            // RFC 9220 section 3 — negotiate extensions via headers
            String offeredExtensions =
                    headers.getValue("sec-websocket-extensions");
            List<WebSocketExtension> negotiated =
                    WebSocketHandshake.negotiateExtensions(
                            offeredExtensions, supportedExtensions);

            try {
                state.upgradeToWebSocket(subprotocol, negotiated, handler);
            } catch (IllegalStateException e) {
                LOGGER.log(Level.WARNING,
                        "WebSocket over HTTP/3 upgrade failed", e);
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
