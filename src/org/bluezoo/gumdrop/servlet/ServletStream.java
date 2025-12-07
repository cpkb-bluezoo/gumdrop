/*
 * ServletStream.java
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

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.http.HTTPConnection;
import org.bluezoo.gumdrop.http.HTTPVersion;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.Stream;
import org.bluezoo.gumdrop.telemetry.Span;

import java.io.IOException;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;
import javax.servlet.ReadListener;

/**
 * A servlet stream represents a single request/response pair.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ServletStream extends Stream {

    static final Logger LOGGER = Logger.getLogger(ServletStream.class.getName());

    final ServletConnection connection;
    int bufferSize;
    Request request;
    Response response;
    boolean explicitCloseConnection;
    
    // WebSocket support
    private ServletWebSocketConnection webSocketConnection;
    private boolean webSocketMode = false;

    private int statusCode;
    private long contentLength;
    private Headers headers;
    private List<ByteBuffer> responseBody;
    private boolean requestComplete, responseComplete;

    protected ServletStream(HTTPConnection connection, int streamId, int bufferSize) {
        super(connection, streamId);
        this.connection = (ServletConnection) connection;
        this.bufferSize = bufferSize;
    }

    boolean isResponseComplete() {
        return responseComplete;
    }

    /**
     * Request headers
     */
    @Override protected void endHeaders(Headers headers) {
        // System.err.println("endHeaders");
        String method = null;
        String requestTarget = null;
        // Extract method and requestTarget, create headers for request
        Headers requestHeaders = new Headers(headers.size());
        for (Header header : headers) {
            String name = header.getName();
            String value = header.getValue();
            if (":method".equals(name)) {
                method = value;
            } else if (":path".equals(name)) {
                requestTarget = value;
            } else if (name.charAt(0) != ':') {
                requestHeaders.add(header);
            }
        }
        try {
            request = new Request(this, bufferSize, method, requestTarget, requestHeaders);
            response = new Response(this, request, bufferSize);
            connection.serviceRequest(this); // ready for servicing
        } catch (IOException e) {
            String message = ServletServer.L10N.getString("error.create_pipe");
            LOGGER.log(Level.SEVERE, message, e);
        }
    }

    /**
     * Request body chunk
     */
    @Override protected void receiveRequestBody(byte[] buf) {
        if (webSocketMode && webSocketConnection != null) {
            // In WebSocket mode, process incoming data as WebSocket frames
            try {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(buf);
                webSocketConnection.processIncomingData(buffer);
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Error processing WebSocket data", e);
                webSocketConnection.onError(e);
            }
            return;
        }

        if (buf.length > 0) {
            ReadListener readListener = request.in.readListener;
            try {
                request.pipe.write(buf);
                if (readListener != null) {
                    readListener.onDataAvailable();
                }
            } catch (IOException e) {
                String message = ServletServer.L10N.getString("error.write_pipe");
                LOGGER.log(Level.SEVERE, message, e);
                if (readListener != null) {
                    readListener.onError(e);
                }
            }
            // System.err.println("handleBody "+buf.length);
        }
    }

    /**
     * End request
     */
    @Override protected void endRequest() {
        explicitCloseConnection = isCloseConnection();
        ReadListener readListener = request.in.readListener;
        try {
            request.pipe.close(); // end of stream for request handling worker thread
            request.in.finished.set(true);
            if (readListener != null) {
                readListener.onAllDataRead();
            }
        } catch (IOException e) {
            String message = ServletServer.L10N.getString("error.close_pipe");
            LOGGER.log(Level.SEVERE, message, e);
            if (readListener != null) {
                readListener.onError(e);
            }
        }
        requestComplete = true;
        // System.err.println("endRequest");
        
        // In WebSocket mode, don't close the request - keep connection open for frames
        if (webSocketMode) {
            return;
        }
    }

    /**
     * Sends the WebSocket upgrade response (101 Switching Protocols).
     *
     * @param responseHeaders the WebSocket response headers
     * @throws IOException if an I/O error occurs
     */
    void sendWebSocketUpgradeResponse(Headers responseHeaders) throws IOException {
        try {
            // Send 101 Switching Protocols response through the underlying HTTP connection
            // Use the Stream's sendResponseHeaders method
            sendResponseHeaders(101, responseHeaders, false);
        } catch (Exception e) {
            throw new IOException("Failed to send WebSocket upgrade response", e);
        }
    }

    /**
     * Switches this stream to WebSocket mode.
     *
     * @param webSocketConnection the WebSocket connection to use
     */
    void switchToWebSocketMode(ServletWebSocketConnection webSocketConnection) {
        this.webSocketConnection = webSocketConnection;
        this.webSocketMode = true;
        
        // Mark response as committed to prevent further HTTP response operations
        response.committed = true;
        
        // Notify the base Stream and HTTPConnection to switch to WebSocket mode
        // This ensures incoming data is routed to this stream for WebSocket processing
        super.switchToWebSocketMode();
        
        LOGGER.fine("Stream switched to WebSocket mode");
    }
    
    /**
     * Configures telemetry for a WebSocket connection.
     * Package-private to allow access from Request.
     * 
     * @param wsConnection the WebSocket connection to configure
     */
    void setupWebSocketTelemetry(org.bluezoo.gumdrop.http.websocket.WebSocketConnection wsConnection) {
        configureWebSocketTelemetry(wsConnection);
    }

    /**
     * Returns true if this stream is in WebSocket mode.
     *
     * @return true if in WebSocket mode
     */
    boolean isWebSocketMode() {
        return webSocketMode;
    }

    /**
     * Returns true if the HTTP response has been started.
     *
     * @return true if response started
     */
    boolean isResponseStarted() {
        return response != null && response.committed;
    }

    /**
     * Sends WebSocket frame data directly to the connection.
     * This is used by the WebSocket transport to send frames after upgrade.
     *
     * @param data the frame data to send
     * @throws IOException if an I/O error occurs
     */
    void sendWebSocketFrameData(ByteBuffer data) throws IOException {
        try {
            // In WebSocket mode, send data directly through the connection
            sendResponseBody(data, false);
        } catch (Exception e) {
            throw new IOException("Failed to send WebSocket frame data", e);
        }
    }

    /**
     * Closes the WebSocket stream.
     * This is used by the WebSocket transport when the connection is closed.
     */
    void closeWebSocket() {
        try {
            // Use the protected close() method from Stream
            close();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error closing WebSocket stream", e);
        }
    }
    
    /**
     * Called when the stream is closed.
     * Handles cleanup of async contexts and notifies listeners.
     */
    @Override
    protected void close() {
        // If async in progress, notify error and complete
        if (request != null && request.asyncContext != null) {
            StreamAsyncContext async = request.asyncContext;
            if (!async.isCompleted()) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(ServletServer.L10N.getString("async.connection_closed"));
                }
                async.error(new IOException(ServletServer.L10N.getString("async.connection_closed")));
            }
        }
        
        // Notify read listener of error if registered
        if (request != null && request.in != null && request.in.hasReadListener()) {
            request.in.notifyError(new IOException(ServletServer.L10N.getString("async.connection_closed")));
        }
        
        super.close();
    }

    /**
     * Returns the telemetry span for this stream.
     * Package-private accessor for async context.
     * 
     * @return the span, or null if telemetry is disabled
     */
    Span getStreamSpan() {
        return getSpan();
    }
    
    boolean isTrailerFieldsReady() {
        for (Header header : headers) {
            if ("Transfer-Encoding".equalsIgnoreCase(header.getName())) {
                return requestComplete;
            }
        }
        return true;
    }

    Map<String,String> getTrailerFields() {
        Map<String,String> trailerFields = new LinkedHashMap<>();
        if (trailerHeaders != null) {
            for (Header header : trailerHeaders) {
                trailerFields.put(header.getName().toLowerCase(), header.getValue());
            }
        }
        return trailerFields;
    }
    
    /**
     * Sends HTTP trailer fields after the response body.
     * This method converts the trailer fields map to HTTP headers and sends them
     * as trailer fields in the HTTP/2 stream or chunked transfer encoding.
     *
     * @param trailerFields map of trailer field names to values
     */
    private void sendTrailerFields(Map<String,String> trailerFields) {
        if (trailerFields == null || trailerFields.isEmpty()) {
            return;
        }
        
        try {
            // Convert trailer fields to Headers
            Headers trailerHeaders = new Headers();
            for (Map.Entry<String,String> entry : trailerFields.entrySet()) {
                trailerHeaders.add(entry.getKey(), entry.getValue());
            }
            
            // Send trailer fields and end the stream
            // This calls the underlying HTTP connection to send trailer fields
            sendTrailerHeaders(trailerHeaders);
            
        } catch (Exception e) {
            // Log error but don't throw - trailer field errors shouldn't break the response
            Logger.getLogger(ServletStream.class.getName()).warning(
                "Error sending trailer fields: " + e.getMessage());
            
            // Still need to end the stream even if trailer sending failed
            try {
                sendResponseBody(ByteBuffer.allocate(0), true); // Send empty buffer with endStream=true
            } catch (Exception endStreamError) {
                Logger.getLogger(ServletStream.class.getName()).severe(
                    "Failed to end stream after trailer field error: " + endStreamError.getMessage());
            }
        }
    }
    
    /**
     * Sends trailer headers using the underlying HTTP connection.
     * This method delegates to the parent Stream class to send trailer fields
     * in the appropriate format for the HTTP version being used.
     *
     * @param trailerHeaders the trailer headers to send
     * @throws ProtocolException if there's an error sending the trailer headers
     */
    private void sendTrailerHeaders(Headers trailerHeaders) throws ProtocolException {
        // For HTTP/2, this will send trailer fields in a HEADERS frame with END_STREAM
        // For HTTP/1.1 with chunked encoding, this will send trailers after the final chunk
        super.sendResponseHeaders(0, trailerHeaders, true); // statusCode=0 indicates trailer headers
    }
    
    // -- Servlet 4.0 Server Push Support --
    
    /**
     * Checks if this stream supports HTTP/2 server push functionality.
     * 
     * @return true if server push is supported, false otherwise
     */
    boolean supportsServerPush() {
        // Server push requires HTTP/2
        return connection.getVersion() == HTTPVersion.HTTP_2_0;
    }
    
    /**
     * Executes an HTTP/2 server push for the specified resource.
     * This delegates to the underlying Stream to handle protocol-specific details.
     * 
     * @param method the HTTP method for the push request (typically GET)
     * @param uri the URI path for the pushed resource
     * @param headers the headers for the push request
     * @return true if push was initiated successfully, false otherwise
     */
    boolean executePush(String method, String uri, Headers headers) {
        if (!supportsServerPush()) {
            return false;
        }
        
        try {
            // Delegate to the base Stream class to handle HTTP/2 protocol specifics
            boolean success = super.sendServerPush(method, uri, headers);
            
            if (success) {
                LOGGER.fine("Server push initiated: " + method + " " + uri);
            } else {
                LOGGER.warning("Server push failed for: " + method + " " + uri);
            }
            
            return success;
            
        } catch (Exception e) {
            LOGGER.warning("Failed to execute server push for " + uri + ": " + e.getMessage());
            return false;
        }
    }

    // Buffer responses until flush is called. Then notify
    // response-sending thread

    protected void sendError(int code) throws ProtocolException {
        if (response == null) {
            super.sendError(code);
            return;
        }
        response.reset();
        try {
            // This will invoke service on the error page if defined
            response.sendError(code);
        } catch (IOException e) {
            String message = ServletServer.L10N.getString("error.send_error");
            LOGGER.log(Level.SEVERE, message, e);
        }
    }

    // Called by worker thread
    void commit(int statusCode, Headers headers) {
        this.statusCode = statusCode;
        this.headers = headers;

        HitStatisticsImpl hitStatistics = request.context.hitStatistics;
        synchronized (hitStatistics) {
            hitStatistics.addHit(statusCode);
        }
    }

    // Called by worker thread
    void writeBody(ByteBuffer buf) {
        if (responseBody == null) {
            responseBody = new ArrayList<>();
        }
        responseBody.add(buf.duplicate());
        contentLength += (long) buf.remaining();
    }

    // called by worker thread
    void endResponse() {
        responseComplete = true;
        connection.responseFlushed();
        // Note: sendCloseConnection() is called in sendResponse() after the response is sent
        // to avoid a race condition where the connection is closed before the response is written
    }

    /// Called by ResponseSender thread
    void sendResponse() {
        try {
            boolean endStream = (contentLength == 0L);
            // We could add Content-Length here if the servlet didn't do it?
            sendResponseHeaders(statusCode, headers, endStream);
            if (!endStream) {
                boolean hasTrailerFields = false;
                Map<String,String> trailerFields = null;
                
                // Check if response has trailer fields to send
                if (response != null) {
                    Supplier<Map<String,String>> trailerSupplier = response.getTrailerFields();
                    if (trailerSupplier != null) {
                        try {
                            trailerFields = trailerSupplier.get();
                            hasTrailerFields = (trailerFields != null && !trailerFields.isEmpty());
                        } catch (Exception e) {
                            // Log error but continue - don't let trailer field errors break response
                            Logger.getLogger(ServletStream.class.getName()).warning(
                                "Error getting trailer fields: " + e.getMessage());
                        }
                    }
                }
                
                // Send response body, but don't end stream if we have trailer fields
                for (Iterator<ByteBuffer> i = responseBody.iterator(); i.hasNext(); ) {
                    ByteBuffer buf = i.next();
                    boolean isLast = !i.hasNext();
                    sendResponseBody(buf, isLast && !hasTrailerFields);
                }
                
                // Send trailer fields if present
                if (hasTrailerFields) {
                    sendTrailerFields(trailerFields);
                }
            }
            // Close connection after response is fully sent if requested
            if (explicitCloseConnection) {
                sendCloseConnection();
            }
        } catch (ProtocolException e) {
            // stream in wrong state
            String message = ServletServer.L10N.getString("error.protocol_error");
            LOGGER.log(Level.SEVERE, message, e);
        }
    }

}
