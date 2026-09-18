/*
 * WebSocketRequestHandler.java
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

package org.bluezoo.gumdrop.websocket.server;

import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.server.DefaultHttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponseState;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.websocket.PerMessageDeflateExtension;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketExtension;
import org.bluezoo.gumdrop.websocket.WebSocketHandshake;
import org.bluezoo.gumdrop.websocket.WebSocketMetricsSource;
import org.bluezoo.gumdrop.websocket.WebSocketServerMetrics;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP-to-WebSocket upgrade handler (RFC 6455, and Extended CONNECT for
 * HTTP/2 &mdash; RFC 8441 &mdash; and HTTP/3 &mdash; RFC 9220).
 *
 * <p>Install on {@link org.bluezoo.gumdrop.http.HttpServer} via
 * {@link org.bluezoo.gumdrop.http.HttpServer.Composer#streamHandler(HttpStreamHandler)}.
 * A single instance handles the upgrade on whichever HTTP version the
 * underlying listener negotiates &mdash; HTTP/1.1's {@code Upgrade} header
 * exchange, or the {@code :method CONNECT} / {@code :protocol websocket}
 * Extended CONNECT exchange HTTP/2 and HTTP/3 share:
 *
 * <pre>{@code
 * HttpServer server = HttpServer.compose()
 *         .listener(new Http2Listener().port(8080))
 *         .listener(new Http3Listener().port(8443).tls(tls))
 *         .streamHandler(WebSocketRequestHandler.builder()
 *                 .onConnect(new WebSocketRequestHandler.ConnectionHandlerFactory() {
 *                     public WebSocketEventHandler create(String path, Headers headers) {
 *                         return new EchoHandler();
 *                     }
 *                 })
 *                 .build())
 *         .server();
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see web/configuration.html
 * @see <a href="https://tools.ietf.org/html/rfc6455">RFC 6455: The WebSocket Protocol</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8441">RFC 8441: Bootstrapping WebSockets with HTTP/2</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9220">RFC 9220: Bootstrapping WebSockets with HTTP/3</a>
 */
public final class WebSocketRequestHandler implements HttpStreamHandler {

    private static final Logger LOGGER =
            Logger.getLogger(WebSocketRequestHandler.class.getName());

    private final ConnectionHandlerFactory connectionHandlerFactory;
    private final SubprotocolSelector subprotocolSelector;
    private final List<WebSocketExtension> supportedExtensions;
    private final WebSocketServerMetrics wsMetrics;

    private WebSocketRequestHandler(ConnectionHandlerFactory connectionHandlerFactory,
                                    SubprotocolSelector subprotocolSelector,
                                    List<WebSocketExtension> supportedExtensions,
                                    WebSocketServerMetrics wsMetrics) {
        this.connectionHandlerFactory = connectionHandlerFactory;
        this.subprotocolSelector = subprotocolSelector;
        this.supportedExtensions = supportedExtensions;
        this.wsMetrics = wsMetrics;
    }

    @Override
    public HttpRequestHandler openStream(HttpResponseState stream) {
        return new UpgradeHandler();
    }

    /**
     * Creates a builder for a {@link WebSocketRequestHandler}.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a {@link WebSocketEventHandler} for an incoming WebSocket
     * connection.
     */
    @FunctionalInterface
    public interface ConnectionHandlerFactory {

        /**
         * Called when a valid WebSocket upgrade request is received.
         *
         * @param requestPath the request path from the HTTP upgrade request
         * @param upgradeHeaders the full HTTP headers of the upgrade request
         * @return a handler for this connection, or null to reject (a 403
         *         Forbidden response is sent)
         */
        WebSocketEventHandler create(String requestPath, Headers upgradeHeaders);

    }

    /**
     * RFC 6455 §4.2.2 — selects a WebSocket subprotocol from the client's
     * {@code Sec-WebSocket-Protocol} header.
     */
    @FunctionalInterface
    public interface SubprotocolSelector {

        /**
         * @param upgradeHeaders the HTTP headers of the upgrade request
         * @return the selected subprotocol, or null for no subprotocol
         *         negotiation
         */
        String select(Headers upgradeHeaders);

    }

    /**
     * Builder for {@link WebSocketRequestHandler}.
     */
    public static final class Builder {

        private ConnectionHandlerFactory connectionHandlerFactory;
        private SubprotocolSelector subprotocolSelector;
        private boolean deflateEnabled = true;
        private TelemetryConfig telemetryConfig;

        private Builder() {
        }

