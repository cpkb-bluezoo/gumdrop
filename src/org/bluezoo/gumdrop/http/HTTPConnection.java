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

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.SendCallback;

import java.io.IOException;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.BufferOverflowException;
import java.nio.CharBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.Iterator;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.mail.internet.MimeUtility;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import org.bluezoo.gumdrop.http.hpack.Decoder;
import org.bluezoo.util.ByteArrays;
import org.bluezoo.gumdrop.http.hpack.Encoder;
import org.bluezoo.gumdrop.util.LineInput;

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
public class HTTPConnection extends Connection {

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.http.L10N");
    static final Logger LOGGER = Logger.getLogger(HTTPConnection.class.getName());

    static final Charset US_ASCII = Charset.forName("US-ASCII");
    static final CharsetDecoder US_ASCII_DECODER = US_ASCII.newDecoder();
    static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");
    static final CharsetDecoder ISO_8859_1_DECODER = ISO_8859_1.newDecoder();

    private static final Pattern METHOD_PATTERN = Pattern.compile("^[!#$%&'*+\\-.^_`|~0-9a-zA-Z]+$"); // token
    private static final Pattern REQUEST_TARGET_PATTERN = Pattern.compile("^[a-zA-Z0-9\\-._~!$&'()*+,;=:@/?#\\[\\]%]+$");
    
