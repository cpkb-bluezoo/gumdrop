/*
 * H3ClientStream.java
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
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.http.qpack.Decoder;
import org.bluezoo.gumdrop.http.client.HTTPResponse;
import org.bluezoo.gumdrop.http.client.HTTPResponseHandler;
import org.bluezoo.gumdrop.quic.QuicConnectionCloseException;
import org.bluezoo.gumdrop.quic.QuicStreamEndpoint;
import org.bluezoo.gumdrop.websocket.WebSocketConnection;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketExtension;
import org.bluezoo.gumdrop.websocket.WebSocketHandshake;
import org.bluezoo.gumdrop.websocket.WebSocketSession;

/**
 * A single HTTP/3 client request/response exchange on a QUIC stream.
 *
 * <p>This is the client-side counterpart of {@link H3Stream}. Each
 * instance is itself the QUIC stream's {@link ProtocolHandler}, owning
 * its own {@link H3Parser} fed directly from {@link #receive}, and
 * translates HTTP/3 response frames into {@link HTTPResponseHandler}
 * callbacks per RFC 9114 section 4.1 (HTTP message exchanges) -- or, for a
 * WebSocket-over-H3 Extended CONNECT (RFC 9220 section 3, see
 * {@link #forWebSocket}), into {@link WebSocketEventHandler} callbacks
 * once the {@code 200} response arrives, mirroring {@link H3Stream}'s own
 * {@code H3WebSocketConnectionAdapter}/{@code H3WebSocketTransport} pair
 * on the server side. Exactly one of {@code responseHandler}/{@code wsHandler}
 * is non-null for a given instance.
 *
 * <p>Response pseudo-headers (RFC 9114 section 4.3.2) are parsed from
 * the initial HEADERS frame, decoded via the connection-shared {@link
 * Decoder} (RFC 9204's full dynamic-table QPACK codec); specifically the
 * {@code :status} pseudo-header determines the response status code.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTP3ClientHandler
 * @see HTTPResponseHandler
 */
class H3ClientStream implements ProtocolHandler, H3FrameHandler {

    private static final Logger LOGGER = Logger.getLogger(H3ClientStream.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.http.h3.L10N");

    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0).asReadOnlyBuffer();

    /**
     * Stream lifecycle states.
     */
    enum State {
        /** Request sent, awaiting response headers. */
        OPEN,
        /** Response headers received, body may follow. */
        HEADERS_RECEIVED,
        /** Response body is being received. */
        RECEIVING_BODY,
        /** Response complete. */
        CLOSED
    }

    private final H3Parser parser = new H3Parser(this);
    private final HTTP3ClientHandler connection;
    private final Decoder qpackDecoder;
    private final HTTPResponseHandler responseHandler;
    private final WebSocketEventHandler wsHandler;
    private final List<WebSocketExtension> requestedExtensions;
    private Endpoint endpoint;
    // Mirrors ((QuicStreamEndpoint) endpoint).getStreamId(), captured
    // once in connected() so QPACK bookkeeping doesn't depend on
    // endpoint being non-null (unit tests construct this class directly
    // without ever calling connected() -- see H3ClientStreamTest).
    // -1 until the QUIC layer actually grants a stream ID.
    private long streamId = -1;

    private State state;
    private boolean bodyStarted;
    private H3ClientWebSocketConnectionAdapter webSocketAdapter;

    // RFC 9204 section 4.4.2: see H3Stream's identically-purposed field
    // -- whether this stream's response field section was ever
    // successfully decoded, consulted the same way on early termination.
    private boolean headersDecoded;

    // Set by HTTP3ClientHandler.sendRequest / connectWebSocket before
    // openStream, then flushed from connected() once a stream ID is
    // granted -- including when the open was queued behind MAX_STREAMS
    // credit (RFC 9000 section 4.6).
    private Headers pendingRequestHeaders;
    private boolean pendingRequestFin;
    private final List<byte[]> pendingBody = new ArrayList<byte[]>();
    private boolean pendingBodyFin;

    H3ClientStream(HTTP3ClientHandler connection, Decoder qpackDecoder, HTTPResponseHandler responseHandler) {
        this.connection = connection;
        this.qpackDecoder = qpackDecoder;
        this.responseHandler = responseHandler;
        this.wsHandler = null;
        this.requestedExtensions = null;
        this.state = State.OPEN;
    }

