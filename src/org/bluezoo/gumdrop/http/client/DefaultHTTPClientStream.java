/*
 * DefaultHTTPClientStream.java
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
import java.util.logging.Logger;

/**
 * Default implementation of HTTPClientStream for standard HTTP client operations.
 * 
 * <p>This implementation provides the basic functionality needed for HTTP
 * request/response streams, including state management, request sending,
 * and integration with the connection's networking layer.
 * 
 * <p>The stream follows the standard HTTP client lifecycle:
 * <ol>
 * <li>Created in {@link State#IDLE} state</li>
 * <li>Request headers sent via {@link #sendRequest} → {@link State#OPEN}</li>
 * <li>Optional request body sent via {@link #sendData} → {@link State#HALF_CLOSED_LOCAL}</li>
 * <li>Response received via connection → {@link State#CLOSED}</li>
 * </ol>
 * 
 * <p>Custom stream implementations can extend this class to add specialized
 * behavior while retaining the standard functionality.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTPClientStream
 * @see HTTPClientStreamFactory
 */
public class DefaultHTTPClientStream implements HTTPClientStream {

    private static final Logger logger = Logger.getLogger(DefaultHTTPClientStream.class.getName());

    private final int streamId;
    private final HTTPClientConnection connection;
    private volatile State state;
    private volatile Object userData;

    /**
     * Creates a new default HTTP client stream.
     * 
     * @param streamId the unique identifier for this stream
     * @param connection the HTTP client connection that owns this stream
     */
    public DefaultHTTPClientStream(int streamId, HTTPClientConnection connection) {
        this.streamId = streamId;
        this.connection = connection;
        this.state = State.IDLE;
    }

    @Override
    public int getStreamId() {
        return streamId;
    }

    @Override
    public State getState() {
        return state;
    }

    @Override
    public boolean canSendData() {
        return state == State.IDLE || state == State.OPEN;
    }

    @Override
    public boolean isComplete() {
        return state == State.CLOSED || state == State.RESET;
    }

    @Override
    public void sendRequest(HTTPRequest request) throws IOException {
        if (state != State.IDLE) {
            throw new IllegalStateException("Cannot send request in state: " + state);
        }

        if (request == null) {
            throw new IllegalArgumentException("Request cannot be null");
        }

        logger.fine("Sending request for stream " + streamId + ": " + request);

        // Delegate to connection to handle protocol-specific request formatting
        connection.sendRequest(this, request);
        
        // Update state - request headers have been sent
        state = State.OPEN;
    }

    @Override
    public void sendData(ByteBuffer data, boolean endStream) throws IOException {
        if (!canSendData()) {
            throw new IllegalStateException("Cannot send data in state: " + state);
        }

        if (data == null) {
            throw new IllegalArgumentException("Data cannot be null");
        }

        logger.fine("Sending " + data.remaining() + " bytes for stream " + streamId + 
                   " (endStream=" + endStream + ")");

        // Delegate to connection to handle protocol-specific data transmission
        connection.sendData(this, data, endStream);

        // Update state if this is the end of the request
        if (endStream) {
            state = State.HALF_CLOSED_LOCAL;
        }
    }

    @Override
    public void completeRequest() throws IOException {
        if (!canSendData()) {
            throw new IllegalStateException("Cannot complete request in state: " + state);
        }

        logger.fine("Completing request for stream " + streamId);

        // Send empty data with endStream=true
        sendData(ByteBuffer.allocate(0), true);
    }

    @Override
    public void cancel(int errorCode) {
        logger.fine("Cancelling stream " + streamId + " with error code: " + errorCode);

        // Delegate to connection to handle protocol-specific cancellation
        connection.cancelStream(this, errorCode);
        
        // Update state
        state = State.RESET;
    }

    @Override
    public HTTPClientConnection getConnection() {
        return connection;
    }

    @Override
    public Object getUserData() {
        return userData;
    }

    @Override
    public void setUserData(Object userData) {
        this.userData = userData;
    }

    /**
     * Called by the connection when a response has been received.
     * This method is package-private as it's only called by the connection.
     * 
     * @param response the HTTP response that was received
     */
    void responseReceived(HTTPResponse response) {
        // Stream is now waiting for response body (if any)
        // State will be updated to CLOSED when response is complete
    }

    /**
     * Called by the connection when response data has been received.
     * This method is package-private as it's only called by the connection.
     * 
     * @param data the response body data
     * @param endStream true if this is the final chunk of response data
     */
    void dataReceived(ByteBuffer data, boolean endStream) {
        if (endStream) {
            state = State.CLOSED;
            logger.fine("Stream " + streamId + " completed");
        }
    }

    /**
     * Called by the connection when an error occurs on this stream.
     * This method is package-private as it's only called by the connection.
     * 
     * @param error the exception that occurred
     */
    void errorOccurred(Exception error) {
        state = State.RESET;
        logger.warning("Stream " + streamId + " encountered error: " + error.getMessage());
    }

    /**
     * Sets the state of this stream directly.
     * This method is package-private for use by the connection.
     * 
     * @param newState the new state for the stream
     */
    void setState(State newState) {
        if (state != newState) {
            logger.fine("Stream " + streamId + " state changed from " + state + " to " + newState);
            state = newState;
        }
    }

    @Override
    public String toString() {
        return "HTTPClientStream[id=" + streamId + ", state=" + state + "]";
    }
}
