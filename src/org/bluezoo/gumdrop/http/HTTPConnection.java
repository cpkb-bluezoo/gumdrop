/*
 * HTTPConnection.java
 * Copyright (C) 2005, 2013, 2025 Chris Burdess
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

package org.bluezoo.gumdrop.http;

import org.bluezoo.gumdrop.LineBasedConnection;
import org.bluezoo.gumdrop.ConnectionInfo;
import org.bluezoo.gumdrop.TLSInfo;

import java.io.IOException;
import java.net.ProtocolException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.mail.internet.MimeUtility;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import org.bluezoo.gumdrop.http.hpack.Decoder;
import org.bluezoo.util.ByteArrays;
import org.bluezoo.gumdrop.http.hpack.Encoder;

/**
 * Connection handler for the HTTP protocol.
 * This manages potentially multiple requests within a single TCP
 * connection. Provides default 404 behavior when no specific
 * Stream implementation is provided.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see https://www.rfc-editor.org/rfc/rfc7230
 * @see https://www.rfc-editor.org/rfc/rfc7540
 */
public class HTTPConnection extends LineBasedConnection {

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.http.L10N");
    static final Logger LOGGER = Logger.getLogger(HTTPConnection.class.getName());

    static final Charset US_ASCII = Charset.forName("US-ASCII");
    static final CharsetDecoder US_ASCII_DECODER = US_ASCII.newDecoder();
    static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");
    static final CharsetDecoder ISO_8859_1_DECODER = ISO_8859_1.newDecoder();

    // Default HTTP methods - used when handler factory doesn't specify custom methods
    private static final Set<String> DEFAULT_METHODS = new HashSet<String>(Arrays.asList(
        "GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "OPTIONS", "TRACE", "PATCH",
        "PRI", // HTTP/2 connection preface
        "PROPFIND", "PROPPATCH", "MKCOL", "COPY", "MOVE", "LOCK", "UNLOCK" // WebDAV methods
    ));

    /**
     * HTTP version being used by the connection.
     */
    protected HTTPVersion version = HTTPVersion.HTTP_1_0;

    /**
     * Authentication provider for this connection.
     */
    private HTTPAuthenticationProvider authenticationProvider;

    /**
     * Handler factory for creating request handlers.
     */
    private HTTPRequestHandlerFactory handlerFactory;

    /**
     * Connection state for protocol parsing.
     */
    private enum State {
        /** Awaiting HTTP/1 request line */
        REQUEST_LINE,
        /** Reading HTTP/1 headers */
        HEADER,
        /** Reading HTTP/1 body with Content-Length */
        BODY,
        /** Reading chunk size line in chunked transfer */
        BODY_CHUNKED_SIZE,
        /** Reading chunk data in chunked transfer */
        BODY_CHUNKED_DATA,
        /** Reading trailer headers after chunked body */
        BODY_CHUNKED_TRAILER,
        /** Reading body until connection close (HTTP/1.0) */
        BODY_UNTIL_CLOSE,
        /** Awaiting HTTP/2 PRI continuation */
        PRI,
        /** Awaiting initial SETTINGS frame after PRI */
        PRI_SETTINGS,
        /** HTTP/2 frame mode */
        HTTP2,
        /** HTTP/2 awaiting CONTINUATION frames */
        HTTP2_CONTINUATION,
        /** WebSocket frame mode (TODO: move frame parsing to websocket package) */
        WEBSOCKET
    }
    
    // Maximum line length for request-line and headers (RFC 7230 doesn't mandate, but 8KB is common)
    private static final int MAX_LINE_LENGTH = 8192;

    // CRLF length constant
    private static final int CRLF_LENGTH = 2;

    // HTTP/2 frame constants (RFC 7540)
    private static final int FRAME_HEADER_LENGTH = 9;           // Frame header is always 9 bytes
    private static final int PRIORITY_FRAME_LENGTH = 5;         // PRIORITY payload is 5 bytes
    private static final int RST_STREAM_FRAME_LENGTH = 4;       // RST_STREAM payload is 4 bytes
    private static final int SETTINGS_ENTRY_LENGTH = 6;         // Each SETTINGS entry is 6 bytes
    private static final int PING_FRAME_LENGTH = 8;             // PING payload is 8 bytes
    private static final int GOAWAY_MIN_LENGTH = 8;             // GOAWAY minimum payload is 8 bytes
    private static final int WINDOW_UPDATE_FRAME_LENGTH = 4;    // WINDOW_UPDATE payload is 4 bytes
    private static final int PRI_CONTINUATION_LENGTH = 8;       // "\r\nSM\r\n\r\n" is 8 bytes

    // HTTP/2 default settings (RFC 7540 section 6.5.2)
    private static final int DEFAULT_HEADER_TABLE_SIZE = 4096;
    private static final int DEFAULT_INITIAL_WINDOW_SIZE = 65535;
    private static final int DEFAULT_MAX_FRAME_SIZE = 16384;

    // Stream ID constants (RFC 7540 section 5.1.1)
    private static final int INITIAL_CLIENT_STREAM_ID = 1;      // Client-initiated streams are odd
    private static final int INITIAL_SERVER_STREAM_ID = 2;      // Server-initiated streams are even

    // CharBuffer allocation sizes
    private static final int HEADER_VALUE_BUFFER_SIZE = 4096;

    // HPACK encoding buffer initial size
    private static final int HPACK_ENCODE_BUFFER_SIZE = 8192;

    private State state = State.REQUEST_LINE;
    private CharBuffer charBuffer; // Buffer for decoding lines

    // HTTP/1
    private String headerName;
    private CharBuffer headerValue;
    
    // Chunked transfer state
    private int currentChunkSize = 0;      // Size of current chunk being read
    private int chunkBytesRemaining = 0;   // Bytes remaining in current chunk
    private ByteBuffer response;

    // HTTP/2 settings (initialized to defaults per RFC 7540 section 6.5.2)
    int headerTableSize = DEFAULT_HEADER_TABLE_SIZE;
    boolean enablePush = true;
    int maxConcurrentStreams = Integer.MAX_VALUE;
    int initialWindowSize = DEFAULT_INITIAL_WINDOW_SIZE;
    int maxFrameSize = DEFAULT_MAX_FRAME_SIZE;
    int maxHeaderListSize = Integer.MAX_VALUE;

    // HTTP/2 header decoder and encoder
    Decoder hpackDecoder;
    Encoder hpackEncoder;

    private int clientStreamId; // synthesized stream ID for HTTP/1
    private int serverStreamId = INITIAL_SERVER_STREAM_ID; // server-initiated streams (even numbers)
    protected final Map<Integer,Stream> streams;
    private int continuationStream;
    private boolean continuationEndStream; // if the continuation should end stream after end of headers
    protected final Set<Integer> activeStreams = new TreeSet<>();
    
