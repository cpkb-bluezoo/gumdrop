/*
 * H3ClientWebSocketResponseHandler.java
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

package org.bluezoo.gumdrop.http.h3;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.http.client.DefaultHTTPResponseHandler;
import org.bluezoo.gumdrop.http.client.HTTPResponse;
import org.bluezoo.gumdrop.quic.QuicConnectionCloseException;
import org.bluezoo.gumdrop.websocket.WebSocketConnection;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketExtension;
import org.bluezoo.gumdrop.websocket.WebSocketHandshake;
import org.bluezoo.gumdrop.websocket.WebSocketSession;

/**
 * RFC 9220 -- bridges a generic HTTP/3 Extended CONNECT response ({@link
 * org.bluezoo.gumdrop.http.client.HTTPResponseHandler}) to a {@link
 * WebSocketConnection}.
 *
 * <p>{@link H3ClientStream} has no notion of WebSocket at all -- it always
 * calls the ordinary {@link org.bluezoo.gumdrop.http.client.HTTPResponseHandler}
 * callback sequence, and this class is what reinterprets that sequence as
 * a WebSocket connection: {@link #header} collects {@code
 * sec-websocket-extensions}, {@link #startResponseBody} builds the {@link
 * WebSocketConnection} bridge (called as soon as headers are known
 * complete -- see {@link H3ClientStream}'s own documentation on why HTTP/3
 * signals this eagerly, unlike HTTP/2's {@code H2WebSocketResponseHandler}
 * which waits for {@code !endStream}), and {@link #responseBodyContent}
 * feeds each DATA frame's bytes to the WebSocket frame parser instead of
 * treating them as a response body. Direct client-side mirror of {@code
 * org.bluezoo.gumdrop.websocket.client.H2WebSocketResponseHandler}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTP3ClientHandler#connectWebSocket
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9220">RFC 9220</a>
 */
class H3ClientWebSocketResponseHandler extends DefaultHTTPResponseHandler {

    private static final Logger LOGGER = Logger.getLogger(H3ClientWebSocketResponseHandler.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.http.h3.L10N");

    private final List<WebSocketExtension> requestedExtensions;
    private final WebSocketEventHandler wsHandler;

    // Bound by HTTP3ClientHandler.connectWebSocket immediately after both
    // this handler and its H3ClientStream are constructed -- see
    // bindStream()'s own documentation for why construction can't just
    // take this in the constructor.
    private H3ClientStream stream;

    private String extensionsHeader;
    private boolean failed;
    private H3ClientWebSocketConnectionAdapter webSocketAdapter;

    H3ClientWebSocketResponseHandler(List<WebSocketExtension> requestedExtensions, WebSocketEventHandler wsHandler) {
        this.requestedExtensions = requestedExtensions;
        this.wsHandler = wsHandler;
    }

    /**
     * Supplies the {@link H3ClientStream} this handler was constructed
     * for, so its transport can write DATA frames back once the upgrade
     * completes. Called exactly once, by {@link
     * HTTP3ClientHandler#connectWebSocket}, right after the {@code
     * H3ClientStream} itself is constructed -- unlike HTTP/2's {@code
     * H2WebSocketResponseHandler} (which receives its {@code HTTPRequest}
     * in the constructor), this can't happen at construction time here:
     * {@code H3ClientStream}'s own constructor requires the response
     * handler already built, so the stream necessarily comes second.
     *
     * @param stream the stream this handler is receiving events for
     */
    void bindStream(H3ClientStream stream) {
        this.stream = stream;
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
                "WebSocket-over-HTTP/3 upgrade failed: " + response.getStatus()));
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

        webSocketAdapter = new H3ClientWebSocketConnectionAdapter(wsHandler);
        // RFC 9220 changes only the opening handshake, not RFC 6455
        // framing -- masking still applies over H3, and this is the
        // client side of it (masks outgoing, expects unmasked incoming).
        webSocketAdapter.setClientMode(true);
        webSocketAdapter.setTransport(new H3ClientWebSocketTransport(stream));
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
            LOGGER.log(Level.WARNING, L10N.getString("warn.websocket_frame_error"), e);
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
            if (ex instanceof QuicConnectionCloseException) {
                // RFC 6455 section 7.4: 1006 is reserved for "the
                // connection was closed abnormally, e.g. without a Close
                // frame being sent" -- exactly this case (the underlying
                // QUIC connection is already gone, no closing handshake
                // is possible). The real QUIC/H3 error code and reason go
                // into the reason string, since 1006 itself carries no
                // code slot.
                webSocketAdapter.notifyTransportClosed(1006, ex.getMessage());
            } else {
                webSocketAdapter.notifyError(ex);
            }
        } else if (!failed) {
            wsHandler.error(ex);
        }
    }

    /**
     * Bridges {@link WebSocketEventHandler} to the {@link WebSocketConnection}
     * abstract class. Direct client-side mirror of {@code
     * H2WebSocketResponseHandler.H2ClientWebSocketConnectionAdapter}.
     */
    private static class H3ClientWebSocketConnectionAdapter extends WebSocketConnection
            implements WebSocketSession {

        private final WebSocketEventHandler wsHandler;

        H3ClientWebSocketConnectionAdapter(WebSocketEventHandler wsHandler) {
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
     * frames as HTTP/3 DATA frames on the bound {@link H3ClientStream}.
     */
    private static class H3ClientWebSocketTransport implements WebSocketConnection.WebSocketTransport {

        private final H3ClientStream stream;

        H3ClientWebSocketTransport(H3ClientStream stream) {
            this.stream = stream;
        }

        @Override
        public void sendFrame(ByteBuffer frameData) throws IOException {
            if (stream.isClosed()) {
                throw new IOException("Stream closed");
            }
            stream.sendRawData(frameData);
        }

        @Override
        public void close(boolean normalClose) throws IOException {
            stream.closeStream();
        }
    }
}