    private H3ClientStream(HTTP3ClientHandler connection, Decoder qpackDecoder, WebSocketEventHandler wsHandler,
            List<WebSocketExtension> requestedExtensions) {
        this.connection = connection;
        this.qpackDecoder = qpackDecoder;
        this.responseHandler = null;
        this.wsHandler = wsHandler;
        this.requestedExtensions = requestedExtensions;
        this.state = State.OPEN;
    }

    /**
     * Creates a stream for a WebSocket-over-H3 Extended CONNECT (RFC 9220
     * section 3), used by {@link HTTP3ClientHandler#connectWebSocket}.
     *
     * @param connection the owning connection, for flushing QPACK
     *                   decoder-stream instructions
     * @param qpackDecoder the QPACK decoder for the response HEADERS frame
     * @param wsHandler the handler to receive WebSocket events once the
     *                  upgrade completes
     * @param requestedExtensions the extensions offered in the request, to
     *                            reconcile against the server's response
     * @return the new stream
     */
    static H3ClientStream forWebSocket(HTTP3ClientHandler connection, Decoder qpackDecoder,
            WebSocketEventHandler wsHandler, List<WebSocketExtension> requestedExtensions) {
        return new H3ClientStream(connection, qpackDecoder, wsHandler, requestedExtensions);
    }

    // ── ProtocolHandler ──

    @Override
    public void connected(Endpoint endpoint) {
        this.endpoint = endpoint;
        this.streamId = ((QuicStreamEndpoint) endpoint).getStreamId();
        if (connection != null && pendingRequestHeaders != null) {
            connection.completePreparedRequest(this);
        }
    }

    /**
     * Returns the QUIC stream ID once {@link #connected} has run, or
     * {@code -1} if the open is still queued behind peer MAX_STREAMS
     * credit.
     */
    long getStreamId() {
        return streamId;
    }

    void prepareRequest(Headers headers, boolean fin) {
        this.pendingRequestHeaders = headers;
        this.pendingRequestFin = fin;
    }

    Headers takePendingRequestHeaders() {
        Headers headers = pendingRequestHeaders;
        pendingRequestHeaders = null;
        return headers;
    }

    boolean takePendingRequestFin() {
        return pendingRequestFin;
    }

    void queueRequestBody(byte[] data, boolean fin) {
        pendingBody.add(data);
        if (fin) {
            pendingBodyFin = true;
        }
    }

    List<byte[]> takePendingBody() {
        List<byte[]> body = new ArrayList<byte[]>(pendingBody);
        pendingBody.clear();
        return body;
    }

    boolean takePendingBodyFin() {
        boolean fin = pendingBodyFin;
        pendingBodyFin = false;
        return fin;
    }

    /**
     * Returns the endpoint for this stream, once {@link #connected} has
     * been called.
     */
    Endpoint getEndpoint() {
        return endpoint;
    }

    @Override
    public void receive(ByteBuffer data) {
        parser.receive(data);
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
    }

    @Override
    public void disconnected() {
        // connection is only ever null when a test constructs this class
        // directly without going through HTTP3ClientHandler (see
        // H3ClientStreamTest) -- never in production.
        if (!headersDecoded && connection != null) {
            connection.cancelQpackStream(streamId);
        }
        // See H3Stream#disconnected: the QUIC layer delivers both a
        // clean FIN and a peer RESET_STREAM through this same callback,
        // so both are treated as a normal finish.
        onFinished();
    }

    @Override
    public void error(Exception cause) {
        if (!headersDecoded && connection != null) {
            connection.cancelQpackStream(streamId);
        }
        state = State.CLOSED;
        if (webSocketAdapter != null) {
            if (cause instanceof QuicConnectionCloseException) {
                // RFC 6455 section 7.4: 1006 is reserved for "the connection
                // was closed abnormally, e.g. without a Close frame being
                // sent" -- exactly this case (the underlying QUIC connection
                // is already gone, no closing handshake is possible). The
                // real QUIC/H3 error code and reason go into the reason
                // string, since 1006 itself carries no code slot.
                webSocketAdapter.notifyTransportClosed(1006, cause.getMessage());
            } else {
                webSocketAdapter.notifyError(cause);
            }
        } else {
            responseHandler.failed(cause);
        }
    }

    // ── H3FrameHandler ──

