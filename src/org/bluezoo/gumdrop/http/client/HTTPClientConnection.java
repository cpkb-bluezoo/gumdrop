/*
 * HTTPClientConnection.java
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

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.http.HTTPVersion;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import javax.net.ssl.SSLEngine;

/**
 * HTTP client connection implementation that manages stream-based HTTP communication.
 * 
 * <p>This class extends {@link Connection} to provide HTTP-specific client functionality,
 * including protocol version negotiation, stream management, and integration with the
 * event-driven {@link HTTPClientHandler} pattern.
 * 
 * <p>The connection handles both HTTP/1.1 and HTTP/2 protocols transparently:
 * <ul>
 * <li><strong>HTTP/1.1:</strong> Sequential stream processing with connection reuse</li>
 * <li><strong>HTTP/2:</strong> Concurrent stream multiplexing with flow control</li>
 * </ul>
 * 
 * <p>Stream lifecycle is managed through the connection, with all stream events
 * routed to the associated {@link HTTPClientHandler}.
 * 
 * <p><strong>Usage Pattern:</strong>
 * <pre>
 * HTTPClientConnection connection = // obtained from HTTPClient
 * HTTPClientStream stream = connection.createStream();
 * stream.sendRequest(request);
 * // Response events delivered via HTTPClientHandler
 * </pre>
 * 
 * <p><strong>Thread Safety:</strong> All methods should be called from the connection's
 * executor thread (typically from within handler callback methods).
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTPClientHandler
 * @see HTTPClientStream
 */
public class HTTPClientConnection extends Connection {

    private static final Logger logger = Logger.getLogger(HTTPClientConnection.class.getName());

    private final HTTPClient client;
    private final HTTPClientHandler handler;
    private HTTPVersion negotiatedProtocol;
    private volatile boolean protocolNegotiated = false;
    private int nextStreamId = 1; // Client-initiated streams use odd numbers
    
    // Track active streams for this connection
    private final Map<Integer, HTTPClientStream> activeStreams = new ConcurrentHashMap<>();
    
    // HTTP/1.1 response parsing state
    private enum ParseState {
        STATUS_LINE,    // Parsing "HTTP/1.1 200 OK"
        HEADERS,        // Parsing "Name: Value" header lines
        BODY,           // Parsing response body
        COMPLETE        // Response complete
    }
    
    private ParseState parseState = ParseState.STATUS_LINE;
    private ByteBuffer parseBuffer = ByteBuffer.allocate(8192);
    private HTTPClientStream currentStream; // HTTP/1.1 has one active stream
    private HTTPRequest currentRequest; // Current request for the stream
    private HTTPResponse currentResponse;
    private long expectedBodyLength = -1;  // -1 = unknown, 0 = no body, >0 = Content-Length
    private long receivedBodyLength = 0;
    private boolean chunkedEncoding = false;
    
    // Authentication state
    private int authRetryCount = 0;
    private HTTPRequest originalRequest; // Original request before authentication

    /**
     * Creates an HTTP client connection.
     * 
     * @param client the HTTP client that owns this connection
     * @param channel the socket channel for the connection  
     * @param engine the SSL engine if this is a secure connection, or null
     * @param secure whether this connection uses SSL/TLS
     * @param handler the handler to receive connection and stream events
     */
    protected HTTPClientConnection(HTTPClient client, SocketChannel channel, SSLEngine engine, boolean secure, HTTPClientHandler handler) {
        super(engine, secure);
        this.client = client;
        this.handler = handler;
        // Note: channel is set by SelectorLoop when connection is registered
    }

    /**
     * Returns the HTTP client that owns this connection.
     * 
     * @return the parent HTTP client
     */
    public HTTPClient getClient() {
        return client;
    }

    /**
     * Returns the negotiated HTTP protocol version.
     * 
     * @return the protocol version, or null if not yet negotiated
     */
    public HTTPVersion getNegotiatedProtocol() {
        return negotiatedProtocol;
    }

    /**
     * Checks if protocol negotiation has completed.
     * 
     * @return true if protocol has been negotiated, false otherwise
     */
    public boolean isProtocolNegotiated() {
        return protocolNegotiated;
    }

