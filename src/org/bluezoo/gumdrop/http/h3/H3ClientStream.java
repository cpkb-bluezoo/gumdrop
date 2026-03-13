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
import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.http.client.HTTPResponse;
import org.bluezoo.gumdrop.http.client.HTTPResponseHandler;

/**
 * A single HTTP/3 client request/response exchange on a QUIC stream.
 *
 * <p>This is the client-side counterpart of {@link H3Stream}. Each
 * instance tracks one outgoing request and translates h3 response events
 * (HEADERS, DATA, FINISHED, RESET) into
 * {@link HTTPResponseHandler} callbacks per RFC 9114 section 4.1
 * (HTTP message exchanges).
 *
 * <p>Response pseudo-headers (RFC 9114 section 4.3.2) are parsed from
 * the initial HEADERS event; specifically the {@code :status}
 * pseudo-header determines the response status code.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTP3ClientHandler
 * @see HTTPResponseHandler
 */
class H3ClientStream {

    private static final Logger LOGGER =
            Logger.getLogger(H3ClientStream.class.getName());

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

    private final HTTP3ClientHandler handler;
    private final long streamId;
    private final HTTPResponseHandler responseHandler;

    private State state;
    private boolean bodyStarted;

    H3ClientStream(HTTP3ClientHandler handler, long streamId,
                   HTTPResponseHandler responseHandler) {
        this.handler = handler;
        this.streamId = streamId;
        this.responseHandler = responseHandler;
        this.state = State.OPEN;
    }

    /**
     * Returns the QUIC stream ID for this HTTP/3 stream.
     */
    long getStreamId() {
        return streamId;
    }

    // ── Event Dispatch (called by HTTP3ClientHandler) ──

    /**
     * Called when an h3 HEADERS event is received for this stream.
     * The headers are provided as a flat array of alternating
     * name/value pairs.
     */
    void onHeaders(String[] headerPairs) {
        if (state == State.OPEN) {
            int statusCode = extractStatus(headerPairs);

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
                for (int i = 0; i < headerPairs.length; i += 2) {
                    if (!headerPairs[i].startsWith(":")) {
                        responseHandler.header(headerPairs[i],
                                headerPairs[i + 1]);
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

        for (int i = 0; i < headerPairs.length; i += 2) {
            String name = headerPairs[i];
            String value = headerPairs[i + 1];
            if (!name.startsWith(":")) {
                responseHandler.header(name, value);
            }
        }
    }

    /**
     * Extracts the :status pseudo-header value (RFC 9114 section 4.3.2).
     * Returns the status code, or -1 if :status is absent.
     */
    private int extractStatus(String[] headerPairs) {
        for (int i = 0; i < headerPairs.length; i += 2) {
            if (":status".equals(headerPairs[i])) {
                try {
                    return Integer.parseInt(headerPairs[i + 1]);
                } catch (NumberFormatException e) {
                    return 500;
                }
            }
        }
        return -1;
    }

    /**
     * Called when an h3 DATA event is received for this stream.
     */
    void onData(ByteBuffer data) {
        if (!bodyStarted) {
            bodyStarted = true;
            state = State.RECEIVING_BODY;
            responseHandler.startResponseBody();
        }
        responseHandler.responseBodyContent(data);
    }

    /**
     * Called when an h3 FINISHED event is received (stream closed
     * by server).
     */
    void onFinished() {
        if (bodyStarted) {
            responseHandler.endResponseBody();
        }
        state = State.CLOSED;
        responseHandler.close();
    }

    /**
     * Called when an h3 RESET event is received (stream aborted
     * by server).
     */
    void onReset() {
        state = State.CLOSED;
        responseHandler.failed(new IOException(
                "HTTP/3 stream reset: " + streamId));
    }

    /**
     * RFC 9114 section 5.2: called when the server's GOAWAY indicates
     * this stream was not processed. The caller may retry on a new
     * connection.
     */
    void onGoawayFailed(IOException cause) {
        state = State.CLOSED;
        responseHandler.failed(cause);
    }
}