    // Stream cleanup management
    private long lastStreamCleanup = 0L;
    private static final long STREAM_CLEANUP_INTERVAL_MS = 30_000L; // 30 seconds
    private static final long STREAM_RETENTION_MS = 30_000L; // Keep closed streams for 30 seconds
    
    // HTTP/2 frame padding configuration
    private final int framePadding; // 0-255 bytes padding for server-oriented frames
    
    // WebSocket mode - stream that has been upgraded to WebSocket protocol
    private int webSocketStreamId = -1;
    
    // TODO stream priority - use PriorityAwareHTTPConnector for priority support

    protected HTTPConnection(SocketChannel channel, SSLEngine engine, boolean secure) {
        this(channel, engine, secure, 0); // Default: no padding
    }
    
    protected HTTPConnection(SocketChannel channel, SSLEngine engine, boolean secure, int framePadding) {
        super(engine, secure);
        this.channel = channel;
        this.framePadding = framePadding;
        charBuffer = CharBuffer.allocate(MAX_LINE_LENGTH);
        if (engine != null) {
            // Configure this engine with ALPN
            SSLParameters sp = engine.getSSLParameters();
            sp.setApplicationProtocols(new String[] { "h2", "http/1.1"}); // h2 and http/1.1
            engine.setSSLParameters(sp);
        }
        streams = new TreeMap<>();
        clientStreamId = INITIAL_CLIENT_STREAM_ID;
    }