    /**
     * Creates a new HTTP stream for sending a request.
     * 
     * <p>For HTTP/1.1, only one stream can be active at a time and streams
     * are processed sequentially. For HTTP/2, multiple streams can be created
     * and processed concurrently.
     * 
     * <p>The created stream will be in {@link HTTPClientStream.State#IDLE}
     * state and ready to accept a request via {@link HTTPClientStream#sendRequest}.
     * 
     * @return a new HTTP stream ready for use
     * @throws IOException if a stream cannot be created
     * @throws IllegalStateException if the connection cannot accept new streams
     */
    public HTTPClientStream createStream() throws IOException {
        if (!protocolNegotiated) {
            throw new IllegalStateException("Protocol not yet negotiated");
        }
        
        // Check if we can create a new stream (HTTP/1.1 vs HTTP/2 limits)
        if (getActiveStreamCount() >= getMaxConcurrentStreams()) {
            throw new IllegalStateException("Maximum concurrent streams exceeded");
        }
        
        // Get the next stream ID and increment
        int streamId = nextStreamId;
        nextStreamId += 2; // Client streams are odd numbers (1, 3, 5, ...)
        
        // Use the client's stream factory to create the stream
        HTTPClientStream stream = client.getStreamFactory().createStream(streamId, this);
        
        // Track the active stream
        activeStreams.put(streamId, stream);
        
        logger.fine("Created stream " + streamId + " (" + activeStreams.size() + " active streams)");
        
        // Notify the handler that a new stream is available
        handler.onStreamCreated(stream);
        
        return stream;
    }

    /**
     * Returns the maximum number of concurrent streams supported.
     * 
     * <p>For HTTP/1.1, this is always 1. For HTTP/2, this depends on the
     * SETTINGS_MAX_CONCURRENT_STREAMS value negotiated with the server.
     * 
     * @return the maximum concurrent streams, or -1 if unlimited
     */
    public int getMaxConcurrentStreams() {
        if (negotiatedProtocol == HTTPVersion.HTTP_1_1 || negotiatedProtocol == HTTPVersion.HTTP_1_0) {
            return 1;
        } else if (negotiatedProtocol == HTTPVersion.HTTP_2_0) {
            // For HTTP/2, return configured limit (default from HTTP/2 spec)
            return 100; // This will be configurable in full implementation
        }
        return 1; // Conservative default for unknown protocols
    }

    /**
     * Returns the number of currently active streams.
     * 
     * @return the count of active streams
     */
    public int getActiveStreamCount() {
        return activeStreams.size();
    }

    /**
     * Returns current connection settings (HTTP/2 only).
     * 
     * <p>For HTTP/1.1 connections, returns an empty map.
     * For HTTP/2 connections, returns the current SETTINGS frame values.
     * 
     * @return a map of setting identifiers to their current values
     */
    public Map<Integer, Long> getSettings() {
        // Implementation will be added in later phases
        return java.util.Collections.emptyMap();
    }

    /**
     * Sends a PING frame to the server (HTTP/2 only).
     * 
     * <p>For HTTP/1.1 connections, this method does nothing.
     * 
     * @param data 8 bytes of ping data
     * @throws IOException if the ping cannot be sent
     */
    public void sendPing(byte[] data) throws IOException {
        if (negotiatedProtocol != HTTPVersion.HTTP_2_0) {
            return; // No-op for HTTP/1.x
        }
        // Implementation will be added in later phases
        throw new UnsupportedOperationException("PING not yet implemented");
    }

    // Connection lifecycle methods

    @Override
    public void connected() {
        super.connected();

        // For non-TLS connections, determine initial protocol based on client preference
        if (!secure) {
            HTTPVersion preferredVersion = client.getVersion();
            
            if (preferredVersion == HTTPVersion.HTTP_2_0) {
                // Will attempt h2c upgrade with first request
                negotiatedProtocol = HTTPVersion.HTTP_1_1; // Start with HTTP/1.1
                logger.fine("Plaintext connection established, will attempt h2c upgrade");
            } else {
                // Use the preferred HTTP/1.x version directly
                negotiatedProtocol = preferredVersion;
                logger.fine("Plaintext connection established, using " + negotiatedProtocol);
            }
            
            protocolNegotiated = true;
            handler.onProtocolNegotiated(negotiatedProtocol, this);
        }

        handler.onConnected();
    }

    @Override
    protected void handshakeComplete(String protocol) {
        super.handshakeComplete(protocol);
        
        // Parse ALPN protocol identifier to HTTPVersion
        negotiatedProtocol = HTTPVersion.fromAlpnIdentifier(protocol);
        
        // Fall back to HTTP/1.1 if protocol is unknown or null
        if (negotiatedProtocol == HTTPVersion.UNKNOWN || negotiatedProtocol == null) {
            negotiatedProtocol = HTTPVersion.HTTP_1_1;
        }
        
        protocolNegotiated = true;
        handler.onProtocolNegotiated(negotiatedProtocol, this);
    }

        @Override
        protected void disconnected() throws IOException {
            // If we were in the middle of parsing a response with unknown body length,
            // the connection close signals the end of the response
            if (parseState == ParseState.BODY && expectedBodyLength == -1 && currentStream != null) {
                logger.fine("Connection closed, completing response with unknown body length");
                
                // Send any remaining body data with endStream=true
                parseBuffer.flip();
                if (parseBuffer.remaining() > 0) {
                    ByteBuffer finalBodyData = ByteBuffer.allocate(parseBuffer.remaining());
                    finalBodyData.put(parseBuffer);
                    finalBodyData.flip();

                    // Notify stream of final data
                    if (currentStream instanceof DefaultHTTPClientStream) {
                        ((DefaultHTTPClientStream) currentStream).dataReceived(finalBodyData, true);
                    }

                    handler.onStreamData(currentStream, finalBodyData, true);
                }
                
                // Complete the response
                completeCurrentResponse();
            }
            
            handler.onDisconnected();
        }

