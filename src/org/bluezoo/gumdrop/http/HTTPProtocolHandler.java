/*
 * HTTPProtocolHandler.java
 * Copyright (C) 2026 Chris Burdess
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

import java.io.IOException;
import java.net.ProtocolException;
import java.net.SocketAddress;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.mail.internet.MimeUtility;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.LineParser;
import org.bluezoo.gumdrop.NullSecurityInfo;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.h2.H2FrameHandler;
import org.bluezoo.gumdrop.http.h2.H2Parser;
import org.bluezoo.gumdrop.http.h2.H2Writer;
import org.bluezoo.gumdrop.http.hpack.Decoder;
import org.bluezoo.gumdrop.http.hpack.Encoder;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

/**
 * HTTP/1.1 and HTTP/2 protocol handler using {@link ProtocolHandler} and
 * {@link LineParser}.
 *
 * <p>Implements the HTTP protocol with the transport layer fully decoupled
 * via composition:
 * <ul>
 * <li>Transport operations delegate to an {@link Endpoint} reference
 *     received in {@link #connected(Endpoint)}</li>
 * <li>Line parsing uses the composable {@link LineParser} utility</li>
 * <li>TLS upgrade uses {@link Endpoint#startTLS()}</li>
 * <li>Security info uses {@link Endpoint#getSecurityInfo()}</li>
 * </ul>
 *
 * <p>Implements {@link HTTPConnectionLike} so that {@link Stream} can
 * work with either HTTPConnection or HTTPProtocolHandler.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ProtocolHandler
 * @see LineParser
 * @see HTTPConnectionLike
 */
