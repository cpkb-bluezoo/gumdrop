/*
 * Stream.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.http;

import java.io.IOException;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.http.hpack.Decoder;
import org.bluezoo.gumdrop.http.hpack.HeaderHandler;
import org.bluezoo.gumdrop.util.LineInput;

import gnu.inet.http.HTTPDateFormat;

/**
 * A stream.
 * This represents a single request/response.
 * Although the concept was introduced in HTTP/2, we use the same
 * mechanism in HTTP/1.
 * This class transparently handles Transfer-Encoding: chunked in requests.
 * This method is deprecated for responses: implementations should always
 * set the Content-Length.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see https://www.rfc-editor.org/rfc/rfc7540#section-5
 */
public class Stream {

    /**
     * Methods that by definition do not have a request body, therefore we
     * do not need a Content-Length.
     */
    private static final Set<String> NO_REQUEST_BODY = new TreeSet<>(Arrays.asList(new String[] {
        "GET", "HEAD", "OPTIONS", "DELETE"
    }));

    /**
     * Date format that can be used to parse and format dates in HTTP
     * headers.
     */
    protected static final HTTPDateFormat dateFormat = new HTTPDateFormat();

    enum State {
        IDLE,
        OPEN,
        CLOSED,
        RESERVED_LOCAL,
        RESERVED_REMOTE,
        HALF_CLOSED_LOCAL,
        HALF_CLOSED_REMOTE;
    }

    final HTTPConnection connection;
    final int streamId;

    protected Stream(HTTPConnection connection, int streamId) {
        this.connection = connection;
        this.streamId = streamId;
    }

    State state = State.IDLE;
    Collection<Header> headers; // NB these are the *request* headers
    protected Collection<Header> trailerHeaders; // Trailer headers in chunked request
    ByteBuffer headerBlock; // raw HPACK-encoded header block
    boolean pushPromise;
    boolean closeConnection;
    Collection<String> upgrade;
    SettingsFrame settingsFrame;

    String method;
    String requestTarget;

    // Length of the request body.
    // -1 indicates that we don't know yet. If we start receiving content in
    // this state it is a client error.
    // Integer.MAX_VALUE indicates that we are dealing with chunked
    // encoding.
    long contentLength = -1L;
    
    // Number of bytes of request body received so far.
    long requestBodyBytesReceived = 0L;

    // Chunked encoding management.
    // The stream implementation won't see the encoding, only the data.
    boolean chunked;
    ChunkLineInput chunkLineInput;
    boolean seenLastChunk; // we have seen the zero-length chunk marker

    long timestampCompleted = 0L; // when this stream entered the CLOSED state

    /**
     * Handles reading the CRLF-delimited chunk sizes and terminators.
     */
    static class ChunkLineInput implements LineInput {

        private ByteBuffer chunkBuffer;
        private CharBuffer chunkLineSink;

        void dataReceived(ByteBuffer buf) {
            if (chunkBuffer == null) {
                chunkBuffer = ByteBuffer.allocate(buf.remaining());
            } else if (chunkBuffer.remaining() < buf.remaining()) {
                ByteBuffer newChunkBuffer = ByteBuffer.allocate(chunkBuffer.capacity() + buf.remaining());
                newChunkBuffer.put(chunkBuffer);
                chunkBuffer = newChunkBuffer;
            }
            chunkBuffer.put(buf);
            chunkBuffer.flip();
        }

        boolean hasRemaining() {
            return chunkBuffer.hasRemaining();
        }

        boolean hasChunk(int chunkSize) {
            return chunkBuffer.remaining() >= chunkSize + 2;
        }

        @Override public ByteBuffer getLineInputBuffer() {
            return chunkBuffer;
        }

        @Override public CharBuffer getOrCreateLineInputCharacterSink(int capacity) {
            if (chunkLineSink == null || chunkLineSink.capacity() < capacity) {
                chunkLineSink = CharBuffer.allocate(capacity);
            }
            return chunkLineSink;
        }

        void getChunk(byte[] chunk) throws IOException {
            if (chunk != null) {
                chunkBuffer.get(chunk);
            }
            if (chunkBuffer.get() != (byte) 0x0d || chunkBuffer.get() != (byte) 0x0a) { // CRLF end of chunk
                throw new ProtocolException("Missing end of chunk marker");
            }
        }