    // Data processing methods

        @Override
        public void receive(ByteBuffer buf) {
            try {
                if (negotiatedProtocol == HTTPVersion.HTTP_1_1 || negotiatedProtocol == HTTPVersion.HTTP_1_0) {
                    processHTTP11Response(buf);
                } else if (negotiatedProtocol == HTTPVersion.HTTP_2_0) {
                    // HTTP/2 implementation will be added later
                    throw new UnsupportedOperationException("HTTP/2 response processing not yet implemented");
                } else {
                    throw new IllegalStateException("Unknown protocol: " + negotiatedProtocol);
                }
            } catch (Exception e) {
                logger.severe("Error processing received data: " + e.getMessage());
                handleError(e);
            }
        }

    // Stream protocol methods - called by HTTPClientStream implementations

        /**
         * Sends an HTTP request for the specified stream.
         * This method is called by stream implementations.
         *
         * @param stream the stream sending the request
         * @param request the HTTP request to send
         * @throws IOException if the request cannot be sent
         */
        void sendRequest(HTTPClientStream stream, HTTPRequest request) throws IOException {
            if (!protocolNegotiated) {
                throw new IllegalStateException("Protocol not yet negotiated");
            }

            // Store original request for authentication retry purposes
            originalRequest = request;
            authRetryCount = 0; // Reset retry count for new request

            // Apply proactive authentication
            HTTPRequest authenticatedRequest = client.getAuthenticationManager().applyProactiveAuthentication(request);

            if (negotiatedProtocol == HTTPVersion.HTTP_1_1 || negotiatedProtocol == HTTPVersion.HTTP_1_0) {
                // For HTTP/1.1, track the current stream and request since only one can be active
                currentStream = stream;
                currentRequest = authenticatedRequest;
                sendHTTP11Request(stream, authenticatedRequest);
            } else if (negotiatedProtocol == HTTPVersion.HTTP_2_0) {
                // HTTP/2 implementation will be added later
                throw new UnsupportedOperationException("HTTP/2 request sending not yet implemented");
            } else {
                throw new IllegalStateException("Unknown protocol: " + negotiatedProtocol);
            }
        }
    
    /**
     * Sends an HTTP/1.1 request for the specified stream.
     */
    private void sendHTTP11Request(HTTPClientStream stream, HTTPRequest request) throws IOException {
        StringBuilder requestBuilder = new StringBuilder();
        
        // Request line: METHOD /path HTTP/1.1\r\n
        requestBuilder.append(request.getMethod()).append(" ");
        requestBuilder.append(request.getUri()).append(" HTTP/1.1\r\n");
        
        // Required Host header
        String hostHeader = client.getHost().getHostAddress();
        int port = client.getPort();
        
        // Only include port if it's not the default for the scheme
        boolean includePort = false;
        if (secure && port != 443) {
            includePort = true;
        } else if (!secure && port != 80) {
            includePort = true;
        }
        
        if (includePort) {
            hostHeader += ":" + port;
        }
        
        requestBuilder.append("Host: ").append(hostHeader).append("\r\n");
        
        // Add custom headers from the request
        for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
            String name = header.getKey();
            String value = header.getValue();
            
            // Skip host header if user provided it (we use our own)
            if (!"host".equals(name.toLowerCase())) {
                requestBuilder.append(name).append(": ").append(value).append("\r\n");
            }
        }
        
            // Handle Content-Length and Transfer-Encoding headers intelligently
            handleContentHeaders(requestBuilder, request);
        
        // Connection management - default to keep-alive for HTTP/1.1
        if (!request.hasHeader("connection")) {
            requestBuilder.append("Connection: keep-alive\r\n");
        }
        
        // End of headers
        requestBuilder.append("\r\n");
        
        // Convert to bytes and send
        String requestString = requestBuilder.toString();
        byte[] requestBytes = requestString.getBytes(StandardCharsets.UTF_8);
        ByteBuffer requestBuffer = ByteBuffer.wrap(requestBytes);
        
        logger.fine("Sending HTTP/1.1 request for stream " + stream.getStreamId() + ":\n" + requestString.trim());
        
