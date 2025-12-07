/*
 * HTTPClientStreamFactory.java
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

/**
 * Factory interface for creating HTTP client stream instances.
 * 
 * <p>This factory allows users to provide custom implementations of
 * {@link HTTPClientStream} while maintaining the standard connection
 * and handler architecture. The factory is called by {@link HTTPClientConnection}
 * when creating new streams via {@link HTTPClientConnection#createStream()}.
 * 
 * <p>Custom stream implementations can add application-specific functionality,
 * state management, or specialized behavior while still integrating with
 * the event-driven handler pattern.
 * 
 * <p><strong>Example Custom Factory:</strong>
 * <pre>
 * HTTPClientStreamFactory factory = new HTTPClientStreamFactory() {
 *     public HTTPClientStream createStream(int streamId, HTTPClientConnection connection) {
 *         return new MyCustomHTTPClientStream(streamId, connection);
 *     }
 * };
 * 
 * HTTPClient client = new HTTPClient("example.com", 80);
 * client.setStreamFactory(factory);
 * </pre>
 * 
 * <p><strong>Thread Safety:</strong> Factory implementations should be thread-safe
 * as they may be called from the connection's executor thread.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTPClientStream
 * @see HTTPClientConnection#createStream()
 */
public interface HTTPClientStreamFactory {

    /**
     * Creates a new HTTP client stream instance.
     * 
     * <p>This method is called by {@link HTTPClientConnection} when a new
     * stream is requested via {@link HTTPClientConnection#createStream()}.
     * The created stream should be in {@link HTTPClientStream.State#IDLE}
     * state and ready to accept a request.
     * 
     * <p>The stream ID is unique within the connection and follows HTTP
     * protocol conventions:
     * <ul>
     * <li><strong>HTTP/1.1:</strong> Sequential numbers (1, 2, 3, ...)</li>
     * <li><strong>HTTP/2:</strong> Odd numbers for client-initiated streams (1, 3, 5, ...)</li>
     * </ul>
     * 
     * <p>The connection parameter provides access to connection-level
     * information and services that the stream may need.
     * 
     * @param streamId the unique identifier for this stream
     * @param connection the HTTP client connection that owns this stream
     * @return a new HTTPClientStream instance ready for use
     * @throws IllegalStateException if a stream cannot be created
     */
    HTTPClientStream createStream(int streamId, HTTPClientConnection connection);
}