    @Override
    public void headersFrameReceived(ByteBuffer encodedFieldSection) {
        List<Header> fields;
        try {
            fields = qpackDecoder.decode(streamId, encodedFieldSection);
        } catch (ProtocolException e) {
            LOGGER.log(Level.WARNING, L10N.getString("warn.qpack_decode_failed"), e);
            state = State.CLOSED;
            IOException ex = new IOException("Malformed HTTP/3 response headers", e);
            if (wsHandler != null) {
                wsHandler.error(ex);
            } else {
                responseHandler.failed(ex);
            }
            return;
        }
        headersDecoded = true;
        onHeaders(fields);
        // connection is only ever null in a test that constructs this
        // class directly (see H3ClientStreamTest).
        if (connection != null) {
            connection.flushQpackDecoderInstructions();
        }
    }

    private void onHeaders(List<Header> fields) {
        if (state == State.OPEN) {
            int statusCode = extractStatus(fields);

            // RFC 9114 section 4.3.2: :status is mandatory; its
            // absence means the response is malformed
            if (statusCode < 0) {
                state = State.CLOSED;
                IOException ex = new IOException("Malformed HTTP/3 response: missing :status");
                if (wsHandler != null) {
                    wsHandler.error(ex);
                } else {
                    responseHandler.failed(ex);
                }
                return;
            }

            // RFC 9114 section 4.1 / RFC 9110 section 15.2:
            // informational 1xx responses are interim — consume
            // headers and return to OPEN to await the final response.
            // RFC 9220 doesn't define an interim response for Extended
            // CONNECT, so these are simply ignored in WebSocket mode.
            if (statusCode >= 100 && statusCode < 200) {
                if (wsHandler == null) {
                    for (Header field : fields) {
                        if (!field.getName().startsWith(":")) {
                            responseHandler.header(field.getName(), field.getValue());
                        }
                    }
                }
                return;
            }

            if (wsHandler != null) {
                state = State.HEADERS_RECEIVED;
                if (statusCode == 200) {
                    completeWebSocketUpgrade(fields);
                } else {
                    state = State.CLOSED;
                    wsHandler.error(new IOException("WebSocket upgrade failed: status " + statusCode));
                }
                return;
            }

            state = State.HEADERS_RECEIVED;
            HTTPStatus status = HTTPStatus.fromCode(statusCode);
            HTTPResponse response = new HTTPResponse(status);
            if (statusCode >= 200 && statusCode < 400) {
                responseHandler.ok(response);
            } else {
                responseHandler.error(response);
            }
        }

        if (wsHandler == null) {
            for (Header field : fields) {
                if (!field.getName().startsWith(":")) {
                    responseHandler.header(field.getName(), field.getValue());
                }
            }
        }
    }

    /**
     * RFC 9220 section 3/4: the server accepted the Extended CONNECT with
     * a {@code 200} response -- reconciles the negotiated subprotocol/
     * extensions and bridges this stream to a {@link WebSocketConnection}.
     */
    private void completeWebSocketUpgrade(List<Header> fields) {
        String extensionsHeader = null;
        for (Header field : fields) {
            if ("sec-websocket-extensions".equalsIgnoreCase(field.getName())) {
                extensionsHeader = field.getValue();
                break;
            }
        }
        List<WebSocketExtension> activeExtensions =
                WebSocketHandshake.reconcileExtensions(extensionsHeader, requestedExtensions);

        webSocketAdapter = new H3ClientWebSocketConnectionAdapter(wsHandler);
        // RFC 9220 changes only the opening handshake, not RFC 6455
        // framing -- masking still applies over H3, and this is the
        // client side of it (masks outgoing, expects unmasked incoming).
        webSocketAdapter.setClientMode(true);
        webSocketAdapter.setTransport(new H3ClientWebSocketTransport());
        if (!activeExtensions.isEmpty()) {
            webSocketAdapter.setExtensions(activeExtensions);
        }
        webSocketAdapter.notifyConnectionOpen();
    }

    /**
     * Extracts the :status pseudo-header value (RFC 9114 section 4.3.2).
     * Returns the status code, or -1 if :status is absent.
     */
    private static int extractStatus(List<Header> fields) {
        for (Header field : fields) {
            if (":status".equals(field.getName())) {
                try {
                    return Integer.parseInt(field.getValue());
                } catch (NumberFormatException e) {
                    return 500;
                }
            }
        }
        return -1;
    }