        void free() {
            chunkBuffer = null;
            chunkLineSink = null;
        }

        void compact() {
            chunkBuffer.compact();
        }

    }

    /**
     * Returns the URI scheme of the connection.
     */
    public String getScheme() {
        return connection.getScheme();
    }

    /**
     * Returns the version of the connection.
     */
    public HTTPVersion getVersion() {
        return connection.getVersion();
    }

    /**
     * Notify that this stream represents a push promise.
     */
    public void setPushPromise() {
        pushPromise = true;
    }
    
    /**
     * Sends an HTTP/2 server push for the specified resource.
     * This method creates a PUSH_PROMISE frame and establishes a promised stream.
     * 
     * <p>Server push is only supported on HTTP/2 connections. On HTTP/1.x connections,
     * this method returns false.
     * 
     * @param method the HTTP method for the push request (typically GET or HEAD)
     * @param uri the URI path for the pushed resource
     * @param headers the headers for the push request
     * @return true if push was initiated successfully, false otherwise
     */
    public boolean sendServerPush(String method, String uri, List<Header> headers) {
        // Only HTTP/2 connections support server push
        if (connection.getVersion() != HTTPVersion.HTTP_2_0) {
            return false;
        }
        
        try {
            HTTPConnection httpConnection = (HTTPConnection) connection;
            
            // Get next available server stream ID (must be even for server-initiated streams)
            int promisedStreamId = httpConnection.getNextServerStreamId();
            
            // Create PUSH_PROMISE frame with the headers
            byte[] headerBlock = httpConnection.encodeHeaders(headers);
            PushPromiseFrame pushPromiseFrame = new PushPromiseFrame(
                this.streamId,      // Parent stream ID
                false,              // Not padded
                true,               // End headers
                0,                  // Pad length (not used)
                promisedStreamId,   // Promised stream ID
                headerBlock         // Encoded headers
            );
            
            // Send PUSH_PROMISE frame
            httpConnection.sendFrame(pushPromiseFrame);
            
            // Create the promised stream for handling the pushed response
            Stream promisedStream = httpConnection.createPushedStream(promisedStreamId, method, uri, headers);
            
            if (promisedStream != null) {
                // Mark as push promise stream
                promisedStream.setPushPromise();
                
                // The pushed response will be handled by the server when 
                // the application generates content for this URI
                return true;
            }
            
        } catch (Exception e) {
            // Log error but don't throw - server push failures should not break main response
            java.util.logging.Logger.getLogger(Stream.class.getName()).log(
                java.util.logging.Level.WARNING, 
                "Failed to execute server push for " + uri, e);
        }
        
        return false;
    }

    /**
     * 5.1.2 Stream Concurrency
     */
    boolean isActive() {
        return state == State.OPEN ||
            state == State.HALF_CLOSED_LOCAL ||
            state == State.HALF_CLOSED_REMOTE;
    }

    /**
     * Indicates whether this stream will cause the connection to the client
     * to be closed after its response is sent.
     * This is the default behaviour for HTTP/1.0, and can be set by using
     * the Connection: close header in HTTP/1.1.
     */
    public boolean isCloseConnection() {
        return closeConnection;
    }

    /**
     * Returns the Content-Length of the request, or -1 if not known: this
     * indicates chunked encoding.
     */
    protected long getContentLength() {
        return contentLength;
    }

    /**
     * Indicates whether this stream has been closed.
     */
    public boolean isClosed() {
        return state == State.CLOSED;
    }

    long getRequestBodyBytesNeeded() {
        return contentLength - requestBodyBytesReceived;
    }

    void addHeader(Header header) {
        if (headers == null) {
            headers = new ArrayList<>();
        }
        headers.add(header);
        if (":method".equals(header.getName())) {
            method = header.getValue();
        } else if (":path".equals(header.getName())) {
            requestTarget = header.getValue();
        }
    }

    void addTrailerHeader(Header header) {
        if (trailerHeaders == null) {
            trailerHeaders = new ArrayList<>();
        }
        trailerHeaders.add(header);
    }

