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

import org.bluezoo.gumdrop.http.HttpMessageHandler;
import org.bluezoo.gumdrop.http.HttpMessageRecorder;
import org.bluezoo.gumdrop.http.HttpMethod;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.http.server.DefaultHttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponse;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.websocket.PerMessageDeflateExtension;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketExtension;
import org.bluezoo.gumdrop.websocket.WebSocketHandshake;
import org.bluezoo.gumdrop.websocket.WebSocketMetricsSource;
import org.bluezoo.gumdrop.websocket.WebSocketServerMetrics;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ResourceBundle;
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
 *                     public WebSocketEventHandler create(String path,
 *                                                         WebSocketRequestHandler.UpgradeRequest request) {
 *                         return new EchoHandler();
 *                     }
 *                 })
 *                 .build())
 *         .server();
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://tools.ietf.org/html/rfc6455">RFC 6455: The WebSocket Protocol</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8441">RFC 8441: Bootstrapping WebSockets with HTTP/2</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9220">RFC 9220: Bootstrapping WebSockets with HTTP/3</a>
 */
public final class WebSocketRequestHandler implements HttpStreamHandler {

    private static final Logger LOGGER =
            Logger.getLogger(WebSocketRequestHandler.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.websocket.L10N");

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
    public HttpRequestHandler openStream(HttpResponse response) {
        return new UpgradeHandler(response);
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
    public interface ConnectionHandlerFactory {

        /**
         * Called when a valid WebSocket upgrade request is received.
         *
         * @param requestPath the request path from the HTTP upgrade request
         * @param request the upgrade request, whose events can be replayed
         *        to a handler of the application's own to see its fields
         * @return a handler for this connection, or null to reject (a 403
         *         Forbidden response is sent)
         */
        WebSocketEventHandler create(String requestPath, UpgradeRequest request);

    }

    /**
     * The HTTP upgrade request that opened a WebSocket connection, as the
     * events it was made of.
     */
    public interface UpgradeRequest {

        /**
         * Delivers the events of the upgrade request, in order, to
         * {@code target}: the method, the target, the version, the scheme and
         * authority where the request had them, the fields, and then
         * {@link HttpMessageHandler#endHeaders()}. May be called more than
         * once, and is valid for as long as the application holds the
         * request.
         *
         * @param target the receiver of the request's events
         */
        void replay(HttpMessageHandler target);

    }

    /**
     * RFC 6455 §4.2.2 — selects a WebSocket subprotocol from the client's
     * {@code Sec-WebSocket-Protocol} header.
     */
    public interface SubprotocolSelector {

        /**
         * @param offeredSubprotocols the subprotocol tokens the client
         *        offered in {@code Sec-WebSocket-Protocol}, trimmed and in
         *        the order offered; empty if the field was absent
         * @return the selected subprotocol, or null for no subprotocol
         *         negotiation
         */
        String select(List<String> offeredSubprotocols);

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
                    public String select(List<String> offeredSubprotocols) {
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
            implements WebSocketMetricsSource, UpgradeRequest {

        private final HttpResponse response;
        // The request as the connection factory is given it, to replay
        private final HttpMessageRecorder recorder = new HttpMessageRecorder();
        // The values the upgrade decision is made from, taken from the events
        private String method;
        private String path;
        private String protocol;
        private String upgrade;
        private String connection;
        private String key;
        private String version;
        private String extensions;
        private final List<String> subprotocols = new ArrayList<String>();

        UpgradeHandler(HttpResponse response) {
            this.response = response;
        }

        private String text(ByteBuffer b) {
            byte[] octets = new byte[b.remaining()];
            b.duplicate().get(octets);
            return new String(octets, StandardCharsets.ISO_8859_1);
        }

        private String combine(String existing, String value) {
            return existing == null ? value : existing + ", " + value;
        }

        @Override
        public void replay(HttpMessageHandler target) {
            recorder.replay(target);
        }

        @Override
        public void method(HttpMethod method) {
            this.method = method.name();
            recorder.method(method);
        }

        @Override
        public void target(ByteBuffer target) {
            path = text(target);
            recorder.target(target);
        }

        @Override
        public void scheme(ByteBuffer scheme) {
            recorder.scheme(scheme);
        }

        @Override
        public void authority(ByteBuffer authority) {
            recorder.authority(authority);
        }

        @Override
        public void protocol(ByteBuffer protocol) {
            this.protocol = text(protocol);
            recorder.protocol(protocol);
        }

        @Override
        public void version(HttpVersion version) {
            recorder.version(version);
        }

        @Override
        public void contentType(ContentType contentType) {
            recorder.contentType(contentType);
        }

        @Override
        public void contentDisposition(ContentDisposition contentDisposition) {
            recorder.contentDisposition(contentDisposition);
        }

        @Override
        public void longHeader(String name, long value) {
            recorder.longHeader(name, value);
        }

        @Override
        public void dateHeader(String name, java.time.Instant value) {
            recorder.dateHeader(name, value);
        }

        @Override
        public void header(String name, ByteBuffer value) {
            String text = text(value).trim();
            if ("upgrade".equalsIgnoreCase(name)) {
                upgrade = combine(upgrade, text);
            } else if ("connection".equalsIgnoreCase(name)) {
                connection = combine(connection, text);
            } else if ("sec-websocket-key".equalsIgnoreCase(name)) {
                if (key == null) {
                    key = text;
                }
            } else if ("sec-websocket-version".equalsIgnoreCase(name)) {
                if (version == null) {
                    version = text;
                }
            } else if ("sec-websocket-extensions".equalsIgnoreCase(name)) {
                if (extensions == null) {
                    extensions = text;
                }
            } else if ("sec-websocket-protocol".equalsIgnoreCase(name)) {
                int start = 0;
                while (start <= text.length()) {
                    int end = text.indexOf(',', start);
                    if (end < 0) {
                        end = text.length();
                    }
                    String token = text.substring(start, end).trim();
                    if (!token.isEmpty()) {
                        subprotocols.add(token);
                    }
                    start = end + 1;
                }
            }
            recorder.header(name, value);
        }

        @Override
        public void endHeaders() {
            recorder.endHeaders();
            // RFC 8441 section 4 / RFC 9220 section 3 -- HTTP/2 and HTTP/3
            // forbid the RFC 6455 Upgrade: header exchange as
            // connection-specific, so both use Extended CONNECT instead.
            boolean extendedConnect = "CONNECT".equals(method)
                    && "websocket".equalsIgnoreCase(protocol);
            if (!extendedConnect
                    && !WebSocketHandshake.isValidWebSocketUpgrade(upgrade, connection, key, version)) {
                sendError(HttpStatus.BAD_REQUEST);
                return;
            }

            WebSocketEventHandler handler =
                    connectionHandlerFactory.create(path, this);
            if (handler == null) {
                sendError(HttpStatus.FORBIDDEN);
                return;
            }

            String subprotocol = subprotocolSelector.select(
                    Collections.unmodifiableList(new ArrayList<String>(subprotocols)));

            List<WebSocketExtension> negotiated = WebSocketHandshake.negotiateExtensions(
                    extensions, supportedExtensions);

            try {
                response.upgradeToWebSocket(subprotocol, negotiated, handler);
            } catch (IllegalStateException e) {
                LOGGER.log(Level.WARNING, L10N.getString("warn.upgrade_failed"), e);
                sendError(HttpStatus.BAD_REQUEST);
            }
        }

        @Override
        public WebSocketServerMetrics getWebSocketMetrics() {
            return wsMetrics;
        }

        private void sendError(HttpStatus status) {
            response.status(status.code);
            response.endMessage();
        }
    }

}