        // Send via Connection's networking layer
        send(requestBuffer);
    }

    /**
     * Sends request body data for the specified stream.
     * This method is called by stream implementations.
     * 
     * @param stream the stream sending the data
     * @param data the request body data to send
     * @param endStream true if this is the final chunk of request data
     * @throws IOException if the data cannot be sent
     */
    void sendData(HTTPClientStream stream, ByteBuffer data, boolean endStream) throws IOException {
        if (!protocolNegotiated) {
            throw new IllegalStateException("Protocol not yet negotiated");
        }
        
        if (negotiatedProtocol == HTTPVersion.HTTP_1_1 || negotiatedProtocol == HTTPVersion.HTTP_1_0) {
            sendHTTP11Data(stream, data, endStream);
        } else if (negotiatedProtocol == HTTPVersion.HTTP_2_0) {
            // HTTP/2 implementation will be added later
            throw new UnsupportedOperationException("HTTP/2 data sending not yet implemented");
        } else {
            throw new IllegalStateException("Unknown protocol: " + negotiatedProtocol);
        }
    }
    
        /**
         * Sends HTTP/1.1 request body data for the specified stream.
         */
        private void sendHTTP11Data(HTTPClientStream stream, ByteBuffer data, boolean endStream) throws IOException {
            // Determine if we're using chunked encoding from the current request
            // We need to track this per-stream, but for HTTP/1.1 there's only one active stream
            HTTPRequest streamRequest = getStreamRequest(stream);
            boolean usingChunked = (streamRequest != null && streamRequest.isChunked());

            int dataSize = data.remaining();
            
            if (usingChunked) {
                // Send data using chunked encoding format
                sendChunkedData(data, endStream);
            } else {
                // Send data directly (Content-Length mode)
                if (dataSize > 0) {
                    logger.fine("Sending " + dataSize + " bytes of body data for stream " + stream.getStreamId());

                    // Create a copy of the data to avoid modification of the original buffer
                    ByteBuffer dataCopy = ByteBuffer.allocate(dataSize);
                    dataCopy.put(data);
                    dataCopy.flip();

                    // Send via Connection's networking layer
                    send(dataCopy);
                }

                if (endStream) {
                    logger.fine("Request body complete for stream " + stream.getStreamId());
                }
            }
        }

        /**
         * Sends data using HTTP/1.1 chunked transfer encoding.
         */
        private void sendChunkedData(ByteBuffer data, boolean endStream) throws IOException {
            int dataSize = data.remaining();
            
            if (dataSize > 0) {
                // Send chunk size in hex + CRLF
                String chunkSizeHex = Integer.toHexString(dataSize);
                ByteBuffer chunkHeader = ByteBuffer.wrap((chunkSizeHex + "\r\n").getBytes(StandardCharsets.US_ASCII));
                send(chunkHeader);

                // Send chunk data
                ByteBuffer dataCopy = ByteBuffer.allocate(dataSize);
                dataCopy.put(data);
                dataCopy.flip();
                send(dataCopy);

                // Send trailing CRLF
                ByteBuffer chunkTrailer = ByteBuffer.wrap("\r\n".getBytes(StandardCharsets.US_ASCII));
                send(chunkTrailer);

                logger.fine("Sent chunked data: " + dataSize + " bytes (hex: " + chunkSizeHex + ")");
            }
            
            if (endStream) {
                // Send final chunk (0 + CRLF + CRLF)
                ByteBuffer finalChunk = ByteBuffer.wrap("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                send(finalChunk);
                logger.fine("Sent final chunk (chunked encoding complete)");
            }
        }

        /**
         * Gets the original request for a stream (simplified for HTTP/1.1).
         */
        private HTTPRequest getStreamRequest(HTTPClientStream stream) {
            // For HTTP/1.1, we only have one active stream at a time
            if (stream == currentStream) {
                return currentRequest;
            }
            return null;
        }

    /**
     * Cancels the specified stream.
     * This method is called by stream implementations.
     * 
     * @param stream the stream to cancel
     * @param errorCode the reason for cancellation (protocol-specific)
     */
    void cancelStream(HTTPClientStream stream, int errorCode) {
        int streamId = stream.getStreamId();
        logger.fine("Cancelling stream " + streamId + " with error code: " + errorCode);
        
        if (negotiatedProtocol == HTTPVersion.HTTP_1_1 || negotiatedProtocol == HTTPVersion.HTTP_1_0) {
            // For HTTP/1.x, cancelling a stream may require closing the entire connection
            // since there's no stream-level cancellation mechanism
            // For now, just remove from active streams - full implementation may close connection
            removeStream(streamId);
        } else if (negotiatedProtocol == HTTPVersion.HTTP_2_0) {
            // HTTP/2 implementation will send RST_STREAM frame
            // For now, just remove from active streams
            removeStream(streamId);
        }
    }
    
    /**
     * Removes a stream from the active streams map.
     * This is called when a stream completes, is cancelled, or encounters an error.
     * 
     * @param streamId the ID of the stream to remove
     */
    void removeStream(int streamId) {
        HTTPClientStream removed = activeStreams.remove(streamId);
        if (removed != null) {
            logger.fine("Removed stream " + streamId + " (" + activeStreams.size() + " active streams remaining)");
        }
    }
    
    /**
     * Marks a stream as completed and removes it from active tracking.
     * This method is called when a stream's response has been fully received.
     * 
     * @param streamId the ID of the stream that completed
     */
    void completeStream(int streamId) {
        HTTPClientStream stream = activeStreams.get(streamId);
        if (stream != null) {
            logger.fine("Stream " + streamId + " completed successfully");
            removeStream(streamId);
            handler.onStreamComplete(stream);
        }
    }

        // HTTP/1.1 response parsing methods

        /**
         * Processes HTTP/1.1 response data using a state machine.
         */
        private void processHTTP11Response(ByteBuffer incomingData) throws IOException {
            // Append incoming data to our parse buffer
            appendToParseBuffer(incomingData);

            // Process data based on current parse state
            while (parseBuffer.position() > 0) {
                switch (parseState) {
                    case STATUS_LINE:
                        if (!parseStatusLine()) {
                            return; // Need more data
                        }
                        break;
                    case HEADERS:
                        if (!parseHeaders()) {
                            return; // Need more data
                        }
                        break;
                    case BODY:
                        if (!parseBody()) {
                            return; // Need more data or body complete
                        }
                        break;
                    case COMPLETE:
                        // Response complete - should not reach here
                        return;
                }
            }
        }

        /**
         * Appends incoming data to the parse buffer, expanding if necessary.
         */
        private void appendToParseBuffer(ByteBuffer incomingData) {
            int newDataSize = incomingData.remaining();
            int currentDataSize = parseBuffer.position();
            int requiredCapacity = currentDataSize + newDataSize;

            if (requiredCapacity > parseBuffer.capacity()) {
                // Expand buffer - double size or required capacity, whichever is larger
                int newCapacity = Math.max(requiredCapacity, parseBuffer.capacity() * 2);
                ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);
                parseBuffer.flip();
                newBuffer.put(parseBuffer);
                parseBuffer = newBuffer;
            }

            // Add new data
            parseBuffer.put(incomingData);
        }

        /**
         * Parses the HTTP status line: "HTTP/1.1 200 OK\r\n"
         * @return true if status line was parsed, false if more data needed
         */
        private boolean parseStatusLine() throws IOException {
            String line = extractLine();
            if (line == null) {
                return false; // Need more data
            }

            logger.fine("Parsing status line: " + line);

            // Parse: "HTTP/1.1 200 OK"
            String[] parts = line.split(" ", 3);
            if (parts.length < 2) {
                throw new IOException("Invalid HTTP status line: " + line);
            }

            String versionString = parts[0];
            int statusCode;
            String statusMessage = parts.length > 2 ? parts[2] : "";

            try {
                statusCode = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid status code in: " + line);
            }

            // Detect actual HTTP version from server response
            HTTPVersion detectedVersion = HTTPVersion.fromVersionString(versionString);
            updateProtocolFromResponse(detectedVersion, versionString);

            // Create HTTPResponse object (will be completed with headers)
            currentResponse = new HTTPResponse(statusCode, statusMessage, versionString, new java.util.HashMap<String, String>());
            
            // Move to headers parsing
            parseState = ParseState.HEADERS;
            return true;
        }

        /**
         * Updates connection behavior based on the HTTP version detected in the server response.
         */
        private void updateProtocolFromResponse(HTTPVersion detectedVersion, String versionString) {
            if (detectedVersion != HTTPVersion.UNKNOWN && detectedVersion != negotiatedProtocol) {
                logger.fine("Server responded with " + versionString + ", adjusting connection behavior");
                
                // Update our understanding of the protocol in use
                HTTPVersion previousVersion = negotiatedProtocol;
                negotiatedProtocol = detectedVersion;
                
                // Adjust connection behavior based on detected version
                if (detectedVersion == HTTPVersion.HTTP_1_0) {
                    logger.fine("HTTP/1.0 detected: connection will close after each request");
                    // HTTP/1.0 connections typically close after each request
                    // We'll handle this in completeCurrentResponse()
                } else if (detectedVersion == HTTPVersion.HTTP_1_1) {
                    logger.fine("HTTP/1.1 detected: persistent connection with keep-alive");
                    // HTTP/1.1 supports persistent connections and chunked encoding
                } else if (detectedVersion == HTTPVersion.HTTP_2_0) {
                    logger.fine("HTTP/2.0 detected: protocol upgrade successful");
                    // This would happen if we successfully upgraded via h2c
                }
                
                // Notify handler of protocol change if it's significant
                if ((previousVersion == HTTPVersion.HTTP_1_1 && detectedVersion == HTTPVersion.HTTP_1_0) ||
                    (previousVersion == HTTPVersion.HTTP_1_1 && detectedVersion == HTTPVersion.HTTP_2_0)) {
                    handler.onProtocolNegotiated(negotiatedProtocol, this);
                }
            }
        }

        /**
         * Parses HTTP headers until "\r\n\r\n"
         * @return true if all headers parsed, false if more data needed
         */
        private boolean parseHeaders() throws IOException {
            java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
            
            String line;
            while ((line = extractLine()) != null) {
                if (line.isEmpty()) {
                    // Empty line marks end of headers
                    logger.fine("Headers complete, " + headers.size() + " headers parsed");

                    // Create final HTTPResponse with all headers
                    currentResponse = new HTTPResponse(
                        currentResponse.getStatusCode(),
                        currentResponse.getStatusMessage(),
                        currentResponse.getVersion(),
                        headers
                    );

                    // Determine body handling from headers
                    setupBodyParsing();

                    // Notify stream that response was received (updates state internally)
                    if (currentStream instanceof DefaultHTTPClientStream) {
                        ((DefaultHTTPClientStream) currentStream).responseReceived(currentResponse);
                    }

                    // Check for authentication challenges before notifying handler
                    if (handleAuthenticationChallenge()) {
                        // Authentication challenge is being handled, don't notify handler yet
                        return true;
                    }

                    // Notify handler of response headers
                    handler.onStreamResponse(currentStream, currentResponse);

                    // Move to body parsing (or complete if no body)
                    if (expectedBodyLength == 0) {
                        completeCurrentResponse();
                    } else {
                        parseState = ParseState.BODY;
                    }
                    return true;
                }

                // Parse "Name: Value" header
                int colonIndex = line.indexOf(':');
                if (colonIndex <= 0) {
                    logger.warning("Invalid header format: " + line);
                    continue;
                }

                String name = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();
                headers.put(name.toLowerCase(), value);

                logger.fine("Header: " + name + " = " + value);
            }

            return false; // Need more data to complete headers
        }

        /**
         * Sets up body parsing based on response headers.
         */
        private void setupBodyParsing() {
            expectedBodyLength = -1; // Default: unknown
            receivedBodyLength = 0;
            chunkedEncoding = false;

            // Check Transfer-Encoding
            String transferEncoding = currentResponse.getHeader("transfer-encoding");
            if (transferEncoding != null && transferEncoding.toLowerCase().contains("chunked")) {
                chunkedEncoding = true;
                logger.fine("Using chunked transfer encoding");
            } else {
                // Check Content-Length
                long contentLength = currentResponse.getContentLength();
                if (contentLength >= 0) {
                    expectedBodyLength = contentLength;
                    logger.fine("Expecting " + contentLength + " bytes of body data");
                } else {
                    // No Content-Length and no chunked encoding
                    // For some status codes (204, 304), there should be no body
                    int statusCode = currentResponse.getStatusCode();
                    if (statusCode == 204 || statusCode == 304 || 
                        (statusCode >= 100 && statusCode < 200)) {
                        expectedBodyLength = 0;
                        logger.fine("No body expected for status code " + statusCode);
                    } else {
                        // Connection close indicates end of body (HTTP/1.0 style)
                        logger.fine("Body length unknown - will read until connection close");
                    }
                }
            }
        }

        /**
         * Parses the response body based on Content-Length or chunked encoding.
         * @return true if more data might be available, false if body is complete
         */
        private boolean parseBody() throws IOException {
            if (chunkedEncoding) {
                return parseChunkedBody();
            } else if (expectedBodyLength >= 0) {
                return parseFixedLengthBody();
            } else {
                return parseUnknownLengthBody();
            }
        }

        /**
         * Parses a fixed-length body based on Content-Length.
         */
        private boolean parseFixedLengthBody() throws IOException {
            long remaining = expectedBodyLength - receivedBodyLength;
            if (remaining <= 0) {
                // Body complete
                completeCurrentResponse();
                return false;
            }

            // Read available data up to remaining bytes
            parseBuffer.flip();
            int availableBytes = parseBuffer.remaining();
            int bytesToRead = (int) Math.min(remaining, availableBytes);

            if (bytesToRead > 0) {
                // Extract body data
                ByteBuffer bodyData = ByteBuffer.allocate(bytesToRead);
                
                // Copy data from parse buffer
                int originalLimit = parseBuffer.limit();
                parseBuffer.limit(parseBuffer.position() + bytesToRead);
                bodyData.put(parseBuffer);
                parseBuffer.limit(originalLimit);
                
                bodyData.flip();
                receivedBodyLength += bytesToRead;

                // Compact remaining data in parse buffer
                parseBuffer.compact();

                // Notify stream of received data
                boolean endStream = (receivedBodyLength >= expectedBodyLength);
                if (currentStream instanceof DefaultHTTPClientStream) {
                    ((DefaultHTTPClientStream) currentStream).dataReceived(bodyData, endStream);
                }

                // Send data to handler
                handler.onStreamData(currentStream, bodyData, endStream);

                if (endStream) {
                    completeCurrentResponse();
                    return false;
                }
            } else {
                // Restore parse buffer state
                parseBuffer.compact();
            }

            return true; // More data needed
        }

        /**
         * Parses chunked transfer encoding (simplified implementation).
         */
        private boolean parseChunkedBody() throws IOException {
            // This is a simplified implementation - full chunked parsing is more complex
            // For now, just read all available data and assume it's valid
            parseBuffer.flip();
            
            if (parseBuffer.remaining() > 0) {
                ByteBuffer bodyData = ByteBuffer.allocate(parseBuffer.remaining());
                bodyData.put(parseBuffer);
                bodyData.flip();
                parseBuffer.clear();

                // Notify stream of received data (chunked - assume not end)
                if (currentStream instanceof DefaultHTTPClientStream) {
                    ((DefaultHTTPClientStream) currentStream).dataReceived(bodyData, false);
                }

                // For chunked encoding, we can't easily determine end-of-stream
                // without full chunk parsing, so assume not end for now
                handler.onStreamData(currentStream, bodyData, false);
            } else {
                parseBuffer.clear();
            }

            return true; // Continue reading chunks
        }

        /**
         * Parses body of unknown length (read until connection close).
         */
        private boolean parseUnknownLengthBody() throws IOException {
            parseBuffer.flip();
            
            if (parseBuffer.remaining() > 0) {
                ByteBuffer bodyData = ByteBuffer.allocate(parseBuffer.remaining());
                bodyData.put(parseBuffer);
                bodyData.flip();
                parseBuffer.clear();

                // Notify stream of received data (unknown length - not end until connection closes)
                if (currentStream instanceof DefaultHTTPClientStream) {
                    ((DefaultHTTPClientStream) currentStream).dataReceived(bodyData, false);
                }

                // Cannot determine end-of-stream without connection close
                handler.onStreamData(currentStream, bodyData, false);
            } else {
                parseBuffer.clear();
            }

            return true; // Continue until connection closes
        }

        /**
         * Extracts a complete CRLF-terminated line from the parse buffer.
         * @return the line without CRLF, or null if no complete line is available
         */
        private String extractLine() throws IOException {
            parseBuffer.flip(); // Switch to read mode

            // Look for CRLF sequence
            int crlfIndex = -1;
            for (int i = 0; i < parseBuffer.limit() - 1; i++) {
                if (parseBuffer.get(i) == '\r' && parseBuffer.get(i + 1) == '\n') {
                    crlfIndex = i;
                    break;
                }
            }

            if (crlfIndex >= 0) {
                // Found complete line - extract it
                byte[] lineBytes = new byte[crlfIndex];
                parseBuffer.get(lineBytes);

                // Skip the CRLF
                parseBuffer.get(); // skip CR
                parseBuffer.get(); // skip LF

                // Compact remaining data
                parseBuffer.compact();

                // Convert to string (HTTP headers are ASCII-based)
                return new String(lineBytes, StandardCharsets.UTF_8);
            } else {
                // No complete line yet - restore buffer state
                parseBuffer.compact();
                return null;
            }
        }

        /**
         * Completes the current HTTP response and resets for the next response.
         */
        private void completeCurrentResponse() {
            if (currentStream != null && currentResponse != null) {
                logger.fine("Response complete for stream " + currentStream.getStreamId() + 
                           ": " + currentResponse.getStatusCode() + " " + currentResponse.getStatusMessage());

                // The stream state is updated automatically by dataReceived() when endStream=true

                // Notify completion
                handler.onStreamComplete(currentStream);

                // Remove the completed stream (don't call completeStream to avoid double notification)
                removeStream(currentStream.getStreamId());

                // Handle connection persistence based on HTTP version and headers
                handleConnectionPersistence();

                // Reset parsing state for next response
                resetParsingState();
            }
        }

        /**
         * Handles connection persistence based on HTTP version and response headers.
         */
        private void handleConnectionPersistence() {
            boolean shouldCloseConnection = false;
            
            if (negotiatedProtocol == HTTPVersion.HTTP_1_0) {
                // HTTP/1.0: Close by default unless "Connection: keep-alive" is present
                String connection = currentResponse.getHeader("connection");
                if (!"keep-alive".equalsIgnoreCase(connection)) {
                    shouldCloseConnection = true;
                    logger.fine("HTTP/1.0 connection will close (no keep-alive)");
                } else {
                    logger.fine("HTTP/1.0 connection will remain open (keep-alive)");
                }
            } else if (negotiatedProtocol == HTTPVersion.HTTP_1_1) {
                // HTTP/1.1: Keep alive by default unless "Connection: close" is present
                String connection = currentResponse.getHeader("connection");
                if ("close".equalsIgnoreCase(connection)) {
                    shouldCloseConnection = true;
                    logger.fine("HTTP/1.1 connection will close (explicit close)");
                } else {
                    logger.fine("HTTP/1.1 connection will remain open (persistent)");
                }
            }
            // HTTP/2.0 connections are always persistent (handled separately)
            
            if (shouldCloseConnection) {
                // Schedule connection close after this response completes
                // The SelectorLoop will handle the actual close operation
                logger.fine("Scheduling connection close after response completion");
                try {
                    // Close the connection - this will trigger disconnected() callback
                    close();
                } catch (Exception e) {
                    logger.warning("Error closing connection: " + e.getMessage());
                    handleError(e);
                }
            }
        }

        /**
         * Handles authentication challenges (401/407 responses).
         *
         * @return true if the challenge is being handled and the request will be retried,
         *         false if the response should be processed normally
         */
        private boolean handleAuthenticationChallenge() {
            int statusCode = currentResponse.getStatusCode();
            if (statusCode != 401 && statusCode != 407) {
                return false; // Not an authentication challenge
            }

            // Check if we've exceeded retry limits
            if (authRetryCount >= client.getAuthenticationManager().getMaxRetries()) {
                logger.warning("Authentication retry limit exceeded (" + authRetryCount + " attempts)");
                return false; // Let the response be processed as a failure
            }

            try {
                // Handle the challenge using the authentication manager
                HTTPAuthenticationManager.AuthenticationResult result = 
                    client.getAuthenticationManager().handleChallenge(currentResponse, originalRequest);

                if (result.isRetry()) {
                    logger.fine("Authentication challenge handled, retrying request (attempt " + (authRetryCount + 1) + ")");

                    // Increment retry count
                    authRetryCount++;

                    // Update current request with authentication
                    currentRequest = result.getRetryRequest();

                    // Reset parsing state for the retry
                    resetParsingStateForRetry();

                    // Resend the authenticated request
                    try {
                        if (negotiatedProtocol == HTTPVersion.HTTP_1_1 || negotiatedProtocol == HTTPVersion.HTTP_1_0) {
                            sendHTTP11Request(currentStream, currentRequest);
                        } else {
                            throw new UnsupportedOperationException("HTTP/2 authentication retry not yet implemented");
                        }
                        return true; // Challenge handled, request retried
                    } catch (IOException e) {
                        logger.warning("Failed to retry request after authentication: " + e.getMessage());
                        handler.onError(e);
                        return false;
                    }
                } else {
                    logger.warning("Authentication challenge could not be handled: " + result.getFailureReason());
                    return false; // Let the response be processed as a failure
                }
            } catch (Exception e) {
                logger.warning("Error handling authentication challenge: " + e.getMessage());
                return false; // Let the response be processed as a failure
            }
        }

        /**
         * Resets parsing state for authentication retry (keeps stream and some context).
         */
        private void resetParsingStateForRetry() {
            parseState = ParseState.STATUS_LINE;
            // Keep currentStream - we're retrying the same stream
            // Keep currentRequest - it will be updated with authentication
            currentResponse = null;
            expectedBodyLength = -1;
            receivedBodyLength = 0;
            chunkedEncoding = false;
            // Clear parseBuffer for new response
            parseBuffer.clear();
        }

        /**
         * Resets parsing state for the next HTTP response.
         */
        private void resetParsingState() {
            parseState = ParseState.STATUS_LINE;
            currentStream = null;
            currentRequest = null; // Clear current request
            originalRequest = null; // Clear original request
            currentResponse = null;
            expectedBodyLength = -1;
            receivedBodyLength = 0;
            chunkedEncoding = false;
            authRetryCount = 0; // Reset authentication retry count
            // Keep parseBuffer for reuse, just reset position
            parseBuffer.clear();
        }

        /**
         * Handles Content-Length and Transfer-Encoding headers based on request characteristics.
         */
        private void handleContentHeaders(StringBuilder requestBuilder, HTTPRequest request) {
            boolean hasContentLength = request.hasHeader("content-length");
            boolean hasTransferEncoding = request.hasHeader("transfer-encoding");
            
            // If user already specified both, use as-is
            if (hasContentLength && hasTransferEncoding) {
                return;
            }
            
            // If user specified one or the other, use as-is
            if (hasContentLength || hasTransferEncoding) {
                return;
            }
            
            // Neither specified - make intelligent choices
            String method = request.getMethod().toUpperCase();
            
            if (!request.expectsRequestBody()) {
                // Methods that typically don't have bodies (GET, HEAD, DELETE, OPTIONS)
                requestBuilder.append("Content-Length: 0\r\n");
            } else {
                // Methods that typically have bodies (POST, PUT, PATCH)
                // Choose based on HTTP version and use case
                
                if (negotiatedProtocol == HTTPVersion.HTTP_1_1 && client.getVersion() != HTTPVersion.HTTP_1_0) {
                    // HTTP/1.1 supports chunked encoding - use it for streaming scenarios
                    // This allows sending data without knowing the length in advance
                    logger.fine("Using chunked transfer encoding for " + method + " request");
                    requestBuilder.append("Transfer-Encoding: chunked\r\n");
                } else {
                    // HTTP/1.0 or user prefers HTTP/1.0 - must use Content-Length
                    // Default to 0, user must call sendData to update this before sending
                    logger.fine("Using Content-Length for " + method + " request (will be updated if body data sent)");
                    requestBuilder.append("Content-Length: 0\r\n");
                }
            }
        }

        // Error handling

        private void handleError(Exception error) {
            handler.onError(error);
        }
    }