    @Override
    public void init() throws IOException {
        super.init();
        // HTTP connections don't send initial banners

        // Record connection opened metric
        HTTPServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            metrics.connectionOpened();
        }
    }

    /**
     * Gets the HTTP server metrics from the connector.
     * Package-private for use by Stream.
     *
     * @return the metrics or null if not available
     */
    HTTPServerMetrics getServerMetrics() {
        if (connector instanceof HTTPServer) {
            return ((HTTPServer) connector).getMetrics();
        }
        return null;
    }

    protected void handshakeComplete(String protocol) {
        // Whether we negotiated h2 or not, the client will still send a PRI
        // so we will change state to HTTP2 based on that, not here
    }

    /**
     * Returns the URI scheme used by this connection.
     */
    public String getScheme() {
        return secure ? "https" : "http";
    }

    /**
     * Returns the version of the HTTP protocol this connection is currently
     * using.
     */
    public HTTPVersion getVersion() {
        return version;
    }

    /**
     * Sets the authentication provider for this connection.
     *
     * @param provider the authentication provider, or null to disable authentication
     */
    public void setAuthenticationProvider(HTTPAuthenticationProvider provider) {
        this.authenticationProvider = provider;
    }

    /**
     * Returns the authentication provider for this connection.
     *
     * @return the authentication provider, or null if authentication is not configured
     */
    public HTTPAuthenticationProvider getAuthenticationProvider() {
        return authenticationProvider;
    }

    /**
     * Sets the handler factory for this connection.
     *
     * @param factory the handler factory, or null to use default 404 behavior
     */
    public void setHandlerFactory(HTTPRequestHandlerFactory factory) {
        this.handlerFactory = factory;
    }

    /**
     * Returns the handler factory for this connection.
     *
     * @return the handler factory, or null if not configured
     */
    public HTTPRequestHandlerFactory getHandlerFactory() {
        return handlerFactory;
    }

    /**
     * Checks if the given HTTP method is supported.
     *
     * <p>If a handler factory is configured and provides custom methods via
     * {@link HTTPRequestHandlerFactory#getSupportedMethods()}, those are used.
     * Otherwise, the default set of methods is used.
     *
     * @param method the HTTP method to check (uppercase)
     * @return true if the method is supported
     */
    private boolean isMethodSupported(String method) {
        if (handlerFactory != null) {
            Set<String> customMethods = handlerFactory.getSupportedMethods();
            if (customMethods != null) {
                return customMethods.contains(method);
            }
        }
        return DEFAULT_METHODS.contains(method);
    }

    /**
     * Returns connection info for use by Stream.
     * Package-private accessor for HTTPResponseState implementation.
     *
     * @return the connection info
     */
    ConnectionInfo getConnectionInfoForStream() {
        return createConnectionInfo();
    }

    /**
     * Returns TLS info for use by Stream.
     * Package-private accessor for HTTPResponseState implementation.
     *
     * @return the TLS info, or null if not secure
     */
    TLSInfo getTLSInfoForStream() {
        return createTLSInfo();
    }

    /**
     * Sends RST_STREAM to cancel a stream.
     * Package-private accessor for HTTPResponseState implementation.
     *
     * @param streamId the stream ID
     * @param errorCode the error code (0x8 for CANCEL)
     */
    void sendRstStream(int streamId, int errorCode) {
        sendErrorFrame(errorCode, streamId);
    }

    /**
     * Received data from the client.
     * Routes data to appropriate handler based on current protocol state.
     * 
     * @param buf the receive buffer
     */
    @Override
    public void receive(ByteBuffer buf) {
        // Process data based on current state
        while (buf.hasRemaining()) {
            int positionBefore = buf.position();
            
            switch (state) {
                // Line-based states
                case REQUEST_LINE:
                case HEADER:
                case BODY_CHUNKED_SIZE:
                case BODY_CHUNKED_TRAILER:
                    receiveLine(buf);
                    break;
                    
                // Body states
                case BODY:
                    receiveBody(buf);
                    break;
                case BODY_CHUNKED_DATA:
                    receiveChunkedData(buf);
                    break;
                case BODY_UNTIL_CLOSE:
                    receiveBodyUntilClose(buf);
                    break;
                    
                // HTTP/2 states
                case PRI:
                    receivePri(buf);
                    break;
                case PRI_SETTINGS:
                case HTTP2:
                case HTTP2_CONTINUATION:
                    receiveFrameData(buf);
                    break;
                    
                // WebSocket state (TODO: move frame parsing to websocket package)
                case WEBSOCKET:
                    receiveWebSocket(buf);
                    return; // WebSocket consumes all remaining data
            }
            
            // If no data was consumed, break to avoid infinite loop (waiting for more data)
            if (buf.position() == positionBefore) {
                break;
            }
        }
    }

    /**
     * Returns false when transitioning out of line-based mode.
     */
    @Override
    protected boolean continueLineProcessing() {
        return state == State.REQUEST_LINE || 
               state == State.HEADER || 
               state == State.BODY_CHUNKED_SIZE ||
               state == State.BODY_CHUNKED_TRAILER;
    }

    /**
     * Called when a complete CRLF-terminated line has been received.
     * Routes to appropriate handler based on current state.
     *
     * @param line buffer containing the complete line including CRLF
     */
    @Override
    protected void lineReceived(ByteBuffer line) {
        switch (state) {
            case REQUEST_LINE:
                processRequestLine(line);
                break;
            case HEADER:
                processHeaderLine(line);
                break;
            case BODY_CHUNKED_SIZE:
                processChunkSizeLine(line);
                break;
            case BODY_CHUNKED_TRAILER:
                processTrailerLine(line);
                break;
            default:
                break;
        }
    }

    /**
     * Processes an HTTP/1 request line.
     */
    private void processRequestLine(ByteBuffer line) {
        Stream stream = getStream(clientStreamId);
        
        // Check line length (including CRLF)
        int lineLength = line.remaining();
        if (lineLength > MAX_LINE_LENGTH + CRLF_LENGTH) {
            sendStreamError(stream, 414); // URI Too Long
            return;
        }

        // Decode line
        charBuffer.clear();
        US_ASCII_DECODER.reset();
        CoderResult result = US_ASCII_DECODER.decode(line, charBuffer, true);
        if (result.isError()) {
            sendStreamError(stream, 400); // Bad Request
            return;
        }
        charBuffer.flip();

        // Remove CRLF
        int len = charBuffer.limit();
        if (len >= CRLF_LENGTH && charBuffer.get(len - 2) == '\r' && charBuffer.get(len - 1) == '\n') {
            charBuffer.limit(len - CRLF_LENGTH);
        }
        String lineStr = charBuffer.toString();

        // Parse request line: METHOD SP REQUEST-TARGET SP HTTP-VERSION
        int mi = lineStr.indexOf(' ', 1);
        int ui = (mi > 0) ? lineStr.indexOf(' ', mi + 2) : -1;
        if (mi == -1 || ui == -1) {
            sendStreamError(stream, 400); // Bad Request
            return;
        }
        String method = lineStr.substring(0, mi);
        String requestTarget = lineStr.substring(mi + 1, ui);
        String versionStr = lineStr.substring(ui + 1);
        this.version = HTTPVersion.fromString(versionStr);

        // Validate method
        if (!HTTPUtils.isValidMethod(method)) {
            sendStreamError(stream, 400); // Bad Request
            return;
        }
        if (!isMethodSupported(method)) {
            sendStreamError(stream, 501); // Not Implemented
            return;
        }

        // Validate request target
        if (!HTTPUtils.isValidRequestTarget(requestTarget)) {
            sendStreamError(stream, 400); // Bad Request
            return;
        }

        // Handle version-specific behavior
        switch (this.version) {
            case UNKNOWN:
                sendStreamError(stream, 505); // HTTP Version Not Supported
                return;
            case HTTP_2_0:
                if ("PRI".equals(method) && "*".equals(requestTarget)) {
                    state = State.PRI;
                } else {
                    sendStreamError(stream, 400); // Bad Request
                }
                return;
            case HTTP_1_0:
                stream.closeConnection = true;
                // fall through
            default: // HTTP_1_1
                stream.addHeader(new Header(":method", method));
                stream.addHeader(new Header(":path", requestTarget));
                stream.addHeader(new Header(":scheme", secure ? "https" : "http"));
                headerName = null;
                headerValue = CharBuffer.allocate(HEADER_VALUE_BUFFER_SIZE);
                state = State.HEADER;
        }
    }

    /**
     * Processes an HTTP/1 header line.
     */
    private void processHeaderLine(ByteBuffer line) {
        Stream stream = getStream(clientStreamId);

        // Check line length (including CRLF)
        int lineLength = line.remaining();
        if (lineLength > MAX_LINE_LENGTH + CRLF_LENGTH) {
            sendStreamError(stream, 431); // Request Header Fields Too Large
            return;
        }

        // Decode line - use ISO-8859-1 for legacy client compatibility
        charBuffer.clear();
        ISO_8859_1_DECODER.reset();
        CoderResult result = ISO_8859_1_DECODER.decode(line, charBuffer, true);
        if (result.isError()) {
            sendStreamError(stream, 400); // Bad Request
            return;
        }
        charBuffer.flip();

        // Remove CRLF
        int len = charBuffer.limit();
        if (len >= CRLF_LENGTH && charBuffer.get(len - 2) == '\r' && charBuffer.get(len - 1) == '\n') {
            charBuffer.limit(len - CRLF_LENGTH);
        }
        String lineStr = charBuffer.toString();

        if (lineStr.length() == 0) {
            // End of headers
            endHeaders(stream);
        } else {
            // Header line
            char c0 = lineStr.charAt(0);
            if (headerName != null && (c0 == ' ' || c0 == '\t')) {
                // Folded continuation
                if (c0 == '\t') {
                    lineStr = " " + lineStr.substring(1);
                }
                appendHeaderValue(lineStr);
            } else {
                // New header
                int ci = lineStr.indexOf(':');
                if (ci < 1) {
                    sendStreamError(stream, 400);
                    return;
                }
                String h = lineStr.substring(0, ci);
                if (headerName != null) {
                    headerValue.flip();
                    String v = headerValue.toString();
                    try {
                        stream.addHeader(new Header(headerName, v));
                    } catch (IllegalArgumentException e) {
                        sendStreamError(stream, 400);
                        return;
                    }
                }
                headerName = h;
                headerValue.clear();
                appendHeaderValue(lineStr.substring(ci + 1));
            }
        }
    }

    /**
     * Called when all headers have been received.
     */
    private void endHeaders(Stream stream) {
        // Add last header if pending
        if (headerName != null) {
            headerValue.flip();
            String v = headerValue.toString();
            stream.addHeader(new Header(headerName, v));
            headerName = null;
            headerValue = null;
        }

        // HTTP/1.1 requires Host header
        if (this.version == HTTPVersion.HTTP_1_1) {
            boolean hasHost = false;
            for (Header header : stream.getHeaders()) {
                if (header.getName().equalsIgnoreCase("host") ||
                    header.getName().equals(":authority")) {
                    hasHost = true;
                    break;
                }
            }
            if (!hasHost) {
                sendStreamError(stream, 400); // Bad Request
                return;
            }
        }

        try {
            stream.streamEndHeaders();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
        }

        // Check for HTTP/2 upgrade
        if (stream.upgrade != null && stream.upgrade.contains("h2c") && stream.settingsFrame != null) {
            Headers responseHeaders = new Headers();
            responseHeaders.add("Connection", "Upgrade");
            responseHeaders.add("Upgrade", "h2c");
            sendResponseHeaders(clientStreamId, 101, responseHeaders, true);
            state = State.REQUEST_LINE;
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Sent 101 Switching Protocols, waiting for client preface");
            }
            return;
        }

        // Determine next state based on body handling
        long contentLength = stream.getContentLength();
        boolean chunked = stream.isChunked();
        
        if (contentLength == 0L) {
            // No body
            stream.streamEndRequest();
            // Note: Do NOT close stream here even if Connection: close is set.
            // The stream remains in HALF_CLOSED_REMOTE state so the handler can send a response.
            // The connection will be closed after the response is complete.
            state = State.REQUEST_LINE;
            clientStreamId += 2;
        } else if (chunked) {
            // Chunked transfer encoding
            state = State.BODY_CHUNKED_SIZE;
        } else if (contentLength > 0L) {
            // Content-Length body
            state = State.BODY;
        } else if (this.version == HTTPVersion.HTTP_1_0) {
            // HTTP/1.0 without Content-Length: read until close
            state = State.BODY_UNTIL_CLOSE;
        } else {
            // HTTP/1.1 without Content-Length or chunked: no body expected
            sendStreamError(stream, 411); // Length Required
        }
    }

    /**
     * Processes a chunk size line in chunked transfer encoding.
     */
    private void processChunkSizeLine(ByteBuffer line) {
        Stream stream = getStream(clientStreamId);

        // Decode line
        charBuffer.clear();
        US_ASCII_DECODER.reset();
        CoderResult result = US_ASCII_DECODER.decode(line, charBuffer, true);
        if (result.isError()) {
            sendStreamError(stream, 400);
            return;
        }
        charBuffer.flip();

        // Remove CRLF
        int len = charBuffer.limit();
        if (len >= CRLF_LENGTH && charBuffer.get(len - 2) == '\r' && charBuffer.get(len - 1) == '\n') {
            charBuffer.limit(len - CRLF_LENGTH);
        }
        String lineStr = charBuffer.toString();

        // Parse chunk size (may have chunk extension after semicolon)
        int semi = lineStr.indexOf(';');
        String sizeStr = (semi > 0) ? lineStr.substring(0, semi) : lineStr;

        try {
            currentChunkSize = Integer.parseInt(sizeStr.trim(), 16);
            chunkBytesRemaining = currentChunkSize;
        } catch (NumberFormatException e) {
            sendStreamError(stream, 400);
            return;
        }

        if (currentChunkSize == 0) {
            // Last chunk - expect trailer headers
            state = State.BODY_CHUNKED_TRAILER;
        } else {
            // Expect chunk data
            state = State.BODY_CHUNKED_DATA;
        }
    }

    /**
     * Processes a trailer header line after chunked body.
     */
    private void processTrailerLine(ByteBuffer line) {
        Stream stream = getStream(clientStreamId);

        // Decode line
        charBuffer.clear();
        US_ASCII_DECODER.reset();
        CoderResult result = US_ASCII_DECODER.decode(line, charBuffer, true);
        if (result.isError()) {
            sendStreamError(stream, 400);
            return;
        }
        charBuffer.flip();

        // Remove CRLF
        int len = charBuffer.limit();
        if (len >= CRLF_LENGTH && charBuffer.get(len - 2) == '\r' && charBuffer.get(len - 1) == '\n') {
            charBuffer.limit(len - CRLF_LENGTH);
        }
        String lineStr = charBuffer.toString();

        if (lineStr.length() == 0) {
            // End of trailers - request complete
            stream.streamEndRequest();
            // Note: Do NOT close stream here even if Connection: close is set.
            // The stream remains in HALF_CLOSED_REMOTE state so the handler can send a response.
            // The connection will be closed after the response is complete.
            state = State.REQUEST_LINE;
            clientStreamId += 2;
        } else {
            // Trailer header - could add to stream if needed
            // For now, we ignore trailer headers
        }
    }

    /**
     * Receives Content-Length body data.
     */
    private void receiveBody(ByteBuffer buf) {
        Stream stream = getStream(clientStreamId);
        long contentLength = stream.getContentLength();
        
        if (contentLength == -1L) {
            sendStreamError(stream, 411); // Length Required
            return;
        }

        int available = buf.remaining();
        if (available < 1) {
            return; // underflow
        }

        long needed = stream.getRequestBodyBytesNeeded();
        if ((long) available > needed) {
            available = (int) needed;
        }

        // Create slice for the body portion
        int savedLimit = buf.limit();
        buf.limit(buf.position() + available);
        stream.receiveRequestBody(buf);
        buf.limit(savedLimit);

        if (stream.getRequestBodyBytesNeeded() == 0L) {
            // Body complete
            stream.streamEndRequest();
            // Note: Do NOT close stream here even if Connection: close is set.
            // The stream remains in HALF_CLOSED_REMOTE state so the handler can send a response.
            // The connection will be closed after the response is complete.
            state = State.REQUEST_LINE;
            clientStreamId += 2;
        }
    }

    /**
     * Receives chunked body data.
     */
    private void receiveChunkedData(ByteBuffer buf) {
        Stream stream = getStream(clientStreamId);

        int available = buf.remaining();
        if (available < 1) {
            return; // underflow
        }

        // Consume up to chunkBytesRemaining
        int toRead = Math.min(available, chunkBytesRemaining);
        
        if (toRead > 0) {
            int savedLimit = buf.limit();
            buf.limit(buf.position() + toRead);
            stream.receiveRequestBody(buf);
            buf.limit(savedLimit);
            
            chunkBytesRemaining -= toRead;
        }

        if (chunkBytesRemaining == 0) {
            // Chunk data complete - need to consume trailing CRLF
            if (buf.remaining() >= CRLF_LENGTH) {
                byte cr = buf.get();
                byte lf = buf.get();
                if (cr != '\r' || lf != '\n') {
                    sendStreamError(stream, 400);
                    return;
                }
                // Ready for next chunk size line
                state = State.BODY_CHUNKED_SIZE;
            }
            // If not enough bytes for CRLF, we'll get them on next receive
        }
    }

    /**
     * Receives body data until connection close (HTTP/1.0 without Content-Length).
     */
    private void receiveBodyUntilClose(ByteBuffer buf) {
        Stream stream = getStream(clientStreamId);
        
        if (buf.hasRemaining()) {
            stream.receiveRequestBody(buf);
        }
        // Body continues until connection is closed
    }

    /**
     * Receives HTTP/2 PRI preface continuation (\r\nSM\r\n\r\n).
     */
    private void receivePri(ByteBuffer buf) {
        Stream stream = getStream(clientStreamId);
        
        if (buf.remaining() < PRI_CONTINUATION_LENGTH) {
            return; // underflow
        }

        byte[] smt = new byte[PRI_CONTINUATION_LENGTH];
        buf.get(smt);
        if (smt[0] != '\r' || smt[1] != '\n' || smt[2] != 'S' || smt[3] != 'M' ||
            smt[4] != '\r' || smt[5] != '\n' || smt[6] != '\r' || smt[7] != '\n') {
            sendStreamError(stream, 400);
            return;
        }
        state = State.PRI_SETTINGS;
    }

    /**
     * Receives HTTP/2 frame data.
     * Parses complete frames from the buffer and dispatches to appropriate handlers.
     * Leaves unconsumed data (partial frames) at the buffer position.
     */
    private void receiveFrameData(ByteBuffer buf) {
        while (buf.hasRemaining()) {
            // Need at least FRAME_HEADER_LENGTH bytes for frame header
            if (buf.remaining() < FRAME_HEADER_LENGTH) {
                return; // underflow
            }

            // Parse frame header (peek without consuming yet)
            int pos = buf.position();
            int length = ((int) buf.get(pos) & 0xff) << 16
                | ((int) buf.get(pos + 1) & 0xff) << 8
                | ((int) buf.get(pos + 2) & 0xff);
            int type = ((int) buf.get(pos + 3) & 0xff);
            int flags = ((int) buf.get(pos + 4) & 0xff);
            int streamId = ((int) buf.get(pos + 5) & 0x7f) << 24 // mask out reserved bit
                | ((int) buf.get(pos + 6) & 0xff) << 16
                | ((int) buf.get(pos + 7) & 0xff) << 8
                | ((int) buf.get(pos + 8) & 0xff);

            // Check if we have complete frame (header + payload)
            if (buf.remaining() < FRAME_HEADER_LENGTH + length) {
                return; // underflow - wait for more data
            }

            // Consume header, position at start of payload
            buf.position(pos + FRAME_HEADER_LENGTH);

            // Create a slice for the payload (no copy)
            int savedLimit = buf.limit();
            buf.limit(buf.position() + length);
            ByteBuffer payload = buf.slice();
            buf.limit(savedLimit);
            buf.position(buf.position() + length); // consume payload

            // Handle State.PRI_SETTINGS first - expects initial SETTINGS frame
            if (state == State.PRI_SETTINGS) {
                handlePriSettings(type, flags, streamId, payload);
                continue;
            }

            // Validate and dispatch frame based on type
            dispatchFrame(type, flags, streamId, length, payload);
        }
    }

    /**
     * Handles the initial SETTINGS frame after PRI preface.
     */
    private void handlePriSettings(int type, int flags, int streamId, ByteBuffer payload) {
        Stream stream = getStream(clientStreamId);
        
        // Send server preface
        SettingsFrame serverPreface = new SettingsFrame(false);
        sendFrame(serverPreface);
        
        if (type != Frame.TYPE_SETTINGS) {
            sendStreamError(stream, 400);
            return;
        }
        
        state = State.HTTP2;
        hpackDecoder = new Decoder(headerTableSize);
        hpackEncoder = new Encoder(headerTableSize, maxHeaderListSize);
        
        try {
            frameReceived(new SettingsFrame(flags, payload));
        } catch (IOException e) {
            sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, clientStreamId);
        }
    }

    /**
     * Validates frame constraints and dispatches to frameReceived().
     */
    private void dispatchFrame(int type, int flags, int streamId, int length, ByteBuffer payload) {
        // Check concurrent streams limit for new streams
        boolean streamExists;
        synchronized (streams) {
            streamExists = streams.containsKey(streamId);
        }
        if (streamId != 0 && !streamExists) {
            int numConcurrentStreams;
            synchronized (activeStreams) {
                numConcurrentStreams = activeStreams.size();
            }
            if (numConcurrentStreams >= maxConcurrentStreams) {
                sendErrorFrame(Frame.ERROR_REFUSED_STREAM, streamId);
                return;
            }
        }

        // In continuation state, only accept continuation frames for the right stream
        if (state == State.HTTP2_CONTINUATION) {
            if (type != Frame.TYPE_CONTINUATION || streamId != continuationStream) {
                frameProtocolError(streamId);
                return;
            }
        }

        // Validate and create frame based on type
        Frame frame = null;
        try {
            switch (type) {
                case Frame.TYPE_DATA:
                    if (streamId == 0) {
                        frameProtocolError(streamId);
                        return;
                    }
                    frame = new DataFrame(flags, streamId, payload);
                    break;
                case Frame.TYPE_HEADERS:
                    if (streamId == 0) {
                        frameProtocolError(streamId);
                        return;
                    }
                    frame = new HeadersFrame(flags, streamId, payload);
                    break;
                case Frame.TYPE_PRIORITY:
                    if (streamId == 0) {
                        frameProtocolError(streamId);
                        return;
                    }
                    if (length != PRIORITY_FRAME_LENGTH) {
                        frameSizeError(streamId);
                        return;
                    }
                    frame = new PriorityFrame(streamId, payload);
                    break;
                case Frame.TYPE_RST_STREAM:
                    if (streamId == 0) {
                        frameProtocolError(streamId);
                        return;
                    }
                    if (length != RST_STREAM_FRAME_LENGTH) {
                        frameSizeError(streamId);
                        return;
                    }
                    frame = new RstStreamFrame(streamId, payload);
                    break;
                case Frame.TYPE_SETTINGS:
                    if (streamId != 0) {
                        frameProtocolError(streamId);
                        return;
                    }
                    if (length % SETTINGS_ENTRY_LENGTH != 0) {
                        frameSizeError(streamId);
                        return;
                    }
                    frame = new SettingsFrame(flags, payload);
                    break;
                case Frame.TYPE_PUSH_PROMISE:
                    if (!enablePush || streamId == 0) {
                        frameProtocolError(streamId);
                        return;
                    }
                    frame = new PushPromiseFrame(flags, streamId, payload);
                    break;
                case Frame.TYPE_PING:
                    if (streamId != 0) {
                        frameProtocolError(streamId);
                        return;
                    }
                    if (length != PING_FRAME_LENGTH) {
                        frameSizeError(streamId);
                        return;
                    }
                    frame = new PingFrame(flags);
                    break;
                case Frame.TYPE_GOAWAY:
                    if (streamId != 0 || length < GOAWAY_MIN_LENGTH) {
                        frameProtocolError(streamId);
                        return;
                    }
                    frame = new GoawayFrame(payload);
                    break;
                case Frame.TYPE_WINDOW_UPDATE:
                    if (length != WINDOW_UPDATE_FRAME_LENGTH) {
                        frameSizeError(streamId);
                        return;
                    }
                    frame = new WindowUpdateFrame(streamId, payload);
                    break;
                case Frame.TYPE_CONTINUATION:
                    if (streamId != continuationStream) {
                        frameProtocolError(streamId);
                        return;
                    }
                    frame = new ContinuationFrame(flags, streamId, payload);
                    break;
                default:
                    // Unknown frame type - ignore per RFC 7540 section 4.1
                    // "Implementations MUST ignore and discard any frame that has a type that is unknown."
                    return;
            }
        } catch (ProtocolException e) {
            frameProtocolError(streamId);
            return;
        }

        // Dispatch valid frame to handler
        try {
            frameReceived(frame);
        } catch (IOException e) {
            sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, clientStreamId);
        }
    }

    /**
     * Called when a frame protocol error is detected.
     */
    private void frameProtocolError(int streamId) {
        sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, streamId);
    }

    /**
     * Called when a frame size error is detected.
     */
    private void frameSizeError(int streamId) {
        sendErrorFrame(Frame.ERROR_FRAME_SIZE_ERROR, streamId);
    }

    /**
     * Receives WebSocket data.
     * TODO: Move frame parsing to websocket package.
     */
    private void receiveWebSocket(ByteBuffer buf) {
        Stream stream = getStream(webSocketStreamId);
        if (stream == null) {
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning(MessageFormat.format(
                    L10N.getString("warn.websocket_stream_not_found"), webSocketStreamId));
            }
            return;
        }
        // Pass all remaining data to stream
        if (buf.hasRemaining()) {
            stream.appendRequestBody(buf);
        }
    }

    /**
     * Return the stream for the given stream ID.
     * If the stream does not exist yet it will be created.
     * Stream ID 0 always results in a null stream.
     */
    Stream getStream(int streamId) {
        // Trigger periodic cleanup to prevent memory accumulation
        maybeCleanupClosedStreams();
        
        synchronized (streams) {
            Stream stream = (streamId == 0) ? null : streams.get(streamId);
            if (streamId != 0 && stream == null) {
                stream = newStream(this, streamId);
                streams.put(streamId, stream);
            }
            return stream;
        }
    }
    
    /**
     * Switches this connection to WebSocket mode for the specified stream.
     * After this call, all incoming data will be passed directly to the stream
     * for WebSocket frame processing rather than being parsed as HTTP.
     * 
     * <p>This method should be called after sending the 101 Switching Protocols
     * response for a WebSocket upgrade request.
     * 
     * @param streamId the ID of the stream that has been upgraded to WebSocket
     */
    void switchToWebSocketMode(int streamId) {
        this.webSocketStreamId = streamId;
        this.state = State.WEBSOCKET;
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Switched to WebSocket mode for stream " + streamId);
        }
    }
    
    /**
     * Returns true if this connection is in WebSocket mode.
     * 
     * @return true if in WebSocket mode
     */
    boolean isWebSocketMode() {
        return state == State.WEBSOCKET;
    }
    
    /**
     * Performs periodic cleanup of closed streams to prevent memory leaks.
     * Called opportunistically during stream access to minimize overhead.
     * Thread-safe and optimized to avoid unnecessary work.
     */
    private void maybeCleanupClosedStreams() {
        long now = System.currentTimeMillis();
        
        // Only cleanup if enough time has passed (avoid excessive cleanup)
        if (now - lastStreamCleanup < STREAM_CLEANUP_INTERVAL_MS) {
            return;
        }
        
        synchronized (streams) {
            // Double-check timing under lock to avoid race conditions
            if (now - lastStreamCleanup < STREAM_CLEANUP_INTERVAL_MS) {
                return;
            }
            
            lastStreamCleanup = now;
            
            // Remove streams that have been closed for longer than retention period
            int removedCount = 0;
            Iterator<Map.Entry<Integer, Stream>> iterator = streams.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Integer, Stream> entry = iterator.next();
                Stream stream = entry.getValue();
                
                if (stream.isClosed() && 
                    (now - stream.timestampCompleted) > STREAM_RETENTION_MS) {
                    iterator.remove();
                    removedCount++;
                }
            }
            
            if (removedCount > 0 && LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(String.format("Cleaned up %d closed streams (total remaining: %d)", 
                                        removedCount, streams.size()));
            }
        }
    }

    void sendStreamError(Stream stream, int statusCode) {
        try {
            stream.sendError(statusCode);
        } catch (ProtocolException e) {
            String message = L10N.getString("err.send_headers");
            LOGGER.log(Level.SEVERE, message, e);
        }
    }

    /**
     * Returns a new stream implementation for this connection.
     * The stream will be notified of events relating to request reception.
     * If no handler factory is configured, the stream will send 404 responses.
     * @param connection the connection the stream is associated with
     * @param streamId the connection-unique stream identifier
     */
    Stream newStream(HTTPConnection connection, int streamId) {
        return new Stream(connection, streamId);
    }

    /**
     * Called when a complete HTTP/2 frame has been received.
     * This is the handler method for frame processing.
     *
     * @param frame the complete frame
     */
    void frameReceived(Frame frame) throws IOException {
        // Allow subclasses to handle priority information
        processFrame(frame);
    }
    
    /**
     * Processes a frame for this connection.
     * Subclasses can override to add priority handling or other frame processing.
     * 
     * @param frame the frame to process
     * @throws Exception if frame processing fails
     */
    protected void processFrame(Frame frame) throws IOException {
        //System.err.println("Received frame: "+frame);
        int streamId = frame.getStream();
        Stream stream = getStream(streamId);
        switch (frame.getType()) {
            case Frame.TYPE_DATA:
                DataFrame df = (DataFrame) frame;
                stream.appendRequestBody(df.data);
                if (df.endStream) {
                    stream.streamEndRequest();
                    if (stream.isActive()) {
                        synchronized (activeStreams) {
                            activeStreams.add(streamId);
                        }
                    }
                }
                break;
            case Frame.TYPE_HEADERS:
                HeadersFrame hf = (HeadersFrame) frame;
                stream.appendHeaderBlockFragment(hf.headerBlockFragment);
                if (hf.endHeaders) {
                    stream.streamEndHeaders();
                    if (hf.endStream) {
                        stream.streamEndRequest();
                    }
                    if (stream.isActive()) {
                        synchronized (activeStreams) {
                            activeStreams.add(streamId);
                        }
                    }
                } else {
                    state = State.HTTP2_CONTINUATION;
                    continuationStream = streamId;
                    continuationEndStream = hf.endStream;
                }
                break;
            case Frame.TYPE_PUSH_PROMISE:
                PushPromiseFrame ppf = (PushPromiseFrame) frame;
                stream.setPushPromise();
                stream.appendHeaderBlockFragment(ppf.headerBlockFragment);
                if (ppf.endHeaders) {
                    stream.streamEndHeaders();
                    stream.streamEndRequest();
                } else {
                    state = State.HTTP2_CONTINUATION;
                    continuationStream = streamId;
                    continuationEndStream = true;
                }
                break;
            case Frame.TYPE_CONTINUATION:
                ContinuationFrame cf = (ContinuationFrame) frame;
                stream.appendHeaderBlockFragment(cf.headerBlockFragment);
                if (cf.endHeaders) {
                    stream.streamEndHeaders();
                    state = State.HTTP2;
                    continuationStream = 0;
                    if (continuationEndStream) {
                        stream.streamEndRequest();
                    }
                    if (stream.isActive()) {
                        synchronized (activeStreams) {
                            activeStreams.add(streamId);
                        }
                    }
                }
                break;
            case Frame.TYPE_RST_STREAM:
                stream.streamClose();
                synchronized (activeStreams) {
                    activeStreams.remove(streamId);
                }
                // Stream will be cleaned up by periodic cleanup after retention period
                break;
            case Frame.TYPE_GOAWAY:
                close();
                break;
            case Frame.TYPE_SETTINGS:
                SettingsFrame settings = (SettingsFrame) frame;
                if (!settings.ack) {
                    settings.apply(this);
                    hpackDecoder.setHeaderTableSize(headerTableSize);
                    hpackEncoder.setHeaderTableSize(headerTableSize);
                    hpackEncoder.setMaxHeaderListSize(maxHeaderListSize);
                    SettingsFrame result = new SettingsFrame(true); // ACK
                    sendFrame(result);
                }
                break;
        }
    }

    void sendErrorFrame(int errorType, int stream) {
        sendFrame(new RstStreamFrame(errorType, stream));
    }

    void sendFrame(Frame frame) {
        //System.err.println("sending frame: "+frame);
        ByteBuffer buf = ByteBuffer.allocate(FRAME_HEADER_LENGTH + frame.getLength());
        frame.write(buf);
        buf.flip();
        send(buf);
    }

    /**
     * Append the specified header line to the current header value.
     * This will decode any quoted-strings and RFC 2047 encoded-words in the
     * header. All linear whitespace will be replaced by a single space.
     * It will not decode comments.
     */
    private void appendHeaderValue(String l) {
        int len = l.length();
        StringBuilder quoteBuf = null;
        boolean escaped = false, text = false;
        int start = 0;
        for (int i = 0; i < len; i++) {
            char c = l.charAt(i);
            if (quoteBuf != null) {
                if (escaped) {
                    quoteBuf.append(c);
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    String word = quoteBuf.toString();
                    appendHeaderWord(word, 0, word.length());
                    quoteBuf = null;
                } else {
                    quoteBuf.append(c);
                }
            } else if (c == ' ' || c == '\t') {
                if (text) {
                    appendHeaderWord(l, start, i);
                    text = false;
                }
                start = i + 1;
            } else if (c == '"') {
                quoteBuf = new StringBuilder();
            } else {
                text = true;
            }
        }
        if (text) {
            appendHeaderWord(l, start, len);
        }
    }

    private void appendHeaderWord(String l, int start, int end) {
        if (end - start > 6 && l.charAt(start) == '=' && l.charAt(start + 1) == '?') {
            // RFC 2047 encoded-words
            String text = l.substring(start, end);
            try {
                text = MimeUtility.decodeWord(text);
                l = text;
                start = 0;
                end = text.length();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, text, e);
            }
        }
        int required = (end - start) + 1;
        if (headerValue.remaining() < required) {
            CharBuffer tmp = CharBuffer.allocate(headerValue.remaining() + Math.max(HEADER_VALUE_BUFFER_SIZE, required));
            headerValue.flip();
            tmp.put(headerValue);
            headerValue = tmp;
        }
        if (headerValue.position() > 0) {
            headerValue.append(' ');
        }
        headerValue.append(l, start, end);
    }

    protected void disconnected() throws IOException {
        // Record connection closed metric
        HTTPServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            metrics.connectionClosed();
        }

        // Clean up all streams when connection closes to prevent memory leaks
        cleanupAllStreams();
    }
    
    /**
     * Immediately cleanup all streams when the connection is closing.
     * This prevents memory leaks on connection termination and ensures
     * telemetry spans are properly ended.
     */
    protected void cleanupAllStreams() {
        synchronized (streams) {
            int streamCount = streams.size();
            // Close each stream to properly end telemetry spans
            for (Stream stream : streams.values()) {
                stream.streamClose();
            }
            streams.clear();
            if (streamCount > 0 && LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(String.format("Connection closing: cleaned up %d remaining streams", streamCount));
            }
        }
        synchronized (activeStreams) {
            activeStreams.clear();
        }
    }

    /**
     * Write a status response: status code and header fields.
     * @param streamId the stream identifier
     * @param statusCode the status code of the response
     * @param headers the headers to be sent to the client
     * @param endStream if no response body data will be sent
     */
    void sendResponseHeaders(int streamId, int statusCode, Headers headers, boolean endStream) {
        ByteBuffer buf;
        boolean success = false;
        switch (state) {
            case HTTP2:
                // send headers frame(s)
                headers.add(0, new Header(":status", Integer.toString(statusCode))); // pseudo-header must always be first
                int streamDependency = 0; // TODO
                boolean streamDependencyExclusive = false; // TODO
                int weight = 0; // TODO
                int padLength = framePadding; // Use configured padding for server-originated frames
                // encode
                buf = ByteBuffer.allocate(headerTableSize);
                while (!success) {
                    try {
                        hpackEncoder.encode(buf, headers);
                        success = true;
                    } catch (BufferOverflowException e) {
                        buf = ByteBuffer.allocate(buf.capacity() + headerTableSize);
                    } catch (ProtocolException e) {
                        // Headers provided exceeded maximum size that
                        // client can handle. This is fatal
                        int errorCode = Frame.ERROR_COMPRESSION_ERROR;
                        sendFrame(new GoawayFrame(streamId, errorCode, new byte[0]));
                        send(null); // close after frame sent
                        return;
                    }
                }
                buf.flip();
                // do we need to split into multiple frames
                int length = buf.remaining();
                if (length <= headerTableSize) {
                    // single HEADERS frame
                    byte[] headerBlockFragment = new byte[length];
                    buf.get(headerBlockFragment);
                    sendFrame(new HeadersFrame(streamId, padLength != 0, endStream, true, streamDependency != 0, padLength, streamDependency, streamDependencyExclusive, weight, headerBlockFragment));
                } else {
                    // sequence of HEADERS and CONTINUATION+
                    byte[] headerBlockFragment = new byte[headerTableSize];
                    buf.get(headerBlockFragment);
                    sendFrame(new HeadersFrame(streamId, padLength != 0, endStream, false, streamDependency != 0, padLength, streamDependency, streamDependencyExclusive, weight, headerBlockFragment));
                    length -= headerTableSize;
                    while (length > headerTableSize) {
                        buf.get(headerBlockFragment);
                        sendFrame(new ContinuationFrame(streamId, false, headerBlockFragment));
                        length -= headerTableSize;
                    }
                    headerBlockFragment = new byte[length];
                    buf.get(headerBlockFragment);
                    sendFrame(new ContinuationFrame(streamId, true, headerBlockFragment));
                }
                break;
            default:
                // HTTP/1
                buf = ByteBuffer.allocate(headerTableSize); // sensible default
                while (!success) {
                    try {
                        writeStatusLineAndHeaders(buf, statusCode, headers);
                        success = true;
                    } catch (BufferOverflowException e) {
                        buf = ByteBuffer.allocate(buf.capacity() + headerTableSize);
                    }
                }
                buf.flip();
                send(buf);
        }
    }

    /**
     * Send HTTP/1 status line and headers.
     */
    private void writeStatusLineAndHeaders(ByteBuffer buf, int statusCode, Headers headers) {
        try {
            String statusLine = String.format("%s %03d %s\r\n", version.toString(), statusCode, HTTPConstants.getMessage(statusCode));
            buf.put(statusLine.getBytes(US_ASCII));
            for (Header header : headers) {
                String name = header.getName();
                if (name.charAt(0) == ':') {
                    continue;
                }
                String value = header.getValue();
                if (value == null) {
                    // Skip headers with null values for HTTP/1.x
                    continue;
                }
                int cflags = getCharsetFlags(value);
                if ((cflags & CHARSET_UNICODE) != 0) {
                    String enc = ((cflags & CHARSET_Q_ENCODING) != 0) ? "Q" : "B";
                    value = MimeUtility.encodeText(value, "UTF-8", enc);
                }
                String headerLine = String.format("%s: %s\r\n", name, value);
                buf.put(headerLine.getBytes(US_ASCII));
            }
            buf.put(new byte[] { (byte) 0x0d, (byte) 0x0a }); // CRLF empty line for end headers
        } catch (IOException e) {
            // non-ASCII characters. This shouldn't happen
            LOGGER.log(Level.SEVERE, "Non-ASCII characters in headers", e);
            RuntimeException e2 = new RuntimeException();
            e2.initCause(e);
            throw e2;
        }
    }

    private static final int CHARSET_UNICODE = 1;
    private static final int CHARSET_Q_ENCODING = 2;

    /*
     * This will tell us 2 things:
     * - whether we can use ASCII or need UTF-8
     * - whether to use B or Q encoding
     */
    private static int getCharsetFlags(String text) {
        int asciiCount = 0, nonAsciiCount = 0, len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if ((c >= 32 && c < 127) || c == '\n' || c == '\r' || c == '\t') {
                asciiCount++;
            } else {
                nonAsciiCount++;
            }
        }
        int ret = (nonAsciiCount == 0) ? 0 : CHARSET_UNICODE;
        if (nonAsciiCount > asciiCount) {
            ret |= CHARSET_Q_ENCODING;
        }
        return ret;
    }

    /**
     * Write response body data.
     * @param streamId the stream identifier
     * @param buf the response data buffer
     * @param endStream if this is the last response data that will be sent
     */
    void sendResponseBody(int streamId, ByteBuffer buf, boolean endStream) {
        switch (state) {
            case HTTP2:
                // Use DATA frame with configured padding
                sendFrame(new DataFrame(streamId, framePadding > 0, endStream, framePadding, buf));
                break;
            default:
                // send directly
                send(buf);
        }
    }

    /**
     * Removes a stream from this connection.
     * Subclasses can override to perform additional cleanup.
     * 
     * @param streamId the stream ID to remove
     * @return the removed stream, or null if not found
     */
    protected Stream removeStream(int streamId) {
        return streams.remove(streamId);
    }
    
    // -- Server Push Support --
    
    /**
     * Gets the next available server stream ID for server-initiated streams.
     * Server-initiated stream IDs are even numbers starting from 2.
     * 
     * @return the next server stream ID
     */
    int getNextServerStreamId() {
        int nextId = serverStreamId;
        serverStreamId += 2; // Server stream IDs are even numbers
        return nextId;
    }
    
    /**
     * Creates a pushed stream for handling server-initiated requests.
     * This creates a Stream object that represents the promised resource.
     * 
     * @param streamId the stream ID for the pushed stream
     * @param method the HTTP method for the pushed request
     * @param uri the URI for the pushed resource
     * @param headers the headers for the pushed request
     * @return the created Stream, or null if creation failed
     */
    Stream createPushedStream(int streamId, String method, String uri, Headers headers) {
        try {
            // Create a new stream for the pushed resource
            Stream pushedStream = newStream(this, streamId);
            
            // Configure the stream as a pushed stream
            pushedStream.setPushPromise();
            
            // Set up the pushed request details
            // The stream will handle this as an incoming request for the specified resource
            for (Header header : headers) {
                pushedStream.addHeader(header);
            }
            
            // Add the stream to our stream collection
            synchronized (streams) {
                streams.put(streamId, pushedStream);
            }
            
            return pushedStream;
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to create pushed stream " + streamId, e);
            return null;
        }
    }
    
    /**
     * Encodes headers using HPACK for HTTP/2 frames.
     * This method uses the connection's HPACK encoder to create header block fragments.
     * 
     * @param headers the headers to encode
     * @return the HPACK-encoded header block
     */
    byte[] encodeHeaders(Headers headers) {
        try {
            if (hpackEncoder == null) {
                // Initialize HPACK encoder with default table sizes
                hpackEncoder = new Encoder(headerTableSize, maxHeaderListSize);
            }
            
            // Create a ByteBuffer for HPACK encoding
            ByteBuffer buffer = ByteBuffer.allocate(HPACK_ENCODE_BUFFER_SIZE);
            
            // Use the HPACK encoder's encode method which takes ByteBuffer and List<Header>
            hpackEncoder.encode(buffer, headers);
            
            // Extract the encoded bytes
            buffer.flip();
            byte[] encoded = new byte[buffer.remaining()];
            buffer.get(encoded);
            
            return encoded;
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to encode headers using HPACK", e);
            // Return empty block on error - better than crashing
            return new byte[0];
        }
    }
    

}