    void appendHeaderBlockFragment(byte[] hbf) {
        if (headerBlock == null) {
            headerBlock = ByteBuffer.allocate(4096);
        } else if (headerBlock.remaining() < hbf.length) {
            ByteBuffer newHeaderBlock = ByteBuffer.allocate(headerBlock.capacity() + hbf.length);
            headerBlock.flip();
            newHeaderBlock.put(headerBlock);
            headerBlock = newHeaderBlock;
        }
        headerBlock.put(hbf);
    }

    void streamEndHeaders() throws IOException {
        if (headerBlock != null) {
            headerBlock.flip();
            headers = new ArrayList<>();
            HeaderHandler handler = new HeaderHandler() {
                @Override public void header(Header header) {
                    headers.add(header);
                }
            };
            connection.hpackDecoder.decode(headerBlock, handler);
            headerBlock = null;
        }
        if (state == State.IDLE) {
            if (pushPromise) {
                state = State.RESERVED_REMOTE;
            } else {
                state = State.OPEN;
            }
        } else if (state == State.RESERVED_REMOTE) {
            state = State.HALF_CLOSED_LOCAL;
        }
        if (headers != null) {
            boolean isUpgrade = false;
            Collection<String> upgrade = new LinkedHashSet<>();
            SettingsFrame settingsFrame = null;
            for (Iterator<Header> i = headers.iterator(); i.hasNext(); ) {
                Header header = i.next();
                String name = header.getName();
                String value = header.getValue();
                if (":method".equals(name)) {
                    if (NO_REQUEST_BODY.contains(value)) {
                        contentLength = 0;
                    }
                } else if (connection.version != HTTPVersion.HTTP_2_0) { // HTTP/1
                    if ("Connection".equalsIgnoreCase(name)) {
                        if ("close".equalsIgnoreCase(value)) {
                            closeConnection = true;
                        } else {
                            for (String v : value.split(",")) {
                                v = v.trim();
                                if ("Upgrade".equalsIgnoreCase(v)) {
                                    isUpgrade = true;
                                }
                            } 
                        }
                    } else if ("Content-Length".equalsIgnoreCase(name)) {
                        try {
                            contentLength = Long.parseLong(value);
                        } catch (NumberFormatException e) {
                            contentLength = -1L;
                        }
                    } else if ("Transfer-Encoding".equalsIgnoreCase(name) && "chunked".equals(value)) {
                        contentLength = Integer.MAX_VALUE;
                        chunked = true;
                        chunkLineInput = new ChunkLineInput();
                        i.remove(); // do not pass this on to stream implementation
                    } else if ("Upgrade".equalsIgnoreCase(name)) {
                        for (String v : value.split(",")) {
                            upgrade.add(v.trim());
                        }
                    } else if ("HTTP2-Settings".equalsIgnoreCase(name)) {
                        try {
                            Base64.Decoder decoder = Base64.getUrlDecoder();
                            byte[] settings = decoder.decode(value);
                            try {
                                settingsFrame = new SettingsFrame(0, settings);
                            } catch (ProtocolException e) {
                                String message = HTTPConnection.L10N.getString("err.decode_http2_settings");
                                HTTPConnection.LOGGER.log(Level.SEVERE, message, e);
                            }
                        } catch (IllegalArgumentException e) {
                            // Invalid base64 in HTTP2-Settings header - ignore it
                            HTTPConnection.LOGGER.log(Level.WARNING, "Invalid base64 in HTTP2-Settings header: " + value, e);
                        }
                    }
                }
            }
            if (isUpgrade) {
                this.upgrade = upgrade;
                this.settingsFrame = settingsFrame;
            }
        }
        if (upgrade != null && upgrade.contains("h2c") && settingsFrame != null) {
            // Upgrading the connection is handled specially by the
            // connection and not delivered to the stream implementation
        } else {
            endHeaders(headers);
        }
    }

    /**
     * Informs the stream that the request headers are complete.
     * @param headers the headers
     */
    protected void endHeaders(Collection<Header> headers) {
    }