public class HTTPProtocolHandler
        implements ProtocolHandler, LineParser.Callback, H2FrameHandler, HTTPConnectionLike {

    static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.http.L10N");
    static final Logger LOGGER =
            Logger.getLogger(HTTPProtocolHandler.class.getName());

    static final Charset US_ASCII = Charset.forName("US-ASCII");
    static final CharsetDecoder US_ASCII_DECODER = US_ASCII.newDecoder();
    static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");
    static final CharsetDecoder ISO_8859_1_DECODER = ISO_8859_1.newDecoder();

    private static final Set<String> DEFAULT_METHODS = new HashSet<String>(Arrays.asList(
        "GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "OPTIONS", "TRACE", "PATCH",
        "PRI",
        "PROPFIND", "PROPPATCH", "MKCOL", "COPY", "MOVE", "LOCK", "UNLOCK"
    ));

    private static final int MAX_LINE_LENGTH = 8192;
    private static final int CRLF_LENGTH = 2;
    private static final int FRAME_HEADER_LENGTH = 9;
    private static final int PRI_CONTINUATION_LENGTH = 8;
    private static final int DEFAULT_HEADER_TABLE_SIZE = 4096;
    private static final int DEFAULT_INITIAL_WINDOW_SIZE = 65535;
    private static final int DEFAULT_MAX_FRAME_SIZE = 16384;
    private static final int INITIAL_CLIENT_STREAM_ID = 1;
    private static final int INITIAL_SERVER_STREAM_ID = 2;
    private static final int HEADER_VALUE_BUFFER_SIZE = 4096;
    private static final int HPACK_ENCODE_BUFFER_SIZE = 8192;
    private static final long STREAM_CLEANUP_INTERVAL_MS = 30000L;
    private static final long STREAM_RETENTION_MS = 30000L;
    private static final int CHARSET_UNICODE = 1;
    private static final int CHARSET_Q_ENCODING = 2;

    private enum State {
        REQUEST_LINE,
        HEADER,
        BODY,
        BODY_CHUNKED_SIZE,
        BODY_CHUNKED_DATA,
        BODY_CHUNKED_TRAILER,
        BODY_UNTIL_CLOSE,
        PRI,
        PRI_SETTINGS,
        HTTP2,
        HTTP2_CONTINUATION,
        WEBSOCKET
    }

    private Endpoint endpoint;

    private final HTTPListener server;
    private final int framePadding;

    private HTTPAuthenticationProvider authenticationProvider;
    private HTTPRequestHandlerFactory handlerFactory;

    HTTPVersion version = HTTPVersion.HTTP_1_0;

    private State state = State.REQUEST_LINE;
    private CharBuffer charBuffer;

    private String headerName;
    private CharBuffer headerValue;

    private int currentChunkSize = 0;
    private int chunkBytesRemaining = 0;

    int headerTableSize = DEFAULT_HEADER_TABLE_SIZE;
    boolean enablePush = true;
    int maxConcurrentStreams = Integer.MAX_VALUE;
    int initialWindowSize = DEFAULT_INITIAL_WINDOW_SIZE;
    int maxFrameSize = DEFAULT_MAX_FRAME_SIZE;
    int maxHeaderListSize = Integer.MAX_VALUE;

    Decoder hpackDecoder;
    Encoder hpackEncoder;

    private H2Parser h2Parser;
    private H2Writer h2Writer;

    private int clientStreamId = INITIAL_CLIENT_STREAM_ID;
    private int serverStreamId = INITIAL_SERVER_STREAM_ID;
    private final Map<Integer, Stream> streams = new TreeMap<Integer, Stream>();
    private int continuationStream;
    private boolean continuationEndStream;
    private final Set<Integer> activeStreams = new TreeSet<Integer>();

    private boolean h2cUpgradePending;
    private long lastStreamCleanup = 0L;
    private int webSocketStreamId = -1;

    /**
     * Creates a new HTTP endpoint handler.
     *
     * @param server the HTTP server configuration
     */
    public HTTPProtocolHandler(HTTPListener server) {
        this(server, 0);
    }

    /**
     * Creates a new HTTP endpoint handler with frame padding.
     *
     * @param server the HTTP server endpoint configuration
     * @param framePadding HTTP/2 frame padding (0-255)
     */
    public HTTPProtocolHandler(HTTPListener server, int framePadding) {
        this.server = server;
        this.framePadding = framePadding;
        this.charBuffer = CharBuffer.allocate(MAX_LINE_LENGTH);
        this.authenticationProvider = server.getAuthenticationProvider();
        this.handlerFactory = server.getHandlerFactory();
    }

    /**
     * Sets the authentication provider.
     */
    public void setAuthenticationProvider(HTTPAuthenticationProvider provider) {
        this.authenticationProvider = provider;
    }

    /**
     * Sets the handler factory.
     */
    public void setHandlerFactory(HTTPRequestHandlerFactory factory) {
        this.handlerFactory = factory;
    }

    // ── ProtocolHandler implementation ──

    @Override
    public void connected(Endpoint endpoint) {
        this.endpoint = endpoint;

        HTTPServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            metrics.connectionOpened();
        }
    }

    @Override
    public void receive(ByteBuffer buf) {
        // If HTTP/2 was negotiated via ALPN but parser not yet initialized,
        // we shouldn't receive data yet (securityEstablished should be called first)
        if ((state == State.PRI_SETTINGS || state == State.HTTP2 
                || state == State.HTTP2_CONTINUATION) && h2Parser == null) {
            LOGGER.warning("Received data in HTTP/2 state but parser not initialized");
            closeEndpoint();
            return;
        }

        while (buf.hasRemaining()) {
            int positionBefore = buf.position();

            switch (state) {
                case REQUEST_LINE:
                case HEADER:
                case BODY_CHUNKED_SIZE:
                case BODY_CHUNKED_TRAILER:
                    LineParser.parse(buf, this);
                    break;
                case BODY:
                    receiveBody(buf);
                    break;
                case BODY_CHUNKED_DATA:
                    receiveChunkedData(buf);
                    break;
                case BODY_UNTIL_CLOSE:
                    receiveBodyUntilClose(buf);
                    break;
                case PRI:
                    receivePri(buf);
                    break;
                case PRI_SETTINGS:
                case HTTP2:
                case HTTP2_CONTINUATION:
                    receiveFrameData(buf);
                    break;
                case WEBSOCKET:
                    receiveWebSocket(buf);
                    return;
            }

            if (buf.position() == positionBefore) {
                break;
            }
        }
    }

    @Override
    public void disconnected() {
        HTTPServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            metrics.connectionClosed();
        }
        cleanupAllStreams();
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
        // Check if HTTP/2 was negotiated via ALPN
        String alpn = info != null ? info.getApplicationProtocol() : null;
        if ("h2".equals(alpn)) {
            // Initialize HTTP/2 immediately (no PRI preface needed for ALPN)
            h2Parser = new H2Parser(this);
            h2Parser.setMaxFrameSize(maxFrameSize);
            h2Writer = new H2Writer(new EndpointChannel());
            version = HTTPVersion.HTTP_2_0;
            state = State.PRI_SETTINGS;
            // RFC 7540 Section 3.5: Server connection preface MUST be
            // a SETTINGS frame as the first frame sent in the connection
            sendSettingsFrame(false,
                    new LinkedHashMap<Integer, Integer>());
        }
        // For HTTP/1.1, state remains REQUEST_LINE (default)
    }

    @Override
    public void error(Exception cause) {
        LOGGER.log(Level.WARNING, "HTTP transport error", cause);
        closeEndpoint();
    }

    // ── LineParser.Callback implementation ──

    @Override
    public void lineReceived(ByteBuffer line) {
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

    @Override
    public boolean continueLineProcessing() {
        return state == State.REQUEST_LINE
                || state == State.HEADER
                || state == State.BODY_CHUNKED_SIZE
                || state == State.BODY_CHUNKED_TRAILER;
    }

    // ── HTTPConnectionLike implementation ──

    @Override
    public String getScheme() {
        return endpoint != null && endpoint.isSecure() ? "https" : "http";
    }

    @Override
    public HTTPVersion getVersion() {
        return version;
    }

    @Override
    public SocketAddress getRemoteSocketAddress() {
        return endpoint != null ? endpoint.getRemoteAddress() : null;
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
        return endpoint != null ? endpoint.getLocalAddress() : null;
    }

    @Override
    public SecurityInfo getSecurityInfoForStream() {
        if (endpoint == null || !endpoint.isSecure()) {
            return NullSecurityInfo.INSTANCE;
        }
        return endpoint.getSecurityInfo();
    }

    @Override
    public HTTPRequestHandlerFactory getHandlerFactory() {
        return handlerFactory;
    }

    @Override
    public void sendResponseHeaders(int streamId, int statusCode, Headers headers, boolean endStream) {
        String altSvc = server.getAltSvc();
        if (altSvc != null) {
            headers.add(new Header("Alt-Svc", altSvc));
        }

        ByteBuffer buf;
        boolean success = false;
        switch (state) {
            case HTTP2:
                // Strip headers that are illegal in HTTP/2
                // (RFC 7540 Section 8.1.2.2)
                headers.removeAll("Connection");
                headers.removeAll("Keep-Alive");
                headers.removeAll("Proxy-Connection");
                headers.removeAll("Transfer-Encoding");
                headers.removeAll("Upgrade");
                headers.add(0, new Header(":status", Integer.toString(statusCode)));
                int streamDependency = 0;
                boolean streamDependencyExclusive = false;
                int weight = 0;
                int padLength = framePadding;
                buf = ByteBuffer.allocate(headerTableSize);
                while (!success) {
                    try {
                        hpackEncoder.encode(buf, headers);
                        success = true;
                    } catch (BufferOverflowException e) {
                        buf = ByteBuffer.allocate(buf.capacity() + headerTableSize);
                    } catch (ProtocolException e) {
                        sendGoaway(H2FrameHandler.ERROR_COMPRESSION_ERROR);
                        return;
                    }
                }
                buf.flip();
                int length = buf.remaining();
                try {
                    if (length <= headerTableSize) {
                        h2Writer.writeHeaders(streamId, buf, endStream, true,
                                padLength, streamDependency, weight, streamDependencyExclusive);
                        h2Writer.flush();
                    } else {
                        int savedLimit = buf.limit();
                        buf.limit(buf.position() + headerTableSize);
                        ByteBuffer fragment = buf.slice();
                        buf.limit(savedLimit);
                        buf.position(buf.position() + headerTableSize);
                        h2Writer.writeHeaders(streamId, fragment, endStream, false,
                                padLength, streamDependency, weight, streamDependencyExclusive);
                        length -= headerTableSize;
                        while (length > headerTableSize) {
                            buf.limit(buf.position() + headerTableSize);
                            fragment = buf.slice();
                            buf.limit(savedLimit);
                            buf.position(buf.position() + headerTableSize);
                            h2Writer.writeContinuation(streamId, fragment, false);
                            length -= headerTableSize;
                        }
                        h2Writer.writeContinuation(streamId, buf, true);
                        h2Writer.flush();
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error sending headers", e);
                }
                break;
            default:
                buf = ByteBuffer.allocate(headerTableSize);
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

    @Override
    public void sendResponseBody(int streamId, ByteBuffer buf, boolean endStream) {
        switch (state) {
            case HTTP2:
                try {
                    h2Writer.writeData(streamId, buf, endStream, framePadding);
                    h2Writer.flush();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error sending data frame", e);
                }
                break;
            default:
                send(buf);
        }
    }

    @Override
    public void send(ByteBuffer buf) {
        if (endpoint == null) {
            return;
        }
        if (buf == null) {
            endpoint.close();
            return;
        }
        endpoint.send(buf);
    }

    @Override
    public void sendRstStream(int streamId, int errorCode) {
        ByteBuffer buf = ByteBuffer.allocate(FRAME_HEADER_LENGTH + 4);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 4);
        buf.put((byte) H2FrameHandler.TYPE_RST_STREAM);
        buf.put((byte) 0);
        buf.putInt(streamId);
        buf.putInt(errorCode);
        buf.flip();
        send(buf);
    }

    @Override
    public void sendGoaway(int errorCode) {
        int lastStreamId = clientStreamId > 0 ? clientStreamId : 0;
        ByteBuffer buf = ByteBuffer.allocate(FRAME_HEADER_LENGTH + 8);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 8);
        buf.put((byte) H2FrameHandler.TYPE_GOAWAY);
        buf.put((byte) 0);
        buf.putInt(0);
        buf.putInt(lastStreamId);
        buf.putInt(errorCode);
        buf.flip();
        send(buf);
        closeEndpoint();
    }

    @Override
    public void switchToWebSocketMode(int streamId) {
        this.webSocketStreamId = streamId;
        this.state = State.WEBSOCKET;
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Switched to WebSocket mode for stream " + streamId);
        }
    }

    @Override
    public Decoder getHpackDecoder() {
        return hpackDecoder;
    }

    @Override
    public boolean isSecure() {
        return endpoint != null && endpoint.isSecure();
    }

    @Override
    public SelectorLoop getSelectorLoop() {
        return endpoint != null ? endpoint.getSelectorLoop() : null;
    }

    @Override
    public TelemetryConfig getTelemetryConfig() {
        return endpoint != null ? endpoint.getTelemetryConfig() : null;
    }

    @Override
    public Trace getTrace() {
        return endpoint != null ? endpoint.getTrace() : null;
    }

    @Override
    public void setTrace(Trace trace) {
        if (endpoint != null) {
            endpoint.setTrace(trace);
        }
    }

    @Override
    public boolean isTelemetryEnabled() {
        return endpoint != null && endpoint.isTelemetryEnabled();
    }

    @Override
    public HTTPServerMetrics getServerMetrics() {
        return server != null ? server.getMetrics() : null;
    }

    @Override
    public boolean isEnablePush() {
        return enablePush;
    }

    @Override
    public Stream newStream(HTTPConnectionLike connection, int streamId) {
        return new Stream(connection, streamId);
    }

    @Override
    public int getNextServerStreamId() {
        int nextId = serverStreamId;
        serverStreamId += 2;
        return nextId;
    }

    @Override
    public byte[] encodeHeaders(Headers headers) {
        try {
            if (hpackEncoder == null) {
                hpackEncoder = new Encoder(headerTableSize, maxHeaderListSize);
            }
            ByteBuffer buffer = ByteBuffer.allocate(HPACK_ENCODE_BUFFER_SIZE);
            hpackEncoder.encode(buffer, headers);
            buffer.flip();
            byte[] encoded = new byte[buffer.remaining()];
            buffer.get(encoded);
            return encoded;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to encode headers using HPACK", e);
            return new byte[0];
        }
    }

    @Override
    public void sendPushPromise(int streamId, int promisedStreamId,
            ByteBuffer headerBlock, boolean endHeaders) {
        if (h2Writer != null) {
            try {
                h2Writer.writePushPromise(streamId, promisedStreamId, headerBlock, endHeaders);
                h2Writer.flush();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error sending PUSH_PROMISE", e);
            }
        }
    }

    @Override
    public Stream createPushedStream(int streamId, String method, String uri, Headers headers) {
        try {
            Stream pushedStream = newStream(this, streamId);
            pushedStream.setPushPromise();
            for (Header header : headers) {
                pushedStream.addHeader(header);
            }
            synchronized (streams) {
                streams.put(streamId, pushedStream);
            }
            return pushedStream;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to create pushed stream " + streamId, e);
            return null;
        }
    }

    // ── Private helpers ──

    private void closeEndpoint() {
        if (endpoint != null) {
            endpoint.close();
        }
    }

    private boolean isMethodSupported(String method) {
        if (handlerFactory != null) {
            Set<String> customMethods = handlerFactory.getSupportedMethods();
            if (customMethods != null) {
                return customMethods.contains(method);
            }
        }
        return DEFAULT_METHODS.contains(method);
    }

    private Stream getStream(int streamId) {
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

    private void maybeCleanupClosedStreams() {
        long now = System.currentTimeMillis();
        if (now - lastStreamCleanup < STREAM_CLEANUP_INTERVAL_MS) {
            return;
        }
        synchronized (streams) {
            if (now - lastStreamCleanup < STREAM_CLEANUP_INTERVAL_MS) {
                return;
            }
            lastStreamCleanup = now;
            int removedCount = 0;
            Iterator<Map.Entry<Integer, Stream>> iterator = streams.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Integer, Stream> entry = iterator.next();
                Stream stream = entry.getValue();
                if (stream.isClosed()
                        && (now - stream.timestampCompleted) > STREAM_RETENTION_MS) {
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

    private void sendStreamError(Stream stream, int statusCode) {
        try {
            stream.sendError(statusCode);
        } catch (ProtocolException e) {
            String message = L10N.getString("err.send_headers");
            LOGGER.log(Level.SEVERE, message, e);
        }
    }

    private void processRequestLine(ByteBuffer line) {
        Stream stream = getStream(clientStreamId);
        int lineLength = line.remaining();
        if (lineLength > MAX_LINE_LENGTH + CRLF_LENGTH) {
            sendStreamError(stream, 414);
            return;
        }
        charBuffer.clear();
        US_ASCII_DECODER.reset();
        CoderResult result = US_ASCII_DECODER.decode(line, charBuffer, true);
        if (result.isError()) {
            sendStreamError(stream, 400);
            return;
        }
        charBuffer.flip();
        int len = charBuffer.limit();
        if (len >= CRLF_LENGTH && charBuffer.get(len - 2) == '\r' && charBuffer.get(len - 1) == '\n') {
            charBuffer.limit(len - CRLF_LENGTH);
        }
        String lineStr = charBuffer.toString();
        int mi = lineStr.indexOf(' ', 1);
        int ui = (mi > 0) ? lineStr.indexOf(' ', mi + 2) : -1;
        if (mi == -1 || ui == -1) {
            sendStreamError(stream, 400);
            return;
        }
        String method = lineStr.substring(0, mi);
        String requestTarget = lineStr.substring(mi + 1, ui);
        String versionStr = lineStr.substring(ui + 1);
        this.version = HTTPVersion.fromString(versionStr);
        if (!HTTPUtils.isValidMethod(method)) {
            sendStreamError(stream, 400);
            return;
        }
        if (!isMethodSupported(method)) {
            sendStreamError(stream, 501);
            return;
        }
        if (!HTTPUtils.isValidRequestTarget(requestTarget)) {
            sendStreamError(stream, 400);
            return;
        }
        switch (this.version) {
            case UNKNOWN:
                sendStreamError(stream, 505);
                return;
            case HTTP_2_0:
                if ("PRI".equals(method) && "*".equals(requestTarget)) {
                    state = State.PRI;
                } else {
                    sendStreamError(stream, 400);
                }
                return;
            case HTTP_1_0:
                stream.closeConnection = true;
            default:
                stream.addHeader(new Header(":method", method));
                stream.addHeader(new Header(":path", requestTarget));
                stream.addHeader(new Header(":scheme", endpoint.isSecure() ? "https" : "http"));
                headerName = null;
                headerValue = CharBuffer.allocate(HEADER_VALUE_BUFFER_SIZE);
                state = State.HEADER;
        }
    }

    private void processHeaderLine(ByteBuffer line) {
        Stream stream = getStream(clientStreamId);
        int lineLength = line.remaining();
        if (lineLength > MAX_LINE_LENGTH + CRLF_LENGTH) {
            sendStreamError(stream, 431);
            return;
        }
        charBuffer.clear();
        ISO_8859_1_DECODER.reset();
        CoderResult result = ISO_8859_1_DECODER.decode(line, charBuffer, true);
        if (result.isError()) {
            sendStreamError(stream, 400);
            return;
        }
        charBuffer.flip();
        int len = charBuffer.limit();
        if (len >= CRLF_LENGTH && charBuffer.get(len - 2) == '\r' && charBuffer.get(len - 1) == '\n') {
            charBuffer.limit(len - CRLF_LENGTH);
        }
        String lineStr = charBuffer.toString();
        if (lineStr.length() == 0) {
            endHeaders(stream);
        } else {
            char c0 = lineStr.charAt(0);
            if (headerName != null && (c0 == ' ' || c0 == '\t')) {
                if (c0 == '\t') {
                    lineStr = " " + lineStr.substring(1);
                }
                appendHeaderValue(lineStr);
            } else {
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

    private void endHeaders(Stream stream) {
        if (headerName != null) {
            headerValue.flip();
            String v = headerValue.toString();
            stream.addHeader(new Header(headerName, v));
            headerName = null;
            headerValue = null;
        }
        if (this.version == HTTPVersion.HTTP_1_1) {
            boolean hasHost = false;
            for (Header header : stream.getHeaders()) {
                if (header.getName().equalsIgnoreCase("host")
                        || header.getName().equals(":authority")) {
                    hasHost = true;
                    break;
                }
            }
            if (!hasHost) {
                sendStreamError(stream, 400);
                return;
            }
        }
        stream.streamEndHeaders();
        if (stream.upgrade != null && stream.upgrade.contains("h2c") && stream.h2cSettings != null) {
            long contentLength = stream.getContentLength();
            boolean chunked = stream.isChunked();
            if (contentLength == 0L && !chunked) {
                completeH2cUpgrade();
                return;
            } else {
                h2cUpgradePending = true;
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("h2c upgrade pending until request body consumed");
                }
            }
        }
        long contentLength = stream.getContentLength();
        boolean chunked = stream.isChunked();
        if (contentLength == 0L) {
            stream.streamEndRequest();
            if (h2cUpgradePending) {
                completeH2cUpgrade();
            } else {
                state = State.REQUEST_LINE;
            }
            clientStreamId += 2;
        } else if (chunked) {
            state = State.BODY_CHUNKED_SIZE;
        } else if (contentLength > 0L) {
            state = State.BODY;
        } else if (this.version == HTTPVersion.HTTP_1_0) {
            state = State.BODY_UNTIL_CLOSE;
        } else {
            sendStreamError(stream, 411);
        }
    }

    private void processChunkSizeLine(ByteBuffer line) {
        Stream stream = getStream(clientStreamId);
        charBuffer.clear();
        US_ASCII_DECODER.reset();
        CoderResult result = US_ASCII_DECODER.decode(line, charBuffer, true);
        if (result.isError()) {
            sendStreamError(stream, 400);
            return;
        }
        charBuffer.flip();
        int len = charBuffer.limit();
        if (len >= CRLF_LENGTH && charBuffer.get(len - 2) == '\r' && charBuffer.get(len - 1) == '\n') {
            charBuffer.limit(len - CRLF_LENGTH);
        }
        String lineStr = charBuffer.toString();
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
            state = State.BODY_CHUNKED_TRAILER;
        } else {
            state = State.BODY_CHUNKED_DATA;
        }
    }

    private void processTrailerLine(ByteBuffer line) {
        Stream stream = getStream(clientStreamId);
        charBuffer.clear();
        US_ASCII_DECODER.reset();
        CoderResult result = US_ASCII_DECODER.decode(line, charBuffer, true);
        if (result.isError()) {
            sendStreamError(stream, 400);
            return;
        }
        charBuffer.flip();
        int len = charBuffer.limit();
        if (len >= CRLF_LENGTH && charBuffer.get(len - 2) == '\r' && charBuffer.get(len - 1) == '\n') {
            charBuffer.limit(len - CRLF_LENGTH);
        }
        String lineStr = charBuffer.toString();
        if (lineStr.length() == 0) {
            stream.streamEndRequest();
            if (h2cUpgradePending) {
                completeH2cUpgrade();
            } else {
                state = State.REQUEST_LINE;
            }
            clientStreamId += 2;
        }
    }

    private void receiveBody(ByteBuffer buf) {
        Stream stream = getStream(clientStreamId);
        long contentLength = stream.getContentLength();
        if (contentLength == -1L) {
            sendStreamError(stream, 411);
            return;
        }
        int available = buf.remaining();
        if (available < 1) {
            return;
        }
        long needed = stream.getRequestBodyBytesNeeded();
        if ((long) available > needed) {
            available = (int) needed;
        }
        int savedLimit = buf.limit();
        buf.limit(buf.position() + available);
        stream.receiveRequestBody(buf);
        buf.limit(savedLimit);
        if (stream.getRequestBodyBytesNeeded() == 0L) {
            stream.streamEndRequest();
            if (h2cUpgradePending) {
                completeH2cUpgrade();
            } else {
                state = State.REQUEST_LINE;
            }
            clientStreamId += 2;
        }
    }

    private void receiveChunkedData(ByteBuffer buf) {
        Stream stream = getStream(clientStreamId);
        int available = buf.remaining();
        if (available < 1) {
            return;
        }
        int toRead = Math.min(available, chunkBytesRemaining);
        if (toRead > 0) {
            int savedLimit = buf.limit();
            buf.limit(buf.position() + toRead);
            stream.receiveRequestBody(buf);
            buf.limit(savedLimit);
            chunkBytesRemaining -= toRead;
        }
        if (chunkBytesRemaining == 0) {
            if (buf.remaining() >= CRLF_LENGTH) {
                byte cr = buf.get();
                byte lf = buf.get();
                if (cr != '\r' || lf != '\n') {
                    sendStreamError(stream, 400);
                    return;
                }
                state = State.BODY_CHUNKED_SIZE;
            }
        }
    }

    private void receiveBodyUntilClose(ByteBuffer buf) {
        Stream stream = getStream(clientStreamId);
        if (buf.hasRemaining()) {
            stream.receiveRequestBody(buf);
        }
    }

    private void receivePri(ByteBuffer buf) {
        Stream stream = getStream(clientStreamId);
        if (buf.remaining() < PRI_CONTINUATION_LENGTH) {
            return;
        }
        byte[] smt = new byte[PRI_CONTINUATION_LENGTH];
        buf.get(smt);
        if (smt[0] != '\r' || smt[1] != '\n' || smt[2] != 'S' || smt[3] != 'M'
                || smt[4] != '\r' || smt[5] != '\n' || smt[6] != '\r' || smt[7] != '\n') {
            sendStreamError(stream, 400);
            return;
        }
        h2Parser = new H2Parser(this);
        h2Parser.setMaxFrameSize(maxFrameSize);
        h2Writer = new H2Writer(new EndpointChannel());
        state = State.PRI_SETTINGS;
    }

    private void receiveFrameData(ByteBuffer buf) {
        if (h2Parser == null) {
            // HTTP/2 not initialized yet - this shouldn't happen
            LOGGER.warning("Received HTTP/2 frame data but parser not initialized");
            closeEndpoint();
            return;
        }
        // The buffer should already be in read mode (flipped) when delivered from SSLState
        if (!buf.hasRemaining()) {
            return;
        }
        
        // Check if we're in PRI_SETTINGS state and receiving the connection preface
        // Clients send "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n" (24 bytes) even
        // when HTTP/2 was already negotiated via ALPN
        if (state == State.PRI_SETTINGS && buf.remaining() >= 24) {
            int pos = buf.position();
            if (buf.get(pos) == 'P' && buf.get(pos + 1) == 'R'
                    && buf.get(pos + 2) == 'I'
                    && buf.get(pos + 3) == ' ' && buf.get(pos + 4) == '*'
                    && buf.get(pos + 5) == ' ' && buf.get(pos + 6) == 'H'
                    && buf.get(pos + 7) == 'T' && buf.get(pos + 8) == 'T'
                    && buf.get(pos + 9) == 'P' && buf.get(pos + 10) == '/'
                    && buf.get(pos + 11) == '2' && buf.get(pos + 12) == '.'
                    && buf.get(pos + 13) == '0' && buf.get(pos + 14) == '\r'
                    && buf.get(pos + 15) == '\n' && buf.get(pos + 16) == '\r'
                    && buf.get(pos + 17) == '\n' && buf.get(pos + 18) == 'S'
                    && buf.get(pos + 19) == 'M' && buf.get(pos + 20) == '\r'
                    && buf.get(pos + 21) == '\n' && buf.get(pos + 22) == '\r'
                    && buf.get(pos + 23) == '\n') {
                buf.position(pos + 24);
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Consumed HTTP/2 connection preface"
                            + " (24 bytes)");
                }
            }
        }
        
        // Log first few bytes for debugging frame parsing issues
        if (buf.remaining() >= 9 && LOGGER.isLoggable(Level.FINE)) {
            int pos = buf.position();
            byte[] preview = new byte[Math.min(24, buf.remaining())];
            for (int i = 0; i < preview.length && (pos + i) < buf.limit(); i++) {
                preview[i] = buf.get(pos + i);
            }
            StringBuilder hex = new StringBuilder();
            StringBuilder ascii = new StringBuilder();
            for (int i = 0; i < Math.min(9, preview.length); i++) {
                hex.append(String.format("%02x ", preview[i] & 0xff));
                char c = (char) (preview[i] & 0xff);
                ascii.append((c >= 32 && c < 127) ? c : '.');
            }
            LOGGER.fine("HTTP/2 frame data (first 9 bytes): hex=[" + hex.toString().trim() 
                + "] ascii=[" + ascii.toString() + "]");
        }
        h2Parser.receive(buf);
    }

    private void receiveWebSocket(ByteBuffer buf) {
        Stream stream = getStream(webSocketStreamId);
        if (stream == null) {
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning(MessageFormat.format(
                        L10N.getString("warn.websocket_stream_not_found"), webSocketStreamId));
            }
            return;
        }
        if (buf.hasRemaining()) {
            stream.appendRequestBody(buf);
        }
    }

    private boolean expectingInitialSettings() {
        if (state == State.PRI_SETTINGS) {
            sendGoaway(H2FrameHandler.ERROR_PROTOCOL_ERROR);
            return true;
        }
        return false;
    }

    private void completeH2cUpgrade() {
        h2cUpgradePending = false;
        Headers responseHeaders = new Headers();
        responseHeaders.add("Connection", "Upgrade");
        responseHeaders.add("Upgrade", "h2c");
        sendResponseHeaders(clientStreamId, 101, responseHeaders, true);
        state = State.REQUEST_LINE;
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Sent 101 Switching Protocols, waiting for client preface");
        }
    }

    private void sendSettingsFrame(boolean ack, Map<Integer, Integer> settings) {
        int payloadLength = ack ? 0 : settings.size() * 6;
        ByteBuffer buf = ByteBuffer.allocate(FRAME_HEADER_LENGTH + payloadLength);
        buf.put((byte) ((payloadLength >> 16) & 0xff));
        buf.put((byte) ((payloadLength >> 8) & 0xff));
        buf.put((byte) (payloadLength & 0xff));
        buf.put((byte) H2FrameHandler.TYPE_SETTINGS);
        buf.put((byte) (ack ? H2FrameHandler.FLAG_ACK : 0));
        buf.putInt(0);
        if (!ack) {
            for (Map.Entry<Integer, Integer> entry : settings.entrySet()) {
                int id = entry.getKey();
                int value = entry.getValue();
                buf.put((byte) ((id >> 8) & 0xff));
                buf.put((byte) (id & 0xff));
                buf.put((byte) ((value >> 24) & 0xff));
                buf.put((byte) ((value >> 16) & 0xff));
                buf.put((byte) ((value >> 8) & 0xff));
                buf.put((byte) (value & 0xff));
            }
        }
        buf.flip();
        send(buf);
    }

    private void sendSettingsAck() {
        sendSettingsFrame(true, Collections.<Integer, Integer>emptyMap());
    }

    private void sendPingAck(long opaqueData) {
        ByteBuffer buf = ByteBuffer.allocate(FRAME_HEADER_LENGTH + 8);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 8);
        buf.put((byte) H2FrameHandler.TYPE_PING);
        buf.put((byte) H2FrameHandler.FLAG_ACK);
        buf.putInt(0);
        buf.putLong(opaqueData);
        buf.flip();
        send(buf);
    }

    private void appendHeaderValue(String l) {
        int len = l.length();
        StringBuilder quoteBuf = null;
        boolean escaped = false;
        boolean text = false;
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
            CharBuffer tmp = CharBuffer.allocate(headerValue.remaining()
                    + Math.max(HEADER_VALUE_BUFFER_SIZE, required));
            headerValue.flip();
            tmp.put(headerValue);
            headerValue = tmp;
        }
        if (headerValue.position() > 0) {
            headerValue.append(' ');
        }
        headerValue.append(l, start, end);
    }

    private void writeStatusLineAndHeaders(ByteBuffer buf, int statusCode, Headers headers) {
        try {
            String statusLine = String.format("%s %03d %s\r\n",
                    version.toString(), statusCode, HTTPConstants.getMessage(statusCode));
            buf.put(statusLine.getBytes(US_ASCII));
            for (Header header : headers) {
                String name = header.getName();
                if (name.charAt(0) == ':') {
                    continue;
                }
                String value = header.getValue();
                if (value == null) {
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
            buf.put(new byte[] { (byte) 0x0d, (byte) 0x0a });
        } catch (IOException e) {
            RuntimeException e2 = new RuntimeException();
            e2.initCause(e);
            throw e2;
        }
    }

    private static int getCharsetFlags(String text) {
        int asciiCount = 0;
        int nonAsciiCount = 0;
        int len = text.length();
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

    private void cleanupAllStreams() {
        synchronized (streams) {
            int streamCount = streams.size();
            for (Stream stream : streams.values()) {
                stream.streamClose();
            }
            streams.clear();
            if (streamCount > 0 && LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(String.format("Connection closing: cleaned up %d remaining streams",
                        streamCount));
            }
        }
        synchronized (activeStreams) {
            activeStreams.clear();
        }
    }

    // ── H2FrameHandler implementation ──

    @Override
    public void dataFrameReceived(int streamId, boolean endStream, ByteBuffer data) {
        if (expectingInitialSettings()) {
            return;
        }
        Stream stream = getStream(streamId);
        stream.appendRequestBody(data);
        if (endStream) {
            stream.streamEndRequest();
            if (stream.isActive()) {
                synchronized (activeStreams) {
                    activeStreams.add(streamId);
                }
            }
        }
    }

    @Override
    public void headersFrameReceived(int streamId, boolean endStream, boolean endHeaders,
            int streamDependency, boolean exclusive, int weight,
            ByteBuffer headerBlockFragment) {
        if (expectingInitialSettings()) {
            return;
        }
        Stream stream = getStream(streamId);
        stream.appendHeaderBlockFragment(headerBlockFragment);
        if (endHeaders) {
            stream.streamEndHeaders();
            if (endStream) {
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
            continuationEndStream = endStream;
        }
    }

    @Override
    public void priorityFrameReceived(int streamId, int streamDependency,
            boolean exclusive, int weight) {
        if (expectingInitialSettings()) {
            return;
        }
    }

    @Override
    public void rstStreamFrameReceived(int streamId, int errorCode) {
        if (expectingInitialSettings()) {
            return;
        }
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("RST_STREAM received: stream=" + streamId
                    + ", error="
                    + H2FrameHandler.errorToString(errorCode));
        }
        Stream stream = getStream(streamId);
        stream.streamClose();
        synchronized (activeStreams) {
            activeStreams.remove(streamId);
        }
    }

    @Override
    public void settingsFrameReceived(boolean ack, Map<Integer, Integer> settings) {
        if (state == State.PRI_SETTINGS) {
            // Client's initial SETTINGS frame received
            if (!ack) {
                // Process client's settings
                for (Map.Entry<Integer, Integer> entry : settings.entrySet()) {
                    int identifier = entry.getKey();
                    int value = entry.getValue();
                    switch (identifier) {
                        case H2FrameHandler.SETTINGS_HEADER_TABLE_SIZE:
                            headerTableSize = value;
                            break;
                        case H2FrameHandler.SETTINGS_ENABLE_PUSH:
                            enablePush = (value == 1);
                            break;
                        case H2FrameHandler.SETTINGS_MAX_CONCURRENT_STREAMS:
                            maxConcurrentStreams = value;
                            break;
                        case H2FrameHandler.SETTINGS_INITIAL_WINDOW_SIZE:
                            initialWindowSize = value;
                            break;
                        case H2FrameHandler.SETTINGS_MAX_FRAME_SIZE:
                            maxFrameSize = value;
                            if (h2Parser != null) {
                                h2Parser.setMaxFrameSize(value);
                            }
                            break;
                        case H2FrameHandler.SETTINGS_MAX_HEADER_LIST_SIZE:
                            maxHeaderListSize = value;
                            break;
                    }
                }
                // Initialize HPACK with client's settings
                if (hpackDecoder == null) {
                    hpackDecoder = new Decoder(headerTableSize);
                }
                if (hpackEncoder == null) {
                    hpackEncoder = new Encoder(headerTableSize,
                            maxHeaderListSize);
                }
                // RFC 7540 Section 3.5: ACK client's SETTINGS
                // (our SETTINGS was already sent as the first frame
                // in securityEstablished or receivePri)
                sendSettingsAck();
            }
            state = State.HTTP2;
            Stream h2cStream = getStream(1);
            if (h2cStream != null) {
                h2cStream.streamEndRequest();
            }
            return;
        }
        if (!ack) {
            // Normal SETTINGS processing (not in PRI_SETTINGS state)
            for (Map.Entry<Integer, Integer> entry : settings.entrySet()) {
                int identifier = entry.getKey();
                int value = entry.getValue();
                switch (identifier) {
                    case H2FrameHandler.SETTINGS_HEADER_TABLE_SIZE:
                        headerTableSize = value;
                        break;
                    case H2FrameHandler.SETTINGS_ENABLE_PUSH:
                        enablePush = (value == 1);
                        break;
                    case H2FrameHandler.SETTINGS_MAX_CONCURRENT_STREAMS:
                        maxConcurrentStreams = value;
                        break;
                    case H2FrameHandler.SETTINGS_INITIAL_WINDOW_SIZE:
                        initialWindowSize = value;
                        break;
                    case H2FrameHandler.SETTINGS_MAX_FRAME_SIZE:
                        maxFrameSize = value;
                        if (h2Parser != null) {
                            h2Parser.setMaxFrameSize(value);
                        }
                        break;
                    case H2FrameHandler.SETTINGS_MAX_HEADER_LIST_SIZE:
                        maxHeaderListSize = value;
                        break;
                }
            }
            if (hpackDecoder != null) {
                hpackDecoder.setHeaderTableSize(headerTableSize);
            }
            if (hpackEncoder != null) {
                hpackEncoder.setHeaderTableSize(headerTableSize);
                hpackEncoder.setMaxHeaderListSize(maxHeaderListSize);
            }
            sendSettingsAck();
        }
    }

    @Override
    public void pushPromiseFrameReceived(int streamId, int promisedStreamId,
            boolean endHeaders, ByteBuffer headerBlockFragment) {
        if (expectingInitialSettings()) {
            return;
        }
        Stream stream = getStream(streamId);
        stream.setPushPromise();
        stream.appendHeaderBlockFragment(headerBlockFragment);
        if (endHeaders) {
            stream.streamEndHeaders();
            stream.streamEndRequest();
        } else {
            state = State.HTTP2_CONTINUATION;
            continuationStream = streamId;
            continuationEndStream = true;
        }
    }

    @Override
    public void pingFrameReceived(boolean ack, long opaqueData) {
        if (expectingInitialSettings()) {
            return;
        }
        if (!ack) {
            sendPingAck(opaqueData);
        }
    }

    @Override
    public void goawayFrameReceived(int lastStreamId, int errorCode, ByteBuffer debugData) {
        if (expectingInitialSettings()) {
            return;
        }
        closeEndpoint();
    }

    @Override
    public void windowUpdateFrameReceived(int streamId, int windowSizeIncrement) {
        if (expectingInitialSettings()) {
            return;
        }
    }

    @Override
    public void continuationFrameReceived(int streamId, boolean endHeaders,
            ByteBuffer headerBlockFragment) {
        if (expectingInitialSettings()) {
            return;
        }
        Stream stream = getStream(streamId);
        stream.appendHeaderBlockFragment(headerBlockFragment);
        if (endHeaders) {
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
    }

    @Override
    public void frameError(int errorCode, int streamId, String message) {
        if (LOGGER.isLoggable(Level.WARNING)) {
            LOGGER.warning("Frame error: " + message + " (error="
                    + H2FrameHandler.errorToString(errorCode) + ", stream=" + streamId + ")");
        }
        // For protocol-level errors (stream 0) or critical errors, send GOAWAY and close
        if (streamId == 0 || errorCode == H2FrameHandler.ERROR_PROTOCOL_ERROR
                || errorCode == H2FrameHandler.ERROR_FRAME_SIZE_ERROR) {
            sendGoaway(errorCode);
            closeEndpoint();
        } else {
            sendRstStream(streamId, errorCode);
        }
    }

    // ── WritableByteChannel adapter for H2Writer ──

    private class EndpointChannel implements WritableByteChannel {

        private boolean open = true;

        @Override
        public int write(ByteBuffer src) {
            if (!open) {
                return 0;
            }
            int written = src.remaining();
            if (written > 0) {
                ByteBuffer copy = ByteBuffer.allocate(written);
                copy.put(src);
                copy.flip();
                send(copy);
            }
            return written;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }

}