    // Known HTTP methods - return 501 for unknown methods
    private static final java.util.Set<String> KNOWN_METHODS = new java.util.HashSet<>(java.util.Arrays.asList(
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

    private static final int STATE_REQUEST_LINE = 0;
    private static final int STATE_HEADER = 1;
    private static final int STATE_BODY = 2;
    private static final int STATE_PRI = 4;
    private static final int STATE_PRI_SETTINGS = 5;
    private static final int STATE_HTTP2 = 6;
    private static final int STATE_HTTP2_CONTINUATION = 7;
    private static final int STATE_WEBSOCKET = 8;
    
    // Maximum line length for request-line and headers (RFC 7230 doesn't mandate, but 8KB is common)
    private static final int MAX_LINE_LENGTH = 8192;

    private int state = STATE_REQUEST_LINE;
    private ByteBuffer in; // input buffer
    private LineReader lineReader;

    class LineReader implements LineInput {

        private CharBuffer sink; // character buffer to receive decoded characters

        @Override public ByteBuffer getLineInputBuffer() {
            return in;
        }

        @Override public CharBuffer getOrCreateLineInputCharacterSink(int capacity) {
            if (sink == null || sink.capacity() < capacity) {
                sink = CharBuffer.allocate(capacity);
            }
            return sink;
        }

    }

    // HTTP/1
    private String headerName;
    private CharBuffer headerValue;
    private ByteBuffer response;

    // HTTP/2 settings
    int headerTableSize = 4096;
    boolean enablePush = true;
    int maxConcurrentStreams = Integer.MAX_VALUE;
    int initialWindowSize = 65535;
    int maxFrameSize = 16384;
    int maxHeaderListSize = Integer.MAX_VALUE;

    // HTTP/2 header decoder and encoder
    Decoder hpackDecoder;
    Encoder hpackEncoder;

    private int clientStreamId; // synthesized stream ID for HTTP/1
    private int serverStreamId = 2; // server-initiated streams start at 2 (even numbers)
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
        in = ByteBuffer.allocate(4096);
        if (engine != null) {
            // Configure this engine with ALPN
            SSLParameters sp = engine.getSSLParameters();
            sp.setApplicationProtocols(new String[] { "h2", "http/1.1"}); // h2 and http/1.1
            engine.setSSLParameters(sp);
        }
        streams = new TreeMap<>();
        clientStreamId = 1;
        lineReader = this.new LineReader();
    }

    @Override
    public void setSendCallback(SendCallback callback) {
        super.setSendCallback(callback);
    }

    @Override
    public void init() throws IOException {
        super.init();
        // HTTP connections don't send initial banners
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
     * Read a frame (HTTP/2).
     * Returns null if there was not enough input data for a frame,
     * and the input buffer position will be unchanged.
     */
    private Frame readFrame() {
        // Must have at least 9-byte header
        if (in.remaining() < 9) {
            return null;
        }
        int pos = in.position();
        byte[] header = new byte[9];
        in.get(header);
        //System.err.println("readFrame header="+toHexString(header));
        int length = ((int) header[0] & 0xff) << 16
            | ((int) header[1] & 0xff) << 8
            | ((int) header[2] & 0xff);
        int type = ((int) header[3] & 0xff);
        int flags = ((int) header[4] & 0xff);
        int stream = ((int) header[5] & 0x7f) << 24 // NB mask out reserved bit
            | ((int) header[6] & 0xff) << 16
            | ((int) header[7] & 0xff) << 8
            | ((int) header[8] & 0xff);
        if (in.remaining() < length) { // not enough data yet for frame
            in.position(pos);
            return null;
        }
        byte[] payload = new byte[length];
        if (length > 0) {
            in.get(payload);
        }
        try {
            switch (type) {
                case Frame.TYPE_DATA:
                    if (stream == 0) {
                        sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, stream);
                        return null;
                    }
                    // TODO STREAM_CLOSED
                    return new DataFrame(flags, stream, payload);
                case Frame.TYPE_HEADERS:
                    if (stream == 0) {
                        sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, stream);
                        return null;
                    }
                    return new HeadersFrame(flags, stream, payload);
                case Frame.TYPE_PRIORITY:
                    if (stream == 0) {
                        sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, stream);
                        return null;
                    }
                    if (length != 5) {
                        sendErrorFrame(Frame.ERROR_FRAME_SIZE_ERROR, stream);
                        return null;
                    }
                    return new PriorityFrame(stream, payload);
                case Frame.TYPE_RST_STREAM:
                    if (stream == 0) {
                        sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, stream);
                        return null;
                    }
                    if (length != 4) {
                        sendErrorFrame(Frame.ERROR_FRAME_SIZE_ERROR, stream);
                        return null;
                    }
                    // TODO stream in idle state
                    return new RstStreamFrame(stream, payload);
                case Frame.TYPE_SETTINGS:
                    if (stream != 0) {
                        sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, stream);
                        return null;
                    }
                    if (length % 6 != 0) {
                        sendErrorFrame(Frame.ERROR_FRAME_SIZE_ERROR, stream);
                        return null;
                    }
                    return new SettingsFrame(flags, payload);
                case Frame.TYPE_PUSH_PROMISE:
                    if (!enablePush || stream == 0) {
                        sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, stream);
                        return null;
                    }
                    // TODO STREAM_CLOSED
                    return new PushPromiseFrame(flags, stream, payload);
                case Frame.TYPE_PING:
                    if (stream != 0) {
                        sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, stream);
                        return null;
                    }
                    if (length != 0) {
                        sendErrorFrame(Frame.ERROR_FRAME_SIZE_ERROR, stream);
                        return null;
                    }
                    return new PingFrame(flags);
                case Frame.TYPE_GOAWAY:
                    if (stream != 0 || length < 8) {
                        sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, stream);
                        return null;
                    }
                    return new GoawayFrame(payload);
                case Frame.TYPE_WINDOW_UPDATE:
                    if (length != 4) {
                        sendErrorFrame(Frame.ERROR_FRAME_SIZE_ERROR, stream);
                        return null;
                    }
                    return new WindowUpdateFrame(stream, payload);
                case Frame.TYPE_CONTINUATION:
                    if (stream != continuationStream) {
                        sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, stream);
                        return null;
                    }
                    return new ContinuationFrame(flags, stream, payload);
                default:
                    sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, stream);
            }
        } catch (ProtocolException e) {
            sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, stream);
        }
        send(null); // close after sending responses
        return null;
    }

    /**
     * Received data from the client.
     * This method is called from a thread in the connector's thread pool.
     * @param buf the receive buffer
     */
    @Override
    public void receive(ByteBuffer buf) {
        // in is ready for put
        int len = buf.remaining();
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest("HTTPConnection.received() called with " + len + " bytes, state=" + state);
        }
        if (in.remaining() < len) {
            // need to reallocate in
            ByteBuffer tmp = ByteBuffer.allocate(in.capacity() + Math.max(4096, len));
            in.flip();
            tmp.put(in);
            in = tmp;
        }
        in.put(buf);
        in.flip();
        // in is ready for get

        Stream stream;
        while (in.hasRemaining()) {
            String line;
            Frame frame;
            switch (state) {
                case STATE_REQUEST_LINE:
                    stream = getStream(clientStreamId);
                    if (LOGGER.isLoggable(Level.FINEST)) {
                        LOGGER.finest("STATE_REQUEST_LINE: reading request line, stream=" + stream);
                    }
                    try {
                        line = lineReader.readLine(US_ASCII_DECODER);
                    } catch (IOException e) {
                        if (LOGGER.isLoggable(Level.FINEST)) {
                            LOGGER.log(Level.FINEST, "Error reading request line", e);
                        }
                        sendStreamError(stream, 400); // Bad Request
                        in.compact();
                        return;
                    }
                    if (line == null) {
                        if (LOGGER.isLoggable(Level.FINEST)) {
                            LOGGER.finest("Request line is null (need more data)");
                        }
                        // Check if line is too long (buffer grew beyond limit without finding CRLF)
                        // After readLine returns null, position is at the end of data we scanned through
                        if (in.position() > MAX_LINE_LENGTH) {
                            sendStreamError(stream, 414); // URI Too Long
                            in.clear();
                            return;
                        }
                        // readLine consumed all bytes (pos==limit). Reset position to 0 so
                        // compact() preserves all the data for the next receive() call
                        in.position(0);
                        in.compact();
                        return; // not enough data for request-line
                    }
                    if (LOGGER.isLoggable(Level.FINEST)) {
                        LOGGER.finest("Parsed request line: " + line);
                    }
                    // Check if request line is too long
                    if (line.length() > MAX_LINE_LENGTH) {
                        sendStreamError(stream, 414); // URI Too Long
                        in.compact();
                        return;
                    }
                    int mi = line.indexOf(' ', 1);
                    int ui = line.indexOf(' ', mi + 2);
                    if (mi == -1 || ui == -1) {
                        sendStreamError(stream, 400); // Bad Request
                        in.compact();
                        return;
                    }
                    String method = line.substring(0, mi);
                    String requestTarget = line.substring(mi + 1, ui);
                    String version = line.substring(ui + 1);
                    this.version = HTTPVersion.fromString(version);
                    // Validate input
                    if (!METHOD_PATTERN.matcher(method).matches()) {
                        sendStreamError(stream, 400); // Bad Request
                        in.compact();
                        return;
                    }
                    // Check if method is known - return 501 for unknown methods
                    if (!KNOWN_METHODS.contains(method)) {
                        sendStreamError(stream, 501); // Not Implemented
                        in.compact();
                        return;
                    }
                    if (!REQUEST_TARGET_PATTERN.matcher(requestTarget).matches()) {
                        // does not match characters in absolute-URI / authority / origin-form / asterisk-form
                        // we will not do full parsing of the request-target structure here
                        sendStreamError(stream, 400); // Bad Request
                        in.compact();
                        return;
                    }
                    switch (this.version) {
                        case UNKNOWN:
                            sendStreamError(stream, 505); // HTTP Version Not Supported
                            in.compact();
                            return;
                        case HTTP_2_0:
                            if ("PRI".equals(method) && "*".equals(requestTarget)) {
                                state = STATE_PRI;
                            } else {
                                // invalid
                                sendStreamError(stream, 400); // Bad Request
                                in.compact();
                                return;
                            }
                            break;
                        case HTTP_1_0:
                            stream.closeConnection = true;
                            // fall through
                        default: // HTTP_1_1
                            stream.addHeader(new Header(":method", method));
                            stream.addHeader(new Header(":path", requestTarget));
                            stream.addHeader(new Header(":scheme", secure ? "https" : "http"));
                            headerName = null;
                            headerValue = CharBuffer.allocate(4096);
                            state = STATE_HEADER;
                            if (LOGGER.isLoggable(Level.FINEST)) {
                                LOGGER.finest("Transitioned to STATE_HEADER");
                            }
                    }
                    break;
                case STATE_HEADER:
                    stream = getStream(clientStreamId);
                    if (LOGGER.isLoggable(Level.FINEST)) {
                        LOGGER.finest("STATE_HEADER: reading header line");
                    }
                    try {
                        // Old clients may still sometimes send iso-latin-1
                        // data in headers. We have to accept this even
                        // though we won't issue any non-ASCII header data
                        // outrselves.
                        line = lineReader.readLine(ISO_8859_1_DECODER);
                    } catch (IOException e) {
                        if (LOGGER.isLoggable(Level.FINEST)) {
                            LOGGER.log(Level.FINEST, "Error reading header line", e);
                        }
                        sendStreamError(stream, 400); // Bad Request
                        in.compact();
                        return;
                    }
                    if (line == null) {
                        if (LOGGER.isLoggable(Level.FINEST)) {
                            LOGGER.finest("Header line is null (need more data), compacting buffer");
                        }
                        // Check if line is too long
                        if (in.position() > MAX_LINE_LENGTH) {
                            sendStreamError(stream, 431); // Request Header Fields Too Large
                            in.clear();
                            return;
                        }
                        // Reset position to 0 so compact() preserves all the data
                        in.position(0);
                        in.compact();
                        return; // not enough data for header-line
                    }
                    if (LOGGER.isLoggable(Level.FINEST)) {
                        LOGGER.finest("Read header line: '" + line + "' (length=" + line.length() + ")");
                    }
                    if (line.length() == 0) { // end of headers
                        if (LOGGER.isLoggable(Level.FINEST)) {
                            LOGGER.finest("End of headers detected, calling stream.streamEndHeaders()");
                        }
                        if (headerName != null) { // add last header
                            headerValue.flip();
                            String v = headerValue.toString();
                            stream.addHeader(new Header(headerName, v));
                            headerName = null;
                            headerValue = null;
                        }
                        
                        // HTTP/1.1 requires Host header (RFC 7230 Section 5.4)
                        if (this.version == HTTPVersion.HTTP_1_1) {
                            boolean hasHost = false;
                            for (Header header : stream.headers) {
                                if (header.getName().equalsIgnoreCase("host") || 
                                    header.getName().equals(":authority")) {
                                    hasHost = true;
                                    break;
                                }
                            }
                            if (!hasHost) {
                                sendStreamError(stream, 400); // Bad Request
                                in.compact();
                                return;
                            }
                        }
                        
                        try {
                            stream.streamEndHeaders();
                        } catch (IOException e) {
                            // This should only happen with malformed HPACK headers
                            LOGGER.log(Level.SEVERE, e.getMessage(), e);
                        }
                        if (stream.upgrade != null && stream.upgrade.contains("h2c") && stream.settingsFrame != null) {
                            // 3.2 Starting HTTP/2
                            Headers responseHeaders = new Headers();
                            responseHeaders.add("Connection", "Upgrade");
                            responseHeaders.add("Upgrade", "h2c");
                            sendResponseHeaders(clientStreamId, 101, responseHeaders, true); // Switching Protocols
                            // We now await the client connection preface
                            // (PRI * HTTP/2.0 request line)
                            state = STATE_REQUEST_LINE;
                            if (LOGGER.isLoggable(Level.FINE)) {
                                LOGGER.fine("Sent 101 Switching Protocols, waiting for client preface");
                            }
                            // client stream ID is not updated
                        } else {
                            state = STATE_BODY;
                            if (stream.getContentLength() == 0L) { // end of request
                                stream.streamEndRequest();
                                if (stream.isCloseConnection()) {
                                    stream.streamClose();
                                    // Schedule close after all pending writes complete
                                    send(null);
                                    in.compact();
                                    return;
                                }
                                state = STATE_REQUEST_LINE;
                                clientStreamId += 2;
                            }
                        }
                    } else { // header line
                        char c0 = line.charAt(0);
                        if (headerName != null && (c0 == ' ' || c0 == '\t')) { // folded continuation
                            if (c0 == '\t') { // RFC 7230: convert HT to SP
                                line = new StringBuilder().append(' ').append(line.substring(1)).toString();
                            }
                            appendHeaderValue(line);
                        } else { // new header
                            int ci = line.indexOf(':');
                            if (ci < 1) {
                                sendStreamError(stream, 400);
                                in.compact();
                                return;
                            }
                            String h = line.substring(0, ci);
                            if (headerName != null) {
                                headerValue.flip();
                                String v = headerValue.toString();
                                try {
                                    stream.addHeader(new Header(headerName, v));
                                } catch (IllegalArgumentException e) {
                                    sendStreamError(stream, 400);
                                    in.compact();
                                    return;
                                }
                            }
                            headerName = h;
                            headerValue.clear();
                            appendHeaderValue(line.substring(ci + 1));
                        }
                    }
                    break;
                case STATE_BODY:
                    stream = getStream(clientStreamId);
                    long contentLength = stream.getContentLength();
                    if (contentLength == -1L) {
                        sendStreamError(stream, 411); // Length Required
                        in.compact();
                        return;
                    }
                    int available = in.remaining();
                    if (available < 1) {
                        in.compact();
                        return; // underflow
                    }
                    long max = stream.getRequestBodyBytesNeeded();
                    if ((long) available > max) {
                        available = (int) max;
                    }
                    int ip = in.position();
                    int il = in.limit();
                    in.limit(ip + available);
                    stream.appendRequestBody(in);
                    in.limit(il);
                    if (stream.getRequestBodyBytesNeeded() == 0L) {
                        // body is complete
                        stream.streamEndRequest();
                        if (stream.closeConnection) {
                            stream.streamClose();
                            // Schedule close after all pending writes complete
                            send(null);
                            return;
                        }
                        state = STATE_REQUEST_LINE;
                        clientStreamId += 2;
                    }
                    break;
                case STATE_PRI:
                    stream = getStream(clientStreamId);
                    // \r\nSM\r\n\r\n + SETTINGS frame
                    // initial CRLF is end of headers
                    if (in.remaining() < 8) {
                        return; // underflow
                    }
                    byte[] smt = new byte[8];
                    in.get(smt);
                    if (smt[0] != '\r' || smt[1] != '\n' || smt[2] != 'S' || smt[3] != 'M' || smt[4] != '\r' || smt[5] != '\n' || smt[6] != '\r' || smt[7] != '\n') {
                        sendStreamError(stream, 400);
                        in.compact();
                        return;
                    }
                    state = STATE_PRI_SETTINGS;
                    // fall through
                case STATE_PRI_SETTINGS:
                    stream = getStream(clientStreamId);
                    frame = readFrame();
                    if (frame == null) {
                        // not enough data for frame
                        return; // not enough data for settings frame
                    }
                    // Start the server side of the connection preface
                    SettingsFrame serverPreface = new SettingsFrame(false);
                    // We don't need to set any special values as we use the defaults
                    sendFrame(serverPreface);
                    // Now process the client settings
                    if (frame.getType() != Frame.TYPE_SETTINGS) {
                        sendStreamError(stream, 400);
                        in.compact();
                        return;
                    }
                    state = STATE_HTTP2;
                    hpackDecoder = new Decoder(headerTableSize);
                    hpackEncoder = new Encoder(headerTableSize, maxHeaderListSize);
                    try {
                        receiveFrame(frame); // handle the frame
                    } catch (IOException e) { // HPACK headers malformed
                        sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, clientStreamId);
                    }
                    break;
                case STATE_HTTP2:
                case STATE_HTTP2_CONTINUATION:
                    frame = readFrame();
                    if (frame == null) {
                        return; // underflow
                    }
                    int streamId = frame.getStream();
                    boolean streamExists;
                    synchronized (streams) {
                        streamExists = streams.containsKey(streamId);
                    }
                    if (streamId != 0 && !streamExists) {
                        // Check max concurrent streams (5.1.2)
                        int numConcurrentStreams;
                        synchronized (activeStreams) {
                            numConcurrentStreams = activeStreams.size();
                        }
                        if (numConcurrentStreams >= maxConcurrentStreams) {
                            sendErrorFrame(Frame.ERROR_REFUSED_STREAM, streamId);
                            in.compact();
                            continue;
                        }
                    }
                    // If we are in a continuation then we can only get
                    // continuation frames until an endHeaders
                    if (state == STATE_HTTP2_CONTINUATION) {
                        int type = frame.getType();
                        if (type != Frame.TYPE_CONTINUATION || streamId != continuationStream) {
                            sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, streamId);
                            in.compact();
                            continue;
                        }
                    }
                    try {
                        receiveFrame(frame); // handle the frame
                    } catch (IOException e) { // HPACK headers malformed
                        sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, clientStreamId);
                        in.compact();
                        return;
                    }
                    break;
                case STATE_WEBSOCKET:
                    // In WebSocket mode, pass all data directly to the stream
                    // for WebSocket frame processing
                    stream = getStream(webSocketStreamId);
                    if (stream == null) {
                        // WebSocket stream no longer exists, close connection
                        if (LOGGER.isLoggable(Level.WARNING)) {
                            LOGGER.warning("WebSocket stream " + webSocketStreamId + " not found, closing connection");
                        }
                        return;
                    }
                    // Pass remaining data to the stream as request body
                    // The stream (in WebSocket mode) will process it as WebSocket frames
                    int wsDataLen = in.remaining();
                    if (wsDataLen > 0) {
                        byte[] wsData = new byte[wsDataLen];
                        in.get(wsData);
                        stream.receiveRequestBody(wsData);
                    }
                    break;
            }
        }
        in.compact();
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
        this.state = STATE_WEBSOCKET;
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
        return state == STATE_WEBSOCKET;
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
     * Default implementation returns a stream that sends 404 responses.
     * @param connection the connection the stream is associated with
     * @param streamId the connection-unique stream identifier
     */
    protected Stream newStream(HTTPConnection connection, int streamId) {
        return new DefaultStream(connection, streamId);
    }

    /**
     * Received a frame from the client.
     */
    void receiveFrame(Frame frame) throws IOException {
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
                    state = STATE_HTTP2_CONTINUATION;
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
                    state = STATE_HTTP2_CONTINUATION;
                    continuationStream = streamId;
                    continuationEndStream = true;
                }
                break;
            case Frame.TYPE_CONTINUATION:
                ContinuationFrame cf = (ContinuationFrame) frame;
                stream.appendHeaderBlockFragment(cf.headerBlockFragment);
                if (cf.endHeaders) {
                    stream.streamEndHeaders();
                    state = STATE_HTTP2;
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
        ByteBuffer buf = ByteBuffer.allocate(9 + frame.getLength());
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
            CharBuffer tmp = CharBuffer.allocate(headerValue.remaining() + Math.max(4096, required));
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
        // Clean up all streams when connection closes to prevent memory leaks
        cleanupAllStreams();
    }
    
    /**
     * Immediately cleanup all streams when the connection is closing.
     * This prevents memory leaks on connection termination.
     */
    protected void cleanupAllStreams() {
        synchronized (streams) {
            int streamCount = streams.size();
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
            case STATE_HTTP2:
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
                        if (LOGGER.isLoggable(Level.FINEST)) {
                            LOGGER.finest("Writing HTTP/1 response: statusCode=" + statusCode + ", headers count=" + headers.size());
                            for (Header h : headers) {
                                LOGGER.finest("  Header: " + h.getName() + ": " + h.getValue());
                            }
                        }
                        writeStatusLineAndHeaders(buf, statusCode, headers);
                        success = true;
                    } catch (BufferOverflowException e) {
                        buf = ByteBuffer.allocate(buf.capacity() + headerTableSize);
                    }
                }
                buf.flip();
                if (LOGGER.isLoggable(Level.FINEST)) {
                    LOGGER.finest("Sending " + buf.remaining() + " bytes of HTTP/1 response");
                }
                send(buf);
        }
    }

    /**
     * Send HTTP/1 status line and headers.
     */
    private void writeStatusLineAndHeaders(ByteBuffer buf, int statusCode, Headers headers) {
        try {
            String statusLine = String.format("%s %03d %s\r\n", version.toString(), statusCode, HTTPConstants.getMessage(statusCode));
            if (LOGGER.isLoggable(Level.FINEST)) {
                LOGGER.finest("Writing status line: " + statusLine.trim());
            }
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
                if (LOGGER.isLoggable(Level.FINEST)) {
                    LOGGER.finest("Writing header: " + headerLine.trim());
                }
                buf.put(headerLine.getBytes(US_ASCII));
            }
            if (LOGGER.isLoggable(Level.FINEST)) {
                LOGGER.finest("Writing final CRLF, total response size: " + buf.position() + " bytes");
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
     * @param data the response data
     * @param endStream if this is the last response data that will be sent
     */
    void sendResponseBody(int streamId, byte[] data, boolean endStream) {
        // NB we have both byte[] and ByteBuffer versions of this method
        // since we're currently storing data payloads for frames as byte[].
        // We should probably store them as ByteBuffer at some point
        switch (state) {
            case STATE_HTTP2:
                // Use DATA frame with configured padding
                sendFrame(new DataFrame(streamId, framePadding > 0, endStream, framePadding, data));
                break;
            default:
                // send directly
                ByteBuffer buf = ByteBuffer.wrap(data);
                send(buf);
        }
    }

    /**
     * Write response body data.
     * @param streamId the stream identifier
     * @param buf the response data buffer
     * @param endStream if this is the last response data that will be sent
     */
    void sendResponseBody(int streamId, ByteBuffer buf, boolean endStream) {
        switch (state) {
            case STATE_HTTP2:
                // Use DATA frame with configured padding
                int length = buf.remaining();
                byte[] data = new byte[length];
                buf.get(data);
                sendFrame(new DataFrame(streamId, framePadding > 0, endStream, framePadding, data));
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
                // Initialize HPACK encoder with table sizes
                hpackEncoder = new Encoder(4096, 4096); // Dynamic table size, max table size
            }
            
            // Create a ByteBuffer for HPACK encoding
            ByteBuffer buffer = ByteBuffer.allocate(8192); // Start with 8KB buffer
            
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
