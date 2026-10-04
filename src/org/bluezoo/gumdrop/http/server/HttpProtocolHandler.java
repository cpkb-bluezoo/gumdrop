/*
 * HttpProtocolHandler.java
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

package org.bluezoo.gumdrop.http.server;


import org.bluezoo.gumdrop.http.HeaderFields;
import java.util.ArrayList;
import org.bluezoo.gumdrop.http.Capsule;
import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.HeaderFieldHandler;
import org.bluezoo.gumdrop.http.HttpError;
import org.bluezoo.gumdrop.http.HttpMessageHandler;
import org.bluezoo.gumdrop.http.HttpMessageRecorder;
import org.bluezoo.gumdrop.http.HttpMethod;
import org.bluezoo.gumdrop.http.h1.Http1Parser;
import org.bluezoo.gumdrop.http.HttpConstants;
import org.bluezoo.gumdrop.http.HttpDateCache;
import org.bluezoo.gumdrop.http.HttpUtils;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.http.PriorityParams;
import org.bluezoo.gumdrop.http.Rfc9218NonIncrementalSlots;

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
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;


import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SelectorLoop;
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
import org.bluezoo.gumdrop.util.IntObjectHashMap;

/**
 * HTTP/1.1 and HTTP/2 protocol handler using {@link ProtocolHandler}.
 *
 * <p>Implements the HTTP protocol with the transport layer fully decoupled
 * via composition:
 * <ul>
 * <li>Transport operations delegate to an {@link Endpoint} reference
 *     received in {@link #connected(Endpoint)}</li>
 * <li>Line parsing uses a streaming {@link HttpLineLexer} (issue #85):
 *     bytes are tokenised as they arrive rather than buffered into whole
 *     lines — see {@link org.bluezoo.gumdrop.ByteStreamLexer}. Content-Length and chunked
 *     bodies use {@link org.bluezoo.gumdrop.ByteStreamLexer#enterRaw(long)}; HTTP/2 framing,
 *     the h2c/prior-knowledge prefaces, the HTTP/1.0 until-close body, and
 *     WebSocket data are read entirely outside the lexer, unchanged</li>
 * <li>TLS upgrade uses {@link Endpoint#startTLS()}</li>
 * <li>Security info uses {@link Endpoint#getSecurityInfo()}</li>
 * </ul>
 *
 * <p>Extends the package-private {@code HttpConnectionLike} so that {@link Stream}
 * can work with it through a single connection abstraction.
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
 * @see HttpLineLexer
 */
