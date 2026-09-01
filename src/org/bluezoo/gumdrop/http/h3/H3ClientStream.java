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
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.Capsule;
import org.bluezoo.gumdrop.http.CapsuleParser;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.http.HTTPUtils;
import org.bluezoo.gumdrop.http.HTTPVersion;
import org.bluezoo.gumdrop.http.qpack.Decoder;
import org.bluezoo.gumdrop.http.client.HTTPResponse;
import org.bluezoo.gumdrop.http.client.HTTPResponseHandler;
import org.bluezoo.gumdrop.quic.QuicStreamEndpoint;

/**
 * A single HTTP/3 client request/response exchange on a QUIC stream.
 *
 * <p>This is the client-side counterpart of {@link H3Stream}. Each
 * instance is itself the QUIC stream's {@link ProtocolHandler}, owning
 * its own {@link H3Parser} fed directly from {@link #receive}, and
 * translates HTTP/3 response frames into {@link HTTPResponseHandler}
 * callbacks per RFC 9114 section 4.1 (HTTP message exchanges) -- uniformly,
 * whether the request is a plain HTTP request or an Extended CONNECT
 * (RFC 8441/9220 WebSocket, RFC 9298 CONNECT-UDP, and future RFC 9484
 * CONNECT-IP). This class has no notion of any of those upgrade
 * protocols itself: it always holds exactly one {@code responseHandler},
 * and callers that need upgrade-specific behaviour (see {@link
 * H3ClientWebSocketResponseHandler}) supply an {@link HTTPResponseHandler}
 * that reinterprets the ordinary {@code responseBodyContent}/{@code
 * wantsDatagrams}/{@code datagramReceived}/{@code capsuleReceived}
 * callbacks accordingly -- exactly how {@code H2WebSocketResponseHandler}
 * already does for HTTP/2, where no such per-protocol branching exists in
 * the generic client stream code at all.
 *
 * <p>The one HTTP/3-specific accommodation this class makes for Extended
 * CONNECT is timing: unlike HTTP/2's HEADERS frame, HTTP/3's carries no
 * END_STREAM-equivalent flag (QUIC signals stream completion
 * independently of H3 framing), so this class cannot tell whether a body
 * will follow the way {@code H2WebSocketResponseHandler}'s caller can.
 * Since an Extended CONNECT accept has no HTTP body at all -- the
 * "body" bytes it sees, if any, are already tunnelled-protocol framing --
 * {@link #onHeaders} calls {@link HTTPResponseHandler#startResponseBody}
 * immediately for such a request, rather than waiting for a first DATA
 * frame that may never come.
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
    private Endpoint endpoint;
    // Mirrors ((QuicStreamEndpoint) endpoint).getStreamId(), captured
    // once in connected() so QPACK bookkeeping doesn't depend on
    // endpoint being non-null (unit tests construct this class directly
    // without ever calling connected() -- see H3ClientStreamTest).
    // -1 until the QUIC layer actually grants a stream ID.
    private long streamId = -1;

    private State state;
    private boolean bodyStarted;

    // RFC 9204 section 4.4.2: see H3Stream's identically-purposed field
    // -- whether this stream's response field section was ever
    // successfully decoded, consulted the same way on early termination.
    private boolean headersDecoded;

    private boolean capsuleMode;
    private final CapsuleParser capsuleParser = new CapsuleParser();

    /**
     * Declared {@code Content-Length} from the response headers, or
     * {@code -1} if absent. Validated against accumulated DATA bytes
     * (RFC 9114 section 4.1.2), except for responses that must not have
     * a body (HEAD / 204 / 304).
     */
    private long contentLength = -1L;
    private long bodyBytesReceived;
    private boolean responseMustNotHaveBody;
    private String requestMethod;

    // RFC 9114 has no HEADERS-frame END_STREAM-equivalent flag the way
    // HTTP/2 does, so onHeaders() cannot tell whether a body will follow
    // the way HTTP/2's processHeaders() can from endStream. An Extended
    // CONNECT request (RFC 8441/9220 WebSocket, RFC 9298 CONNECT-UDP, and
    // future CONNECT-IP) never has an HTTP body in the first place -- its
    // "body" bytes, if any, are already tunnelled-protocol framing -- so
    // for these, startResponseBody() fires immediately once headers
    // arrive rather than waiting for a first DATA frame that may never
    // come. Set once in prepareRequest() from the request's own
    // :method/:protocol.
    private boolean extendedConnect;

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
        this.state = State.OPEN;
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
        this.requestMethod = headers.getValue(":method");
        // RFC 9114 section 4.4 / RFC 8441 section 4: Extended CONNECT is
        // exactly CONNECT with a :protocol pseudo-header.
        this.extendedConnect = "CONNECT".equals(requestMethod) && headers.getValue(":protocol") != null;
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

    /**
     * Writes {@code frameData} as a DATA frame (RFC 9114 section 7.2.1)
     * directly to this stream's endpoint. For an {@link HTTPResponseHandler}
     * that reinterprets response body bytes as some other framing --
     * {@link H3ClientWebSocketResponseHandler}'s RFC 6455 frames, or
     * {@link H3ClientConnectUdpResponseHandler}'s capsule-framed HTTP
     * Datagrams -- and needs to write back on the same stream, once the
     * tunnel has been accepted (see {@link #getStreamId}/{@link #connected}).
     *
     * @param frameData the frame payload
     */
    void sendRawData(ByteBuffer frameData) {
        int length = frameData.remaining();
        ByteBuffer out = ByteBuffer.allocate(H3Writer.dataLength(length));
        byte[] bytes = new byte[length];
        frameData.get(bytes);
        H3Writer.writeData(out, bytes);
        out.flip();
        endpoint.send(out);
    }

    /**
     * Returns whether this stream has already closed.
     */
    boolean isClosed() {
        return state == State.CLOSED;
    }

    /**
     * Closes this stream's endpoint, if not already closed.
     */
    void closeStream() {
        if (state != State.CLOSED) {
            endpoint.close();
            state = State.CLOSED;
        }
    }

    @Override
    public void receive(ByteBuffer data) {
        parser.receive(data);
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
    }

    @Override
    public void readFinished() {
        handlePeerSendFinished();
    }

    @Override
    public void disconnected() {
        handlePeerSendFinished();
    }

    private void handlePeerSendFinished() {
        // connection is only ever null when a test constructs this class
        // directly without going through HTTP3ClientHandler (see
        // H3ClientStreamTest) -- never in production.
        if (!headersDecoded && connection != null) {
            connection.cancelQpackStream(streamId);
        }
        onFinished();
    }

    @Override
    public void error(Exception cause) {
        if (!headersDecoded && connection != null) {
            connection.cancelQpackStream(streamId);
        }
        state = State.CLOSED;
        responseHandler.failed(cause);
    }

    /**
     * Aborts this stream with {@link H3ErrorCode#H3_EXCESSIVE_LOAD}
     * (RFC 9114 section 4.2.2) and notifies the response handler.
     *
     * @param reason a human-readable reason for {@link HTTPResponseHandler#failed}
     */
    void abortExcessiveLoad(String reason) {
        state = State.CLOSED;
        if (endpoint instanceof QuicStreamEndpoint) {
            ((QuicStreamEndpoint) endpoint).resetStream(H3ErrorCode.H3_EXCESSIVE_LOAD);
        }
        responseHandler.failed(new IOException(reason));
    }

    void httpDatagramReceived(ByteBuffer data) {
        if (responseHandler.wantsDatagrams()) {
            responseHandler.datagramReceived(data);
            return;
        }
        abortDatagramError();
    }

    private void abortDatagramError() {
        state = State.CLOSED;
        if (endpoint instanceof QuicStreamEndpoint) {
            ((QuicStreamEndpoint) endpoint).resetStream(H3ErrorCode.H3_DATAGRAM_ERROR);
        }
        responseHandler.failed(new IOException("HTTP Datagram error"));
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
            responseHandler.failed(new IOException("Malformed HTTP/3 response headers", e));
            return;
        }
        headersDecoded = true;
        if (connection != null
                && H3Writer.fieldSectionSize(fields) > connection.getLocalMaxFieldSectionSize()) {
            abortExcessiveLoad("response field section exceeds SETTINGS_MAX_FIELD_SECTION_SIZE");
            connection.flushQpackDecoderInstructions();
            return;
        }
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
                responseHandler.failed(new IOException("Malformed HTTP/3 response: missing :status"));
                return;
            }

            // RFC 9114 section 4.1 / RFC 9110 section 15.2:
            // informational 1xx responses are interim — consume
            // headers and return to OPEN to await the final response.
            // RFC 9220/9298 don't define an interim response for
            // Extended CONNECT, but delivering these via header() is
            // harmless even then (an upgrade handler that only cares
            // about sec-websocket-extensions et al. simply ignores
            // anything else).
            if (statusCode >= 100 && statusCode < 200) {
                deliverStrippedHeaders(fields);
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

            Headers hdrs = toHeaders(fields);
            // Capture Content-Length before stripHttp1FramingHeaders removes
            // it (same ordering as H3Stream / HTTP/2 Stream).
            if (!captureContentLength(hdrs)) {
                return;
            }
            HTTPVersion.stripHttp1FramingHeaders(hdrs);
            for (Header field : hdrs) {
                if (!field.getName().startsWith(":")) {
                    responseHandler.header(field.getName(), field.getValue());
                }
            }
            // RFC 9110 section 6.4.1 / 15.4.5 / 15.4.6: HEAD, 204, and
            // 304 responses must not carry a message body; Content-Length
            // on HEAD may still describe a would-be GET body.
            responseMustNotHaveBody = statusCode == 204 || statusCode == 304
                    || "HEAD".equalsIgnoreCase(requestMethod);
            capsuleMode = Capsule.capsuleProtocolEnabled(hdrs);

            // See this class's own documentation: an Extended CONNECT
            // response has no HTTP body, and HTTP/3 has no way to learn
            // "no body follows" from the HEADERS frame itself the way
            // HTTP/2's endStream flag does -- so signal "body" start
            // immediately rather than waiting for a first DATA frame
            // that, for a rejected (non-2xx) upgrade, may never come.
            if (extendedConnect && !bodyStarted) {
                bodyStarted = true;
                state = State.RECEIVING_BODY;
                responseHandler.startResponseBody();
            }
            return;
        }

        // Trailer field section (or late headers after the final status).
        deliverStrippedHeaders(fields);
    }

    /**
     * Strips HTTP/1 framing headers (RFC 9114 section 4.2) and delivers
     * the remaining non-pseudo fields to the response handler.
     */
    private void deliverStrippedHeaders(List<Header> fields) {
        Headers hdrs = toHeaders(fields);
        HTTPVersion.stripHttp1FramingHeaders(hdrs);
        for (Header field : hdrs) {
            if (!field.getName().startsWith(":")) {
                responseHandler.header(field.getName(), field.getValue());
            }
        }
    }

    private static Headers toHeaders(List<Header> fields) {
        Headers hdrs = new Headers();
        for (int i = 0; i < fields.size(); i++) {
            hdrs.add(fields.get(i));
        }
        return hdrs;
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
        if (capsuleMode) {
            dispatchCapsules(data);
            return;
        }
        if (state == State.OPEN) {
            // RFC 9114 section 4.1: DATA before the initial HEADERS is
            // a connection error of type H3_FRAME_UNEXPECTED.
            connectionError(H3ErrorCode.H3_FRAME_UNEXPECTED,
                    "DATA received before HEADERS");
            return;
        }
        if (responseMustNotHaveBody) {
            abortMessageError("DATA on a response that must not have a body");
            return;
        }
        if (!bodyStarted) {
            bodyStarted = true;
            state = State.RECEIVING_BODY;
            responseHandler.startResponseBody();
        }
        bodyBytesReceived += data.remaining();
        if (contentLength >= 0 && bodyBytesReceived > contentLength) {
            abortMessageError("DATA exceeds Content-Length");
            return;
        }
        responseHandler.responseBodyContent(data);
    }

    private void dispatchCapsules(ByteBuffer data) {
        List<Capsule> capsules;
        try {
            capsules = capsuleParser.push(data);
        } catch (CapsuleParser.CapsuleException e) {
            abortDatagramError();
            return;
        }
        for (int i = 0; i < capsules.size(); i++) {
            Capsule capsule = capsules.get(i);
            if (capsule.getType() == Capsule.TYPE_DATAGRAM) {
                if (responseHandler.wantsDatagrams()) {
                    responseHandler.datagramReceived(ByteBuffer.wrap(capsule.getValue()));
                } else {
                    abortDatagramError();
                    return;
                }
            } else {
                responseHandler.capsuleReceived(capsule.getType(),
                        ByteBuffer.wrap(capsule.getValue()));
            }
        }
    }

    private void onFinished() {
        if (state == State.CLOSED) {
            return;
        }
        if (capsuleMode && !capsuleParser.finish()) {
            abortDatagramError();
            return;
        }
        if (!responseMustNotHaveBody
                && contentLength >= 0
                && bodyBytesReceived != contentLength) {
            // RFC 9114 section 4.1.2
            abortMessageError("Content-Length does not match DATA frame bytes");
            return;
        }
        if (bodyStarted) {
            responseHandler.endResponseBody();
        }
        state = State.CLOSED;
        responseHandler.close();
    }

    /**
     * Parses and stores {@code Content-Length} from the response headers.
     *
     * @return false if the field was present but malformed (stream aborted)
     */
    private boolean captureContentLength(Headers headers) {
        String value = headers.getCombinedValue("content-length");
        if (value == null) {
            return true;
        }
        long parsed = HTTPUtils.validateContentLength(value);
        if (parsed < 0) {
            abortMessageError("invalid Content-Length");
            return false;
        }
        contentLength = parsed;
        return true;
    }

    /**
     * Aborts this stream with {@link H3ErrorCode#H3_MESSAGE_ERROR}
     * (RFC 9114 section 4.1.2 / 8.1) because the message is malformed.
     */
    private void abortMessageError(String reason) {
        state = State.CLOSED;
        if (endpoint instanceof QuicStreamEndpoint) {
            ((QuicStreamEndpoint) endpoint).resetStream(H3ErrorCode.H3_MESSAGE_ERROR);
        }
        responseHandler.failed(new IOException(reason));
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
    public void priorityUpdateRequestFrameReceived(long streamId, String fieldValue) {
        connectionError(H3ErrorCode.H3_FRAME_UNEXPECTED,
                "PRIORITY_UPDATE is not valid on a request stream");
    }

    @Override
    public void priorityUpdatePushFrameReceived(long pushId, String fieldValue) {
        connectionError(H3ErrorCode.H3_FRAME_UNEXPECTED,
                "PRIORITY_UPDATE is not valid on a request stream");
    }

    @Override
    public void unknownFrameReceived(long frameType) {
        if (H3FrameHandler.isReservedHttp2FrameType(frameType)) {
            // RFC 9114 section 7.2.8
            connectionError(H3ErrorCode.H3_FRAME_UNEXPECTED,
                    "reserved HTTP/2 frame type: " + frameType);
        }
        // Genuine GREASE / extension frame types are ignored (section 9).
    }

    @Override
    public void frameError(String message) {
        String formatted = MessageFormat.format(L10N.getString("warn.frame_error"), message);
        LOGGER.warning(formatted);
        state = State.CLOSED;
        responseHandler.failed(new IOException("HTTP/3 frame error: " + message));
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
        responseHandler.failed(cause);
    }
}
