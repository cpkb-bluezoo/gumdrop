/*
 * HTTPClientHandler.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.http.client;

import org.bluezoo.gumdrop.ClientHandler;
import org.bluezoo.gumdrop.http.HTTPVersion;
import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Handler interface for HTTP client connections that manages stream-based
 * request/response interactions.
 * 
 * <p>HTTP communication is fundamentally stream-based, where each stream
 * represents a single request/response pair. This handler provides events
 * for both connection-level lifecycle and stream-specific interactions.
 * 
 * <p>The handler follows the same event-driven pattern as other Gumdrop
 * client handlers, but adds stream context to properly handle HTTP's
 * multiplexed nature (especially in HTTP/2).
 * 
 * <p><strong>Connection Flow:</strong>
 * <ol>
 * <li>{@link #onConnected()} - TCP connection established</li>
 * <li>{@link #onProtocolNegotiated(String, HTTPClientConnection)} - HTTP version determined</li>
 * <li>Stream interactions via stream-specific methods</li>
 * <li>{@link #onDisconnected()} - Connection closed</li>
 * </ol>
 * 
 * <p><strong>Stream Flow (per request/response):</strong>
 * <ol>
 * <li>{@link #onStreamCreated(HTTPClientStream)} - Stream ready for request</li>
 * <li>{@link #onStreamResponse(HTTPClientStream, HTTPResponse)} - Response headers received</li>
 * <li>{@link #onStreamData(HTTPClientStream, ByteBuffer, boolean)} - Response body data (may be called multiple times)</li>
 * <li>{@link #onStreamComplete(HTTPClientStream)} - Response fully received</li>
 * </ol>
 * 
 * <p><strong>Thread Safety:</strong> All handler methods are called from the
 * connection's executor thread and should not perform blocking operations.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientHandler
 * @see HTTPClientConnection
 * @see HTTPClientStream
 */
public interface HTTPClientHandler extends ClientHandler {

    /**
     * Called when HTTP protocol version has been negotiated.
     * 
     * <p>This method is invoked after the TCP connection is established and
     * the HTTP protocol version has been determined (either through ALPN
     * negotiation for HTTP/2 or by default assumption for HTTP/1.1).
     * 
     * <p>The handler can use this information to understand connection
     * capabilities and adjust behavior accordingly.
     * 
     * @param protocol the negotiated HTTP protocol version
     * @param connection the HTTP connection for creating streams
     */
    void onProtocolNegotiated(HTTPVersion protocol, HTTPClientConnection connection);

    /**
     * Called when a new HTTP stream has been created and is ready for use.
     * 
     * <p>This method is invoked when {@link HTTPClientConnection#createStream()}
     * successfully creates a new stream. The stream is ready to accept a
     * request via {@link HTTPClientStream#sendRequest(HTTPRequest)}.
     * 
     * <p>For HTTP/1.1, streams are created sequentially. For HTTP/2, multiple
     * streams can be active concurrently.
     * 
     * @param stream the newly created stream ready for request
     */
    void onStreamCreated(HTTPClientStream stream);

    /**
     * Called when HTTP response headers have been received for a stream.
     * 
     * <p>This method is invoked when the server sends the initial HTTP response
     * (status line and headers). For most requests, this is followed by
     * {@link #onStreamData} calls for the response body.
     * 
     * <p>The handler can examine the response status and headers to determine
     * how to process the response body or whether to expect additional data.
     * 
     * @param stream the stream that received the response
     * @param response the HTTP response with status and headers
     */
    void onStreamResponse(HTTPClientStream stream, HTTPResponse response);

    /**
     * Called when response body data is received for a stream.
     * 
     * <p>This method is invoked for each chunk of response body data. It may
     * be called multiple times for a single response, depending on how the
     * server sends the data and the underlying transport characteristics.
     * 
     * <p>The {@code endStream} parameter indicates whether this is the final
     * chunk of data for this response.
     * 
     * @param stream the stream that received the data
     * @param data the response body data chunk
     * @param endStream true if this is the final data chunk for the response
     */
    void onStreamData(HTTPClientStream stream, ByteBuffer data, boolean endStream);

    /**
     * Called when a stream has completed successfully.
     * 
     * <p>This method is invoked when a request/response cycle has completed
     * normally. All response data has been received and the stream is now
     * closed and available for cleanup.
     * 
     * <p>After this method is called, the stream object should not be used
     * for further operations.
     * 
     * @param stream the stream that has completed
     */
    void onStreamComplete(HTTPClientStream stream);

    /**
     * Called when a stream encounters an error.
     * 
     * <p>This method is invoked when an error occurs during stream processing,
     * such as protocol violations, timeouts, or server-initiated stream resets.
     * The stream is considered failed and should not be used for further operations.
     * 
     * <p>This is distinct from HTTP error status codes (4xx, 5xx), which are
     * delivered normally via {@link #onStreamResponse}.
     * 
     * @param stream the stream that encountered an error
     * @param error the exception describing the error condition
     */
    void onStreamError(HTTPClientStream stream, Exception error);

    /**
     * Called when the server sends updated settings (HTTP/2 only).
     * 
     * <p>This method is invoked when the server sends a SETTINGS frame that
     * updates connection-level parameters such as maximum concurrent streams,
     * flow control windows, or frame size limits.
     * 
     * <p>For HTTP/1.1 connections, this method is never called.
     * 
     * @param settings map of setting identifiers to their new values
     */
    void onServerSettings(Map<Integer, Long> settings);

    /**
     * Called when the server initiates connection shutdown (HTTP/2 GOAWAY).
     * 
     * <p>This method is invoked when the server sends a GOAWAY frame indicating
     * it will not accept new streams and existing streams should complete.
     * The connection will close after all active streams finish.
     * 
     * <p>For HTTP/1.1 connections, this method is called if the server closes
     * the connection after indicating it won't accept further requests.
     * 
     * @param lastStreamId the highest stream ID the server will process
     * @param errorCode the reason for shutdown (0 = no error)
     * @param debugData optional debug information from server
     */
    void onGoAway(int lastStreamId, int errorCode, String debugData);

    /**
     * Called when the server sends a PUSH_PROMISE (HTTP/2 only).
     * 
     * <p>This method is invoked when the server promises to push a resource
     * that the client has not requested. The handler can choose to accept
     * or reject the promised stream.
     * 
     * <p>For HTTP/1.1 connections, this method is never called.
     * 
     * @param promisedStream the stream that will deliver the promised resource
     * @param promisedRequest the request the server will fulfill
     * @return true to accept the push promise, false to reject it
     */
    boolean onPushPromise(HTTPClientStream promisedStream, HTTPRequest promisedRequest);
}