    /**
     * Receive request body data from the specified input buffer.
     * If this is chunked encoding, read into the chunk buffer and process
     * in chunks.
     */
    void appendRequestBody(ByteBuffer buf) {
        if (chunked) {
            chunkLineInput.dataReceived(buf);
            while (chunkLineInput.hasRemaining()) {
                String line;
                try {
                    line = chunkLineInput.readLine(HTTPConnection.US_ASCII_DECODER);
                } catch (IOException e) {
                    connection.sendStreamError(this, 400);
                    return;
                }
                if (seenLastChunk) {
                    // We are now in trailer header mode or EOF
                    if (line == null) {
                        return; // not enough data to read header line
                    } else if (line.length() == 0) { // end of request
                    } else { // add header line
                             // NB header values in trailer headers may not be
                             // folded, so we don't have to worry about value
                             // state management over multiple lines. The whole
                             // header must be on one line.
                        int ci = line.indexOf(':');
                        if (ci < 1) {
                            connection.sendStreamError(this, 400);
                            return;
                        }
                        String name = line.substring(0, ci);
                        String value = line.substring(ci + 1).trim();
                        addTrailerHeader(new Header(name, value));
                    }
                } else {
                    if (line == null) {
                        return; // not enough data to read chunk size
                    }
                    try {
                        int chunkSize = Integer.parseInt(line, 16);
                        if (!chunkLineInput.hasChunk(chunkSize)) { // underflow
                            return;
                        }
                        byte[] chunk = null;
                        if (chunkSize > 0) {
                            chunk = new byte[chunkSize];
                        }
                        try {
                            chunkLineInput.getChunk(chunk);
                        } catch (IOException e) {
                            connection.sendStreamError(this, 400);
                            return;
                        }
                        if (chunkSize == 0) { // end of chunks
                            contentLength = requestBodyBytesReceived;
                            chunkLineInput.compact(); // prepare for headers
                            seenLastChunk = true;
                            return;
                        } else {
                            chunkLineInput.compact(); // prepare for next chunk
                            requestBodyBytesReceived += chunk.length;
                            receiveRequestBody(chunk);
                        }
                    } catch (NumberFormatException e) {
                        connection.sendStreamError(this, 400);
                        return;
                    }
                }
            }
        } else {
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            requestBodyBytesReceived += bytes.length;
            receiveRequestBody(bytes);
        }
    }

    /**
     * Receive request body data from the specified frame data.
     * No chunked encoding applies.
     */
    void appendRequestBody(byte[] bytes) {
        requestBodyBytesReceived += bytes.length;
        receiveRequestBody(bytes);
    }

    /**
     * Receive request body data. This method may be called more than once
     * for a single stream (request).
     * @param buf the request body data
     */
    protected void receiveRequestBody(byte[] buf) {
    }

    void streamEndRequest() {
        state = State.HALF_CLOSED_REMOTE;
        endRequest();
    }

    /**
     * Informs the stream that the request body is complete.
     */
    protected void endRequest() {
    }

    /**
     * Sends response headers for this stream.
     * This will include a Status-Line for HTTP/1 streams.
     * For HTTP/2 streams, the status will be added to the headers
     * automatically.
     * This method should only be called once. It corresponds to
     * "committing" the response.
     * @param statusCode the status code of the response
     * @param headers the headers to send in the response
     * @param endStream if no response data will be sent and this is a
     * complete response
     */
    protected final void sendResponseHeaders(int statusCode, List<Header> headers, boolean endStream) throws ProtocolException {
        if (state != State.HALF_CLOSED_REMOTE && state != State.OPEN) {
            throw new ProtocolException(String.format("Invalid state: %s", state));
        }

        // Standard HTTP headers
        headers.add(new Header("Server", "gumdrop/" + SelectorLoop.VERSION));
        headers.add(new Header("Date", dateFormat.format(new Date())));
        if (closeConnection) {
            headers.add(new Header("Connection", "close"));
        }

        connection.sendResponseHeaders(streamId, statusCode, headers, endStream);
        if (endStream) {
            if (state == State.HALF_CLOSED_REMOTE) {
                state = State.CLOSED; // normal request termination
                timestampCompleted = System.currentTimeMillis();
            } else {
                state = State.HALF_CLOSED_LOCAL;
            }
        }
    }

