/*
 * HttpRequest.java
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
import java.time.Instant;

import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentType;

/**
 * An HTTP request being sent by an HTTP client.
 *
 * <p>A request is made by the client factory methods, which take the method,
 * the target and the {@link HttpResponseHandler} that is to receive the
 * response: {@code client.get(path, handler)}, {@code client.post(path,
 * handler)} or {@code client.request(method, path, handler)}. The method and
 * the target belong to the request from the start, so there are no methods for
 * them here. What follows is the other half of a message, in the same order
 * and with the same types as the events a server handler receives for a
 * request: the field methods, then {@link #bodyContent} any number of times,
 * then {@link #endMessage}.
 *
 * <p>Nothing is written until it has to be. The header section goes out with
 * the first piece of body, or with {@link #endMessage} if there is none. The
 * first piece of body is held back until it is known whether it is also the
 * last, so that a short body is sent with its final flag in one frame (HTTP/2
 * and HTTP/3) or one write (HTTP/1.x); every later piece is sent as it is
 * given.
 *
 * <p>A field given once the body has begun is a trailer field (RFC 9110
 * section 6.5), sent after the body: as chunked trailers on HTTP/1.x, or a
 * final HEADERS frame on HTTP/2 and HTTP/3. A request that declares its
 * {@code Content-Length} has no room for trailers, and framing fields
 * ({@code Content-Length}, {@code Transfer-Encoding}, {@code Host},
 * {@code Trailer}) cannot be trailers.
 *
 * <h3>Simple Request (No Body)</h3>
 * <pre>
 * HttpRequest request = client.get("/api/users", new DefaultHttpResponseHandler() {
 *     &#64;Override
 *     public void status(int code) {
 *         // Handle the status
 *     }
 * });
 * request.header("Accept", "application/json");
 * request.endMessage();
 * </pre>
 *
 * <h3>Request with Body</h3>
 * <pre>
 * HttpRequest request = client.post("/api/users", handler);
 * request.contentType(new ContentType("application", "json", null));
 * request.header("Content-Encoding", "gzip");  // optional; client compresses plaintext
 * request.bodyContent(ByteBuffer.wrap(jsonData));
 * request.endMessage();
 * </pre>
 *
 * <h3>Streaming Upload with Backpressure</h3>
 * <pre>
 * while (hasMoreData()) {
 *     ByteBuffer chunk = getNextChunk();
 *     while (chunk.hasRemaining()) {
 *         int sent = request.bodyContent(chunk);
 *         if (sent == 0) {
 *             // wait for the transport to drain, then retry
 *         }
 *     }
 * }
 * request.endMessage();
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HttpResponseHandler
 */
public interface HttpRequest {

    // ─────────────────────────────────────────────────────────────────────────
    // Fields
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Adds a field. May be called more than once for a field name; names are
     * case-insensitive.
     *
     * @param name the field name
     * @param value the field value
     * @throws IllegalStateException if the message has ended, or this would be
     *     a trailer on a request that declared its length
     * @throws IllegalArgumentException if this would be a trailer that may not
     *     be one, or a trailer value contains a line break
     */
    void header(String name, String value);

    /**
     * Adds a field whose value is raw octets (ISO-8859-1).
     *
     * @param name the field name
     * @param value the field value
     */
    default void header(String name, ByteBuffer value) {
        byte[] b = new byte[value.remaining()];
        value.duplicate().get(b);
        header(name, new String(b, java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    /**
     * Adds a field whose value is a number, such as {@code Content-Length}.
     *
     * @param name the field name
     * @param value the value
     * @throws IllegalStateException if the header section has already been sent
     */
    void longHeader(String name, long value);

    /**
     * Adds a field whose value is a date, such as {@code If-Modified-Since}.
     * It is written in the form HTTP defines (RFC 9110 section 5.6.7), which
     * is always GMT.
     *
     * @param name the field name
     * @param value the instant
     * @throws IllegalStateException if the header section has already been sent
     */
    void dateHeader(String name, Instant value);

    /**
     * Sets the {@code Content-Type} of the request body.
     *
     * @param contentType the media type
     * @throws IllegalStateException if the header section has already been sent
     */
    void contentType(ContentType contentType);

    /**
     * Sets the {@code Content-Disposition} of the request body.
     *
     * @param contentDisposition the disposition
     * @throws IllegalStateException if the header section has already been sent
     */
    void contentDisposition(ContentDisposition contentDisposition);

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP/2 Priority (Optional)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sets RFC 9218 urgency via the {@code Priority} header.
     *
     * <p>The weight (0–255, higher = more important) is mapped to urgency
     * 0–7. This applies to HTTP/2 and HTTP/3; it has no effect for HTTP/1.x.
     *
     * @param weight the 0–255 weight
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
    void dependency(HttpRequest parent);

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
    // Body
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Ends the header section and sends it now, leaving the message open for
     * body that follows. It is for a request whose body flows for as long as
     * the exchange lasts, such as an Extended CONNECT tunnel; any other
     * request can leave it out, and the header section is sent with the first
     * piece of body or with {@link #endMessage}.
     *
     * @throws IllegalStateException if the header section has already been sent
     */
    void endHeaders();

    /**
     * Sends request body data.
     *
     * <p>May be called any number of times. The buffer's position and limit
     * define the data to send. The header section is sent before the first
     * piece of body that is not held back (see the class description).
     *
     * <p><strong>Backpressure:</strong> this method returns the number of bytes
     * consumed from the buffer. If fewer bytes are consumed than available
     * (the return value is less than {@code data.remaining()}), the caller
     * should wait and retry with the remaining data. This can occur when the
     * send buffer is full.
     *
     * @param data the body data to send
     * @return the number of bytes consumed from the buffer
     * @throws IllegalStateException if the message has already ended
     */
    int bodyContent(ByteBuffer data);

    /**
     * Ends the request: the header section is sent if it has not been, and
     * the body, if there was one, is complete. No more can be sent.
     *
     * @throws IllegalStateException if the message has already ended
     */
    void endMessage();

    // ─────────────────────────────────────────────────────────────────────────
    // Cancellation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Cancels the request.
     *
     * <p>For HTTP/2, this sends an RST_STREAM frame. For HTTP/1.x, this
     * may close the connection.
     *
     * <p>The handler's {@link HttpResponseHandler#failed(Exception)} method
     * will be called with a {@link java.util.concurrent.CancellationException}.
     */
    void cancel();
}
