/*
 * H2WebSocketResponseHandler.java
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

package org.bluezoo.gumdrop.websocket.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.http.client.DefaultHTTPResponseHandler;
import org.bluezoo.gumdrop.http.client.HTTPRequest;
import org.bluezoo.gumdrop.http.client.HTTPResponse;
import org.bluezoo.gumdrop.websocket.WebSocketConnection;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketExtension;
import org.bluezoo.gumdrop.websocket.WebSocketHandshake;
import org.bluezoo.gumdrop.websocket.WebSocketSession;

/**
 * RFC 8441 — bridges a generic HTTP/2 Extended CONNECT response
 * ({@link org.bluezoo.gumdrop.http.client.HTTPResponseHandler}) to a
 * {@link WebSocketConnection}.
 *
 * <p>Unlike the h3/RFC 9220 client (which has its own dedicated
 * {@code H3ClientStream}), h2 responses already route generically through
 * {@link org.bluezoo.gumdrop.http.client.HTTPResponseHandler} — a {@code 200}
 * to an Extended CONNECT is indistinguishable, at that layer, from a
 * {@code 200} to any other request. This class is what makes it WebSocket-
 * shaped: it collects {@code sec-websocket-extensions} from the header
 * callbacks (which arrive after {@link #ok}, before {@link #startResponseBody}),
 * then builds the {@link WebSocketConnection} bridge once headers are
 * complete — {@link #startResponseBody} fires as soon as the server's
 * HEADERS frame arrives without {@code END_STREAM} (i.e. exactly when the
 * upgrade is accepted), not lazily on the first WebSocket message.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://tools.ietf.org/html/rfc8441">RFC 8441: Bootstrapping WebSockets with HTTP/2</a>
 */
class H2WebSocketResponseHandler extends DefaultHTTPResponseHandler {

    private static final Logger LOGGER =
            Logger.getLogger(H2WebSocketResponseHandler.class.getName());

    private final HTTPRequest request;
    private final List<WebSocketExtension> requestedExtensions;
    private final WebSocketEventHandler wsHandler;

    private String extensionsHeader;
    private boolean failed;
    private H2ClientWebSocketConnectionAdapter webSocketAdapter;

    H2WebSocketResponseHandler(HTTPRequest request,
                               List<WebSocketExtension> requestedExtensions,
                               WebSocketEventHandler wsHandler) {
        this.request = request;
        this.requestedExtensions = requestedExtensions;
        this.wsHandler = wsHandler;
    }

    @Override
    public void ok(HTTPResponse response) {
        // Nothing to do yet -- sec-websocket-extensions (if any) arrives
        // via header(), and the bridge is built in startResponseBody()
        // once the header section is known to be complete.
    }

    @Override
    public void error(HTTPResponse response) {
        failed = true;
        wsHandler.error(new IOException(
                "WebSocket-over-HTTP/2 upgrade failed: " + response.getStatus()));
    }

    @Override
    public void header(String name, String value) {
        if ("sec-websocket-extensions".equalsIgnoreCase(name)) {
            extensionsHeader = value;
        }
    }

    @Override
    public void startResponseBody() {
        if (failed) {
            return;
        }
        List<WebSocketExtension> activeExtensions =
                WebSocketHandshake.reconcileExtensions(extensionsHeader, requestedExtensions);

        webSocketAdapter = new H2ClientWebSocketConnectionAdapter(wsHandler);
        // RFC 8441 changes only the opening handshake, not RFC 6455
        // framing -- masking still applies over H2, and this is the
        // client side of it (masks outgoing, expects unmasked incoming).
        webSocketAdapter.setClientMode(true);
        webSocketAdapter.setTransport(new H2ClientWebSocketTransport(request));
        if (!activeExtensions.isEmpty()) {
            webSocketAdapter.setExtensions(activeExtensions);
        }
        webSocketAdapter.notifyConnectionOpen();
    }

    @Override
    public void responseBodyContent(ByteBuffer data) {
        if (webSocketAdapter == null) {
            return;
        }
        try {
            webSocketAdapter.processIncomingData(data);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "WebSocket frame processing error", e);
            webSocketAdapter.notifyError(e);
        }
    }

    @Override
    public void endResponseBody() {
        if (webSocketAdapter != null) {
            webSocketAdapter.notifyTransportClosed(1001, "Transport closed");
        }
    }

    @Override
    public void failed(Exception ex) {
        if (webSocketAdapter != null) {
            webSocketAdapter.notifyError(ex);
        } else if (!failed) {
            wsHandler.error(ex);
        }
    }

    /**
     * Bridges {@link WebSocketEventHandler} to the {@link WebSocketConnection}
     * abstract class. Direct client-side mirror of
     * {@code H3ClientStream.H3ClientWebSocketConnectionAdapter}.
     */
    private static class H2ClientWebSocketConnectionAdapter extends WebSocketConnection
            implements WebSocketSession {

        private final WebSocketEventHandler wsHandler;

        H2ClientWebSocketConnectionAdapter(WebSocketEventHandler wsHandler) {
            this.wsHandler = wsHandler;
        }

        @Override
        protected void opened() {
            wsHandler.opened(this);
        }

        @Override
        protected void textMessageReceived(String message) {
            wsHandler.textMessageReceived(this, message);
        }

        @Override
        protected void binaryMessageReceived(ByteBuffer data) {
            wsHandler.binaryMessageReceived(this, data);
        }

        @Override
        protected void closed(int code, String reason) {
            wsHandler.closed(code, reason);
        }

        @Override
        protected void error(Throwable cause) {
            wsHandler.error(cause);
        }

        @Override
        public Principal getPrincipal() {
            // No server-asserted principal concept on the client side.
            return null;
        }

        void notifyError(Throwable cause) {
            error(cause);
        }

        void notifyTransportClosed(int code, String reason) {
            if (isOpen()) {
                abnormalClose(code, reason);
            }
        }
    }

    /**
     * {@link WebSocketConnection.WebSocketTransport} that sends WebSocket
     * frames as HTTP/2 DATA frames on this stream, and closes just the
     * stream (not the shared connection) on shutdown.
     */
    private static class H2ClientWebSocketTransport
            implements WebSocketConnection.WebSocketTransport {

        private final HTTPRequest request;

        H2ClientWebSocketTransport(HTTPRequest request) {
            this.request = request;
        }

        @Override
        public void sendFrame(ByteBuffer frameData) throws IOException {
            request.requestBodyContent(frameData);
        }

        @Override
        public void close(boolean normalClose) throws IOException {
            request.endRequestBody();
        }
    }
}