        /**
         * Sets the factory that creates a handler for each accepted
         * WebSocket connection. Required.
         */
        public Builder onConnect(ConnectionHandlerFactory connectionHandlerFactory) {
            if (connectionHandlerFactory == null) {
                throw new NullPointerException("connectionHandlerFactory");
            }
            this.connectionHandlerFactory = connectionHandlerFactory;
            return this;
        }

        /**
         * Sets the subprotocol selector. Optional; the default selects no
         * subprotocol.
         */
        public Builder subprotocolSelector(SubprotocolSelector subprotocolSelector) {
            this.subprotocolSelector = subprotocolSelector;
            return this;
        }

        /**
         * RFC 7692 — enables or disables permessage-deflate compression.
         * Enabled by default.
         */
        public Builder deflateEnabled(boolean enabled) {
            this.deflateEnabled = enabled;
            return this;
        }

        /**
         * Enables {@link WebSocketServerMetrics} for connections upgraded
         * by the built handler. Optional; no metrics are recorded by
         * default.
         */
        public Builder metrics(TelemetryConfig telemetryConfig) {
            this.telemetryConfig = telemetryConfig;
            return this;
        }

        public WebSocketRequestHandler build() {
            if (connectionHandlerFactory == null) {
                throw new IllegalStateException("onConnect is required");
            }
            List<WebSocketExtension> extensions = new ArrayList<WebSocketExtension>();
            if (deflateEnabled) {
                extensions.add(new PerMessageDeflateExtension());
            }
            WebSocketServerMetrics wsMetrics = null;
            if (telemetryConfig != null && telemetryConfig.isMetricsEnabled()) {
                wsMetrics = new WebSocketServerMetrics(telemetryConfig);
            }
            SubprotocolSelector selector = subprotocolSelector != null
                    ? subprotocolSelector
                    : NO_SUBPROTOCOL;
            return new WebSocketRequestHandler(
                    connectionHandlerFactory, selector, extensions, wsMetrics);
        }

        private static final SubprotocolSelector NO_SUBPROTOCOL =
                new SubprotocolSelector() {
                    @Override
                    public String select(Headers upgradeHeaders) {
                        return null;
                    }
                };
    }

    // ── Internal HTTP upgrade machinery ──

    /**
     * Validates the WebSocket upgrade (RFC 6455 §4.2, or Extended CONNECT
     * per RFC 8441 / RFC 9220), negotiates extensions (RFC 6455 §9), and
     * delegates to {@link #connectionHandlerFactory}.
     */
    private final class UpgradeHandler extends DefaultHttpRequestHandler
            implements WebSocketMetricsSource {

        @Override
        public void headers(HttpResponseState state, Headers headers) {
            boolean extendedConnect = "CONNECT".equals(headers.getValue(":method"))
                    && "websocket".equalsIgnoreCase(headers.getValue(":protocol"));
            String offeredExtensions;
            String path;
            if (extendedConnect) {
                // RFC 8441 section 4 / RFC 9220 section 3 -- HTTP/2 and
                // HTTP/3 forbid the RFC 6455 Upgrade: header exchange as
                // connection-specific, so both use Extended CONNECT instead.
                path = headers.getValue(":path");
                if (path == null) {
                    path = headers.getValue(":authority");
                }
                offeredExtensions = headers.getValue("sec-websocket-extensions");
            } else if (WebSocketHandshake.isValidWebSocketUpgrade(headers)) {
                path = headers.getValue(":path");
                offeredExtensions = headers.getValue("Sec-WebSocket-Extensions");
            } else {
                sendError(state, HttpStatus.BAD_REQUEST);
                return;
            }

            WebSocketEventHandler handler =
                    connectionHandlerFactory.create(path, headers);
            if (handler == null) {
                sendError(state, HttpStatus.FORBIDDEN);
                return;
            }

            String subprotocol = subprotocolSelector.select(headers);

            List<WebSocketExtension> negotiated = WebSocketHandshake.negotiateExtensions(
                    offeredExtensions, supportedExtensions);

            try {
                state.upgradeToWebSocket(subprotocol, negotiated, handler);
            } catch (IllegalStateException e) {
                LOGGER.log(Level.WARNING, "WebSocket upgrade failed", e);
                sendError(state, HttpStatus.BAD_REQUEST);
            }
        }

        @Override
        public WebSocketServerMetrics getWebSocketMetrics() {
            return wsMetrics;
        }

        private void sendError(HttpResponseState state, HttpStatus status) {
            Headers response = new Headers();
            response.status(status);
            state.headers(response);
            state.complete();
        }
    }

}
