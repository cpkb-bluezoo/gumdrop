/*
 * HTTPClientStream.java
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

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Represents an individual HTTP request/response stream in a client connection.
 * 
 * <p>An HTTP stream encapsulates the lifecycle of a single request/response pair,
 * providing methods to send the request and request body data while receiving
 * events for the response through the associated {@link HTTPClientHandler}.
 * 
 * <p>This abstraction works consistently across HTTP versions:
 * <ul>
 * <li><strong>HTTP/1.1:</strong> One stream active at a time, processed sequentially</li>
 * <li><strong>HTTP/2:</strong> Multiple streams active concurrently, multiplexed over single connection</li>
 * </ul>
 * 
 * <p><strong>Stream Lifecycle:</strong>
 * <ol>
 * <li>{@link #sendRequest(HTTPRequest)} - Send initial request headers</li>
 * <li>{@link #sendData(ByteBuffer, boolean)} - Send request body (if applicable)</li>
 * <li>Response received via {@link HTTPClientHandler} events</li>
 * <li>Stream completes or encounters error</li>
 * </ol>
 * 
 * <p><strong>Flow Control:</strong> For HTTP/2, the stream automatically handles
 * flow control windows. Calling {@link #sendData} may block if the flow control
 * window is exhausted, or throw an exception if the stream cannot accept more data.
 * 
 * <p><strong>Thread Safety:</strong> Stream methods should only be called from
 * the connection's executor thread (typically from within handler methods).
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTPClientHandler
 * @see HTTPClientConnection
 */
public interface HTTPClientStream {

    /**
     * Stream states reflecting the request/response lifecycle.
     */
    enum State {
        /** Stream created but no request sent yet */
        IDLE,
        
        /** Request headers sent, may be sending request body */
        OPEN,
        
        /** Request complete, waiting for response */
        HALF_CLOSED_LOCAL,
        
        /** Response complete, stream finished */
        CLOSED,
        
        /** Stream cancelled or encountered error */
        RESET
    }

    /**
     * Returns the unique identifier for this stream.
     * 
     * <p>For HTTP/1.1, this is typically a sequential number.
     * For HTTP/2, this follows the HTTP/2 stream ID specification
     * (client-initiated streams are odd numbers).
     * 
     * @return the stream identifier
     */
    int getStreamId();

    /**
     * Returns the current state of this stream.
     * 
     * @return the current stream state
     */
    State getState();

    /**
     * Checks if this stream can accept more request data.
     * 
     * <p>Returns false if the stream is closed, reset, or the request
     * has already been completed.
     * 
     * @return true if request data can be sent, false otherwise
     */
    boolean canSendData();

    /**
     * Checks if this stream has completed (successfully or with error).
     * 
     * @return true if the stream is finished, false if still active
     */
    boolean isComplete();

    /**
     * Sends the initial HTTP request for this stream.
     * 
     * <p>This method sends the request line and headers to the server.
     * For requests that include a body (POST, PUT, etc.), follow this
     * call with one or more {@link #sendData} calls.
     * 
     * <p>This method can only be called once per stream and only when
     * the stream is in {@link State#IDLE} state.
     * 
     * @param request the HTTP request to send
     * @throws IOException if the request cannot be sent
     * @throws IllegalStateException if the stream is not in a valid state for sending
     */
    void sendRequest(HTTPRequest request) throws IOException;

    /**
     * Sends request body data for this stream.
     * 
     * <p>This method sends a chunk of request body data. It can be called
     * multiple times to send the complete request body. The {@code endStream}
     * parameter indicates whether this is the final chunk.
     * 
     * <p>For HTTP/2, this method respects flow control windows and may block
     * if the window is exhausted. For HTTP/1.1, data is sent immediately.
     * 
     * @param data the request body data to send
     * @param endStream true if this is the final chunk of request data
     * @throws IOException if the data cannot be sent
     * @throws IllegalStateException if the stream cannot accept more data
     */
    void sendData(ByteBuffer data, boolean endStream) throws IOException;

    /**
     * Completes the request if no body data will be sent.
     * 
     * <p>This method is equivalent to calling {@code sendData(empty, true)}
     * and is useful for requests that have no body (GET, HEAD, etc.) or
     * when the request body has been sent via other means.
     * 
     * @throws IOException if the stream cannot be completed
     * @throws IllegalStateException if the stream is not in a valid state
     */
    void completeRequest() throws IOException;

    /**
     * Cancels this stream, terminating the request/response.
     * 
     * <p>This method sends a cancellation signal to the server and transitions
     * the stream to {@link State#RESET}. Any pending request or response data
     * is discarded.
     * 
     * <p>For HTTP/2, this sends an RST_STREAM frame. For HTTP/1.1, this may
     * require closing the entire connection.
     * 
     * @param errorCode the reason for cancellation (protocol-specific)
     */
    void cancel(int errorCode);

    /**
     * Returns the connection that owns this stream.
     * 
     * <p>This provides access to connection-level operations and information
     * while maintaining the stream abstraction.
     * 
     * @return the parent HTTP client connection
     */
    HTTPClientConnection getConnection();

    /**
     * Returns application-specific data associated with this stream.
     * 
     * <p>This allows handlers to associate custom data with streams for
     * tracking purposes, such as request context, callbacks, or user data.
     * 
     * @return the associated user data, or null if none set
     */
    Object getUserData();

    /**
     * Associates application-specific data with this stream.
     * 
     * <p>This allows handlers to store custom data with streams for
     * tracking purposes. The data is not used by the stream implementation.
     * 
     * @param userData the user data to associate with this stream
     */
    void setUserData(Object userData);
}