    /**
     * Sends response body data for this stream.
     * This must be called after sendResponseHeaders.
     * This method may be called multiple times. The caller should ensure
     * that the total number of bytes supplied via this method add up to
     * the number specified for the Content-Length.
     * @param buf response body contents
     * @param endStream if this is the last response body data that will be
     * sent
     */
    protected final void sendResponseBody(byte[] buf, boolean endStream) throws ProtocolException {
        if (state != State.HALF_CLOSED_REMOTE && state != State.OPEN) {
            throw new ProtocolException(String.format("Invalid state: %s", state));
        }
        connection.sendResponseBody(streamId, buf, endStream);
        if (endStream) {
            if (state == State.HALF_CLOSED_REMOTE) {
                state = State.CLOSED; // normal request termination
                timestampCompleted = System.currentTimeMillis();
            } else {
                state = State.HALF_CLOSED_LOCAL;
            }
        }
    }

    /**
     * Sends response body data for this stream.
     * This must be called after sendResponseHeaders.
     * This method may be called multiple times. The caller should ensure
     * that the total number of bytes supplied via this method add up to
     * the number specified for the Content-Length.
     * @param buf response body contents
     * @param endStream if this is the last response body data that will be
     * sent
     */
    protected final void sendResponseBody(ByteBuffer buf, boolean endStream) throws ProtocolException {
        if (state != State.HALF_CLOSED_REMOTE && state != State.OPEN) {
            throw new ProtocolException(String.format("Invalid state: %s", state));
        }
        connection.sendResponseBody(streamId, buf, endStream);
        if (endStream) {
            if (state == State.HALF_CLOSED_REMOTE) {
                state = State.CLOSED; // normal request termination
                timestampCompleted = System.currentTimeMillis();
            } else {
                state = State.HALF_CLOSED_LOCAL;
            }
        }
    }

    /**
     * The stream implementation should send an error response with the
     * specified status code.
     * @param statusCode the HTTP status code
     */
    protected void sendError(int statusCode) throws ProtocolException {
        if (state == State.IDLE) {
            state = State.OPEN;
        }
        List<Header> headers = new ArrayList<>();
        sendResponseHeaders(statusCode, headers, true);
    }

    void streamClose() {
        state = State.CLOSED;
        timestampCompleted = System.currentTimeMillis();
        close();
    }

    /**
     * Informs the stream that it has been closed.
     */
    protected void close() {
    }

    /**
     * Close the connection after writing all pending data.
     */
    public void sendCloseConnection() {
        connection.send(null);
    }

    /**
     * Get a header value from the request headers.
     *
     * @param name the header name (case-insensitive)
     * @return the header value, or null if not found
     */
    protected String getHeader(String name) {
        if (headers != null) {
            for (Header header : headers) {
                if (header.getName().equalsIgnoreCase(name)) {
                    return header.getValue();
                }
            }
        }
        return null;
    }

    /**
     * Authenticate the request using the configured authentication provider.
     * 
     * @return authentication result, or null if no authentication is configured
     */
    public HTTPAuthenticationProvider.AuthenticationResult authenticateRequest() {
        HTTPAuthenticationProvider provider = connection.getAuthenticationProvider();
        if (provider == null) {
            return null; // No authentication configured
        }

        String authHeader = getHeader("Authorization");
        return provider.authenticate(authHeader);
    }

    /**
     * Send 401 Unauthorized response with appropriate WWW-Authenticate challenge.
     *
     * @throws ProtocolException if unable to send the response
     */
    public void sendAuthenticationChallenge() throws ProtocolException {
        HTTPAuthenticationProvider provider = connection.getAuthenticationProvider();
        if (provider == null) {
            sendError(500); // Server misconfiguration
            return;
        }

        String challenge = provider.generateChallenge();
        if (challenge == null) {
            sendError(500); // Provider misconfiguration
            return;
        }

        List<Header> headers = new ArrayList<>();
        headers.add(new Header("WWW-Authenticate", challenge));
        sendResponseHeaders(401, headers, true);
    }

    /**
     * Check if authentication is required for this request.
     * 
     * @return true if authentication is required, false otherwise
     */
    protected boolean isAuthenticationRequired() {
        HTTPAuthenticationProvider provider = connection.getAuthenticationProvider();
        return provider != null && provider.isAuthenticationRequired();
    }

}
