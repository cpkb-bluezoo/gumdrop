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
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.mail.internet.MimeUtility;


import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.LineParser;
import org.bluezoo.gumdrop.NullSecurityInfo;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.http.h2.H2FlowControl;
import org.bluezoo.gumdrop.http.h2.H2FrameHandler;
import org.bluezoo.gumdrop.http.h2.H2Parser;
import org.bluezoo.gumdrop.http.h2.H2Writer;
import org.bluezoo.gumdrop.http.hpack.Decoder;
import org.bluezoo.gumdrop.http.hpack.Encoder;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.util.ByteBufferPool;

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
 * <p>HTTP/1.1 message syntax and routing per RFC 9112:
 * <ul>
 * <li>Request-line parsing (section 3)</li>
 * <li>Header field parsing (section 5)</li>
 * <li>Message body framing: Content-Length, chunked, until-close (sections 6-7)</li>
 * <li>Connection management and persistent connections (section 9)</li>
 * </ul>
 *
 * <p>HTTP semantics per RFC 9110:
 * <ul>
 * <li>Method validation and dispatch (section 9)</li>
 * <li>Status codes and reason phrases (section 15)</li>
 * <li>Date and Server response headers (section 6.6)</li>
 * </ul>
 *
 * <p>HTTP/2 framing and multiplexing per RFC 9113:
 * <ul>
 * <li>Connection startup: ALPN "h2" (section 3.2), prior knowledge (section 3.3),
 *     connection preface (section 3.4)</li>
 * <li>Frame parsing and serialization via {@link H2Parser} and {@link H2Writer}
 *     (section 4)</li>
 * <li>Stream lifecycle and concurrency limits (section 5.1)</li>
 * <li>Flow control via {@link H2FlowControl} (section 5.2)</li>
 * <li>Error handling: GOAWAY and RST_STREAM (sections 5.4, 7)</li>
 * <li>SETTINGS negotiation (section 6.5)</li>
 * </ul>
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

    // RFC 9112 section 2.1: HTTP/1.1 messages are parsed as a sequence of
    // octets in a superset of US-ASCII.
    static final Charset US_ASCII = Charset.forName("US-ASCII");
    static final CharsetDecoder US_ASCII_DECODER = US_ASCII.newDecoder();
    // RFC 9112 section 5.5: field values historically allowed ISO-8859-1.
    static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");
    static final CharsetDecoder ISO_8859_1_DECODER = ISO_8859_1.newDecoder();

    // RFC 9110 section 9: standard HTTP methods
    // RFC 9110 section 9.3.1: GET   (safe, idempotent)
    // RFC 9110 section 9.3.2: HEAD  (safe, idempotent, no response body)
    // RFC 9110 section 9.3.3: POST
    // RFC 9110 section 9.3.4: PUT   (idempotent)
    // RFC 9110 section 9.3.5: DELETE (idempotent)
    // RFC 9110 section 9.3.6: CONNECT (tunnel)
    // RFC 9110 section 9.3.7: OPTIONS (safe, idempotent)
    // RFC 9110 section 9.3.8: TRACE   (safe, idempotent, no request body)
    // Plus PATCH (RFC 5789), PRI (RFC 9113), WebDAV (RFC 4918)
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
    // RFC 9113 section 5.1.1: client-initiated streams use odd IDs,
    // server-initiated streams use even IDs
    private static final int INITIAL_CLIENT_STREAM_ID = 1;
    private static final int INITIAL_SERVER_STREAM_ID = 2;
    private static final int HEADER_VALUE_BUFFER_SIZE = 4096;
    private static final int HPACK_ENCODE_BUFFER_SIZE = 8192;
    private static final long STREAM_CLEANUP_INTERVAL_MS = 30000L;
    private static final long STREAM_RETENTION_MS = 30000L;
    private static final int CHARSET_UNICODE = 1;
    private static final int CHARSET_Q_ENCODING = 2;

    // RFC 9112 section 2: HTTP/1.1 message = start-line CRLF
    //                       *( field-line CRLF ) CRLF [ message-body ]
    private enum State {
        REQUEST_LINE,            // RFC 9112 section 3: request-line
        HEADER,                  // RFC 9112 section 5: field-line
        BODY,                    // RFC 9112 section 6.2: Content-Length delimited
        BODY_CHUNKED_SIZE,       // RFC 9112 section 7.1: chunk-size line
        BODY_CHUNKED_DATA,       // RFC 9112 section 7.1: chunk-data
        BODY_CHUNKED_TRAILER,    // RFC 9112 section 7.1.2: trailer section
        BODY_UNTIL_CLOSE,        // RFC 9112 section 6.3: read until close (HTTP/1.0)
        PRI,                     // RFC 9113 section 3.4: HTTP/2 connection preface
        PRI_SETTINGS,            // RFC 9113 section 3.4: awaiting client SETTINGS
        HTTP2,                   // RFC 9113 section 4: HTTP/2 frame processing
        HTTP2_CONTINUATION,      // RFC 9113 section 6.10: CONTINUATION frame
        WEBSOCKET                // RFC 6455: WebSocket
    }

    private Endpoint endpoint;

    private final HTTPListener server;
    private final int framePadding;
    private final int serverMaxConcurrentStreams;

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
    // RFC 9113 section 6.5.2: SETTINGS_ENABLE_PUSH (default: 1 = enabled)
    boolean enablePush = true;
    int maxConcurrentStreams = Integer.MAX_VALUE;
    int initialWindowSize = DEFAULT_INITIAL_WINDOW_SIZE;
    int maxFrameSize = DEFAULT_MAX_FRAME_SIZE;
    int maxHeaderListSize = Integer.MAX_VALUE;

    Decoder hpackDecoder;
    Encoder hpackEncoder;

    private H2Parser h2Parser;
    private H2Writer h2Writer;
    private H2FlowControl h2FlowControl;
    private final H2FlowControl.DataReceivedResult h2DataResult =
            new H2FlowControl.DataReceivedResult();
    private final Map<Integer, Runnable> h2WriteCallbacks =
            new LinkedHashMap<Integer, Runnable>();
    private final Map<Integer, PendingData> h2PendingData =
            new LinkedHashMap<Integer, PendingData>();

    private int clientStreamId = INITIAL_CLIENT_STREAM_ID;
    private int serverStreamId = INITIAL_SERVER_STREAM_ID;
    private int lastClientStreamId;
    private final Map<Integer, Stream> streams = new ConcurrentHashMap<Integer, Stream>();
    private int continuationStream;
    private boolean continuationEndStream;
    private final Set<Integer> activeStreams = new ConcurrentSkipListSet<Integer>();

    private boolean h2cUpgradePending;
    private long lastStreamCleanup = 0L;
    private int webSocketStreamId = -1;

    // RFC 9113 section 6.5.3: SETTINGS_TIMEOUT enforcement
    private static final long SETTINGS_ACK_TIMEOUT_MS = 5000L;
    private TimerHandle settingsTimeoutHandle;

    // RFC 9113 section 5.4.1: graceful GOAWAY two-phase delay
    private static final long GRACEFUL_GOAWAY_DELAY_MS = 1000L;
    private boolean goawaySent;

    // RFC 9113 section 6.7: PING keep-alive
    private TimerHandle pingKeepAliveHandle;

    // RFC 9112 section 9.8: idle connection timeout
    private TimerHandle idleTimeoutHandle;
    // RFC 9112 section 9.6: request counter for Connection: close
    private int requestCount = 0;

    /**
     * Creates a new HTTP endpoint handler.
     *
     * @param server the HTTP server configuration
     */
    public HTTPProtocolHandler(HTTPListener server) {
        this(server, 0, 100);
    }

    /**
     * Creates a new HTTP endpoint handler with frame padding.
     *
     * @param server the HTTP server endpoint configuration
     * @param framePadding HTTP/2 frame padding (0-255)
     */
    public HTTPProtocolHandler(HTTPListener server, int framePadding) {
        this(server, framePadding, 100);
    }

    /**
     * Creates a new HTTP endpoint handler with frame padding and
     * concurrent stream limit.
     *
     * @param server the HTTP server endpoint configuration
     * @param framePadding HTTP/2 frame padding (0-255)
     * @param serverMaxConcurrentStreams max concurrent streams to advertise
     */
    public HTTPProtocolHandler(HTTPListener server, int framePadding,
            int serverMaxConcurrentStreams) {
        this.server = server;
        this.framePadding = framePadding;
        this.serverMaxConcurrentStreams = serverMaxConcurrentStreams;
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
        // RFC 9112 section 9.8: start idle timeout if configured
        resetIdleTimeout();
    }

    @Override
    public void receive(ByteBuffer buf) {
        // RFC 9112 section 9.8: reset idle timer on activity
        resetIdleTimeout();
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

    // RFC 9113 section 3.2: HTTP/2 over TLS uses ALPN with "h2" identifier
    @Override
    public void securityEstablished(SecurityInfo info) {
        String alpn = info != null ? info.getApplicationProtocol() : null;
        if ("h2".equals(alpn)) {
            // RFC 9113 section 9.2.2: reject non-AEAD cipher suites
            // for TLS 1.2 (TLS 1.3 only has AEAD suites)
            if (info != null && isBlockedH2CipherSuite(info)) {
                LOGGER.warning("Blocked HTTP/2 cipher suite: "
                        + info.getCipherSuite());
                h2Parser = new H2Parser(this);
                h2Writer = new H2Writer(new EndpointChannel());
                version = HTTPVersion.HTTP_2_0;
                sendGoaway(H2FrameHandler.ERROR_INADEQUATE_SECURITY,
                        info.getCipherSuite());
                return;
            }
            h2Parser = new H2Parser(this);
            h2Parser.setMaxFrameSize(maxFrameSize);
            h2Writer = new H2Writer(new EndpointChannel());
            h2FlowControl = new H2FlowControl();
            version = HTTPVersion.HTTP_2_0;
            state = State.PRI_SETTINGS;
            // RFC 9113 section 3.4: server connection preface MUST be
            // a SETTINGS frame as the first frame sent
            Map<Integer, Integer> initialSettings = new LinkedHashMap<Integer, Integer>();
            initialSettings.put(H2FrameHandler.SETTINGS_MAX_CONCURRENT_STREAMS,
                    serverMaxConcurrentStreams);
            sendSettingsFrame(false, initialSettings);
            startSettingsTimeout();
        }
    }

    // RFC 9113 section 9.2.2: TLS 1.2 connections MUST use an AEAD
    // cipher suite; non-AEAD (CBC-mode) suites are blocklisted.
    // TLS 1.3 only defines AEAD suites, so the check is unnecessary.
    static boolean isBlockedH2CipherSuite(SecurityInfo info) {
        String protocol = info.getProtocol();
        if (protocol == null || protocol.startsWith("TLSv1.3")) {
            return false;
        }
        String cipher = info.getCipherSuite();
        if (cipher == null) {
            return false;
        }
        return !cipher.contains("GCM")
                && !cipher.contains("CCM")
                && !cipher.contains("CHACHA20");
    }

    @Override
    public void error(Exception cause) {
        LOGGER.log(Level.WARNING, "HTTP transport error", cause);
        closeEndpoint();
    }

    // ── LineParser.Callback implementation ──
    // RFC 9112 section 2: message = start-line CRLF *( field-line CRLF ) CRLF [ message-body ]

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
                // RFC 9113 section 8.2.2: connection-specific headers MUST NOT
                // appear in an HTTP/2 message
                headers.removeAll("Connection");
                headers.removeAll("Keep-Alive");
                headers.removeAll("Proxy-Connection");
                headers.removeAll("Transfer-Encoding");
                headers.removeAll("Upgrade");
                // RFC 9113 section 8.3.2: :status is the only response pseudo-header
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
                // RFC 9113 section 4.3: header blocks that exceed
                // SETTINGS_MAX_FRAME_SIZE are split across HEADERS +
                // CONTINUATION frames with no intervening frames
                try {
                    if (length <= maxFrameSize) {
                        h2Writer.writeHeaders(streamId, buf, endStream, true,
                                padLength, streamDependency, weight, streamDependencyExclusive);
                        h2Writer.flush();
                    } else {
                        int savedLimit = buf.limit();
                        buf.limit(buf.position() + maxFrameSize);
                        ByteBuffer fragment = buf.slice();
                        buf.limit(savedLimit);
                        buf.position(buf.position() + maxFrameSize);
                        h2Writer.writeHeaders(streamId, fragment, endStream, false,
                                padLength, streamDependency, weight, streamDependencyExclusive);
                        length -= maxFrameSize;
                        while (length > maxFrameSize) {
                            buf.limit(buf.position() + maxFrameSize);
                            fragment = buf.slice();
                            buf.limit(savedLimit);
                            buf.position(buf.position() + maxFrameSize);
                            h2Writer.writeContinuation(streamId, fragment, false);
                            length -= maxFrameSize;
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
                sendH2Data(streamId, buf, endStream);
                break;
            default:
                send(buf);
        }
    }

    /**
     * Sends HTTP/2 DATA, respecting flow control windows
     * (RFC 9113 section 6.9).  If the send window is insufficient,
     * the remainder is queued and will be drained when
     * WINDOW_UPDATE frames arrive.
     */
    private void sendH2Data(int streamId, ByteBuffer buf, boolean endStream) {
        PendingData pending = h2PendingData.get(streamId);
        if (pending != null) {
            pending.enqueue(buf, endStream);
            return;
        }

        int toSend = buf.remaining();
        int window = h2FlowControl.availableSendWindow(streamId);
        if (window >= toSend) {
            h2FlowControl.consumeSendWindow(streamId, toSend);
            sendH2DataDirect(streamId, buf, endStream);
        } else if (window > 0) {
            h2FlowControl.consumeSendWindow(streamId, window);
            int savedLimit = buf.limit();
            buf.limit(buf.position() + window);
            ByteBuffer slice = buf.slice();
            buf.position(buf.position() + window);
            buf.limit(savedLimit);
            sendH2DataDirect(streamId, slice, false);
            pending = acquirePendingData();
            pending.enqueue(buf, endStream);
            h2PendingData.put(streamId, pending);
        } else {
            pending = acquirePendingData();
            pending.enqueue(buf, endStream);
            h2PendingData.put(streamId, pending);
        }
    }

    // RFC 9113 section 4.2: DATA frames MUST NOT exceed SETTINGS_MAX_FRAME_SIZE
    private void sendH2DataDirect(int streamId, ByteBuffer buf, boolean endStream) {
        int maxPayload = framePadding > 0 ? maxFrameSize - framePadding - 1 : maxFrameSize;
        try {
            while (buf.remaining() > maxPayload) {
                int savedLimit = buf.limit();
                buf.limit(buf.position() + maxPayload);
                ByteBuffer slice = buf.slice();
                buf.position(buf.position() + maxPayload);
                buf.limit(savedLimit);
                h2Writer.writeData(streamId, slice, false, framePadding);
            }
            h2Writer.writeData(streamId, buf, endStream, framePadding);
            h2Writer.flush();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error sending data frame", e);
        }
    }

    /**
     * Drains queued DATA for a stream whose send window has opened.
     */
    private void drainPendingData(int streamId) {
        PendingData pending = h2PendingData.get(streamId);
        if (pending == null) {
            return;
        }

        int available = h2FlowControl.availableSendWindow(streamId);
        while (available > 0 && !pending.isEmpty()) {
            ByteBuffer head = pending.buffers.peek();
            int headRemaining = head.remaining();
            if (available >= headRemaining) {
                pending.buffers.poll();
                h2FlowControl.consumeSendWindow(streamId, headRemaining);
                available -= headRemaining;
                boolean fin = pending.endStream && pending.isEmpty();
                sendH2DataDirect(streamId, head, fin);
            } else {
                h2FlowControl.consumeSendWindow(streamId, available);
                int savedLimit = head.limit();
                head.limit(head.position() + available);
                ByteBuffer slice = head.slice();
                head.position(head.position() + available);
                head.limit(savedLimit);
                sendH2DataDirect(streamId, slice, false);
                available = 0;
            }
        }

        if (pending.isEmpty()) {
            h2PendingData.remove(streamId);
            releasePendingData(pending);
            Runnable cb = h2WriteCallbacks.remove(streamId);
            if (cb != null) {
                cb.run();
            }
        }
    }

    // RFC 9112 section 9.6: sending null signals connection close
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

    // RFC 9113 section 5.4.2: stream errors are signaled with RST_STREAM
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

    // RFC 9113 section 5.4.1: connection errors are signaled with GOAWAY
    @Override
    public void sendGoaway(int errorCode) {
        sendGoaway(errorCode, null);
    }

    // RFC 9113 section 6.8: GOAWAY with optional debug data
    void sendGoaway(int errorCode, String debugMessage) {
        cancelSettingsTimeout();
        cancelPingKeepAlive();
        int lastStreamId = clientStreamId > 0 ? clientStreamId : 0;
        sendGoawayFrame(lastStreamId, errorCode, debugMessage);
        closeEndpoint();
    }

    // RFC 9113 section 5.4.1: graceful two-phase GOAWAY shutdown.
    // Phase 1: send GOAWAY with MAX_VALUE last-stream-ID to signal intent.
    // Phase 2: after a brief delay, send final GOAWAY with the actual
    // last-stream-ID and close the connection.
    void sendGracefulGoaway(String debugMessage) {
        if (goawaySent) {
            return;
        }
        goawaySent = true;
        cancelSettingsTimeout();
        cancelPingKeepAlive();

        sendGoawayFrame(Integer.MAX_VALUE, H2FrameHandler.ERROR_NO_ERROR,
                debugMessage);

        if (endpoint != null) {
            endpoint.scheduleTimer(GRACEFUL_GOAWAY_DELAY_MS, () -> {
                int lastStreamId = clientStreamId > 0 ? clientStreamId : 0;
                sendGoawayFrame(lastStreamId,
                        H2FrameHandler.ERROR_NO_ERROR, debugMessage);
                closeEndpoint();
            });
        } else {
            closeEndpoint();
        }
    }

    private void sendGoawayFrame(int lastStreamId, int errorCode,
            String debugMessage) {
        byte[] debugData = (debugMessage != null)
                ? debugMessage.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                : new byte[0];
        int payloadLength = 8 + debugData.length;
        ByteBuffer buf = ByteBuffer.allocate(FRAME_HEADER_LENGTH + payloadLength);
        buf.put((byte) ((payloadLength >> 16) & 0xff));
        buf.put((byte) ((payloadLength >> 8) & 0xff));
        buf.put((byte) (payloadLength & 0xff));
        buf.put((byte) H2FrameHandler.TYPE_GOAWAY);
        buf.put((byte) 0);
        buf.putInt(0);
        buf.putInt(lastStreamId);
        buf.putInt(errorCode);
        if (debugData.length > 0) {
            buf.put(debugData);
        }
        buf.flip();
        send(buf);
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

    HTTPListener getListener() {
        return server;
    }

    @Override
    public boolean isEnablePush() {
        return enablePush;
    }

    @Override
    public Stream newStream(HTTPConnectionLike connection, int streamId) {
        return new Stream(connection, streamId);
    }

    // RFC 9113 section 5.1.1: server-initiated stream IDs are even
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

    // RFC 9113 section 8.4: server push via PUSH_PROMISE
    @Override
    public void sendPushPromise(int streamId, int promisedStreamId,
            ByteBuffer headerBlock, boolean endHeaders) {
        // RFC 9113 section 6.5.2: MUST NOT send PUSH_PROMISE if
        // client disabled server push via SETTINGS_ENABLE_PUSH=0
        if (!enablePush) {
            return;
        }
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
            streams.put(streamId, pushedStream);
            return pushedStream;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to create pushed stream " + streamId, e);
            return null;
        }
    }

    // ── Backpressure / flow control (HTTPConnectionLike) ──

    @Override
    public void onWritable(int streamId, Runnable callback) {
        if (h2FlowControl != null) {
            if (callback != null) {
                h2WriteCallbacks.put(streamId, callback);
            } else {
                h2WriteCallbacks.remove(streamId);
            }
            // Also register on the TCP endpoint so we know when the
            // socket is writable (connection-level backpressure).
            if (!h2WriteCallbacks.isEmpty()) {
                endpoint.onWriteReady(new Runnable() {
                    @Override
                    public void run() {
                        for (Iterator<Map.Entry<Integer, Runnable>> it =
                                h2WriteCallbacks.entrySet().iterator(); it.hasNext(); ) {
                            Map.Entry<Integer, Runnable> entry = it.next();
                            it.remove();
                            entry.getValue().run();
                        }
                    }
                });
            } else {
                endpoint.onWriteReady(null);
            }
        } else {
            endpoint.onWriteReady(callback);
        }
    }

    @Override
    public void pauseRead(int streamId) {
        if (h2FlowControl != null) {
            h2FlowControl.pauseStream(streamId);
        } else {
            endpoint.pauseRead();
        }
    }

    @Override
    public void resumeRead(int streamId) {
        if (h2FlowControl != null) {
            int increment = h2FlowControl.resumeStream(streamId);
            if (increment > 0) {
                try {
                    h2Writer.writeWindowUpdate(streamId, increment);
                    h2Writer.flush();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error sending deferred WINDOW_UPDATE", e);
                }
            }
        } else {
            endpoint.resumeRead();
        }
    }

    // ── Private helpers ──

    // RFC 9112 section 9.6: close connection
    private void closeEndpoint() {
        cancelIdleTimeout();
        cancelPingKeepAlive();
        if (endpoint != null) {
            endpoint.close();
        }
    }

    // RFC 9112 section 9.8 / RFC 9113 section 9.1: idle connection timeout
    private void resetIdleTimeout() {
        cancelIdleTimeout();
        long timeoutMs = server.getIdleTimeoutMs();
        if (timeoutMs > 0 && endpoint != null) {
            idleTimeoutHandle = endpoint.scheduleTimer(timeoutMs, () -> {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Closing idle HTTP connection after "
                            + timeoutMs + "ms");
                }
                // RFC 9113 section 9.1: use graceful GOAWAY for HTTP/2
                if (version == HTTPVersion.HTTP_2_0) {
                    sendGracefulGoaway("idle timeout");
                } else {
                    closeEndpoint();
                }
            });
        }
    }

    private void cancelIdleTimeout() {
        if (idleTimeoutHandle != null) {
            idleTimeoutHandle.cancel();
            idleTimeoutHandle = null;
        }
    }

    // RFC 9113 section 6.7: periodic PING keep-alive for HTTP/2
    private void startPingKeepAlive() {
        long intervalMs = server.getPingIntervalMs();
        if (intervalMs > 0 && endpoint != null
                && version == HTTPVersion.HTTP_2_0) {
            schedulePing(intervalMs);
        }
    }

    private void schedulePing(long intervalMs) {
        pingKeepAliveHandle = endpoint.scheduleTimer(intervalMs, () -> {
            if (endpoint != null && !goawaySent) {
                long opaqueData = System.nanoTime();
                sendPingFrame(opaqueData);
                schedulePing(intervalMs);
            }
        });
    }

    // RFC 9113 section 6.7: send a PING frame (non-ACK)
    private void sendPingFrame(long opaqueData) {
        ByteBuffer buf = ByteBuffer.allocate(FRAME_HEADER_LENGTH + 8);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 8);
        buf.put((byte) H2FrameHandler.TYPE_PING);
        buf.put((byte) 0);
        buf.putInt(0);
        buf.putLong(opaqueData);
        buf.flip();
        send(buf);
    }

    private void cancelPingKeepAlive() {
        if (pingKeepAliveHandle != null) {
            pingKeepAliveHandle.cancel();
            pingKeepAliveHandle = null;
        }
    }

    // RFC 9110 section 9.3.7: OPTIONS * targets the server itself.
    // Responds with 200 and Allow header listing supported methods.
    private void handleOptionsAsterisk(Stream stream) {
        try {
            Headers headers = new Headers();
            headers.add("Allow", getAllowedMethods());
            headers.add("Content-Length", "0");
            stream.sendResponseHeaders(200, headers, true);
        } catch (ProtocolException e) {
            LOGGER.log(Level.WARNING, "Error sending OPTIONS * response", e);
        }
        state = State.REQUEST_LINE;
        clientStreamId += 2;
    }

    // RFC 9110 section 9.3.8: TRACE echoes the request back.
    // Disabled by default for security; responds 405 when disabled.
    private void handleTrace(Stream stream) {
        if (!server.isTraceMethodEnabled()) {
            sendStreamError(stream, 405);
            return;
        }
        try {
            // Echo the request message as message/http
            StringBuilder echo = new StringBuilder();
            echo.append("TRACE ").append(stream.getHeaders().getValue(":path"))
                    .append(' ').append(version).append("\r\n");
            for (Header h : stream.getHeaders()) {
                if (!h.getName().startsWith(":")) {
                    echo.append(h.getName()).append(": ").append(h.getValue()).append("\r\n");
                }
            }
            echo.append("\r\n");
            byte[] body = echo.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            Headers headers = new Headers();
            headers.add("Content-Type", "message/http");
            headers.add("Content-Length", Integer.toString(body.length));
            stream.sendResponseHeaders(200, headers, false);
            stream.sendResponseBody(ByteBuffer.wrap(body), true);
        } catch (ProtocolException e) {
            LOGGER.log(Level.WARNING, "Error sending TRACE response", e);
        }
        state = State.REQUEST_LINE;
        clientStreamId += 2;
    }

    private String getAllowedMethods() {
        Set<String> methods;
        if (handlerFactory != null && handlerFactory.getSupportedMethods() != null) {
            methods = handlerFactory.getSupportedMethods();
        } else {
            methods = DEFAULT_METHODS;
        }
        StringBuilder sb = new StringBuilder();
        for (String m : methods) {
            if ("PRI".equals(m)) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(m);
        }
        return sb.toString();
    }

    // RFC 9110 section 15.6.2: 501 Not Implemented if method not recognised
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
        if (streamId == 0) {
            return null;
        }
        return streams.computeIfAbsent(streamId, id -> {
            Stream s = newStream(this, id);
            if (h2FlowControl != null) {
                h2FlowControl.openStream(id);
            }
            return s;
        });
    }

    private void maybeCleanupClosedStreams() {
        long now = System.currentTimeMillis();
        if (now - lastStreamCleanup < STREAM_CLEANUP_INTERVAL_MS) {
            return;
        }
        lastStreamCleanup = now;
        int removedCount = 0;
        for (Map.Entry<Integer, Stream> entry : streams.entrySet()) {
            Stream stream = entry.getValue();
            if (stream.isClosed()
                    && (now - stream.timestampCompleted) > STREAM_RETENTION_MS) {
                int sid = entry.getKey();
                if (streams.remove(sid, stream)) {
                    if (h2FlowControl != null) {
                        h2FlowControl.closeStream(sid);
                    }
                    h2WriteCallbacks.remove(sid);
                    PendingData removed = h2PendingData.remove(sid);
                    if (removed != null) {
                        releasePendingData(removed);
                    }
                    removedCount++;
                }
            }
        }
        if (removedCount > 0 && LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(String.format("Cleaned up %d closed streams (total remaining: %d)",
                    removedCount, streams.size()));
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

    // RFC 9112 section 3: request-line = method SP request-target SP HTTP-version
    private void processRequestLine(ByteBuffer line) {
        Stream stream = getStream(clientStreamId);
        int lineLength = line.remaining();
        // RFC 9110 section 15.5.15: 414 URI Too Long
        if (lineLength > MAX_LINE_LENGTH + CRLF_LENGTH) {
            sendStreamError(stream, 414);
            return;
        }
        // RFC 9112 section 2.1: parse as US-ASCII octets
        charBuffer.clear();
        US_ASCII_DECODER.reset();
        CoderResult result = US_ASCII_DECODER.decode(line, charBuffer, true);
        if (result.isError()) {
            sendStreamError(stream, 400);
            return;
        }
        charBuffer.flip();
        int len = charBuffer.limit();
        // Strip trailing CRLF (RFC 9112 section 2.1)
        if (len >= CRLF_LENGTH && charBuffer.get(len - 2) == '\r' && charBuffer.get(len - 1) == '\n') {
            charBuffer.limit(len - CRLF_LENGTH);
        }
        String lineStr = charBuffer.toString();
        // RFC 9112 section 3: method SP request-target SP HTTP-version
        int mi = lineStr.indexOf(' ', 1);
        int ui = (mi > 0) ? lineStr.indexOf(' ', mi + 2) : -1;
        if (mi == -1 || ui == -1) {
            sendStreamError(stream, 400);
            return;
        }
        String method = lineStr.substring(0, mi);
        String requestTarget = lineStr.substring(mi + 1, ui);
        String versionStr = lineStr.substring(ui + 1);
        // RFC 9112 section 2.3: HTTP-version = HTTP-name "/" DIGIT "." DIGIT
        this.version = HTTPVersion.fromString(versionStr);
        // RFC 9110 section 5.6.2: method = token
        if (!HTTPUtils.isValidMethod(method)) {
            sendStreamError(stream, 400);
            return;
        }
        // RFC 9110 section 15.6.2: 501 if method not recognised/implemented
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
                // RFC 9110 section 15.6.6: 505 HTTP Version Not Supported
                sendStreamError(stream, 505);
                return;
            case HTTP_2_0:
                // RFC 9113 section 3.4: PRI * HTTP/2.0 connection preface
                if ("PRI".equals(method) && "*".equals(requestTarget)) {
                    state = State.PRI;
                } else {
                    sendStreamError(stream, 400);
                }
                return;
            case HTTP_1_0:
                // RFC 9112 section 9.3: HTTP/1.0 defaults to close
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

    // RFC 9112 section 5: field-line = field-name ":" OWS field-value OWS
    private void processHeaderLine(ByteBuffer line) {
        Stream stream = getStream(clientStreamId);
        int lineLength = line.remaining();
        // RFC 9110 section 15.5.18: 431 Request Header Fields Too Large
        if (lineLength > MAX_LINE_LENGTH + CRLF_LENGTH) {
            sendStreamError(stream, 431);
            return;
        }
        // RFC 9112 section 5.5: field values historically allowed ISO-8859-1
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
            // Empty line terminates header section (RFC 9112 section 2)
            endHeaders(stream);
        } else {
            char c0 = lineStr.charAt(0);
            // RFC 9112 section 5.2: obs-fold = OWS CRLF RWS
            // A server MAY reject or replace each obs-fold with one or more SP.
            // We replace obs-fold with SP prior to interpreting the field value.
            if (headerName != null && (c0 == ' ' || c0 == '\t')) {
                if (c0 == '\t') {
                    lineStr = " " + lineStr.substring(1);
                }
                appendHeaderValue(lineStr);
            } else {
                // RFC 9112 section 5.1: field-name ":" OWS field-value OWS
                // No whitespace allowed between field-name and colon;
                // Header constructor validates via HTTPUtils.isValidHeaderName().
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
        // RFC 9112 section 3.2: A server MUST respond with 400 to any
        // HTTP/1.1 request that lacks a Host header field and to any request
        // that contains more than one Host header field line or a Host header
        // field with an invalid field-value.
        if (this.version == HTTPVersion.HTTP_1_1) {
            int hostCount = 0;
            for (Header header : stream.getHeaders()) {
                if (header.getName().equalsIgnoreCase("host")
                        || header.getName().equals(":authority")) {
                    hostCount++;
                }
            }
            if (hostCount != 1) {
                sendStreamError(stream, 400);
                return;
            }
        }
        // RFC 9110 section 9.3.7: OPTIONS * targets the server itself
        String method = stream.getHeaders().getValue(":method");
        String target = stream.getHeaders().getValue(":path");
        if ("OPTIONS".equals(method) && "*".equals(target)) {
            handleOptionsAsterisk(stream);
            return;
        }
        // RFC 9110 section 9.3.8: TRACE method handling
        if ("TRACE".equals(method)) {
            handleTrace(stream);
            return;
        }
        stream.streamEndHeaders();
        // RFC 9112 section 9.6: track requests for Connection: close
        requestCount++;
        int maxRequests = server.getMaxRequestsPerConnection();
        if (maxRequests > 0 && requestCount >= maxRequests) {
            stream.closeConnection = true;
        }
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
        // RFC 9112 section 6.3: Message body length determination precedence:
        // 1. Transfer-Encoding overrides Content-Length
        // 2. Content-Length if present and valid
        // 3. Read until connection close (HTTP/1.0 only)
        // 4. Otherwise 411 Length Required
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
            // RFC 9110 section 15.5.12: 411 Length Required
            sendStreamError(stream, 411);
        }
    }

    // RFC 9112 section 7.1: chunk = chunk-size [ chunk-ext ] CRLF chunk-data CRLF
    // last-chunk = 1*("0") [ chunk-ext ] CRLF
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
        // RFC 9112 section 7.1.1: chunk-ext = *( BWS ";" BWS chunk-ext-name [ ... ] )
        int semi = lineStr.indexOf(';');
        String sizeStr = (semi > 0) ? lineStr.substring(0, semi) : lineStr;
        try {
            // RFC 9112 section 7.1: chunk-size = 1*HEXDIG
            currentChunkSize = Integer.parseInt(sizeStr.trim(), 16);
            chunkBytesRemaining = currentChunkSize;
        } catch (NumberFormatException e) {
            sendStreamError(stream, 400);
            return;
        }
        if (currentChunkSize == 0) {
            // RFC 9112 section 7.1: last-chunk, followed by trailer section
            state = State.BODY_CHUNKED_TRAILER;
        } else {
            state = State.BODY_CHUNKED_DATA;
        }
    }

    // RFC 9112 section 7.1.2: trailer-section = *( field-line CRLF )
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

    // RFC 9112 section 6.2: Content-Length delimited body
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

    // RFC 9112 section 7.1: chunk-data = 1*OCTET
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

    // RFC 9112 section 6.3: read until connection close (HTTP/1.0 fallback)
    private void receiveBodyUntilClose(ByteBuffer buf) {
        Stream stream = getStream(clientStreamId);
        if (buf.hasRemaining()) {
            stream.receiveRequestBody(buf);
        }
    }

    // RFC 9113 section 3.4: client connection preface starts with
    // "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n" (24 octets).
    // The request-line portion is parsed by processRequestLine; this
    // method validates the remaining "\r\nSM\r\n\r\n" (8 octets).
    private void receivePri(ByteBuffer buf) {
        Stream stream = getStream(clientStreamId);
        if (buf.remaining() < PRI_CONTINUATION_LENGTH) {
            return;
        }
        byte[] smt = new byte[PRI_CONTINUATION_LENGTH];
        buf.get(smt);
        // RFC 9113 section 3.4: validate "\r\nSM\r\n\r\n"
        if (smt[0] != '\r' || smt[1] != '\n' || smt[2] != 'S' || smt[3] != 'M'
                || smt[4] != '\r' || smt[5] != '\n' || smt[6] != '\r' || smt[7] != '\n') {
            sendStreamError(stream, 400);
            return;
        }
        h2Parser = new H2Parser(this);
        h2Parser.setMaxFrameSize(maxFrameSize);
        h2Writer = new H2Writer(new EndpointChannel());
        h2FlowControl = new H2FlowControl();
        state = State.PRI_SETTINGS;
        // RFC 9113 section 3.4: server connection preface is a SETTINGS frame
        Map<Integer, Integer> initialSettings = new LinkedHashMap<Integer, Integer>();
        initialSettings.put(H2FrameHandler.SETTINGS_MAX_CONCURRENT_STREAMS,
                serverMaxConcurrentStreams);
        sendSettingsFrame(false, initialSettings);
        startSettingsTimeout();
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
        
        // RFC 9113 section 3.4: clients MUST send the connection preface
        // ("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n", 24 octets) even when
        // HTTP/2 was negotiated via ALPN
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

    // RFC 9110 section 7.8: Upgrade header field
    // RFC 9110 section 15.2.2: 101 Switching Protocols
    // RFC 9113 section 3.1: h2c upgrade from HTTP/1.1 (deprecated by RFC 9113,
    // but intentionally retained for backwards compatibility)
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

    // RFC 9113 section 6.5.3: SETTINGS_TIMEOUT enforcement
    private void startSettingsTimeout() {
        if (endpoint != null) {
            settingsTimeoutHandle = endpoint.scheduleTimer(
                    SETTINGS_ACK_TIMEOUT_MS, new Runnable() {
                @Override
                public void run() {
                    if (LOGGER.isLoggable(Level.WARNING)) {
                        LOGGER.warning("SETTINGS ACK timeout — sending GOAWAY");
                    }
                    sendGoaway(H2FrameHandler.ERROR_SETTINGS_TIMEOUT);
                }
            });
        }
    }

    private void cancelSettingsTimeout() {
        if (settingsTimeoutHandle != null) {
            settingsTimeoutHandle.cancel();
            settingsTimeoutHandle = null;
        }
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

    // RFC 9112 section 4: status-line = HTTP-version SP status-code SP [ reason-phrase ] CRLF
    // RFC 9112 section 5: field-line = field-name ":" OWS field-value OWS
    private void writeStatusLineAndHeaders(ByteBuffer buf, int statusCode, Headers headers) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(version.toString()).append(' ')
              .append(statusCode / 100).append(statusCode / 10 % 10).append(statusCode % 10)
              .append(' ').append(HTTPConstants.getMessage(statusCode)).append("\r\n");
            buf.put(sb.toString().getBytes(US_ASCII));
            for (Header header : headers) {
                String name = header.getName();
                // Skip HTTP/2 pseudo-headers (RFC 9113 section 8.3)
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
                sb.setLength(0);
                sb.append(name).append(": ").append(value).append("\r\n");
                buf.put(sb.toString().getBytes(US_ASCII));
            }
            // Empty line terminates header section (RFC 9112 section 2)
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
        int streamCount = streams.size();
        for (Stream stream : streams.values()) {
            stream.streamClose();
        }
        streams.clear();
        if (streamCount > 0 && LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(String.format("Connection closing: cleaned up %d remaining streams",
                    streamCount));
        }
        activeStreams.clear();
    }

    // ── H2FrameHandler implementation ──

    // RFC 9113 section 6.1: DATA frame reception
    @Override
    public void dataFrameReceived(int streamId, boolean endStream, ByteBuffer data) {
        if (expectingInitialSettings()) {
            return;
        }
        int dataLength = data.remaining();
        Stream stream = getStream(streamId);
        stream.appendRequestBody(data);

        // RFC 9113 section 6.9: receive-side flow control accounting;
        // send WINDOW_UPDATE to replenish the peer's send window
        if (h2FlowControl != null && dataLength > 0) {
            h2FlowControl.onDataReceived(streamId, dataLength, h2DataResult);
            try {
                if (h2DataResult.connectionIncrement > 0) {
                    h2Writer.writeWindowUpdate(0, h2DataResult.connectionIncrement);
                }
                if (h2DataResult.streamIncrement > 0) {
                    h2Writer.writeWindowUpdate(streamId, h2DataResult.streamIncrement);
                }
                if (h2DataResult.connectionIncrement > 0 || h2DataResult.streamIncrement > 0) {
                    h2Writer.flush();
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error sending WINDOW_UPDATE", e);
            }
        }

        if (endStream) {
            stream.streamEndRequest();
            if (stream.isActive()) {
                activeStreams.add(streamId);
            }
        }
    }

    // RFC 9113 section 6.2: HEADERS frame reception
    @Override
    public void headersFrameReceived(int streamId, boolean endStream, boolean endHeaders,
            int streamDependency, boolean exclusive, int weight,
            ByteBuffer headerBlockFragment) {
        if (expectingInitialSettings()) {
            return;
        }
        // RFC 9113 section 5.1.1: client-initiated streams MUST use odd
        // stream IDs and MUST be monotonically increasing; violation is
        // a connection error of type PROTOCOL_ERROR
        if (streamId % 2 == 0 || streamId <= lastClientStreamId) {
            sendGoaway(H2FrameHandler.ERROR_PROTOCOL_ERROR);
            closeEndpoint();
            return;
        }
        lastClientStreamId = streamId;
        // RFC 9113 section 5.1.2: streams exceeding
        // SETTINGS_MAX_CONCURRENT_STREAMS SHOULD be refused
        if (activeStreams.size() >= serverMaxConcurrentStreams) {
            sendRstStream(streamId, H2FrameHandler.ERROR_REFUSED_STREAM);
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
                activeStreams.add(streamId);
            }
        } else {
            state = State.HTTP2_CONTINUATION;
            continuationStream = streamId;
            continuationEndStream = endStream;
        }
    }

    // RFC 9113 section 5.3: stream priority signaling is deprecated.
    // PRIORITY frames are still parsed for wire compatibility but
    // the semantics are not acted upon.
    @Override
    public void priorityFrameReceived(int streamId, int streamDependency,
            boolean exclusive, int weight) {
        if (expectingInitialSettings()) {
            return;
        }
    }

    // RFC 9113 section 6.4: RST_STREAM frame reception
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
        activeStreams.remove(streamId);
    }

    // RFC 9113 section 6.5: SETTINGS frame reception
    @Override
    public void settingsFrameReceived(boolean ack, Map<Integer, Integer> settings) {
        if (ack) {
            cancelSettingsTimeout();
        }
        if (state == State.PRI_SETTINGS) {
            // RFC 9113 section 3.4: first frame MUST be SETTINGS
            if (!ack) {
                // RFC 9113 section 6.5.2: process peer's settings
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
                            // RFC 9113 section 6.9.2: update all stream send windows
                            initialWindowSize = value;
                            if (h2FlowControl != null) {
                                if (h2FlowControl.onSettingsInitialWindowSize(value)) {
                                    // RFC 9113 section 6.9.2: overflow is FLOW_CONTROL_ERROR
                                    sendGoaway(H2FrameHandler.ERROR_FLOW_CONTROL_ERROR);
                                    closeEndpoint();
                                    return;
                                }
                            }
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
                // RFC 7541 section 4: initialize HPACK encoder/decoder
                // with peer's SETTINGS_HEADER_TABLE_SIZE
                if (hpackDecoder == null) {
                    hpackDecoder = new Decoder(headerTableSize);
                }
                if (hpackEncoder == null) {
                    hpackEncoder = new Encoder(headerTableSize,
                            maxHeaderListSize);
                }
                // RFC 9113 section 6.5: ACK peer's SETTINGS
                sendSettingsAck();
            }
            state = State.HTTP2;
            // RFC 9113 section 6.7: start PING keep-alive if configured
            startPingKeepAlive();
            Stream h2cStream = getStream(1);
            if (h2cStream != null) {
                h2cStream.streamEndRequest();
            }
            return;
        }
        if (!ack) {
            // RFC 9113 section 6.5: process updated SETTINGS from peer
            for (Map.Entry<Integer, Integer> entry : settings.entrySet()) {
                int identifier = entry.getKey();
                int value = entry.getValue();
                switch (identifier) {
                    case H2FrameHandler.SETTINGS_HEADER_TABLE_SIZE:
                        // RFC 7541 section 6.3: dynamic table size update
                        headerTableSize = value;
                        break;
                    case H2FrameHandler.SETTINGS_ENABLE_PUSH:
                        enablePush = (value == 1);
                        break;
                    case H2FrameHandler.SETTINGS_MAX_CONCURRENT_STREAMS:
                        maxConcurrentStreams = value;
                        break;
                    case H2FrameHandler.SETTINGS_INITIAL_WINDOW_SIZE:
                        // RFC 9113 section 6.9.2: adjust all stream send windows
                        initialWindowSize = value;
                        if (h2FlowControl != null) {
                            if (h2FlowControl.onSettingsInitialWindowSize(value)) {
                                sendGoaway(H2FrameHandler.ERROR_FLOW_CONTROL_ERROR);
                                closeEndpoint();
                                return;
                            }
                        }
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

    // RFC 9113 section 6.6: PUSH_PROMISE frame reception
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

    // RFC 9113 section 6.7: PING acknowledgement
    @Override
    public void pingFrameReceived(boolean ack, long opaqueData) {
        if (expectingInitialSettings()) {
            return;
        }
        // RFC 9113 section 6.7: non-ACK PING MUST be responded to with ACK
        if (!ack) {
            sendPingAck(opaqueData);
        }
    }

    // RFC 9113 section 6.8: GOAWAY initiates graceful shutdown
    @Override
    public void goawayFrameReceived(int lastStreamId, int errorCode, ByteBuffer debugData) {
        if (expectingInitialSettings()) {
            return;
        }
        closeEndpoint();
    }

    // RFC 9113 section 6.9: WINDOW_UPDATE frame reception
    @Override
    public void windowUpdateFrameReceived(int streamId, int windowSizeIncrement) {
        if (expectingInitialSettings()) {
            return;
        }
        if (h2FlowControl == null) {
            return;
        }
        boolean overflow = h2FlowControl.onWindowUpdate(streamId, windowSizeIncrement);
        // RFC 9113 section 6.9.1: window exceeding 2^31-1 is a
        // connection error (stream 0) or stream error
        if (overflow) {
            if (streamId == 0) {
                sendGoaway(H2FrameHandler.ERROR_FLOW_CONTROL_ERROR);
                closeEndpoint();
            } else {
                sendRstStream(streamId, H2FrameHandler.ERROR_FLOW_CONTROL_ERROR);
            }
            return;
        }
        if (streamId == 0) {
            h2FlowControl.forEachUnblockedStream(this::drainPendingData);
        } else {
            drainPendingData(streamId);
        }
    }

    // RFC 9113 section 6.10: CONTINUATION frame reception
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
                activeStreams.add(streamId);
            }
        }
    }

    // RFC 9113 section 5.4: error handling
    // Section 5.4.1: connection errors → GOAWAY
    // Section 5.4.2: stream errors → RST_STREAM
    @Override
    public void frameError(int errorCode, int streamId, String message) {
        if (LOGGER.isLoggable(Level.WARNING)) {
            LOGGER.warning("Frame error: " + message + " (error="
                    + H2FrameHandler.errorToString(errorCode) + ", stream=" + streamId + ")");
        }
        if (streamId == 0 || errorCode == H2FrameHandler.ERROR_PROTOCOL_ERROR
                || errorCode == H2FrameHandler.ERROR_FRAME_SIZE_ERROR) {
            sendGoaway(errorCode);
            closeEndpoint();
        } else {
            sendRstStream(streamId, errorCode);
        }
    }

    // ── Flow-control pending data ──

    /**
     * Buffered DATA payloads waiting for the send window to open.
     * Buffers are kept in a queue and drained in order, avoiding
     * the cost of merging into a single growing buffer.
     */
    private static class PendingData {
        final ArrayDeque<ByteBuffer> buffers = new ArrayDeque<ByteBuffer>();
        boolean endStream;

        void enqueue(ByteBuffer data, boolean fin) {
            if (data.hasRemaining()) {
                ByteBuffer copy = ByteBufferPool.acquire(data.remaining());
                copy.put(data);
                copy.flip();
                buffers.add(copy);
            }
            if (fin) {
                endStream = true;
            }
        }

        int remaining() {
            int total = 0;
            for (ByteBuffer buf : buffers) {
                total += buf.remaining();
            }
            return total;
        }

        boolean isEmpty() {
            return buffers.isEmpty();
        }

        void reset() {
            for (ByteBuffer buf : buffers) {
                ByteBufferPool.release(buf);
            }
            buffers.clear();
            endStream = false;
        }
    }

    private static final int MAX_POOLED_PENDING = 32;
    private final ArrayDeque<PendingData> pendingDataPool =
            new ArrayDeque<PendingData>();

    private PendingData acquirePendingData() {
        PendingData pd = pendingDataPool.poll();
        if (pd != null) {
            pd.reset();
            return pd;
        }
        return new PendingData();
    }

    private void releasePendingData(PendingData pd) {
        if (pendingDataPool.size() < MAX_POOLED_PENDING) {
            pd.reset();
            pendingDataPool.offer(pd);
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
                ByteBuffer copy = ByteBufferPool.acquire(written);
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
