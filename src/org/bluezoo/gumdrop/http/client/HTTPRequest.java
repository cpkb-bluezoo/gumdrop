/*
 * HTTPRequest.java
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

import java.nio.ByteBuffer;

/**
 * Represents an HTTP request to be sent by an HTTP client.
 *
 * <p>Instances are obtained from an {@link HTTPClientSession} via factory methods
 * like {@link HTTPClientSession#get(String)}, {@link HTTPClientSession#post(String)}, etc.
 * The request is configured by calling setter methods, then sent via {@link #send(HTTPResponseHandler)}
 * or {@link #startRequestBody(HTTPResponseHandler)}.
 *
 * <h3>Simple Request (No Body)</h3>
 * <pre>
 * HTTPRequest request = session.get("/api/users");
 * request.header("Accept", "application/json");
 * request.send(new DefaultHTTPResponseHandler() {
 *     &#64;Override
 *     public void ok(HTTPResponse response) {
 *         // Handle success
 *     }
 * });
 * </pre>
 *
 * <h3>Request with Body</h3>
 * <pre>
 * HTTPRequest request = session.post("/api/users");
 * request.header("Content-Type", "application/json");
 * request.startRequestBody(handler);
 * request.requestBodyContent(ByteBuffer.wrap(jsonData));
 * request.endRequestBody();
 * </pre>
 *
 * <h3>Streaming Upload with Backpressure</h3>
 * <pre>
 * request.startRequestBody(handler);
 * while (hasMoreData()) {
 *     ByteBuffer chunk = getNextChunk();
 *     int consumed = request.requestBodyContent(chunk);
 *     if (consumed &lt; chunk.remaining()) {
 *         // Buffer not fully consumed, retry later
 *         scheduleRetry(chunk);
 *         break;
 *     }
 * }
 * request.endRequestBody();
 * </pre>
 *
 * <h3>HTTP/2 Priority (Optional)</h3>
 * <pre>
 * HTTPRequest request = session.get("/style.css");
 * request.setPriority(200);  // Higher priority (1-256)
 * request.setDependency(htmlRequest);  // Depends on HTML request
 * request.send(handler);
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTPClientSession
 * @see HTTPResponseHandler
 */
public interface HTTPRequest {

    // ─────────────────────────────────────────────────────────────────────────
    // Request Configuration
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sets a request header.
     *
     * <p>Can be called multiple times for multi-value headers. Header names
     * are case-insensitive per HTTP specification.
     *
     * @param name the header name
     * @param value the header value
     */
    void header(String name, String value);

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP/2 Priority (Optional)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sets the stream priority weight for HTTP/2.
     *
     * <p>Higher weights indicate higher priority. The server may use this
     * to allocate bandwidth between concurrent streams. This has no effect
     * for HTTP/1.x connections.
     *
     * @param weight the priority weight (1-256, default 16)
     */
    void priority(int weight);

    /**
     * Sets a dependency on another request for HTTP/2 stream prioritization.
     *
     * <p>The server should try to complete the parent request before this one.
     * This has no effect for HTTP/1.x connections.
     *
     * @param parent the parent request this depends on
     */
    void dependency(HTTPRequest parent);

    /**
     * Sets the exclusive dependency flag for HTTP/2 stream prioritization.
     *
     * <p>When true, this stream becomes the sole child of its parent, and all
     * other children of the parent become children of this stream. This has
     * no effect for HTTP/1.x connections.
     *
     * @param exclusive true for exclusive dependency
     */
    void exclusive(boolean exclusive);

    // ─────────────────────────────────────────────────────────────────────────
    // Sending (No Body)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sends the request without a body.
     *
     * <p>Use this for GET, HEAD, DELETE, and other methods that don't have
     * a request body. The response will be delivered to the provided handler.
     *
     * <p>This method must not be called if {@link #startRequestBody(HTTPResponseHandler)}
     * has already been called.
     *
     * @param handler the handler to receive response events
     */
    void send(HTTPResponseHandler handler);

    // ─────────────────────────────────────────────────────────────────────────
    // Sending (With Body)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Begins sending a request with a body.
     *
     * <p>After calling this method, use {@link #requestBodyContent(ByteBuffer)}
     * to send body data, then {@link #endRequestBody()} to complete the request.
     *
     * <p>This method must not be called if {@link #send(HTTPResponseHandler)}
     * has already been called.
     *
     * @param handler the handler to receive response events
     */
    void startRequestBody(HTTPResponseHandler handler);

    /**
     * Sends request body data.
     *
     * <p>This method may be called multiple times to stream large bodies.
     * The buffer's position and limit define the data to send.
     *
     * <p><strong>Backpressure:</strong> This method returns the number of bytes
     * consumed from the buffer. If fewer bytes are consumed than available
     * (i.e., return value &lt; buffer.remaining()), the caller should wait and
     * retry with the remaining data. This can occur when the send buffer is full.
     *
     * @param data the body data to send
     * @return the number of bytes consumed from the buffer
     */
    int requestBodyContent(ByteBuffer data);

    /**
     * Signals the end of the request body.
     *
     * <p>This must be called after all body data has been sent via
     * {@link #requestBodyContent(ByteBuffer)}. After calling this method,
     * no more body data can be sent.
     */
    void endRequestBody();

    // ─────────────────────────────────────────────────────────────────────────
    // Cancellation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Cancels the request.
     *
     * <p>For HTTP/2, this sends an RST_STREAM frame. For HTTP/1.x, this
     * may close the connection.
     *
     * <p>The handler's {@link HTTPResponseHandler#failed(Exception)} method
     * will be called with a {@link java.util.concurrent.CancellationException}.
     */
    void cancel();
}