public class HttpProtocolHandler extends HttpConnectionLike
        implements ProtocolHandler, H2FrameHandler {

    static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.http.L10N");
    static final Logger LOGGER =
            Logger.getLogger(HttpProtocolHandler.class.getName());

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
    private static final int MAX_HEADER_COUNT = 100;
    private static final int CRLF_LENGTH = 2;
    private static final int FRAME_HEADER_LENGTH = 9;
    private static final int PRI_CONTINUATION_LENGTH = 8;
    // RFC 9113 section 3.4: "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"
    private static final byte[] H2C_CONNECTION_PREFACE =
        "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private int h2cPrefacePos;
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

    // RFC 9112 section 2: HTTP/1.1 message = start-line CRLF
    //                       *( field-line CRLF ) CRLF [ message-body ]
    private enum State {
        HTTP1,                   // RFC 9112: HTTP/1.x requests, read by Http1Parser
        H2C_PREFACE,             // RFC 9113 section 3.4: awaiting full h2c connection preface after 101
        PRI_SETTINGS,            // RFC 9113 section 3.4: awaiting client SETTINGS
        HTTP2,                   // RFC 9113 section 4: HTTP/2 frame processing
        HTTP2_CONTINUATION,      // RFC 9113 section 6.10: CONTINUATION frame
        // RFC 6455: WebSocket -- despite the name, dispatch for this
        // state (receiveWebSocket()) is not actually WebSocket-specific:
        // it just hands the connection's raw remaining bytes to one
        // stream (see switchToStreamTunnelMode()), which is exactly what
        // any single-stream-owns-the-rest-of-the-connection upgrade
        // needs, WebSocket or not (e.g. RFC 9298 CONNECT-UDP's own
        // Capsule Protocol tunnel over HTTP/1.1).
        WEBSOCKET
    }

    private Endpoint endpoint;

    private final Http2Listener server;
    private final int framePadding;
    private final int serverMaxConcurrentStreams;
    private final int serverMaxHeaderListSize;

    private static final int DEFAULT_MAX_HEADER_LIST_SIZE = 8192;
    private static final int MAX_CONTINUATION_FRAMES_PER_BLOCK = 512;
    private static final int MAX_RST_STREAMS_PER_SECOND = 200;

    private int continuationFramesInBlock;
    private int rstStreamCount;
    private long rstStreamWindowStartMs;

    private HttpAuthenticationProvider authenticationProvider;
    private HttpStreamHandler streamHandler;

    HttpVersion version = HttpVersion.HTTP_1_0;

    private State state = State.HTTP1;

    // RFC 9112: the HTTP/1.x request parser, and the receiver of its events.
    private final RequestEvents requestEvents = new RequestEvents();
    private final Http1Parser requestParser = newRequestParser();
    // True between requests: the next bytes may be the HTTP/2 preface
    private boolean http1AtMessageStart = true;
    // Set while the request being read is one the server answers itself
    // (OPTIONS *, TRACE): its remaining events are swallowed
    private boolean requestAnsweredInternally;
    // Set when a request is refused or fails to parse; once true, receive()
    // stops feeding this connection anything further.
    private boolean fatalParseError;

    /** The octets between position and limit as a string, one char per octet (RFC 9110 section 5.5). */
    private static String octetString(ByteBuffer b) {
        return new String(copyOf(b), StandardCharsets.ISO_8859_1);
    }

    private static byte[] copyOf(ByteBuffer b) {
        byte[] bytes = new byte[b.remaining()];
        b.duplicate().get(bytes);
        return bytes;
    }

    private Http1Parser newRequestParser() {
        Http1Parser parser = Http1Parser.forRequests(requestEvents);
        parser.setMaxStartLineLength(MAX_LINE_LENGTH);
        parser.setMaxFieldLineLength(MAX_LINE_LENGTH);
        parser.setMaxFieldCount(MAX_HEADER_COUNT);
        parser.setFieldTap(requestEvents);
        return parser;
    }

    int headerTableSize = DEFAULT_HEADER_TABLE_SIZE;
    // RFC 9113 section 6.5.2: SETTINGS_ENABLE_PUSH (default: 1 = enabled)
    boolean enablePush = true;
    // RFC 8441 section 3: whether the client sent SETTINGS_ENABLE_CONNECT_PROTOCOL.
    // Tracked for completeness, but deliberately never consulted before
    // accepting an upgrade: per section 3, "Receipt of this parameter by
    // a server does not have any impact" -- it's the client, not the
    // server, that must not attempt Extended CONNECT before receiving
    // this setting FROM the server (enforced client-side, see
    // HttpClientProtocolHandler#whenConnectProtocolKnown/
    // WebSocketClient#connectExtendedConnect). RFC 9220 section 3
    // states HTTP/3's semantics for this setting are identical.
    boolean clientEnablesConnectProtocol;
    int maxConcurrentStreams = Integer.MAX_VALUE;
    int initialWindowSize = DEFAULT_INITIAL_WINDOW_SIZE;
    int maxFrameSize = DEFAULT_MAX_FRAME_SIZE;
    int maxHeaderListSize = DEFAULT_MAX_HEADER_LIST_SIZE;
    long maxRequestBodySize = Http2Listener.DEFAULT_MAX_REQUEST_BODY_SIZE;

    Decoder hpackDecoder;
    Encoder hpackEncoder;

    private H2Parser h2Parser;
    private H2Writer h2Writer;
    private H2FlowControl h2FlowControl;
    private final H2FlowControl.DataReceivedResult h2DataResult =
            new H2FlowControl.DataReceivedResult();
    // HTTP/1.x output held while a read is handled (see hold())
    private static final int HELD_OUTPUT_LIMIT = 16384;
    private Thread receivingThread;
    private ByteBuffer heldOutput;

    // Issue #322: depth counter for h2Dispatch()'s flush-coalescing
    // window -- see its javadoc. A counter rather than a boolean because
    // a top-level frame callback (e.g. dataFrameReceived) can itself
    // synchronously trigger another one indirectly through application
    // handler code in ways not worth auditing case-by-case; only the
    // outermost h2Dispatch() call should actually flush.
    private int h2DispatchDepth;
    // Boxed-Integer keys on every stream-lifecycle-event lookup, for a
    // connection with no equivalent HTTP/1.1 cost, was worth a dedicated
    // primitive-keyed map (issue #299) rather than accepting it as
    // inherent to Map<Integer, V>. Iteration order is unspecified (unlike
    // the LinkedHashMaps these replace) -- nothing downstream depends on
    // insertion order: h2PendingData's own drain re-sorts by RFC 9218
    // priority regardless, and h2WriteCallbacks' write-readiness draining
    // has no cross-stream fairness requirement beyond "every registered
    // callback runs once per drain", which holds either way.
    private final IntObjectHashMap<Runnable> h2WriteCallbacks =
            new IntObjectHashMap<Runnable>();
    private final IntObjectHashMap<PendingData> h2PendingData =
            new IntObjectHashMap<PendingData>();
    // Mirrors the byte count queued in h2PendingData per stream, but as an
    // AtomicInteger so it can be read from another thread (e.g. a servlet
    // worker thread applying backpressure) without touching h2PendingData
    // itself, which is only ever safe to access from the SelectorLoop
    // thread (see #123).
    private final Map<Integer, AtomicInteger> h2PendingBytes =
            new ConcurrentHashMap<Integer, AtomicInteger>();
    private final IntObjectHashMap<PriorityParams> h2Priority =
            new IntObjectHashMap<PriorityParams>();
    private final Rfc9218NonIncrementalSlots h2NonIncSlots = new Rfc9218NonIncrementalSlots();

    private int clientStreamId = INITIAL_CLIENT_STREAM_ID;
    private int serverStreamId = INITIAL_SERVER_STREAM_ID;
    private int lastClientStreamId;
    // Not a ConcurrentHashMap: every access to an HttpProtocolHandler
    // instance - HTTP/1.x request handling and HTTP/2 frame processing
    // alike - happens on this connection's own SelectorLoop thread only
    // (see ScheduledTimer's class javadoc: even timer callbacks are
    // marshalled back onto a connection's owning loop thread rather than
    // firing on the timer thread directly), so the concurrent-safety this
    // buys is unused cost on every get/put and, worse, on every resize
    // (issue #279).
    private final Map<Integer, Stream> streams = new HashMap<Integer, Stream>();
    private int continuationStream;
    private boolean continuationEndStream;
    // Also loop-thread-only, like streams above (issue #298) -- a plain
    // HashSet needs none of ConcurrentSkipListSet's lock-free, pointer-
    // chasing traversal on every stream open/close.
    private final Set<Integer> activeStreams = new HashSet<Integer>();

    private boolean h2cUpgradePending;
    /**
     * Set when a bodyless h2c upgrade ({@link #completeH2cUpgrade()}) is
     * committed before {@link Stream#streamEndRequest()} ran, so the
     * upgrading stream still needs {@link HttpRequestHandler#endMessage}.
     * Cleared when that completion is delivered at the HTTP/2 SETTINGS
     * handshake. Must not run for native h2 (ALPN / prior knowledge).
     */
    private boolean h2cBodylessUpgradeNeedsRequestComplete;
    /** Package-private so tests can rewind the cleanup gate (no clock needed). */
    long lastStreamCleanup = 0L;
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
    public HttpProtocolHandler(Http2Listener server) {
        this(server, 0, 100, Http2Listener.DEFAULT_MAX_HEADER_LIST_SIZE);
    }

    /**
     * Creates a new HTTP endpoint handler with frame padding.
     *
     * @param server the HTTP server endpoint configuration
     * @param framePadding HTTP/2 frame padding (0-255)
     */
    public HttpProtocolHandler(Http2Listener server, int framePadding) {
        this(server, framePadding, 100, Http2Listener.DEFAULT_MAX_HEADER_LIST_SIZE);
    }

    /**
     * Creates a new HTTP endpoint handler with frame padding and
     * concurrent stream limit.
     *
     * @param server the HTTP server endpoint configuration
     * @param framePadding HTTP/2 frame padding (0-255)
     * @param serverMaxConcurrentStreams max concurrent streams to advertise
     */
    public HttpProtocolHandler(Http2Listener server, int framePadding,
            int serverMaxConcurrentStreams) {
        this(server, framePadding, serverMaxConcurrentStreams,
                server.getMaxHeaderListSize());
    }

    /**
     * Creates a new HTTP endpoint handler with HTTP/2 limits.
     */
    public HttpProtocolHandler(Http2Listener server, int framePadding,
            int serverMaxConcurrentStreams, int serverMaxHeaderListSize) {
        this.server = server;
        this.framePadding = framePadding;
        this.serverMaxConcurrentStreams = serverMaxConcurrentStreams;
        this.serverMaxHeaderListSize = serverMaxHeaderListSize;
        this.maxHeaderListSize = serverMaxHeaderListSize;
        this.maxRequestBodySize = server.getMaxRequestBodySize();
        this.authenticationProvider = server.getAuthenticationProvider();
        this.streamHandler = server.getStreamHandler();
    }

    public void setStreamHandler(HttpStreamHandler streamHandler) {
        this.streamHandler = streamHandler;
    }

    // ── ProtocolHandler implementation ──

    @Override
    public void connected(Endpoint endpoint) {
        this.endpoint = endpoint;

        HttpServerMetrics metrics = getServerMetrics();
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
        receivingThread = Thread.currentThread();
        try {
            receiveWhileHolding(buf);
        } finally {
            receivingThread = null;
            flushHeld();
        }
    }

    private void receiveWhileHolding(ByteBuffer buf) {
        // If HTTP/2 was negotiated via ALPN but parser not yet initialized,
        // we shouldn't receive data yet (securityEstablished should be called first)
        if ((state == State.PRI_SETTINGS || state == State.HTTP2 
                || state == State.HTTP2_CONTINUATION) && h2Parser == null) {
            LOGGER.warning(L10N.getString("warn.h2_data_before_parser_init"));
            closeEndpoint();
            return;
        }

        while (buf.hasRemaining()) {
            if (fatalParseError) {
                break;
            }
            int positionBefore = buf.position();

            switch (state) {
                case HTTP1:
                    receiveHttp1(buf);
                    break;
                case H2C_PREFACE:
                    receiveH2cPreface(buf);
                    break;
                case PRI_SETTINGS:
                case HTTP2:
                case HTTP2_CONTINUATION:
                    // One flush for everything this read produces: the
                    // responses to all the requests in it reach the peer as
                    // one write and, over TLS, one record.
                    h2DispatchDepth++;
                    try {
                        receiveFrameData(buf);
                    } finally {
                        if (--h2DispatchDepth == 0) {
                            flushH2Writer();
                        }
                    }
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
        HttpServerMetrics metrics = getServerMetrics();
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
                LOGGER.warning(MessageFormat.format(
                        L10N.getString("warn.blocked_h2_cipher_suite"), info.getCipherSuite()));
                h2Parser = new H2Parser(this);
                h2Writer = new H2Writer(new EndpointChannel());
                version = HttpVersion.HTTP_2_0;
                sendGoaway(H2FrameHandler.ERROR_INADEQUATE_SECURITY,
                        info.getCipherSuite());
                return;
            }
            h2Parser = new H2Parser(this);
            h2Parser.setMaxFrameSize(maxFrameSize);
            h2Writer = new H2Writer(new EndpointChannel());
            h2FlowControl = new H2FlowControl();
            version = HttpVersion.HTTP_2_0;
            state = State.PRI_SETTINGS;
            // RFC 9113 section 3.4: server connection preface MUST be
            // a SETTINGS frame as the first frame sent
            Map<Integer, Integer> initialSettings = new LinkedHashMap<Integer, Integer>();
            initialSettings.put(H2FrameHandler.SETTINGS_MAX_CONCURRENT_STREAMS,
                    serverMaxConcurrentStreams);
            initialSettings.put(H2FrameHandler.SETTINGS_MAX_HEADER_LIST_SIZE,
                    serverMaxHeaderListSize);
            // RFC 8441 section 3: advertise support for the extended
            // CONNECT method (WebSocket-over-HTTP/2).
            initialSettings.put(H2FrameHandler.SETTINGS_ENABLE_CONNECT_PROTOCOL, 1);
            initialSettings.put(H2FrameHandler.SETTINGS_NO_RFC7540_PRIORITIES, 1);
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
        LOGGER.log(Level.WARNING, MessageFormat.format(
                "{0} remote={1} version={2} state={3}",
                L10N.getString("warn.http_transport_error"),
                getRemoteSocketAddress(), version, state), cause);
        closeEndpoint();
    }

    // ── HttpConnectionLike implementation ──

    @Override
    public String getScheme() {
        return endpoint != null && endpoint.isSecure() ? "https" : "http";
    }

    @Override
    public HttpVersion getVersion() {
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

    /**
     * Sets the authentication provider.
     */
    public void setAuthenticationProvider(HttpAuthenticationProvider provider) {
        this.authenticationProvider = provider;
    }

    @Override
    public HttpStreamHandler getStreamHandler() {
        return streamHandler;
    }

    @Override
    void sendResponseHeaders(int streamId, int statusCode, List<Header> headers, boolean endStream) {
        String altSvc = server.getAltSvc();
        if (altSvc != null) {
            headers.add(new Header("Alt-Svc", altSvc));
        }

        ByteBuffer buf;
        boolean success = false;
        switch (state) {
            case HTTP2:
                // RFC 9113 section 8.2.2: do not send HTTP/1 framing headers
                HeaderFields.stripHttp1FramingHeaders(headers, true);
                // RFC 9113 section 8.3.2: :status is the only response pseudo-header
                headers.add(0, new Header(":status", Integer.toString(statusCode)));
                writeH2Headers(streamId, headers, endStream);
                break;
            default:
                buf = ByteBufferPool.acquire(headerTableSize);
                while (!success) {
                    try {
                        writeStatusLineAndHeaders(buf, statusCode, headers);
                        success = true;
                    } catch (BufferOverflowException e) {
                        ByteBuffer oldBuf = buf;
                        buf = ByteBufferPool.acquire(buf.capacity() + headerTableSize);
                        ByteBufferPool.release(oldBuf);
                    }
                }
                buf.flip();
                try {
                    send(buf);
                } finally {
                    ByteBufferPool.release(buf);
                }
        }
    }

    /**
     * Runs {@code body} with {@link #requestH2Flush} calls inside it
     * coalesced into a single flush once {@code body} returns, instead of
     * each firing its own channel write (issue #322).
     *
     * <p>Wraps every top-level {@code H2FrameHandler} callback that can,
     * directly or by synchronously invoking application handler code,
     * write HEADERS/DATA/WINDOW_UPDATE frames -- HEADERS and DATA for a
     * small response answered synchronously from within one such callback
     * (the common case for small responses) then reach the peer as one
     * channel write, and for TLS, one record, instead of two or three.
     * Bounding this to one synchronous callback's duration (rather than
     * deferring a flush indefinitely, hoping some future call handles it)
     * is what keeps this safe for a response whose HEADERS carry no body
     * at all in this dispatch and none is coming soon either -- e.g. the
     * 200 response accepting an RFC 8441 WebSocket-over-HTTP/2 tunnel,
     * where the peer must see those headers before it sends anything
     * else: {@code requestH2Flush} still runs once this callback returns,
     * even though nothing else in it wrote to {@code h2Writer}.
     *
     * @param body the callback logic to run
     */
    private void h2Dispatch(Runnable body) {
        h2DispatchDepth++;
        try {
            body.run();
        } finally {
            if (--h2DispatchDepth == 0) {
                flushH2Writer();
            }
        }
    }

    /**
     * Writes out whatever {@link #h2Writer} holds. Frames that do not go
     * through the writer (RST_STREAM, GOAWAY, SETTINGS, PING) and closing
     * the connection call this first, so that frames reach the peer in the
     * order they were produced.
     */
    private void flushH2Writer() {
        if (h2Writer == null) {
            return;
        }
        try {
            h2Writer.flush();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, L10N.getString("warn.error_flushing_h2_frames"), e);
        }
    }

    /**
     * Flushes {@link #h2Writer}, unless called from inside {@link
     * #h2Dispatch}, which flushes once on the way out instead (issue
     * #322).
     */
    private void requestH2Flush() {
        if (h2DispatchDepth > 0) {
            return;
        }
        flushH2Writer();
    }

    /**
     * Encodes a header block as HEADERS (and CONTINUATION) frames. The block
     * holds the fields as given: the caller has added any pseudo-header.
     */
    private void writeH2Headers(int streamId, List<Header> headers, boolean endStream) {
        ByteBuffer buf;
        boolean success = false;
        int streamDependency = 0;
        boolean streamDependencyExclusive = false;
        int weight = 0;
        int padLength = framePadding;
        buf = ByteBufferPool.acquire(headerTableSize);
        while (!success) {
            try {
                hpackEncoder.encode(buf, headers);
                success = true;
            } catch (BufferOverflowException e) {
                ByteBuffer oldBuf = buf;
                buf = ByteBufferPool.acquire(buf.capacity() + headerTableSize);
                ByteBufferPool.release(oldBuf);
            } catch (ProtocolException e) {
                ByteBufferPool.release(buf);
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
            }
            if (endStream) {
                responseEndWritten(streamId);
            }
            requestH2Flush();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, L10N.getString("warn.error_sending_headers"), e);
        } finally {
            ByteBufferPool.release(buf);
        }
    }

    @Override
    void sendResponseTrailers(int streamId, List<Header> trailers) {
        if (state != State.HTTP2) {
            return;
        }
        PendingData pending = h2PendingData.get(streamId);
        if (pending != null) {
            // DATA is still queued behind the flow-control window: the
            // trailer section goes out after it, ending the stream
            pending.trailers = trailers;
        } else {
            writeH2Headers(streamId, trailers, true);
            requestH2Flush();
            releaseStreamIfFinished(streamId);
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
            int enqueued = buf.remaining();
            pending.enqueue(buf, endStream);
            pendingBytesCounter(streamId).addAndGet(enqueued);
        } else if (!claimH2BodySlot(streamId)) {
            int enqueued = buf.remaining();
            pending = acquirePendingData();
            pending.enqueue(buf, endStream);
            h2PendingData.put(streamId, pending);
            pendingBytesCounter(streamId).addAndGet(enqueued);
        } else {
            int toSend = buf.remaining();
            int window = h2FlowControl.availableSendWindow(streamId);
            if (window >= toSend) {
                h2FlowControl.consumeSendWindow(streamId, toSend);
                sendH2DataDirect(streamId, buf, endStream);
                // Nothing was queued for this stream (the whole buffer went out
                // synchronously above), so - same as the completion branch of
                // drainPendingData() below - release the RFC 9218 non-incremental
                // slot now rather than holding it forever. Without this, a
                // stream that always sends its full body in one shot (the
                // common case for small responses) claims the slot in
                // claimH2BodySlot() above but never releases it, permanently
                // starving every other non-incremental stream at the same
                // urgency from ever sending DATA again.
                releaseH2BodySlot(streamId);
                if (endStream) {
                    releaseStreamIfFinished(streamId);
                }
                drainRfc9218Pending();
            } else if (window > 0) {
                h2FlowControl.consumeSendWindow(streamId, window);
                int savedLimit = buf.limit();
                buf.limit(buf.position() + window);
                ByteBuffer slice = buf.slice();
                buf.position(buf.position() + window);
                buf.limit(savedLimit);
                sendH2DataDirect(streamId, slice, false);
                int enqueued = buf.remaining();
                pending = acquirePendingData();
                pending.enqueue(buf, endStream);
                h2PendingData.put(streamId, pending);
                pendingBytesCounter(streamId).addAndGet(enqueued);
            } else {
                int enqueued = buf.remaining();
                pending = acquirePendingData();
                pending.enqueue(buf, endStream);
                h2PendingData.put(streamId, pending);
                pendingBytesCounter(streamId).addAndGet(enqueued);
            }
        }
        requestH2Flush();
    }

    private AtomicInteger pendingBytesCounter(int streamId) {
        AtomicInteger counter = h2PendingBytes.get(streamId);
        if (counter == null) {
            counter = new AtomicInteger();
            AtomicInteger existing = h2PendingBytes.putIfAbsent(streamId, counter);
            if (existing != null) {
                counter = existing;
            }
        }
        return counter;
    }

    @Override
    public int pendingResponseBytes(int streamId) {
        if (h2FlowControl == null) {
            // HTTP/1.1 has no flow control window to hold body data back:
            // whatever is unsent is in the connection's outbound buffer.
            Endpoint ep = endpoint;
            return ep == null ? 0 : ep.pendingWriteBytes();
        }
        AtomicInteger counter = h2PendingBytes.get(streamId);
        return counter == null ? 0 : counter.get();
    }

    // RFC 9113 section 4.2: DATA frames MUST NOT exceed SETTINGS_MAX_FRAME_SIZE
    //
    // Issue #322: does not flush -- callers own the flush, once per
    // logical operation (see sendH2Data and drainPendingData), so that
    // one operation writing several DATA frames (or DATA following
    // deferred HEADERS) reaches the peer as one channel write instead of
    // one per frame.
    private void sendH2DataDirect(int streamId, ByteBuffer buf, boolean endStream) {
        int maxPayload = framePadding > 0 ? maxFrameSize - framePadding - 1 : maxFrameSize;
        try {
            if (endStream && !buf.hasRemaining() && h2Writer.endStreamOnLastData(streamId)) {
                // the stream's last DATA frame is still in the writer: it
                // ends the stream itself, with no empty frame after it
                responseEndWritten(streamId);
                return;
            }
            while (buf.remaining() > maxPayload) {
                int savedLimit = buf.limit();
                buf.limit(buf.position() + maxPayload);
                ByteBuffer slice = buf.slice();
                buf.position(buf.position() + maxPayload);
                buf.limit(savedLimit);
                h2Writer.writeData(streamId, slice, false, framePadding);
            }
            h2Writer.writeData(streamId, buf, endStream, framePadding);
            if (endStream) {
                responseEndWritten(streamId);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, L10N.getString("warn.error_sending_data_frame"), e);
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

        AtomicInteger counter = h2PendingBytes.get(streamId);
        int available = h2FlowControl.availableSendWindow(streamId);
        while (available > 0 && !pending.isEmpty()) {
            ByteBuffer head = pending.buffers.peek();
            int headRemaining = head.remaining();
            if (available >= headRemaining) {
                pending.buffers.poll();
                h2FlowControl.consumeSendWindow(streamId, headRemaining);
                available -= headRemaining;
                if (counter != null) {
                    counter.addAndGet(-headRemaining);
                }
                boolean fin = pending.endStream && pending.isEmpty() && pending.trailers == null;
                try {
                    sendH2DataDirect(streamId, head, fin);
                } finally {
                    ByteBufferPool.release(head);
                }
            } else {
                h2FlowControl.consumeSendWindow(streamId, available);
                int savedLimit = head.limit();
                head.limit(head.position() + available);
                ByteBuffer slice = head.slice();
                head.position(head.position() + available);
                head.limit(savedLimit);
                sendH2DataDirect(streamId, slice, false);
                if (counter != null) {
                    counter.addAndGet(-available);
                }
                available = 0;
            }
        }

        // Issue #322: sendH2DataDirect no longer flushes per call, so a
        // multi-chunk drain (e.g. several queued DATA frames released by
        // one WINDOW_UPDATE) reaches the peer as a single channel write
        // instead of one per chunk.
        if (pending.isEmpty() && pending.trailers != null) {
            writeH2Headers(streamId, pending.trailers, true);
            pending.trailers = null;
        }
        requestH2Flush();

        if (pending.isEmpty()) {
            h2PendingData.remove(streamId);
            h2PendingBytes.remove(streamId);
            releasePendingData(pending);
            Runnable cb = h2WriteCallbacks.remove(streamId);
            if (cb != null) {
                cb.run();
            }
            releaseH2BodySlot(streamId);
            releaseStreamIfFinished(streamId);
            drainRfc9218Pending();
        }
    }

    /** Package-private for direct testing of h2Priority storage (issue #299). */
    PriorityParams h2PriorityOf(int streamId) {
        PriorityParams params = h2Priority.get(streamId);
        return params != null ? params : PriorityParams.DEFAULT;
    }

    private boolean claimH2BodySlot(int streamId) {
        return h2NonIncSlots.claim(streamId, h2PriorityOf(streamId));
    }

    private void releaseH2BodySlot(int streamId) {
        h2NonIncSlots.release(streamId, h2PriorityOf(streamId));
    }

    private void drainRfc9218Pending() {
        if (h2PendingData.isEmpty()) {
            return;
        }
        // Priority-ordered scheduling needs a sort, which needs boxed
        // Integer comparisons either way -- this snapshot-then-sort path
        // isn't the per-DATA-frame hot path IntObjectHashMap targets, so
        // boxing here (unlike the get/put/remove/containsKey calls below)
        // isn't worth avoiding.
        int[] rawIds = h2PendingData.keys();
        Integer[] ids = new Integer[rawIds.length];
        for (int i = 0; i < rawIds.length; i++) {
            ids[i] = Integer.valueOf(rawIds[i]);
        }
        Arrays.sort(ids, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return PriorityParams.compareSchedule(h2PriorityOf(a.intValue()), a.intValue(),
                        h2PriorityOf(b.intValue()), b.intValue());
            }
        });
        for (int i = 0; i < ids.length; i++) {
            int id = ids[i].intValue();
            if (h2PendingData.containsKey(id) && claimH2BodySlot(id)) {
                drainPendingData(id);
            }
        }
    }

    // RFC 9112 section 9.6: sending null signals connection close
    @Override
    public void send(ByteBuffer buf) {
        if (endpoint == null) {
            return;
        }
        flushH2Writer();
        if (buf == null) {
            flushHeld();
            endpoint.close();
            return;
        }
        // only for output from the thread handling the read: a response
        // written from elsewhere meanwhile is sent as it comes
        if (h2Writer == null && Thread.currentThread() == receivingThread && hold(buf)) {
            return;
        }
        endpoint.send(buf);
    }

    /**
     * Keeps HTTP/1.x output produced while a read is being handled, to be
     * written in one go when the read has been handled: the header section,
     * the chunk and the last chunk of a response, and the responses to
     * pipelined requests, reach the endpoint as one write and so, over TLS,
     * one record rather than one each. Output too large to be worth copying
     * is not held: what is held is written and the caller sends it directly.
     *
     * @return true if the data was taken; false if the caller must send it
     */
    private boolean hold(ByteBuffer buf) {
        int length = buf.remaining();
        if (length > HELD_OUTPUT_LIMIT
                || (heldOutput != null && heldOutput.position() + length > HELD_OUTPUT_LIMIT)) {
            flushHeld();
            if (length > HELD_OUTPUT_LIMIT / 2) {
                return false;
            }
        }
        if (heldOutput == null) {
            heldOutput = ByteBufferPool.acquire(HELD_OUTPUT_LIMIT);
        }
        heldOutput.put(buf);
        return true;
    }

    /** Writes what {@link #hold} kept. */
    private void flushHeld() {
        ByteBuffer held = heldOutput;
        if (held == null) {
            return;
        }
        heldOutput = null;
        try {
            held.flip();
            if (held.hasRemaining() && endpoint != null) {
                endpoint.send(held);
            }
        } finally {
            ByteBufferPool.release(held);
        }
    }

    // RFC 9113 section 5.4.2: stream errors are signaled with RST_STREAM
    @Override
    public void sendRstStream(int streamId, int errorCode) {
        ByteBuffer buf = ByteBufferPool.acquire(FRAME_HEADER_LENGTH + 4);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 4);
        buf.put((byte) H2FrameHandler.TYPE_RST_STREAM);
        buf.put((byte) 0);
        buf.putInt(streamId);
        buf.putInt(errorCode);
        buf.flip();
        try {
            send(buf);
        } finally {
            ByteBufferPool.release(buf);
        }
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
            endpoint.scheduleTimer(GRACEFUL_GOAWAY_DELAY_MS, new Runnable() {
                @Override
                public void run() {
                    int lastStreamId = clientStreamId > 0 ? clientStreamId : 0;
                    sendGoawayFrame(lastStreamId,
                            H2FrameHandler.ERROR_NO_ERROR, debugMessage);
                    closeEndpoint();
                }
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
        ByteBuffer buf = ByteBufferPool.acquire(FRAME_HEADER_LENGTH + payloadLength);
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
        try {
            send(buf);
        } finally {
            ByteBufferPool.release(buf);
        }
    }

    @Override
    public void switchToWebSocketMode(int streamId) {
        if (version == HttpVersion.HTTP_2_0) {
            // RFC 8441 section 5: unlike RFC 6455 over HTTP/1.1 (where the
            // single stream *is* the whole connection, so upgrading means
            // the entire connection stops being parsed as HTTP), WebSocket
            // frames over HTTP/2 stay carried inside ordinary DATA frames
            // on this one stream — HTTP/2 framing itself never stops, and
            // every other concurrent stream on this connection is
            // unaffected. Flipping the connection-wide `state` to
            // WEBSOCKET here (as the HTTP/1.1 branch below does) would
            // divert receive()'s top-level dispatch away from
            // receiveFrameData()/h2Parser entirely, silently breaking
            // every other stream multiplexed on this connection. Nothing
            // to do here: Stream.webSocketAdapter (already set by the
            // caller before this method runs) is the only signal needed —
            // dataFrameReceived() -> Stream.receiveRequestBody() already
            // routes this stream's DATA frame payloads to it per-stream.
            return;
        }
        switchToStreamTunnelMode(streamId);
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(L10N.getString("debug.switched_websocket_mode"), streamId));
        }
    }

    @Override
    public void switchToStreamTunnelMode(int streamId) {
        if (version == HttpVersion.HTTP_2_0) {
            // See switchToWebSocketMode's identical HTTP/2 branch: HTTP/2
            // framing never stops for any upgraded stream, since every
            // other concurrent stream on the connection is unaffected by
            // one being handed over to a tunnel (e.g. Stream.capsuleMode,
            // already set by the time this runs, is all
            // receiveRequestBody() needs to route this stream's DATA
            // frame payloads as Capsule Protocol data instead of an
            // ordinary request body).
            return;
        }
        this.webSocketStreamId = streamId;
        this.state = State.WEBSOCKET;
        // May be called synchronously, from within the call stack of a
        // LINE token's dispatch (e.g. an app accepting a CONNECT-UDP
        // tunnel as soon as headers complete) — if the current buffer
        // has more bytes after this point (a pipelined capsule), the
        // lexer must stop trying to tokenise them as HTTP lines and hand
        // them to receive()'s WEBSOCKET-state dispatch instead (that
        // dispatch is not actually WebSocket-specific -- see
        // receiveWebSocket()/State.WEBSOCKET's own documentation).
        // Harmless if called asynchronously too: receive() checks state
        // before ever calling lexer.feed(), so this just becomes a no-op
        // wait for the next feed() call that will never come.
        requestParser.handOff();
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
    public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
        return endpoint != null ? endpoint.scheduleTimer(delayMs, callback) : null;
    }

    @Override
    public int getMaxHeaderListSize() {
        return maxHeaderListSize;
    }

    @Override
    public long getMaxRequestBodySize() {
        return maxRequestBodySize;
    }

    @Override
    public HttpAuthenticationProvider getAuthenticationProvider() {
        return authenticationProvider;
    }

    private void checkContinuationLimit() {
        continuationFramesInBlock++;
        if (continuationFramesInBlock > MAX_CONTINUATION_FRAMES_PER_BLOCK) {
            sendGoaway(H2FrameHandler.ERROR_PROTOCOL_ERROR);
            closeEndpoint();
        }
    }

    private void resetContinuationLimit() {
        continuationFramesInBlock = 0;
    }

    private void checkRstStreamRate() {
        long now = System.currentTimeMillis();
        if (now - rstStreamWindowStartMs > 1000L) {
            rstStreamWindowStartMs = now;
            rstStreamCount = 0;
        }
        rstStreamCount++;
        if (rstStreamCount > MAX_RST_STREAMS_PER_SECOND) {
            sendGoaway(H2FrameHandler.ERROR_ENHANCE_YOUR_CALM);
            closeEndpoint();
        }
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
    public HttpServerMetrics getServerMetrics() {
        return server != null ? server.getMetrics() : null;
    }

    Http2Listener getListener() {
        return server;
    }

    @Override
    public boolean isEnablePush() {
        return enablePush;
    }

    @Override
    Stream newStream(HttpConnectionLike connection, int streamId) {
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
    byte[] encodeHeaders(List<Header> headers) {
        try {
            if (hpackEncoder == null) {
                hpackEncoder = new Encoder(headerTableSize, maxHeaderListSize);
            }
            ByteBuffer buffer = ByteBufferPool.acquire(HPACK_ENCODE_BUFFER_SIZE);
            try {
                hpackEncoder.encode(buffer, headers);
                buffer.flip();
                byte[] encoded = new byte[buffer.remaining()];
                buffer.get(encoded);
                return encoded;
            } finally {
                ByteBufferPool.release(buffer);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, L10N.getString("warn.hpack_encode_failed"), e);
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
                requestH2Flush();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, L10N.getString("warn.error_sending_push_promise"), e);
            }
        }
    }

    @Override
    Stream createPushedStream(int streamId, String method, String uri, List<Header> headers) {
        try {
            Stream pushedStream = newStream(this, streamId);
            pushedStream.setPushPromise();
            for (Header header : headers) {
                pushedStream.addHeader(header);
            }
            streams.put(streamId, pushedStream);
            pushedStream.openApplicationHandler();
            return pushedStream;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, MessageFormat.format(L10N.getString("warn.failed_create_pushed_stream"), streamId), e);
            return null;
        }
    }

    // ── Backpressure / flow control (HttpConnectionLike) ──

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
                        h2WriteCallbacks.drainEach(new IntObjectHashMap.EntryConsumer<Runnable>() {
                            @Override
                            public void accept(int streamId, Runnable callback) {
                                callback.run();
                            }
                        });
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
                    requestH2Flush();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, L10N.getString("warn.error_deferred_window_update"), e);
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
        flushH2Writer();
        flushHeld();
        if (endpoint != null) {
            endpoint.close();
        }
    }

    // RFC 9112 section 9.8 / RFC 9113 section 9.1: idle connection timeout
    private void resetIdleTimeout() {
        cancelIdleTimeout();
        long timeoutMs = server.getIdleTimeoutMs();
        if (timeoutMs > 0 && endpoint != null) {
            idleTimeoutHandle = endpoint.scheduleTimer(timeoutMs, new Runnable() {
                @Override
                public void run() {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.fine(MessageFormat.format(L10N.getString("debug.closing_idle_http_connection"), timeoutMs));
                    }
                    // RFC 9113 section 9.1: use graceful GOAWAY for HTTP/2
                    if (version == HttpVersion.HTTP_2_0) {
                        sendGracefulGoaway("idle timeout");
                    } else {
                        closeEndpoint();
                    }
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
                && version == HttpVersion.HTTP_2_0) {
            schedulePing(intervalMs);
        }
    }

    private void schedulePing(long intervalMs) {
        pingKeepAliveHandle = endpoint.scheduleTimer(intervalMs, new Runnable() {
            @Override
            public void run() {
                if (endpoint != null && !goawaySent) {
                    long opaqueData = System.nanoTime();
                    sendPingFrame(opaqueData);
                    schedulePing(intervalMs);
                }
            }
        });
    }

    // RFC 9113 section 6.7: send a PING frame (non-ACK)
    private void sendPingFrame(long opaqueData) {
        ByteBuffer buf = ByteBufferPool.acquire(FRAME_HEADER_LENGTH + 8);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 8);
        buf.put((byte) H2FrameHandler.TYPE_PING);
        buf.put((byte) 0);
        buf.putInt(0);
        buf.putLong(opaqueData);
        buf.flip();
        try {
            send(buf);
        } finally {
            ByteBufferPool.release(buf);
        }
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
        // This shortcut bypasses streamEndHeaders()/streamEndRequest(), so
        // advance the (bodyless, fully-received) stream out of IDLE and carry
        // over the client's Connection: close intent before committing.
        markInternalRequestReceived(stream);
        try {
            List<Header> headers = new ArrayList<Header>();
            HeaderFields.add(headers, "Allow", getAllowedMethods());
            HeaderFields.add(headers, "Content-Length", "0");
            stream.sendResponseHeaders(200, headers, true);
        } catch (ProtocolException e) {
            LOGGER.log(Level.WARNING, L10N.getString("warn.error_options_star_response"), e);
        }
    }

    // Prepares a stream answered by an internally generated response (OPTIONS *,
    // TRACE) that bypasses the normal streamEndHeaders()/streamEndRequest() path.
    // RFC 9112 section 9.6: a request with Connection: close ends the connection.
    private void markInternalRequestReceived(Stream stream) {
        String conn = HeaderFields.getValue(stream.getHeaders(), "Connection");
        if (conn != null && conn.toLowerCase().contains("close")) {
            stream.closeConnection = true;
        }
        stream.markInternalRequestComplete();
    }

    // RFC 9110 section 9.3.8: TRACE echoes the request back.
    // Disabled by default for security; responds 405 when disabled.
    private void handleTrace(Stream stream) {
        if (!server.isTraceMethodEnabled()) {
            sendStreamError(stream, 405);
            return;
        }
        // Same as OPTIONS *: advance the bodyless stream out of IDLE since the
        // TRACE shortcut bypasses streamEndHeaders()/streamEndRequest().
        markInternalRequestReceived(stream);
        try {
            // Echo the request message as message/http
            StringBuilder echo = new StringBuilder();
            echo.append("TRACE ").append(HeaderFields.getValue(stream.getHeaders(), ":path"))
                    .append(' ').append(version).append("\r\n");
            for (Header h : stream.getHeaders()) {
                if (!h.getName().startsWith(":")) {
                    echo.append(h.getName()).append(": ").append(h.getValue()).append("\r\n");
                }
            }
            echo.append("\r\n");
            byte[] body = echo.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            List<Header> headers = new ArrayList<Header>();
            HeaderFields.add(headers, "Content-Type", "message/http");
            HeaderFields.add(headers, "Content-Length", Integer.toString(body.length));
            stream.sendResponseHeaders(200, headers, false);
            stream.sendResponseBody(ByteBuffer.wrap(body), true);
        } catch (ProtocolException e) {
            LOGGER.log(Level.WARNING, L10N.getString("warn.error_trace_response"), e);
        }
    }

    private String getAllowedMethods() {
        Set<String> methods = DEFAULT_METHODS;
        StringBuilder sb = new StringBuilder();
        for (String m : methods) {
            if ("PRI".equals(m)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(m);
        }
        return sb.toString();
    }

    // RFC 9110 section 15.6.2: 501 Not Implemented if method not recognised
    private boolean isMethodSupported(String method) {
        if (streamHandler != null) {
            return true;
        }
        return DEFAULT_METHODS.contains(method);
    }

    Stream getStream(int streamId) {
        maybeCleanupClosedStreams();
        if (streamId == 0) {
            return null;
        }
        Stream s = streams.get(streamId);
        if (s == null) {
            // A plain HTTP/1.x connection processes exactly one
            // request/response at a time, sequentially, on this
            // connection's own SelectorLoop thread - so a brand-new stream
            // ID while still in one of these states means any previously
            // tracked stream has already fully completed. Evict it here
            // instead of waiting for the next periodic
            // maybeCleanupClosedStreams() sweep (gated 30s apart), so
            // streams never grows past one entry - and never needs to
            // resize - under sustained HTTP/1.1 keep-alive throughput
            // (issue #279). Deliberately an allowlist of the plain
            // HTTP/1.x states rather than excluding the HTTP/2 ones: the
            // h2c-upgrade transition (H2C_PREFACE, PRI, PRI_SETTINGS) can
            // have a stream from before the upgrade (the upgrading request
            // itself, or a prior-knowledge placeholder) that must survive
            // into real HTTP/2 processing, and a future state added to the
            // enum defaults to not clearing rather than clearing wrongly.
            if (isSequentialHttp1State() && !streams.isEmpty()) {
                streams.clear();
            }
            s = newStream(this, streamId);
            if (h2FlowControl != null) {
                h2FlowControl.openStream(streamId);
            }
            streams.put(streamId, s);
            s.openApplicationHandler();
        }
        return s;
    }

    private boolean isSequentialHttp1State() {
        switch (state) {
            case HTTP1:
                return true;
            default:
                return false;
        }
    }

    /** Package-private, only ever read by tests (see issue #279). */
    int streamCountForTesting() {
        return streams.size();
    }

    /** Package-private, only ever read by tests. */
    int activeStreamCountForTesting() {
        return activeStreams.size();
    }

    // Called by Stream when its response path sets state to CLOSED, freeing the
    // concurrency slot immediately.  Must NOT be called from the RST_STREAM path
    // so that rapid-reset attacks cannot bypass SETTINGS_MAX_CONCURRENT_STREAMS.
    void streamResponseCompleted(int streamId) {
        activeStreams.remove(streamId);
        releaseStreamIfFinished(streamId);
    }

    /** Notes that the frame carrying END_STREAM for this stream's response has been written. */
    private void responseEndWritten(int streamId) {
        Stream stream = streams.get(streamId);
        if (stream != null) {
            stream.responseEndWritten = true;
        }
    }

    /**
     * Releases an HTTP/2 stream, and everything held for it, once both its
     * request and its response have ended and the last frame of the response
     * has been written. A busy connection finishes streams as fast as it
     * starts them; leaving each one for the periodic sweep made the memory
     * held by a connection grow with its request rate.
     *
     * <p>A stream that was reset rather than finished is not released here.
     * It keeps its concurrency slot until {@link #maybeCleanupClosedStreams}
     * sweeps it (CVE-2023-44487).
     */
    private void releaseStreamIfFinished(int streamId) {
        if (h2FlowControl == null) {
            return;
        }
        Stream stream = streams.get(streamId);
        if (stream == null || !stream.isClosed() || !stream.responseEndWritten
                || activeStreams.contains(streamId) || h2PendingData.containsKey(streamId)) {
            return;
        }
        streams.remove(streamId);
        h2FlowControl.closeStream(streamId);
        h2WriteCallbacks.remove(streamId);
        h2PendingBytes.remove(streamId);
        h2Priority.remove(streamId);
    }

    /**
     * Whether this stream identifier belongs to a stream that has been and
     * gone: one the peer or this server opened earlier that is no longer
     * tracked. RFC 9113 section 5.1.1: identifiers only increase, so an
     * identifier at or below the highest one used cannot be a new stream.
     */
    private boolean isReleasedStream(int streamId) {
        if (streamId == 0 || streams.containsKey(streamId)) {
            return false;
        }
        if ((streamId & 1) == 1) {
            return streamId <= lastClientStreamId;
        }
        return streamId < serverStreamId;
    }

    private void maybeCleanupClosedStreams() {
        long now = System.currentTimeMillis();
        if (now - lastStreamCleanup < STREAM_CLEANUP_INTERVAL_MS) {
            return;
        }
        lastStreamCleanup = now;
        int removedCount = 0;
        // An explicit iterator with it.remove() is required now that
        // streams is a plain HashMap (issue #279): calling streams.remove()
        // directly while a for-each loop holds the entrySet's own iterator
        // open is safe for the former ConcurrentHashMap (weakly-consistent
        // iterators tolerate concurrent structural changes) but throws
        // ConcurrentModificationException on a HashMap.
        for (Iterator<Map.Entry<Integer, Stream>> it = streams.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Stream> entry = it.next();
            Stream stream = entry.getValue();
            if (stream.isClosed()) {
                // Free the concurrency slot for any closed stream not already
                // freed via the normal response path (e.g. RST-cancelled streams
                // whose handler exited without sending a response).
                activeStreams.remove(entry.getKey());
                if ((now - stream.timestampCompleted) > STREAM_RETENTION_MS) {
                    int sid = entry.getKey();
                    it.remove();
                    if (h2FlowControl != null) {
                        h2FlowControl.closeStream(sid);
                    }
                    h2WriteCallbacks.remove(sid);
                    h2PendingBytes.remove(sid);
                    h2Priority.remove(sid);
                    PendingData removed = h2PendingData.remove(sid);
                    if (removed != null) {
                        releasePendingData(removed);
                    }
                    removedCount++;
                }
            }
        }
        if (removedCount > 0 && LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(
                    L10N.getString("debug.cleaned_up_closed_streams"),
                    removedCount, streams.size()));
        }
    }

    private void sendStreamError(Stream stream, int statusCode) {
        if (stream == null || !stream.canCommitErrorResponse()) {
            if (stream != null && LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(
                        L10N.getString("debug.skipping_stream_error_response"),
                        statusCode));
            }
            return;
        }
        // Stream.sendError() marks an HTTP/1.x connection for close: once
        // the error response is committed nothing further on this
        // connection may be parsed or dispatched to the application (the
        // rest of the malformed request, e.g. its terminating blank line,
        // would otherwise still reach the application handler).
        boolean http1 = isSequentialHttp1State();
        if (http1) {
            fatalParseError = true;
        }
        try {
            stream.sendError(statusCode);
            if (http1) {
                // the request was only partly received, so Stream will not
                // itself close the connection after this response; close is
                // graceful (queued output is flushed first) and idempotent
                closeEndpoint();
            }
        } catch (ProtocolException e) {
            if (!stream.canCommitErrorResponse()) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.log(Level.FINE, L10N.getString("err.send_headers"), e);
                }
                return;
            }
            String message = L10N.getString("err.send_headers");
            LOGGER.log(Level.SEVERE, message, e);
        }
    }

    // ── HTTP/1.x requests (RFC 9112), parsed by Http1Parser ──

    /** RFC 9113 section 3.4: the octets every prior-knowledge cleartext HTTP/2 connection starts with. */
    private static final byte[] H2_PREFACE =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private static final int PREFACE_NO = 0;
    private static final int PREFACE_MAYBE = 1;
    private static final int PREFACE_YES = 2;

    /**
     * Whether the bytes at the front of {@code buf} are, or may yet become,
     * the HTTP/2 connection preface. "PRI * HTTP/2.0" is not an HTTP/1.x
     * request, so it is recognised before the request parser sees it.
     */
    private static int matchH2Preface(ByteBuffer buf) {
        int n = Math.min(buf.remaining(), H2_PREFACE.length);
        for (int i = 0; i < n; i++) {
            if (buf.get(buf.position() + i) != H2_PREFACE[i]) {
                return PREFACE_NO;
            }
        }
        return n == H2_PREFACE.length ? PREFACE_YES : PREFACE_MAYBE;
    }

    /** Reads HTTP/1.x request bytes: a prior-knowledge HTTP/2 preface, or a request for the parser. */
    private void receiveHttp1(ByteBuffer buf) {
        if (http1AtMessageStart) {
            int preface = matchH2Preface(buf);
            if (preface == PREFACE_YES) {
                buf.position(buf.position() + H2_PREFACE.length);
                startPriorKnowledgeHttp2();
                return;
            }
            if (preface == PREFACE_MAYBE) {
                return;     // wait for more of it
            }
        }
        http1AtMessageStart = false;
        requestParser.receive(buf);
    }

    // RFC 9113 section 3.4: the client has sent the connection preface in
    // cleartext (prior knowledge); this is where HTTP/2 starts.
    private void startPriorKnowledgeHttp2() {
        version = HttpVersion.HTTP_2_0;
        h2Parser = new H2Parser(this);
        h2Parser.setMaxFrameSize(maxFrameSize);
        h2Writer = new H2Writer(new EndpointChannel());
        h2FlowControl = new H2FlowControl();
        for (Integer existingStreamId : streams.keySet()) {
            h2FlowControl.openStream(existingStreamId.intValue());
        }
        state = State.PRI_SETTINGS;
        // RFC 9113 section 3.4: server connection preface is a SETTINGS frame
        Map<Integer, Integer> initialSettings = new LinkedHashMap<Integer, Integer>();
        initialSettings.put(H2FrameHandler.SETTINGS_MAX_CONCURRENT_STREAMS,
                serverMaxConcurrentStreams);
        initialSettings.put(H2FrameHandler.SETTINGS_MAX_HEADER_LIST_SIZE,
                serverMaxHeaderListSize);
        // RFC 8441 section 3: advertise support for the extended CONNECT
        // method (WebSocket-over-HTTP/2).
        initialSettings.put(H2FrameHandler.SETTINGS_ENABLE_CONNECT_PROTOCOL, 1);
        initialSettings.put(H2FrameHandler.SETTINGS_NO_RFC7540_PRIORITIES, 1);
        sendSettingsFrame(false, initialSettings);
        startSettingsTimeout();
    }

    /**
     * Receives the events of the HTTP/1.x request being parsed and carries
     * out the server's part: it checks the request is one this server will
     * take (method, target, {@code Host}), builds the header list the
     * stream works from, and keeps the events so they can be replayed to the
     * application handler once the server has decided what to do with the
     * request (see {@link Stream}).
     *
     * <p>It is also the parser's field tap, which supplies the fields as sent,
     * original case and exact text, for that list.
     */
    private final class RequestEvents implements HttpMessageHandler, HeaderFieldHandler {

        private Stream stream;
        /** Set once the request is refused or the connection is handed over: later events are ignored. */
        private boolean ignoring;
        /** Set once the header section has ended: further fields are trailers. */
        private boolean headersDone;

        private Stream current() {
            if (stream == null) {
                // until the request's own version is known, errors are
                // answered in HTTP/1.1 (RFC 9110 section 15.6.6 for 505)
                HttpProtocolHandler.this.version = HttpVersion.HTTP_1_1;
                stream = getStream(clientStreamId);
            }
            return stream;
        }

        private HttpMessageRecorder recorder() {
            return current().eventRecorder();
        }

        /** Answers the request with an error status and stops reading it. */
        private void refuse(int statusCode) {
            ignoring = true;
            sendStreamError(current(), statusCode);
            requestParser.handOff();
        }

        private String text(ByteBuffer b) {
            return octetString(b);
        }

        // the tap: the field as it was sent
        @Override
        public void field(ByteBuffer name, ByteBuffer value) {
            if (ignoring) {
                return;
            }
            try {
                current().addHeader(Header.ofOctets(name, value));
            } catch (IllegalArgumentException e) {
                refuse(400);
            }
        }

        @Override
        public void method(HttpMethod method) {
            Stream s = current();
            // RFC 9110 section 15.6.2: 501 if the method is not implemented
            if (!isMethodSupported(method.name())) {
                refuse(501);
                return;
            }
            s.addHeader(new Header(":method", method.name()));
            s.eventRecorder().method(method);
        }

        @Override
        public void target(ByteBuffer target) {
            if (ignoring) {
                return;
            }
            String t = text(target);
            if (!HttpUtils.isValidRequestTarget(t)) {
                refuse(400);
                return;
            }
            Stream s = current();
            s.addHeader(new Header(":path", t));
            s.eventRecorder().target(target);
        }

        @Override
        public void version(HttpVersion v) {
            if (ignoring) {
                return;
            }
            HttpProtocolHandler.this.version = v;
            Stream s = current();
            if (v == HttpVersion.HTTP_1_0) {
                // RFC 9112 section 9.3: HTTP/1.0 defaults to close
                s.closeConnection = true;
            }
            String scheme = endpoint.isSecure() ? "https" : "http";
            s.addHeader(new Header(":scheme", scheme));
            s.eventRecorder().version(v);
            // HTTP/1.x carries no scheme; it belongs to the connection
            s.eventRecorder().scheme(ByteBuffer.wrap(scheme.getBytes(StandardCharsets.US_ASCII)));
        }

        @Override public void scheme(ByteBuffer scheme) { }
        @Override public void protocol(ByteBuffer protocol) { }
        @Override public void status(int code) { }
        @Override public void reason(ByteBuffer phrase) { }

        @Override public void authority(ByteBuffer authority) {
            if (!ignoring) { recorder().authority(authority); }
        }
        @Override public void contentType(ContentType contentType) {
            if (!ignoring) { recorder().contentType(contentType); }
        }
        @Override public void contentDisposition(ContentDisposition disposition) {
            if (!ignoring) { recorder().contentDisposition(disposition); }
        }
        @Override public void longHeader(String name, long value) {
            if (!ignoring) { recorder().longHeader(name, value); }
        }
        @Override public void dateHeader(String name, java.time.Instant value) {
            if (!ignoring) { recorder().dateHeader(name, value); }
        }
        @Override public void header(String name, ByteBuffer value) {
            if (ignoring) {
                return;
            }
            if (headersDone) {
                // a trailer field, after the body
                stream.trailerField(name, value);
            } else {
                recorder().header(name, value);
            }
        }

        @Override
        public void endHeaders() {
            if (ignoring) {
                return;
            }
            recorder().endHeaders();
            headersDone = true;
            http1EndHeaders(stream);
        }

        @Override
        public void bodyContent(ByteBuffer data) {
            if (ignoring || requestAnsweredInternally) {
                return;
            }
            stream.receiveRequestBody(data);
        }

        @Override
        public void endMessage() {
            Stream s = stream;
            stream = null;
            headersDone = false;
            http1AtMessageStart = true;
            if (ignoring) {
                return;
            }
            if (requestAnsweredInternally) {
                // OPTIONS * or TRACE: answered by the server itself
                requestAnsweredInternally = false;
                clientStreamId += 2;
                return;
            }
            s.streamEndRequest();
            if (h2cUpgradePending) {
                completeH2cUpgrade();
                requestParser.handOff();
            } else if (s.hasWebSocketUpgrade()) {
                // RFC 6455 section 4.1: a bodyless upgrade may carry pipelined
                // WebSocket frames in the same TCP read; hold the raw bytes for
                // receiveWebSocket() instead of parsing them as the next request.
                webSocketStreamId = clientStreamId;
                state = State.WEBSOCKET;
                requestParser.handOff();
            } else {
                clientStreamId += 2;
            }
        }

        @Override
        public void failed(Exception cause) {
            // the parser reports malformed input through error(); transport
            // failures are handled by the connection itself
        }

        @Override
        public void error(HttpError error, String detail) {
            if (ignoring) {
                return;
            }
            fatalParseError = true;
            ignoring = true;
            sendStreamError(current(), error.getStatusCode());
        }
    }

    /**
     * What the server does when it has the whole header section of an HTTP/1.x
     * request: the checks that need all of it, the requests it answers itself,
     * and handing the request to the stream.
     */
    private void http1EndHeaders(Stream stream) {
        // RFC 9112 section 3.2: A server MUST respond with 400 to any
        // HTTP/1.1 request that lacks a Host header field and to any request
        // that contains more than one Host header field line or a Host header
        // field with an invalid field-value.
        if (this.version == HttpVersion.HTTP_1_1) {
            int hostCount = 0;
            for (Header header : stream.getHeaders()) {
                if (header.getName().equalsIgnoreCase("host")
                        || header.getName().equals(":authority")) {
                    hostCount++;
                }
            }
            if (hostCount != 1) {
                requestEvents.refuse(400);
                return;
            }
            String hostValue = HeaderFields.getValue(stream.getHeaders(), "host");
            if (hostValue == null) {
                hostValue = HeaderFields.getValue(stream.getHeaders(), ":authority");
            }
            if (!HttpUtils.isValidHost(hostValue)) {
                requestEvents.refuse(400);
                return;
            }
        }
        // RFC 9110 section 9.3.7: OPTIONS * targets the server itself
        String method = HeaderFields.getValue(stream.getHeaders(), ":method");
        String target = HeaderFields.getValue(stream.getHeaders(), ":path");
        if ("OPTIONS".equals(method) && "*".equals(target)) {
            requestAnsweredInternally = true;
            handleOptionsAsterisk(stream);
            return;
        }
        // RFC 9110 section 9.3.8: TRACE method handling
        if ("TRACE".equals(method)) {
            requestAnsweredInternally = true;
            handleTrace(stream);
            return;
        }
        stream.streamEndHeaders();
        // RFC 6455 section 4.1: an application's headers() callback may
        // synchronously switch this connection into WEBSOCKET mode (via
        // switchToWebSocketMode(), called from HttpResponse.
        // upgradeToWebSocket()); the parser must then stop at once, so the
        // WebSocket frame bytes that follow are not read as another request.
        if (state == State.WEBSOCKET) {
            requestParser.handOff();
            return;
        }
        // RFC 9112 section 9.6: track requests for Connection: close
        requestCount++;
        int maxRequests = server.getMaxRequestsPerConnection();
        if (maxRequests > 0 && requestCount >= maxRequests) {
            stream.closeConnection = true;
        }
        long contentLength = stream.getContentLength();
        boolean chunked = stream.isChunked();
        if (stream.upgrade != null && stream.upgrade.contains("h2c") && stream.h2cSettings != null) {
            if (contentLength == 0L && !chunked) {
                h2cBodylessUpgradeNeedsRequestComplete = true;
                completeH2cUpgrade();
                requestParser.handOff();
                return;
            }
            h2cUpgradePending = true;
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(L10N.getString("debug.h2c_upgrade_pending_body"));
            }
        }
        // RFC 9112 section 6.3: the body length is Transfer-Encoding, else
        // Content-Length, else (for a request) none. A request that needs a
        // body but declares no length is refused with 411.
        if (contentLength < 0L && !chunked) {
            // RFC 9110 section 15.5.12: 411 Length Required
            requestEvents.refuse(411);
        }
    }

    private void receiveFrameData(ByteBuffer buf) {
        if (h2Parser == null) {
            // HTTP/2 not initialized yet - this shouldn't happen
            LOGGER.warning(L10N.getString("warn.h2_frame_data_before_parser_init"));
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
                    LOGGER.fine(L10N.getString("debug.consumed_h2_connection_preface"));
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
            LOGGER.fine(MessageFormat.format(L10N.getString("debug.h2_frame_data_preview"), hex.toString().trim(), ascii.toString()));
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
    private void receiveH2cPreface(ByteBuffer buf) {
        while (buf.hasRemaining() && h2cPrefacePos < H2C_CONNECTION_PREFACE.length) {
            byte b = buf.get();
            if (b != H2C_CONNECTION_PREFACE[h2cPrefacePos]) {
                sendGoaway(H2FrameHandler.ERROR_PROTOCOL_ERROR);
                closeEndpoint();
                return;
            }
            h2cPrefacePos++;
        }
        if (h2cPrefacePos == H2C_CONNECTION_PREFACE.length) {
            h2Parser = new H2Parser(this);
            h2Parser.setMaxFrameSize(maxFrameSize);
            h2Writer = new H2Writer(new EndpointChannel());
            h2FlowControl = new H2FlowControl();
            for (Integer existingStreamId : streams.keySet()) {
                h2FlowControl.openStream(existingStreamId.intValue());
            }
            state = State.PRI_SETTINGS;
            Map<Integer, Integer> initialSettings = new LinkedHashMap<Integer, Integer>();
            initialSettings.put(H2FrameHandler.SETTINGS_MAX_CONCURRENT_STREAMS,
                    serverMaxConcurrentStreams);
            initialSettings.put(H2FrameHandler.SETTINGS_MAX_HEADER_LIST_SIZE,
                    serverMaxHeaderListSize);
            // RFC 8441 section 3: advertise support for the extended
            // CONNECT method (WebSocket-over-HTTP/2).
            initialSettings.put(H2FrameHandler.SETTINGS_ENABLE_CONNECT_PROTOCOL, 1);
            initialSettings.put(H2FrameHandler.SETTINGS_NO_RFC7540_PRIORITIES, 1);
            sendSettingsFrame(false, initialSettings);
            startSettingsTimeout();
        }
    }

    private void completeH2cUpgrade() {
        h2cUpgradePending = false;
        List<Header> responseHeaders = new ArrayList<Header>();
        HeaderFields.add(responseHeaders, "Connection", "Upgrade");
        HeaderFields.add(responseHeaders, "Upgrade", "h2c");
        sendResponseHeaders(clientStreamId, 101, responseHeaders, true);
        h2cPrefacePos = 0;
        state = State.H2C_PREFACE;
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(L10N.getString("debug.sent_101_switching_protocols"));
        }
    }

    private void sendSettingsFrame(boolean ack, Map<Integer, Integer> settings) {
        int payloadLength = ack ? 0 : settings.size() * 6;
        ByteBuffer buf = ByteBufferPool.acquire(FRAME_HEADER_LENGTH + payloadLength);
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
        try {
            send(buf);
        } finally {
            ByteBufferPool.release(buf);
        }
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
                        LOGGER.warning(L10N.getString("warn.settings_ack_timeout"));
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
        ByteBuffer buf = ByteBufferPool.acquire(FRAME_HEADER_LENGTH + 8);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 8);
        buf.put((byte) H2FrameHandler.TYPE_PING);
        buf.put((byte) H2FrameHandler.FLAG_ACK);
        buf.putInt(0);
        buf.putLong(opaqueData);
        buf.flip();
        try {
            send(buf);
        } finally {
            ByteBufferPool.release(buf);
        }
    }

    // RFC 9112 section 4: status-line = HTTP-version SP status-code SP [ reason-phrase ] CRLF
    // RFC 9112 section 5: field-line = field-name ":" OWS field-value OWS
    private void writeStatusLineAndHeaders(ByteBuffer buf, int statusCode, List<Header> headers) {
        // RFC 9110 section 15.6.6: a request whose version is unknown
        // (505) or is HTTP/2.0 on an HTTP/1.x connection (e.g. a bad
        // preface) is still answered with a valid HTTP/1.x status line
        HttpVersion statusVersion = version;
        if (statusVersion == HttpVersion.UNKNOWN
                || statusVersion == HttpVersion.HTTP_2_0) {
            statusVersion = HttpVersion.HTTP_1_1;
        }
        buf.put(VERSION_TOKEN_BYTES[statusVersion.ordinal()]);
        buf.put((byte) ' ');
        buf.put((byte) ('0' + statusCode / 100));
        buf.put((byte) ('0' + statusCode / 10 % 10));
        buf.put((byte) ('0' + statusCode % 10));
        buf.put((byte) ' ');
        buf.put(HttpConstants.getMessageBytes(statusCode));
        buf.put(CRLF);
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
            if (writeWellKnownLine(buf, name, value)) {
                continue;
            }
            // Common case: write the ASCII bytes of a guaranteed-ASCII
            // name plus the value straight into buf, no intermediate
            // String/StringBuilder/byte[] allocation. Only a value that
            // actually contains non-ASCII characters needs the
            // rejection below (issue #280).
            if (isAscii(value)) {
                writeAscii(buf, name);
                buf.put((byte) ':');
                buf.put((byte) ' ');
                writeAscii(buf, value);
                buf.put(CRLF);
            } else {
                // Values are never encoded: RFC 9110 section 5.5 leaves
                // non-ASCII octets opaque and does not define RFC 2047 for
                // HTTP. Stream.headers and H3Stream.headers reject them where
                // the handler sets them; this is the last line of defence.
                HttpUtils.requireAsciiFieldValue(name, value);
                throw new IllegalArgumentException("Response header '" + name
                        + "' has a value with control characters that HTTP field values must not carry");
            }
        }
        // Empty line terminates header section (RFC 9112 section 2)
        buf.put(CRLF);
    }

    private static final byte[] CRLF = { (byte) 0x0d, (byte) 0x0a };

    // Values Stream.sendResponseHeaders always adds verbatim for these
    // header names - every one a compile-time-constant String literal (or,
    // for SERVER_HEADER_VALUE, a concatenation of two of them, which javac
    // folds into a single constant the same way), so every occurrence in
    // the compiled classes is the exact same interned object. That is what
    // makes matching by reference (==) in writeWellKnownLine below sound:
    // it isn't comparing "does this look like the same text", it is
    // guaranteed to be the identical object Stream.java added, or it is
    // some other value entirely - there is no in-between "looks the same
    // but isn't" case a reference check could get wrong.
    static final String SERVER_HEADER_VALUE = "gumdrop/" + Gumdrop.VERSION;
    static final String CONNECTION_CLOSE_VALUE = "close";
    static final String X_FRAME_OPTIONS_VALUE = "SAMEORIGIN";
    static final String X_CONTENT_TYPE_OPTIONS_VALUE = "nosniff";
    static final String TRANSFER_ENCODING_CHUNKED_VALUE = "chunked";

    // The complete "name: value\r\n" line for each constant above, encoded
    // once. Never stale: unlike the Date header, none of these values ever
    // change while the JVM is running.
    private static final byte[] SERVER_LINE_BYTES =
            asciiLineBytes("Server", SERVER_HEADER_VALUE);
    private static final byte[] CONNECTION_CLOSE_LINE_BYTES =
            asciiLineBytes("Connection", CONNECTION_CLOSE_VALUE);
    private static final byte[] X_FRAME_OPTIONS_LINE_BYTES =
            asciiLineBytes("X-Frame-Options", X_FRAME_OPTIONS_VALUE);
    private static final byte[] X_CONTENT_TYPE_OPTIONS_LINE_BYTES =
            asciiLineBytes("X-Content-Type-Options", X_CONTENT_TYPE_OPTIONS_VALUE);
    private static final byte[] TRANSFER_ENCODING_CHUNKED_LINE_BYTES =
            asciiLineBytes("Transfer-Encoding", TRANSFER_ENCODING_CHUNKED_VALUE);

    private static byte[] asciiLineBytes(String name, String value) {
        return (name + ": " + value + "\r\n").getBytes(US_ASCII);
    }

    // Indexed by HttpVersion.ordinal(); avoids converting version.toString()
    // char-by-char on every response for what is, in practice, one of two
    // fixed tokens (HTTP/1.0, HTTP/1.1 - HTTP/2 and HTTP/3 responses never
    // reach this HTTP/1.x status-line writer).
    private static final byte[][] VERSION_TOKEN_BYTES;
    static {
        HttpVersion[] versions = HttpVersion.values();
        VERSION_TOKEN_BYTES = new byte[versions.length][];
        for (int i = 0; i < versions.length; i++) {
            VERSION_TOKEN_BYTES[i] = versions[i].toString().getBytes(US_ASCII);
        }
    }

    /**
     * Writes the complete header line for one of the small, fixed set of
     * values the framework always produces byte-for-byte identically
     * (Date, Server, the default security headers, Transfer-Encoding:
     * chunked, Connection: close) in one bulk put, instead of the
     * generic per-character path. Matches by reference, not content, so a
     * false match is structurally impossible (see the constants above and
     * HttpDateCache's own javadoc for Date specifically). Returns false
     * (writes nothing) for any other header, which the caller then writes
     * via the generic path.
     */
    private static boolean writeWellKnownLine(ByteBuffer buf, String name, String value) {
        if (value == HttpDateCache.get() && "Date".equals(name)) {
            buf.put(HttpDateCache.getLineBytes());
            return true;
        }
        if (value == SERVER_HEADER_VALUE && "Server".equals(name)) {
            buf.put(SERVER_LINE_BYTES);
            return true;
        }
        if (value == X_FRAME_OPTIONS_VALUE && "X-Frame-Options".equals(name)) {
            buf.put(X_FRAME_OPTIONS_LINE_BYTES);
            return true;
        }
        if (value == X_CONTENT_TYPE_OPTIONS_VALUE && "X-Content-Type-Options".equals(name)) {
            buf.put(X_CONTENT_TYPE_OPTIONS_LINE_BYTES);
            return true;
        }
        if (value == TRANSFER_ENCODING_CHUNKED_VALUE && "Transfer-Encoding".equals(name)) {
            buf.put(TRANSFER_ENCODING_CHUNKED_LINE_BYTES);
            return true;
        }
        if (value == CONNECTION_CLOSE_VALUE && "Connection".equals(name)) {
            buf.put(CONNECTION_CLOSE_LINE_BYTES);
            return true;
        }
        return false;
    }

    /**
     * Writes a string known to contain only ASCII characters (header
     * names, the HTTP version token, status reason phrases) directly as
     * bytes, with no intermediate String/byte[] allocation.
     */
    private static void writeAscii(ByteBuffer buf, String s) {
        int len = s.length();
        for (int i = 0; i < len; i++) {
            buf.put((byte) s.charAt(i));
        }
    }

    /** True iff every character is printable ASCII, tab, CR or LF. */
    private static boolean isAscii(String text) {
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (!((c >= 32 && c < 127) || c == '\n' || c == '\r' || c == '\t')) {
                return false;
            }
        }
        return true;
    }

    private void cleanupAllStreams() {
        int streamCount = streams.size();
        Exception cause = new IOException("Connection closed");
        for (Stream stream : streams.values()) {
            stream.streamAbort(cause);
        }
        streams.clear();
        if (streamCount > 0 && LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(String.format("Connection closing: cleaned up %d remaining streams",
                    streamCount));
        }
        activeStreams.clear();
        h2WriteCallbacks.clear();
        h2PendingData.clear();
        h2PendingBytes.clear();
        h2Priority.clear();
    }

    // ── H2FrameHandler implementation ──

    // RFC 9113 section 6.1: DATA frame reception
    @Override
    public void dataFrameReceived(int streamId, boolean endStream, ByteBuffer data) {
        if (expectingInitialSettings()) {
            return;
        }
        h2Dispatch(new Runnable() {
            @Override
            public void run() {
            int dataLength = data.remaining();
            if (isReleasedStream(streamId)) {
                // RFC 9113 section 5.1: DATA for a closed stream is a
                // stream error; section 6.9: it still counts against the
                // connection flow-control window
                if (h2FlowControl != null && dataLength > 0) {
                    h2FlowControl.onDataReceived(streamId, dataLength, h2DataResult);
                    if (h2DataResult.connectionIncrement > 0) {
                        try {
                            h2Writer.writeWindowUpdate(0, h2DataResult.connectionIncrement);
                        } catch (IOException e) {
                            LOGGER.log(Level.WARNING, L10N.getString("warn.error_sending_window_update"), e);
                        }
                    }
                }
                data.position(data.limit());
                sendRstStream(streamId, H2FrameHandler.ERROR_STREAM_CLOSED);
                return;
            }
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
                        requestH2Flush();
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, L10N.getString("warn.error_sending_window_update"), e);
                }
            }

            if (endStream) {
                stream.streamEndRequest();
                if (stream.isActive()) {
                    activeStreams.add(streamId);
                }
            }
            }
        });
    }

    // RFC 9113 section 6.2: HEADERS frame reception
    @Override
    public void headersFrameReceived(int streamId, boolean endStream, boolean endHeaders,
            int streamDependency, boolean exclusive, int weight,
            ByteBuffer headerBlockFragment) {
        if (expectingInitialSettings()) {
            return;
        }
        h2Dispatch(new Runnable() {
            @Override
            public void run() {
            // RFC 9113 section 5.1.1: client-initiated streams MUST use odd
            // stream IDs and MUST be monotonically increasing; violation is
            // a connection error of type PROTOCOL_ERROR
            // RFC 9113 section 8.1: a HEADERS frame on a stream that is
            // already open carries trailers, not a new request, so only a
            // new stream is held to the rules for opening one.
            boolean openStream = streams.containsKey(streamId);
            if (streamId % 2 == 0 || (!openStream && streamId <= lastClientStreamId)) {
                sendGoaway(H2FrameHandler.ERROR_PROTOCOL_ERROR);
                closeEndpoint();
                return;
            }
            lastClientStreamId = Math.max(lastClientStreamId, streamId);
            // RFC 9113 section 5.1.2: streams exceeding
            // SETTINGS_MAX_CONCURRENT_STREAMS SHOULD be refused
            if (!openStream && activeStreams.size() >= serverMaxConcurrentStreams) {
                sendRstStream(streamId, H2FrameHandler.ERROR_REFUSED_STREAM);
                return;
            }
            resetContinuationLimit();
            checkContinuationLimit();
            Stream stream = getStream(streamId);
            stream.appendHeaderBlockFragment(headerBlockFragment);
            if (endHeaders) {
                resetContinuationLimit();
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
        });
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

    @Override
    public void priorityUpdateFrameReceived(int prioritizedStreamId, String fieldValue) {
        if (expectingInitialSettings()) {
            return;
        }
        if (isReleasedStream(prioritizedStreamId)) {
            return;
        }
        applyRfc9218Priority(prioritizedStreamId, PriorityParams.parse(fieldValue), true);
    }

    @Override
    void applyRfc9218Priority(int streamId, List<Header> headers) {
        applyRfc9218Priority(streamId, PriorityParams.parse(
                HeaderFields.getValue(headers, PriorityParams.PRIORITY_HEADER)), false);
    }

    private void applyRfc9218Priority(int streamId, PriorityParams params, boolean fromUpdate) {
        if (!fromUpdate && h2Priority.containsKey(streamId)) {
            return;
        }
        h2Priority.put(streamId, params);
    }

    // RFC 9113 section 6.4: RST_STREAM frame reception
    @Override
    public void rstStreamFrameReceived(int streamId, int errorCode) {
        if (expectingInitialSettings()) {
            return;
        }
        checkRstStreamRate();
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(L10N.getString("debug.rst_stream_received"), streamId, H2FrameHandler.errorToString(errorCode)));
        }
        if (isReleasedStream(streamId)) {
            // RFC 9113 section 5.1: the stream finished before the reset arrived
            return;
        }
        Stream stream = getStream(streamId);
        stream.streamAbort(new IOException("Stream reset by peer: "
                + H2FrameHandler.errorToString(errorCode)));
        // Intentionally do NOT remove from activeStreams here.  The concurrency
        // slot is held until the stream is fully cleaned up, preventing a
        // rapid-reset attacker from keeping activeStreams.size() artificially
        // low while unbounded handler work executes (CVE-2023-44487).
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
                            if (hpackDecoder != null) {
                                hpackDecoder.setMaxHeaderListSize(maxHeaderListSize);
                            }
                            break;
                        case H2FrameHandler.SETTINGS_ENABLE_CONNECT_PROTOCOL:
                            clientEnablesConnectProtocol = (value == 1);
                            break;
                        case H2FrameHandler.SETTINGS_NO_RFC7540_PRIORITIES:
                            if (value != 0 && value != 1) {
                                sendGoaway(H2FrameHandler.ERROR_PROTOCOL_ERROR);
                                closeEndpoint();
                                return;
                            }
                            break;
                    }
                }
                // RFC 7541 section 4: initialize HPACK encoder/decoder
                // with peer's SETTINGS_HEADER_TABLE_SIZE
                if (hpackDecoder == null) {
                    hpackDecoder = new Decoder(headerTableSize, maxHeaderListSize);
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
            if (h2cBodylessUpgradeNeedsRequestComplete) {
                h2cBodylessUpgradeNeedsRequestComplete = false;
                Stream h2cStream = getStream(1);
                if (h2cStream != null) {
                    h2cStream.streamEndRequest();
                }
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
                        if (hpackDecoder != null) {
                            hpackDecoder.setMaxHeaderListSize(maxHeaderListSize);
                        }
                        break;
                    case H2FrameHandler.SETTINGS_ENABLE_CONNECT_PROTOCOL:
                        clientEnablesConnectProtocol = (value == 1);
                        break;
                    case H2FrameHandler.SETTINGS_NO_RFC7540_PRIORITIES:
                        if (value != 0 && value != 1) {
                            sendGoaway(H2FrameHandler.ERROR_PROTOCOL_ERROR);
                            closeEndpoint();
                            return;
                        }
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

    // RFC 9113 section 6.6 / 8.4: clients MUST NOT send PUSH_PROMISE;
    // receipt of one is a connection error of type PROTOCOL_ERROR.
    @Override
    public void pushPromiseFrameReceived(int streamId, int promisedStreamId,
            boolean endHeaders, ByteBuffer headerBlockFragment) {
        sendGoaway(H2FrameHandler.ERROR_PROTOCOL_ERROR);
        closeEndpoint();
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
        h2Dispatch(new Runnable() {
            @Override
            public void run() {
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
                drainRfc9218Pending();
            } else {
                // Only claim the slot when there is actually queued DATA to
                // drain for this stream. A per-stream WINDOW_UPDATE is routine
                // flow-control housekeeping that a client may send long after
                // the stream's response was already fully sent (e.g. via the
                // direct-send path in sendH2Data()); claiming unconditionally
                // here leaked the slot forever in that case, because
                // drainPendingData() returns immediately when there is nothing
                // pending for the stream, without ever releasing what was just
                // claimed above.
                if (h2PendingData.containsKey(streamId) && claimH2BodySlot(streamId)) {
                    drainPendingData(streamId);
                }
            }
            }
        });
    }

    // RFC 9113 section 6.10: CONTINUATION frame reception
    @Override
    public void continuationFrameReceived(int streamId, boolean endHeaders,
            ByteBuffer headerBlockFragment) {
        if (expectingInitialSettings()) {
            return;
        }
        h2Dispatch(new Runnable() {
            @Override
            public void run() {
            checkContinuationLimit();
            Stream stream = getStream(streamId);
            stream.appendHeaderBlockFragment(headerBlockFragment);
            if (endHeaders) {
                resetContinuationLimit();
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
        });
    }

    // RFC 9113 section 5.4: error handling
    // Section 5.4.1: connection errors → GOAWAY
    // Section 5.4.2: stream errors → RST_STREAM
    @Override
    public void frameError(int errorCode, int streamId, String message) {
        if (LOGGER.isLoggable(Level.WARNING)) {
            LOGGER.warning(MessageFormat.format(
                    L10N.getString("warn.frame_error"),
                    message, H2FrameHandler.errorToString(errorCode), streamId));
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
        /** Trailer fields to send, as the final HEADERS, once the data is out. */
        List<Header> trailers;

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
            trailers = null;
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
            // HTTP/1.x output from before the connection became HTTP/2
            // (the 101 response to an h2c upgrade) goes first
            flushHeld();
            if (written > 0 && endpoint != null) {
                // the endpoint takes what it is given before it returns
                // (copied to its output buffer, or sealed into a TLS
                // record), so the writer's own buffer can be handed over
                endpoint.send(src);
            }
            src.position(src.limit());
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
