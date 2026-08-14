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
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.http.qpack.SimpleDecoder;
import org.bluezoo.gumdrop.http.client.HTTPResponse;
import org.bluezoo.gumdrop.http.client.HTTPResponseHandler;

/**
 * A single HTTP/3 client request/response exchange on a QUIC stream.
 *
 * <p>This is the client-side counterpart of {@link H3Stream}. Each
 * instance is itself the QUIC stream's {@link ProtocolHandler}, owning
 * its own {@link H3Parser} fed directly from {@link #receive}, and
 * translates HTTP/3 response frames into {@link HTTPResponseHandler}
 * callbacks per RFC 9114 section 4.1 (HTTP message exchanges).
 *
 * <p>Response pseudo-headers (RFC 9114 section 4.3.2) are parsed from
 * the initial HEADERS frame, decoded via {@link SimpleDecoder} (the
 * static-table-only QPACK codec); specifically the {@code :status}
 * pseudo-header determines the response status code.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTP3ClientHandler
 * @see HTTPResponseHandler
 */
class H3ClientStream implements ProtocolHandler, H3FrameHandler {

    private static final Logger LOGGER = Logger.getLogger(H3ClientStream.class.getName());

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
    private final SimpleDecoder qpackDecoder;
    private final HTTPResponseHandler responseHandler;
    private Endpoint endpoint;

    private State state;
    private boolean bodyStarted;

    H3ClientStream(SimpleDecoder qpackDecoder, HTTPResponseHandler responseHandler) {
        this.qpackDecoder = qpackDecoder;
        this.responseHandler = responseHandler;
        this.state = State.OPEN;
    }

    // ── ProtocolHandler ──

    @Override
    public void connected(Endpoint endpoint) {
        this.endpoint = endpoint;
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
        // See H3Stream#disconnected: the QUIC layer delivers both a
        // clean FIN and a peer RESET_STREAM through this same callback,
        // so both are treated as a normal finish.
        onFinished();
    }

    @Override
    public void error(Exception cause) {
        state = State.CLOSED;
        responseHandler.failed(cause);
    }

    // ── H3FrameHandler ──

    @Override
    public void headersFrameReceived(ByteBuffer encodedFieldSection) {
        List<Header> fields;
        try {
            fields = qpackDecoder.decode(encodedFieldSection);
        } catch (ProtocolException e) {
            LOGGER.log(Level.WARNING, "QPACK decode failed", e);
            state = State.CLOSED;
            responseHandler.failed(new IOException("Malformed HTTP/3 response headers", e));
            return;
        }
        onHeaders(fields);
    }

    private void onHeaders(List<Header> fields) {
        if (state == State.OPEN) {
            int statusCode = extractStatus(fields);

            // RFC 9114 section 4.3.2: :status is mandatory; its
            // absence means the response is malformed
            if (statusCode < 0) {
                state = State.CLOSED;
                responseHandler.failed(new IOException(
                        "Malformed HTTP/3 response: missing :status"));
                return;
            }

            // RFC 9114 section 4.1 / RFC 9110 section 15.2:
            // informational 1xx responses are interim — consume
            // headers and return to OPEN to await the final response
            if (statusCode >= 100 && statusCode < 200) {
                for (Header field : fields) {
                    if (!field.getName().startsWith(":")) {
                        responseHandler.header(field.getName(), field.getValue());
                    }
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

        for (Header field : fields) {
            if (!field.getName().startsWith(":")) {
                responseHandler.header(field.getName(), field.getValue());
            }
        }
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
        if (bodyStarted) {
            responseHandler.endResponseBody();
        }
        state = State.CLOSED;
        responseHandler.close();
    }

    @Override
    public void cancelPushFrameReceived(long pushId) {
    }

    @Override
    public void settingsFrameReceived(long[] settings) {
        // SETTINGS is control-stream only; request-stream-level framing
        // errors are handled uniformly via frameError.
    }

    @Override
    public void pushPromiseFrameReceived(long pushId, ByteBuffer encodedFieldSection) {
    }

    @Override
    public void goawayFrameReceived(long streamOrPushId) {
    }

    @Override
    public void maxPushIdFrameReceived(long maxPushId) {
    }

    @Override
    public void frameError(String message) {
        LOGGER.warning("HTTP/3 frame error: " + message);
        state = State.CLOSED;
        responseHandler.failed(new IOException("HTTP/3 frame error: " + message));
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