    @Override
    public void dataFrameReceived(ByteBuffer data, boolean endOfFrame) {
        if (webSocketAdapter != null) {
            try {
                webSocketAdapter.processIncomingData(data);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, L10N.getString("warn.websocket_frame_error"), e);
                webSocketAdapter.notifyError(e);
            }
            return;
        }
        if (!bodyStarted) {
            bodyStarted = true;
            state = State.RECEIVING_BODY;
            responseHandler.startResponseBody();
        }
        responseHandler.responseBodyContent(data);
    }

    private void onFinished() {
        if (state == State.CLOSED) {
            return;
        }
        if (webSocketAdapter != null) {
            try {
                webSocketAdapter.processIncomingData(EMPTY_BUFFER.duplicate());
            } catch (IOException ignored) {
                // FIN with empty data
            }
            webSocketAdapter.notifyTransportClosed(1001, "Transport closed");
            state = State.CLOSED;
            return;
        }
        if (bodyStarted) {
            responseHandler.endResponseBody();
        }
        state = State.CLOSED;
        responseHandler.close();
    }

    @Override
    public void cancelPushFrameReceived(long pushId) {
        connectionError(H3ErrorCode.H3_FRAME_UNEXPECTED,
                "CANCEL_PUSH is not valid on a request stream");
    }

    @Override
    public void settingsFrameReceived(long[] settings) {
        connectionError(H3ErrorCode.H3_FRAME_UNEXPECTED,
                "SETTINGS is not valid on a request stream");
    }

    @Override
    public void pushPromiseFrameReceived(long pushId, ByteBuffer encodedFieldSection) {
        // RFC 9114 section 7.2.5: gumdrop never sends MAX_PUSH_ID, so
        // any PUSH_PROMISE exceeds the permitted push ID set.
        connectionError(H3ErrorCode.H3_ID_ERROR,
                "PUSH_PROMISE for a push ID that was never permitted");
    }

    @Override
    public void goawayFrameReceived(long streamOrPushId) {
        connectionError(H3ErrorCode.H3_FRAME_UNEXPECTED,
                "GOAWAY is not valid on a request stream");
    }

    @Override
    public void maxPushIdFrameReceived(long maxPushId) {
        connectionError(H3ErrorCode.H3_FRAME_UNEXPECTED,
                "MAX_PUSH_ID is not valid on a request stream");
    }

    @Override
    public void frameError(String message) {
        String formatted = MessageFormat.format(L10N.getString("warn.frame_error"), message);
        LOGGER.warning(formatted);
        state = State.CLOSED;
        IOException ex = new IOException("HTTP/3 frame error: " + message);
        if (webSocketAdapter != null) {
            webSocketAdapter.notifyError(ex);
        } else if (wsHandler != null) {
            wsHandler.error(ex);
        } else {
            responseHandler.failed(ex);
        }
    }

    private void connectionError(long errorCode, String message) {
        String formatted = MessageFormat.format(L10N.getString("warn.frame_error"), message);
        LOGGER.warning(formatted);
        // connection is only ever null when a test constructs this class
        // directly without going through HTTP3ClientHandler.
        if (connection != null) {
            connection.closeWithApplicationError(errorCode, message);
        }
    }

    /**
     * RFC 9114 section 5.2: called when the server's GOAWAY indicates
     * this stream was not processed. The caller may retry on a new
     * connection.
     */
    void onGoawayFailed(IOException cause) {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSED;
        if (webSocketAdapter != null) {
            webSocketAdapter.notifyError(cause);
        } else if (wsHandler != null) {
            wsHandler.error(cause);
        } else {
            responseHandler.failed(cause);
        }
    }

    // ── WebSocket adapter inner classes ──
    //
    // Direct client-side mirrors of H3Stream's H3WebSocketConnectionAdapter/
    // H3WebSocketTransport.

    /**
     * Bridges {@link WebSocketEventHandler} to the {@link WebSocketConnection}
     * abstract class.
     */
    private static class H3ClientWebSocketConnectionAdapter extends WebSocketConnection implements WebSocketSession {

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
     * frames as HTTP/3 DATA frames on this stream.
     */
    private class H3ClientWebSocketTransport implements WebSocketConnection.WebSocketTransport {

        @Override
        public void sendFrame(ByteBuffer frameData) throws IOException {
            if (state == State.CLOSED) {
                throw new IOException("Stream closed");
            }
            int length = frameData.remaining();
            ByteBuffer out = ByteBuffer.allocate(H3Writer.dataLength(length));
            byte[] bytes = new byte[length];
            frameData.get(bytes);
            H3Writer.writeData(out, bytes);
            out.flip();
            endpoint.send(out);
        }

        @Override
        public void close(boolean normalClose) throws IOException {
            if (state != State.CLOSED) {
                endpoint.close();
                state = State.CLOSED;
            }
        }
    }
}
