/*
 * HTTPClientProtocolHandler.java
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

package org.bluezoo.gumdrop.http.client;

import org.bluezoo.util.ByteArrays;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.http.HTTPVersion;
import org.bluezoo.gumdrop.http.h2.H2FlowControl;
import org.bluezoo.gumdrop.http.h2.H2FrameHandler;
import org.bluezoo.gumdrop.http.h2.H2Parser;
import org.bluezoo.gumdrop.http.h2.H2Writer;
import org.bluezoo.gumdrop.http.hpack.Decoder;
import org.bluezoo.gumdrop.http.hpack.Encoder;
import org.bluezoo.gumdrop.http.hpack.HeaderHandler;
import org.bluezoo.gumdrop.util.ByteBufferPool;

/**
 * HTTP client endpoint handler that manages protocol-level communication
 * for HTTP/1.1 (RFC 9112) and HTTP/2 (RFC 9113, obsoletes RFC 7540).
 *
 * <p>Implements {@link ProtocolHandler} and {@link H2FrameHandler}, storing
 * an {@link Endpoint} field set in {@link #connected(Endpoint)} and delegating
 * all I/O to the endpoint.
 *
 * <p>HTTP/2 connection startup follows RFC 9113 section 3:
 * <ul>
 *   <li>TLS with ALPN "h2" (section 3.2) via {@link #securityEstablished}</li>
 *   <li>Prior knowledge over cleartext (section 3.3) via {@link #connected}</li>
 *   <li>h2c upgrade from HTTP/1.1 (section 3.1, deprecated by RFC 9113 but
 *       intentionally retained) via {@link #completeH2cUpgrade()}</li>
 * </ul>
 *
 * <p>HTTP/2 framing, stream multiplexing, flow control, and HPACK header
 * compression use the shared {@code h2} and {@code hpack} packages.
 * Header encoding/decoding uses RFC 7541 (HPACK).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPClientProtocolHandler implements ProtocolHandler, H2FrameHandler, HTTPClientConnectionOps {

    private static final ResourceBundle L10N =
        ResourceBundle.getBundle("org.bluezoo.gumdrop.http.client.L10N");
    private static final Logger LOGGER = Logger.getLogger(HTTPClientProtocolHandler.class.getName());

    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

    private final HTTPClientHandler handler;
    private final String host;
    private final int port;
    private final boolean secure;

    protected Endpoint endpoint;
    private HTTPVersion negotiatedVersion;
    private volatile boolean open;

    // h2c upgrade state
    private boolean h2Enabled = true;
    private boolean h2cUpgradeEnabled = true;
    private boolean h2cUpgradeAttempted;
    private boolean h2cUpgradeInFlight;
    private HTTPStream h2cUpgradeRequest;
    private boolean h2WithPriorKnowledge = false;

    // Active streams
    protected final Map<Integer, HTTPStream> activeStreams = new ConcurrentHashMap<Integer, HTTPStream>();
    private final Map<HTTPStream, Integer> streamIdByRequest = new ConcurrentHashMap<HTTPStream, Integer>();
    private int nextStreamId = 1;

    // RFC 9113 section 5.1.2: requests queued when activeStreams.size()
    // reaches the server-advertised SETTINGS_MAX_CONCURRENT_STREAMS
    private final ArrayDeque<PendingRequest> pendingRequests = new ArrayDeque<>();

    // HTTP/2
    private H2Parser h2Parser;
    private H2Writer h2Writer;
    private H2FlowControl h2FlowControl;
    private final H2FlowControl.DataReceivedResult h2DataResult =
            new H2FlowControl.DataReceivedResult();
    private Decoder hpackDecoder;
    private Encoder hpackEncoder;

    // HTTP/2 settings
    private int headerTableSize = 4096;
    private int maxConcurrentStreams = 100;
    private int initialWindowSize = 65535;
    private int maxFrameSize = 16384;
    private int maxHeaderListSize = 8192;
    private boolean serverPushEnabled = true;

    // RFC 9113 section 6.8: highest server-initiated (even) stream ID seen
    private int highestServerStreamId = 0;

    private int continuationStreamId;
    private ByteBuffer headerBlockBuffer;

    // HTTP/1.1 parsing state
    protected HTTPStream currentStream;
    protected ByteBuffer parseBuffer;
    protected ParseState parseState = ParseState.IDLE;
    private HTTPStatus responseStatus;
    private Headers responseHeaders;
    private long contentLength = -1;
    private long bytesReceived = 0;
    private boolean chunkedEncoding;
    private boolean discardingBody;
    private String pendingAuthChallenge;
    private boolean pendingProxyAuth;

    protected enum ParseState {
        IDLE, STATUS_LINE, HEADERS, BODY, CHUNK_SIZE, CHUNK_DATA, CHUNK_TRAILER,
        H2C_UPGRADE_PENDING, HTTP2
    }

    // Authentication
    private volatile String username;
    private volatile String password;
    private boolean authRetryPending;

    // Alt-Svc discovery
    private AltSvcListener altSvcListener;
    private boolean altSvcNotified;

    // RFC 9113 section 9.1: configurable idle connection timeout
    private long idleTimeoutMs;
    private TimerHandle idleTimeoutHandle;

    // RFC 9112 section 5: maximum response header size (bytes)
    private int maxResponseHeaderSize = 1024 * 1024;

    /**
     * Creates an HTTP client endpoint handler.
     *
     * @param handler the handler to receive connection events
     * @param host the target host
     * @param port the target port
     * @param secure whether this is a secure (TLS) connection
     */
    public HTTPClientProtocolHandler(HTTPClientHandler handler,
                                     String host, int port,
                                     boolean secure) {
        this.handler = handler;
        this.host = host;
        this.port = port;
        this.secure = secure;
        this.parseBuffer = ByteBuffer.allocate(8192);
    }

    /**
     * Sets a listener for Alt-Svc header discovery.
     *
     * @param listener the listener, or null to disable
     */
    void setAltSvcListener(AltSvcListener listener) {
        this.altSvcListener = listener;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ProtocolHandler implementation
    // ─────────────────────────────────────────────────────────────────────────

    // RFC 9113 section 3.3: prior knowledge over cleartext;
    // RFC 9113 section 3.1: h2c upgrade (deprecated but intentionally retained)
    @Override
    public void connected(Endpoint ep) {
        this.endpoint = ep;

        if (!secure) {
            open = true;
            if (h2WithPriorKnowledge) {
                negotiatedVersion = HTTPVersion.HTTP_2_0;
                initializeHTTP2();
                sendConnectionPreface();
                parseState = ParseState.H2C_UPGRADE_PENDING;
                LOGGER.fine("HTTP/2 connection established to " + host + ":" + port + " (prior knowledge)");
            } else {
                negotiatedVersion = HTTPVersion.HTTP_1_1;
                if (h2cUpgradeEnabled) {
                    LOGGER.fine("HTTP/1.1 connection established to " + host + ":" + port + ", will attempt h2c upgrade");
                } else {
                    LOGGER.fine("HTTP/1.1 connection established to " + host + ":" + port);
                }
            }
            // RFC 9113 section 9.1: start idle timer
            resetIdleTimeout();
            if (Boolean.getBoolean("gumdrop.http.debug")) {
                LOGGER.info("[HTTPClient] connected to " + host + ":" + port + ", calling onConnected");
            }
            if (handler != null) {
                handler.onConnected(ep);
            }
            if (Boolean.getBoolean("gumdrop.http.debug")) {
                LOGGER.info("[HTTPClient] onConnected returned");
            }
        }
        // For secure connections, wait for securityEstablished()
    }

    // RFC 9113 section 3.2: ALPN negotiation with "h2" token
    @Override
    public void securityEstablished(SecurityInfo info) {
        open = true;
        String alpn = info.getApplicationProtocol();
        if ("h2".equals(alpn)) {
            // RFC 9113 section 9.2.2: validate cipher suite for TLS 1.2
            if (isBlockedH2CipherSuite(info)) {
                String cipher = info.getCipherSuite();
                LOGGER.warning("HTTP/2 blocked cipher suite: " + cipher);
                sendGoaway(H2FrameHandler.ERROR_INADEQUATE_SECURITY,
                        "blocked cipher suite: " + cipher);
                return;
            }
            negotiatedVersion = HTTPVersion.HTTP_2_0;
            initializeHTTP2();
            sendConnectionPreface();
            parseState = ParseState.H2C_UPGRADE_PENDING;
            LOGGER.fine("HTTP/2 (ALPN) connection established to " + host + ":" + port);
        } else {
            negotiatedVersion = HTTPVersion.HTTP_1_1;
            LOGGER.fine("HTTP/1.1 connection established to " + host + ":" + port);
        }
        // RFC 9113 section 9.1: start idle timer after TLS handshake
        resetIdleTimeout();
        if (handler != null) {
            handler.onConnected(endpoint);
            handler.onSecurityEstablished(info);
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
        // AEAD suites contain GCM, CCM, or CHACHA20 in their name
        return !cipher.contains("GCM")
                && !cipher.contains("CCM")
                && !cipher.contains("CHACHA20");
    }

    @Override
    public void receive(ByteBuffer data) {
        if (Boolean.getBoolean("gumdrop.http.debug")) {
            LOGGER.info("[HTTPClient] receive() " + data.remaining() + " bytes, parseState=" + parseState);
        }
        // RFC 9113 section 9.1: reset idle timer on activity
        resetIdleTimeout();
        // Append to parse buffer
        if (parseBuffer.remaining() < data.remaining()) {
            int newSize = parseBuffer.position() + data.remaining() + 4096;
            // RFC 9112 section 5: protect against unbounded header growth
            if (newSize > maxResponseHeaderSize
                    && (parseState == ParseState.STATUS_LINE
                        || parseState == ParseState.HEADERS)) {
                LOGGER.warning("Response header size exceeds limit ("
                        + maxResponseHeaderSize + " bytes)");
                failAllStreams(new IOException("Response header too large"));
                close();
                return;
            }
            ByteBuffer newBuffer = ByteBuffer.allocate(newSize);
            parseBuffer.flip();
            newBuffer.put(parseBuffer);
            parseBuffer = newBuffer;
        }
        parseBuffer.put(data);

        // Process based on protocol
        if (negotiatedVersion == HTTPVersion.HTTP_2_0
                || parseState == ParseState.H2C_UPGRADE_PENDING
                || parseState == ParseState.HTTP2) {
            processHTTP2Response();
        } else {
            processHTTP11Response();
        }
    }

    @Override
    public void disconnected() {
        open = false;

        Exception disconnectException = new IOException(L10N.getString("err.connection_disconnected"));
        failAllStreams(disconnectException);

        if (handler != null) {
            handler.onDisconnected();
        }
    }

    @Override
    public void error(Exception cause) {
        open = false;

        failAllStreams(cause);

        if (handler != null) {
            handler.onError(cause);
        }
    }

    private void failAllStreams(Exception cause) {
        for (HTTPStream request : activeStreams.values()) {
            HTTPResponseHandler responseHandler = request.getHandler();
            if (responseHandler != null) {
                try {
                    responseHandler.failed(cause);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error notifying handler", e);
                }
            }
        }
        activeStreams.clear();
        streamIdByRequest.clear();
        for (PendingRequest pending : pendingRequests) {
            HTTPResponseHandler responseHandler = pending.request.getHandler();
            if (responseHandler != null) {
                try {
                    responseHandler.failed(cause);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error notifying handler", e);
                }
            }
        }
        pendingRequests.clear();
    }

    // RFC 9113 section 9.1: reset idle timer on activity
    private void resetIdleTimeout() {
        cancelIdleTimeout();
        if (idleTimeoutMs > 0 && endpoint != null) {
            idleTimeoutHandle = endpoint.scheduleTimer(idleTimeoutMs, () -> {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Idle timeout (" + idleTimeoutMs + "ms) — closing connection");
                }
                if (negotiatedVersion == HTTPVersion.HTTP_2_0) {
                    sendGoaway(H2FrameHandler.ERROR_NO_ERROR, "idle timeout");
                } else {
                    close();
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

    // ─────────────────────────────────────────────────────────────────────────
    // Extension hooks
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called when a 101 Switching Protocols response is received that is
     * not an h2c upgrade. Subclasses can override this to handle
     * protocol switches (e.g. WebSocket, RFC 6455).
     *
     * <p>Per RFC 9110 section 15.2.2, the server switches to the protocol
     * defined by the response Upgrade header. When this method returns
     * {@code true}, the caller assumes the protocol switch has been fully
     * handled and the HTTP parsing state machine will not process the
     * response further. The subclass is responsible for managing all
     * subsequent data on the connection.
     *
     * @param status the response status (always 101)
     * @param headers the response headers
     * @return true if the switch was handled, false to log a warning
     */
    protected boolean handleProtocolSwitch(HTTPStatus status, Headers headers) {
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Request Factory Methods
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a GET request for the specified path.
     *
     * @param path the request path (e.g., "/api/users")
     * @return a new request
     */
    public HTTPRequest get(String path) {
        return createRequest("GET", path);
    }

    /**
     * Creates a POST request for the specified path.
     *
     * @param path the request path
     * @return a new request
     */
    public HTTPRequest post(String path) {
        return createRequest("POST", path);
    }

    /**
     * Creates a PUT request for the specified path.
     *
     * @param path the request path
     * @return a new request
     */
    public HTTPRequest put(String path) {
        return createRequest("PUT", path);
    }

    /**
     * Creates a DELETE request for the specified path.
     *
     * @param path the request path
     * @return a new request
     */
    public HTTPRequest delete(String path) {
        return createRequest("DELETE", path);
    }

    /**
     * Creates a HEAD request for the specified path.
     *
     * @param path the request path
     * @return a new request
     */
    public HTTPRequest head(String path) {
        return createRequest("HEAD", path);
    }

    /**
     * Creates an OPTIONS request for the specified path.
     *
     * @param path the request path
     * @return a new request
     */
    public HTTPRequest options(String path) {
        return createRequest("OPTIONS", path);
    }

    /**
     * Creates a PATCH request for the specified path.
     *
     * @param path the request path
     * @return a new request
     */
    public HTTPRequest patch(String path) {
        return createRequest("PATCH", path);
    }

    /**
     * Creates a request with a custom HTTP method.
     *
     * @param method the HTTP method
     * @param path the request path
     * @return a new request
     */
    public HTTPRequest request(String method, String path) {
        return createRequest(method, path);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Authentication
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sets credentials for HTTP authentication.
     *
     * @param username the username
     * @param password the password
     */
    public void credentials(String username, String password) {
        this.username = username;
        this.password = password;
    }

    /**
     * Clears any configured credentials.
     */
    public void clearCredentials() {
        this.username = null;
        this.password = null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Connection State
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if the connection is open and can accept new requests.
     *
     * @return true if the connection is open
     */
    public boolean isOpen() {
        return open && endpoint != null && endpoint.isOpen();
    }

    /**
     * Returns the negotiated HTTP version, or null if not yet connected.
     *
     * @return the HTTP version, or null if not yet known
     */
    public HTTPVersion getVersion() {
        return negotiatedVersion;
    }

    /**
     * Closes the connection gracefully.
     */
    public void close() {
        open = false;
        cancelIdleTimeout();

        failAllStreams(new IOException(L10N.getString("err.connection_closed")));

        try {
            if (endpoint != null) {
                endpoint.close();
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Error closing connection", e);
        }
    }

    /**
     * Enables or disables HTTP/2 via ALPN for TLS connections.
     *
     * @param enabled true to offer HTTP/2 in ALPN negotiation
     */
    public void setH2Enabled(boolean enabled) {
        this.h2Enabled = enabled;
    }

    /**
     * Enables or disables h2c (HTTP/2 over cleartext) upgrade attempts.
     *
     * <p>RFC 9113 section 3.1 deprecates this mechanism, but it is
     * intentionally retained.
     *
     * @param enabled true to enable h2c upgrade attempts
     */
    public void setH2cUpgradeEnabled(boolean enabled) {
        this.h2cUpgradeEnabled = enabled;
        if (enabled) {
            h2cUpgradeAttempted = false;
        }
    }

    /**
     * Enables or disables HTTP/2 with prior knowledge.
     *
     * <p>Per RFC 9113 section 3.3, the client sends the connection preface
     * immediately without negotiation.
     *
     * @param enabled true to use HTTP/2 with prior knowledge
     */
    public void setH2WithPriorKnowledge(boolean enabled) {
        this.h2WithPriorKnowledge = enabled;
    }

    /**
     * Sets the maximum response header size in bytes.
     * If a response exceeds this limit, the connection is closed
     * with an error (RFC 9112 section 5).
     *
     * @param bytes maximum header size; default is 1 MB
     */
    public void setMaxResponseHeaderSize(int bytes) {
        this.maxResponseHeaderSize = bytes;
    }

    /**
     * Returns the maximum response header size in bytes.
     */
    public int getMaxResponseHeaderSize() {
        return maxResponseHeaderSize;
    }

    /**
     * Sets the idle connection timeout in milliseconds.
     * When positive, the connection is closed with GOAWAY after
     * the specified period of inactivity (RFC 9113 section 9.1).
     *
     * @param ms timeout in milliseconds, 0 to disable
     */
    public void setIdleTimeoutMs(long ms) {
        this.idleTimeoutMs = ms;
    }

    /**
     * Returns the idle connection timeout in milliseconds.
     */
    public long getIdleTimeoutMs() {
        return idleTimeoutMs;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Request creation and sending
    // ─────────────────────────────────────────────────────────────────────────

    private HTTPRequest createRequest(String method, String path) {
        if (!isOpen()) {
            throw new IllegalStateException(L10N.getString("err.connection_not_open"));
        }
        return new HTTPStream(this, method, path);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTPClientConnectionOps implementation
    // ─────────────────────────────────────────────────────────────────────────

    // RFC 9113 section 5.1.1: client-initiated streams use odd IDs
    @Override
    public void sendRequest(HTTPStream request, boolean hasBody) {
        // RFC 9113 section 5.1.2: do not exceed the server's
        // SETTINGS_MAX_CONCURRENT_STREAMS for HTTP/2 connections
        if (negotiatedVersion == HTTPVersion.HTTP_2_0
                && activeStreams.size() >= maxConcurrentStreams) {
            pendingRequests.add(new PendingRequest(request, hasBody));
            return;
        }

        dispatchRequest(request, hasBody);
    }

    private void dispatchRequest(HTTPStream request, boolean hasBody) {
        int streamId = nextStreamId;
        nextStreamId += 2;

        activeStreams.put(streamId, request);
        streamIdByRequest.put(request, streamId);
        if (h2FlowControl != null) {
            h2FlowControl.openStream(streamId);
        }
        currentStream = request;

        if (negotiatedVersion == HTTPVersion.HTTP_2_0) {
            sendHTTP2Request(request, streamId, hasBody);
        } else {
            sendHTTP11Request(request, hasBody);
        }
    }

    @Override
    public int sendRequestBody(HTTPStream request, ByteBuffer data) {
        if (negotiatedVersion == HTTPVersion.HTTP_2_0) {
            return sendHTTP2Data(request, data);
        } else {
            return sendHTTP11Data(request, data);
        }
    }

    @Override
    public void endRequestBody(HTTPStream request) {
        if (negotiatedVersion == HTTPVersion.HTTP_2_0) {
            endHTTP2Data(request);
        } else {
            endHTTP11Data(request);
        }
    }

    @Override
    public void cancelRequest(HTTPStream request) {
        // Also check the pending queue before active streams
        pendingRequests.removeIf(p -> p.request == request);

        Integer streamId = streamIdByRequest.remove(request);

        if (streamId != null) {
            activeStreams.remove(streamId);

            if (negotiatedVersion == HTTPVersion.HTTP_2_0) {
                sendHTTP2Reset(streamId);
            }

            HTTPResponseHandler responseHandler = request.getHandler();
            if (responseHandler != null) {
                responseHandler.failed(new CancellationException("Request cancelled"));
            }
            drainPendingRequests();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP/1.1 implementation
    // ─────────────────────────────────────────────────────────────────────────

    // RFC 9112 section 3.1: request-line = method SP request-target SP HTTP-version
    // RFC 9112 section 3.2 / RFC 9110 section 7.2: Host header required
    private void sendHTTP11Request(HTTPStream request, boolean hasBody) {
        if (Boolean.getBoolean("gumdrop.http.debug")) {
            LOGGER.info("[HTTPClient] sendHTTP11Request " + request.getMethod() + " " + request.getPath());
        }
        StringBuilder sb = new StringBuilder();

        // RFC 9112 section 3.1: request-line
        sb.append(request.getMethod());
        sb.append(" ");
        sb.append(request.getPath());
        sb.append(" HTTP/1.1\r\n");

        // RFC 9110 section 7.2: Host header
        String hostHeader = host;
        if ((secure && port != 443) || (!secure && port != 80)) {
            hostHeader = hostHeader + ":" + port;
        }
        sb.append("Host: ");
        sb.append(hostHeader);
        sb.append("\r\n");

        Headers headers = request.getHeaders();
        for (Header header : headers) {
            sb.append(header.getName());
            sb.append(": ");
            sb.append(header.getValue());
            sb.append("\r\n");
        }

        // RFC 9113 section 3.1: h2c upgrade (deprecated but intentionally retained)
        boolean attemptingH2cUpgrade = false;
        if (h2cUpgradeEnabled && !h2cUpgradeAttempted && !h2cUpgradeInFlight && !secure) {
            sb.append("Connection: Upgrade, HTTP2-Settings\r\n");
            sb.append("Upgrade: h2c\r\n");
            sb.append("HTTP2-Settings: ");
            sb.append(createHTTP2SettingsHeaderValue());
            sb.append("\r\n");
            h2cUpgradeInFlight = true;
            h2cUpgradeAttempted = true;
            h2cUpgradeRequest = request;
            attemptingH2cUpgrade = true;
        } else if (!headers.containsName("Connection")) {
            // RFC 9112 section 9.6: persistent connections via Connection header
            sb.append("Connection: keep-alive\r\n");
        }

        // RFC 9112 section 7.1: chunked transfer coding for request body
        if (hasBody && !headers.containsName("Content-Length") && !headers.containsName("Transfer-Encoding")) {
            sb.append("Transfer-Encoding: chunked\r\n");
            request.getHeaders().add("Transfer-Encoding", "chunked");
        }

        sb.append("\r\n");

        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        endpoint.send(ByteBuffer.wrap(bytes));

        parseState = ParseState.STATUS_LINE;
        responseStatus = null;
        responseHeaders = new Headers();
        contentLength = -1;
        bytesReceived = 0;
        chunkedEncoding = false;

        if (attemptingH2cUpgrade) {
            LOGGER.fine("Sent HTTP/1.1 request with h2c upgrade: " + request.getMethod() + " " + request.getPath());
        } else {
            LOGGER.fine("Sent HTTP/1.1 request: " + request.getMethod() + " " + request.getPath());
        }
    }

    // RFC 9113 section 3.1: Base64url-encoded SETTINGS payload (no padding)
    private String createHTTP2SettingsHeaderValue() {
        ByteBuffer payload = ByteBuffer.allocate(6);
        payload.put((byte) 0x00);
        payload.put((byte) 0x02);
        payload.put((byte) 0x00);
        payload.put((byte) 0x00);
        payload.put((byte) 0x00);
        payload.put((byte) 0x00);
        payload.flip();
        byte[] settingsBytes = new byte[payload.remaining()];
        payload.get(settingsBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(settingsBytes);
    }

    // RFC 9112 section 7.1: chunked transfer coding for request body data
    private int sendHTTP11Data(HTTPStream request, ByteBuffer data) {
        int bytes = data.remaining();

        if (request.getHeaders().containsName("Transfer-Encoding")) {
            String chunkHeader = Integer.toHexString(bytes) + "\r\n";
            endpoint.send(ByteBuffer.wrap(chunkHeader.getBytes(StandardCharsets.US_ASCII)));
            endpoint.send(data);
            endpoint.send(ByteBuffer.wrap("\r\n".getBytes(StandardCharsets.US_ASCII)));
        } else {
            endpoint.send(data);
        }

        return bytes;
    }

    // RFC 9112 section 7.1: final zero-length chunk terminates body
    private void endHTTP11Data(HTTPStream request) {
        if (request.getHeaders().containsName("Transfer-Encoding")) {
            endpoint.send(ByteBuffer.wrap("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP/2 Request Sending
    // ─────────────────────────────────────────────────────────────────────────

    private void sendHTTP2Request(HTTPStream request, int streamId, boolean hasBody) {
        try {
            ByteBuffer headerBlock = encodeRequestHeaders(request);

            final int fStreamId = streamId;
            final boolean fHasBody = hasBody;
            endpoint.getSelectorLoop().invokeLater(new SendHeadersTask(fStreamId, headerBlock, fHasBody, request));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error encoding HTTP/2 request headers", e);
            HTTPResponseHandler responseHandler = request.getHandler();
            if (responseHandler != null) {
                try {
                    responseHandler.failed(e);
                } catch (Exception ex) {
                    LOGGER.log(Level.WARNING, "Error in response handler", ex);
                }
            }
            activeStreams.remove(streamId);
            streamIdByRequest.remove(request);
        }
    }

    private int sendHTTP2Data(HTTPStream request, ByteBuffer data) {
        int streamId = findStreamId(request);
        if (streamId < 0) {
            LOGGER.warning(L10N.getString("warn.unknown_stream_data"));
            return 0;
        }

        int written = data.remaining();
        if (written == 0) {
            return 0;
        }

        ByteBuffer copy = ByteBufferPool.acquire(written);
        copy.put(data);
        copy.flip();

        final int fStreamId = streamId;
        endpoint.getSelectorLoop().invokeLater(new SendDataTask(fStreamId, copy, false));

        return written;
    }

    private void endHTTP2Data(HTTPStream request) {
        int streamId = findStreamId(request);
        if (streamId < 0) {
            return;
        }

        final int fStreamId = streamId;
        endpoint.getSelectorLoop().invokeLater(new SendDataTask(fStreamId, EMPTY_BUFFER.duplicate(), true));
    }

    private void sendHTTP2Reset(int streamId) {
        sendRstStream(streamId, H2FrameHandler.ERROR_CANCEL);
    }

    // RFC 9113 section 8.3.1: request pseudo-headers (:method, :scheme,
    // :authority, :path) before regular headers;
    // RFC 9113 section 8.2.2: connection-specific headers MUST NOT appear
    private ByteBuffer encodeRequestHeaders(HTTPStream request) throws IOException {
        List<Header> headerList = new ArrayList<Header>();

        // RFC 9113 section 8.3.1: required request pseudo-headers
        headerList.add(new Header(":method", request.getMethod()));
        headerList.add(new Header(":scheme", secure ? "https" : "http"));
        headerList.add(new Header(":authority", host + ":" + port));
        headerList.add(new Header(":path", request.getPath()));

        Headers headers = request.getHeaders();
        if (headers != null) {
            for (Header header : headers) {
                String name = header.getName().toLowerCase();
                // RFC 9113 section 8.2.2: strip connection-specific headers
                if ("connection".equals(name) || "transfer-encoding".equals(name)
                        || "upgrade".equals(name) || "host".equals(name)) {
                    continue;
                }
                headerList.add(new Header(name, header.getValue()));
            }
        }

        ByteBuffer buffer = ByteBuffer.allocate(headerTableSize);
        boolean success = false;

        while (!success) {
            try {
                buffer.clear();
                hpackEncoder.encode(buffer, headerList);
                success = true;
            } catch (BufferOverflowException e) {
                buffer = ByteBuffer.allocate(buffer.capacity() * 2);
            }
        }

        buffer.flip();
        return buffer;
    }

    private int findStreamId(HTTPStream request) {
        Integer streamId = streamIdByRequest.get(request);
        return streamId != null ? streamId.intValue() : -1;
    }

    /**
     * Completes the h2c upgrade after receiving 101 Switching Protocols.
     *
     * <p>RFC 9113 section 3.1 deprecates the h2c upgrade mechanism, but
     * we intentionally retain support for it.
     */
    private void completeH2cUpgrade() {
        negotiatedVersion = HTTPVersion.HTTP_2_0;
        h2cUpgradeInFlight = false;

        initializeHTTP2();
        sendConnectionPreface();

        if (h2cUpgradeRequest != null) {
            activeStreams.put(1, h2cUpgradeRequest);
            streamIdByRequest.put(h2cUpgradeRequest, 1);
            if (h2FlowControl != null) {
                h2FlowControl.openStream(1);
            }
            nextStreamId = 3;
        }
        h2cUpgradeRequest = null;

        parseState = ParseState.H2C_UPGRADE_PENDING;

        LOGGER.fine("HTTP/2 connection preface sent, h2c upgrade complete to " + host + ":" + port);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Response parsing
    // ─────────────────────────────────────────────────────────────────────────

    // RFC 9112 sections 4-7: HTTP/1.1 response parsing state machine
    // (status-line → headers → body via Content-Length or chunked encoding)
    private void processHTTP11Response() {
        parseBuffer.flip();

        try {
            while (parseBuffer.hasRemaining()) {
                switch (parseState) {
                    case STATUS_LINE:
                        if (!parseStatusLine()) {
                            parseBuffer.compact();
                            return;
                        }
                        break;

                    case HEADERS:
                        if (!parseHeaders()) {
                            parseBuffer.compact();
                            return;
                        }
                        break;

                    case BODY:
                        if (!parseBody()) {
                            parseBuffer.compact();
                            return;
                        }
                        break;

                    case CHUNK_SIZE:
                        if (!parseChunkSize()) {
                            parseBuffer.compact();
                            return;
                        }
                        break;

                    case CHUNK_DATA:
                        if (!parseChunkData()) {
                            parseBuffer.compact();
                            return;
                        }
                        break;

                    case CHUNK_TRAILER:
                        if (!parseChunkTrailer()) {
                            parseBuffer.compact();
                            return;
                        }
                        break;

                    case IDLE:
                        parseBuffer.compact();
                        return;

                    case H2C_UPGRADE_PENDING:
                    case HTTP2:
                        parseBuffer.compact();
                        processHTTP2Response();
                        return;
                }
            }
        } finally {
            if (parseState != ParseState.IDLE) {
                parseBuffer.compact();
            }
        }
    }

    // RFC 9112 section 4: status-line = HTTP-version SP status-code SP [reason-phrase]
    private boolean parseStatusLine() {
        int lineEnd = findCRLF(parseBuffer);
        if (lineEnd < 0) {
            if (Boolean.getBoolean("gumdrop.http.debug")) {
                LOGGER.info("[HTTPClient] parseStatusLine: no CRLF yet, need more data");
            }
            return false;
        }
        if (Boolean.getBoolean("gumdrop.http.debug")) {
            byte[] line = new byte[lineEnd];
            parseBuffer.duplicate().position(0).get(line, 0, lineEnd);
            LOGGER.info("[HTTPClient] parseStatusLine: " + new String(line, StandardCharsets.UTF_8));
        }

        byte[] lineBytes = new byte[lineEnd];
        parseBuffer.get(lineBytes);
        parseBuffer.get();
        parseBuffer.get();

        String line = new String(lineBytes, StandardCharsets.UTF_8);

        int firstSpace = line.indexOf(' ');
        int secondSpace = line.indexOf(' ', firstSpace + 1);

        if (firstSpace < 0) {
            LOGGER.warning(MessageFormat.format(L10N.getString("warn.invalid_status_line"), line));
            responseStatus = HTTPStatus.UNKNOWN;
        } else {
            try {
                String codeStr;
                if (secondSpace > 0) {
                    codeStr = line.substring(firstSpace + 1, secondSpace);
                } else {
                    codeStr = line.substring(firstSpace + 1);
                }
                int statusCode = Integer.parseInt(codeStr.trim());
                responseStatus = HTTPStatus.fromCode(statusCode);
            } catch (NumberFormatException e) {
                responseStatus = HTTPStatus.UNKNOWN;
            }
        }

        parseState = ParseState.HEADERS;
        return true;
    }

    // RFC 9112 section 5: header fields terminated by empty line (CRLF)
    private boolean parseHeaders() {
        while (true) {
            int lineEnd = findCRLF(parseBuffer);
            if (lineEnd < 0) {
                return false;
            }

            if (lineEnd == 0) {
                parseBuffer.get();
                parseBuffer.get();

                // RFC 9110 section 15.2.2: 101 Switching Protocols
                if (h2cUpgradeInFlight && responseStatus == HTTPStatus.SWITCHING_PROTOCOLS) {
                    String upgrade = responseHeaders.getValue("upgrade");
                    if (upgrade != null && upgrade.equalsIgnoreCase("h2c")) {
                        LOGGER.fine("h2c upgrade accepted, switching to HTTP/2");
                        completeH2cUpgrade();
                        return true;
                    } else if (!handleProtocolSwitch(responseStatus, responseHeaders)) {
                        LOGGER.warning(L10N.getString("warn.unexpected_101_response"));
                    }
                    h2cUpgradeInFlight = false;
                } else if (responseStatus == HTTPStatus.SWITCHING_PROTOCOLS) {
                    if (handleProtocolSwitch(responseStatus, responseHeaders)) {
                        return true;
                    }
                    LOGGER.warning(L10N.getString("warn.unexpected_101_response"));
                } else if (h2cUpgradeInFlight) {
                    LOGGER.fine("Server declined h2c upgrade, continuing with HTTP/1.1");
                    h2cUpgradeInFlight = false;
                    h2cUpgradeRequest = null;
                }

                // RFC 9110 section 15.2: discard 1xx interim responses
                // (101 Switching Protocols is handled above)
                if (responseStatus.isInformational()) {
                    responseHeaders = new Headers();
                    responseStatus = null;
                    parseState = ParseState.STATUS_LINE;
                    return true;
                }

                if (responseStatus == HTTPStatus.UNAUTHORIZED
                        && username != null && password != null && !authRetryPending) {

                    String wwwAuth = responseHeaders.getValue("www-authenticate");
                    if (wwwAuth != null) {
                        pendingAuthChallenge = wwwAuth;
                        pendingProxyAuth = false;
                        startBodyDiscard();
                        return true;
                    }
                }

                // RFC 9110 section 11.7.1: 407 Proxy Authentication Required
                if (responseStatus == HTTPStatus.PROXY_AUTHENTICATION_REQUIRED
                        && username != null && password != null && !authRetryPending) {

                    String proxyAuth = responseHeaders.getValue("proxy-authenticate");
                    if (proxyAuth != null) {
                        pendingAuthChallenge = proxyAuth;
                        pendingProxyAuth = true;
                        startBodyDiscard();
                        return true;
                    }
                }

                authRetryPending = false;

                HTTPResponseHandler responseHandler = null;
                if (currentStream != null) {
                    responseHandler = currentStream.getHandler();
                }
                if (responseHandler != null) {
                    HTTPResponse response = new HTTPResponse(responseStatus);

                    if (responseStatus.isSuccess()) {
                        responseHandler.ok(response);
                    } else {
                        responseHandler.error(response);
                    }

                    for (Header header : responseHeaders) {
                        responseHandler.header(header.getName(), header.getValue());
                    }
                }

                if (!altSvcNotified && altSvcListener != null) {
                    String altSvc = responseHeaders.getValue("alt-svc");
                    if (altSvc != null) {
                        altSvcNotified = true;
                        altSvcListener.altSvcReceived(altSvc);
                    }
                }

                // RFC 9110 section 9.3.2: HEAD response MUST NOT contain body
                // RFC 9110 section 15.3.5 / 15.4.5: 204 and 304 have no body
                boolean headRequest = currentStream != null
                        && "HEAD".equals(currentStream.getMethod());

                if (headRequest
                        || responseStatus == HTTPStatus.NO_CONTENT
                        || responseStatus == HTTPStatus.NOT_MODIFIED) {
                    if (Boolean.getBoolean("gumdrop.http.debug")) {
                        LOGGER.info("[HTTPClient] no body (HEAD/204/304), completeResponse");
                    }
                    completeResponse();
                } else {
                    // RFC 9112 section 6.3: body length determination precedence
                    // Transfer-Encoding takes priority over Content-Length
                    String transferEncoding =
                            responseHeaders.getValue("transfer-encoding");
                    String contentLengthStr =
                            responseHeaders.getValue("content-length");
                    if (Boolean.getBoolean("gumdrop.http.debug")) {
                        LOGGER.info("[HTTPClient] body type: transferEncoding=" + transferEncoding
                                + " contentLength=" + contentLengthStr);
                    }

                    // RFC 9112 section 6.2: a message with both
                    // Transfer-Encoding and Content-Length is potentially
                    // a smuggling attack — log a warning and ignore
                    // Content-Length (Transfer-Encoding takes priority)
                    if (transferEncoding != null && contentLengthStr != null) {
                        LOGGER.warning("Response has both Transfer-Encoding"
                                + " and Content-Length — ignoring Content-Length"
                                + " (RFC 9112 section 6.2)");
                        contentLengthStr = null;
                    }

                    if (transferEncoding != null
                            && transferEncoding.toLowerCase()
                                    .contains("chunked")) {
                        chunkedEncoding = true;
                        parseState = ParseState.CHUNK_SIZE;
                        if (Boolean.getBoolean("gumdrop.http.debug")) {
                            LOGGER.info("[HTTPClient] chunked, startResponseBody");
                        }
                        if (responseHandler != null) {
                            responseHandler.startResponseBody();
                        }
                    } else if (contentLengthStr != null) {
                        // RFC 9110 section 8.6: validate Content-Length
                        contentLength = validateContentLength(contentLengthStr);
                        if (contentLength < 0) {
                            LOGGER.warning("Invalid Content-Length: "
                                    + contentLengthStr);
                            if (responseHandler != null) {
                                responseHandler.failed(new IOException(
                                        "Invalid Content-Length"));
                            }
                            completeResponse();
                        } else if (contentLength > 0) {
                            parseState = ParseState.BODY;
                            if (Boolean.getBoolean("gumdrop.http.debug")) {
                                LOGGER.info("[HTTPClient] Content-Length=" + contentLength + ", startResponseBody");
                            }
                            if (responseHandler != null) {
                                responseHandler.startResponseBody();
                            }
                        } else {
                            completeResponse();
                        }
                    } else {
                        // RFC 9112 section 6.3: response with body must have
                        // Content-Length or Transfer-Encoding for HTTP/1.1
                        String msg = "Response has a body but lacks Content-Length "
                                + "and Transfer-Encoding headers. The server must send "
                                + "Content-Length or Transfer-Encoding: chunked.";
                        LOGGER.warning(msg);
                        if (responseHandler != null) {
                            responseHandler.failed(new IOException(msg));
                        }
                        completeResponse();
                    }
                }

                return true;
            }

            byte[] lineBytes = new byte[lineEnd];
            parseBuffer.get(lineBytes);
            parseBuffer.get();
            parseBuffer.get();

            String line = new String(lineBytes, StandardCharsets.UTF_8);

            // RFC 9112 section 5.2: obs-fold — a line starting with SP
            // or HTAB is a continuation of the previous header value
            if (!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) {
                int lastIdx = responseHeaders.size() - 1;
                if (lastIdx >= 0) {
                    Header prev = responseHeaders.get(lastIdx);
                    responseHeaders.set(lastIdx,
                            new Header(prev.getName(),
                                    prev.getValue() + " " + line.trim()));
                }
                continue;
            }

            int colonPos = line.indexOf(':');
            if (colonPos > 0) {
                String name = line.substring(0, colonPos).trim();
                String value = line.substring(colonPos + 1).trim();
                responseHeaders.add(name, value);
            }
        }
    }

    // RFC 9112 section 6.3: Content-Length delimited body, or
    // read-until-close when Content-Length is absent
    private boolean parseBody() {
        HTTPResponseHandler responseHandler = null;
        if (!discardingBody && currentStream != null) {
            responseHandler = currentStream.getHandler();
        }

        if (Boolean.getBoolean("gumdrop.http.debug")) {
            LOGGER.info("[HTTPClient] parseBody: contentLength=" + contentLength
                    + " bytesReceived=" + bytesReceived + " remaining=" + parseBuffer.remaining());
        }

        if (contentLength >= 0) {
            long remaining = contentLength - bytesReceived;
            int available = parseBuffer.remaining();
            int toRead = (int) Math.min(remaining, available);

            if (toRead > 0) {
                if (responseHandler != null) {
                    ByteBuffer bodyData = ByteBuffer.allocate(toRead);
                    int oldLimit = parseBuffer.limit();
                    parseBuffer.limit(parseBuffer.position() + toRead);
                    bodyData.put(parseBuffer);
                    parseBuffer.limit(oldLimit);
                    bodyData.flip();
                    responseHandler.responseBodyContent(bodyData);
                } else {
                    parseBuffer.position(parseBuffer.position() + toRead);
                }
                bytesReceived += toRead;
            }

            if (bytesReceived >= contentLength) {
                if (Boolean.getBoolean("gumdrop.http.debug")) {
                    LOGGER.info("[HTTPClient] parseBody: body complete, endResponseBody+completeResponse");
                }
                if (discardingBody) {
                    completeBodyDiscard();
                } else {
                    if (responseHandler != null) {
                        responseHandler.endResponseBody();
                    }
                    completeResponse();
                }
            }
        } else {
            if (responseHandler != null && parseBuffer.hasRemaining()) {
                ByteBuffer bodyData = ByteBuffer.allocate(parseBuffer.remaining());
                bodyData.put(parseBuffer);
                bodyData.flip();
                responseHandler.responseBodyContent(bodyData);
            } else if (discardingBody) {
                parseBuffer.position(parseBuffer.limit());
            }
        }

        return true;
    }

    // RFC 9112 section 7.1: chunk-size in hex followed by CRLF
    private boolean parseChunkSize() {
        int lineEnd = findCRLF(parseBuffer);
        if (lineEnd < 0) {
            return false;
        }

        byte[] lineBytes = new byte[lineEnd];
        parseBuffer.get(lineBytes);
        parseBuffer.get();
        parseBuffer.get();

        String line = new String(lineBytes, StandardCharsets.US_ASCII);
        // RFC 9112 section 7.1.1: strip chunk extensions (after semicolon)
        int semi = line.indexOf(';');
        if (semi >= 0) {
            line = line.substring(0, semi);
        }

        try {
            contentLength = Long.parseLong(line.trim(), 16);
        } catch (NumberFormatException e) {
            LOGGER.warning(MessageFormat.format(L10N.getString("warn.invalid_chunk_size"), line));
            contentLength = 0;
        }

        if (contentLength == 0) {
            parseState = ParseState.CHUNK_TRAILER;
        } else {
            bytesReceived = 0;
            parseState = ParseState.CHUNK_DATA;
        }

        return true;
    }

    // RFC 9112 section 7.1: chunk-data
    private boolean parseChunkData() {
        HTTPResponseHandler responseHandler = null;
        if (!discardingBody && currentStream != null) {
            responseHandler = currentStream.getHandler();
        }

        long remaining = contentLength - bytesReceived;
        int available = parseBuffer.remaining();
        int toRead = (int) Math.min(remaining, available);

        if (toRead > 0) {
            if (responseHandler != null) {
                ByteBuffer bodyData = ByteBuffer.allocate(toRead);
                int oldLimit = parseBuffer.limit();
                parseBuffer.limit(parseBuffer.position() + toRead);
                bodyData.put(parseBuffer);
                parseBuffer.limit(oldLimit);
                bodyData.flip();
                responseHandler.responseBodyContent(bodyData);
            } else {
                parseBuffer.position(parseBuffer.position() + toRead);
            }
            bytesReceived += toRead;
        }

        if (bytesReceived >= contentLength) {
            if (parseBuffer.remaining() >= 2) {
                parseBuffer.get();
                parseBuffer.get();
                parseState = ParseState.CHUNK_SIZE;
                return true;
            }
            return false;
        }

        return true;
    }

    // RFC 9112 section 7.1.2: trailer section after final zero-length chunk
    private boolean parseChunkTrailer() {
        int lineEnd = findCRLF(parseBuffer);
        if (lineEnd < 0) {
            return false;
        }

        if (lineEnd == 0) {
            parseBuffer.get();
            parseBuffer.get();

            if (discardingBody) {
                completeBodyDiscard();
            } else {
                HTTPResponseHandler responseHandler = null;
                if (currentStream != null) {
                    responseHandler = currentStream.getHandler();
                }
                if (responseHandler != null) {
                    responseHandler.endResponseBody();
                }
                completeResponse();
            }
            return true;
        }

        byte[] lineBytes = new byte[lineEnd];
        parseBuffer.get(lineBytes);
        parseBuffer.get();
        parseBuffer.get();

        if (!discardingBody) {
            HTTPResponseHandler responseHandler = null;
            if (currentStream != null) {
                responseHandler = currentStream.getHandler();
            }
            String line = new String(lineBytes, StandardCharsets.UTF_8);
            int colonPos = line.indexOf(':');
            if (colonPos > 0 && responseHandler != null) {
                String name = line.substring(0, colonPos).trim();
                String value = line.substring(colonPos + 1).trim();
                responseHandler.header(name, value);
            }
        }

        return true;
    }

    // RFC 9112 section 9.3: persistent connection ready for reuse after response
    private void completeResponse() {
        HTTPResponseHandler responseHandler = null;
        if (currentStream != null) {
            responseHandler = currentStream.getHandler();
        }

        // RFC 9112 section 9.6: Connection: close means the server
        // will close after this response — do not reuse
        boolean serverClose = responseHeaders != null
                && "close".equalsIgnoreCase(responseHeaders.getValue("connection"));

        if (responseHandler != null) {
            if (Boolean.getBoolean("gumdrop.http.debug")) {
                LOGGER.info("[HTTPClient] completeResponse calling responseHandler.close()");
            }
            responseHandler.close();
            if (Boolean.getBoolean("gumdrop.http.debug")) {
                LOGGER.info("[HTTPClient] responseHandler.close() returned");
            }
        }

        Integer streamId = currentStream != null ? streamIdByRequest.remove(currentStream) : null;
        if (streamId != null) {
            activeStreams.remove(streamId);
        }

        currentStream = null;
        parseState = ParseState.IDLE;
        parseBuffer.clear();

        if (serverClose) {
            LOGGER.fine("Server sent Connection: close — closing connection");
            close();
        } else {
            LOGGER.fine("Response complete");
        }
    }

    // RFC 9110 section 8.6: Content-Length must be a non-negative integer;
    // if multiple values appear, they MUST all be equal or the message
    // is invalid. Returns the parsed value, or -1 if invalid.
    static long validateContentLength(String value) {
        if (value == null) {
            return -1;
        }
        value = value.trim();
        if (value.indexOf(',') >= 0) {
            String[] parts = value.split(",");
            String first = parts[0].trim();
            for (int i = 1; i < parts.length; i++) {
                if (!first.equals(parts[i].trim())) {
                    return -1;
                }
            }
            value = first;
        }
        try {
            long len = Long.parseLong(value);
            return len >= 0 ? len : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private int findCRLF(ByteBuffer buffer) {
        for (int i = buffer.position(); i < buffer.limit() - 1; i++) {
            if (buffer.get(i) == '\r' && buffer.get(i + 1) == '\n') {
                return i - buffer.position();
            }
        }
        return -1;
    }

    private void processHTTP2Response() {
        parseBuffer.flip();
        try {
            h2Parser.receive(parseBuffer);
        } finally {
            parseBuffer.compact();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Authentication
    // ─────────────────────────────────────────────────────────────────────────

    // RFC 9110 section 11.6.1 / 11.7.1: authentication retry for
    // 401 (WWW-Authenticate) and 407 (Proxy-Authenticate)
    private boolean attemptAuthentication(String challenge) {
        if (currentStream == null) {
            return false;
        }

        String scheme = parseAuthScheme(challenge);
        String authHeader = null;

        if ("basic".equalsIgnoreCase(scheme)) {
            authHeader = computeBasicAuth();
        } else if ("digest".equalsIgnoreCase(scheme)) {
            authHeader = computeDigestAuth(challenge, currentStream.getMethod(), currentStream.getPath());
        }

        if (authHeader != null) {
            authRetryPending = true;

            HTTPStream retryStream = new HTTPStream(this, currentStream.getMethod(), currentStream.getPath());

            for (Header h : currentStream.getHeaders()) {
                retryStream.header(h.getName(), h.getValue());
            }

            // RFC 9110 section 11.6.2 / 11.7.2
            String headerName = pendingProxyAuth ? "Proxy-Authorization" : "Authorization";
            retryStream.header(headerName, authHeader);

            HTTPResponseHandler responseHandler = currentStream.getHandler();

            Integer oldStreamId = streamIdByRequest.remove(currentStream);
            if (oldStreamId != null) {
                activeStreams.remove(oldStreamId);
            }

            currentStream = null;

            retryStream.send(responseHandler);

            LOGGER.fine("Authentication retry initiated with " + scheme);
            return true;
        }

        return false;
    }

    private String parseAuthScheme(String wwwAuthenticate) {
        int space = wwwAuthenticate.indexOf(' ');
        if (space > 0) {
            return wwwAuthenticate.substring(0, space);
        }
        return wwwAuthenticate;
    }

    // RFC 7617: HTTP Basic Authentication
    private String computeBasicAuth() {
        String credentials = username + ":" + password;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    // RFC 7616: HTTP Digest Access Authentication
    // Supports MD5, MD5-sess, SHA-256, and SHA-256-sess algorithms
    private String computeDigestAuth(String wwwAuthenticate, String method, String uri) {
        String realm = parseDirective(wwwAuthenticate, "realm");
        String nonce = parseDirective(wwwAuthenticate, "nonce");
        String qop = parseDirective(wwwAuthenticate, "qop");
        String opaque = parseDirective(wwwAuthenticate, "opaque");
        String algorithm = parseDirective(wwwAuthenticate, "algorithm");
        String userhashStr = parseDirective(wwwAuthenticate, "userhash");
        boolean userhash = "true".equalsIgnoreCase(userhashStr);

        if (realm == null || nonce == null) {
            LOGGER.warning(L10N.getString("warn.invalid_digest_challenge"));
            return null;
        }

        if (algorithm == null) {
            algorithm = "MD5";
        }

        // RFC 7616 section 3.4: map algorithm name to Java digest
        // e.g. "SHA-256-sess" → "SHA-256", "MD5" → "MD5"
        String digestName = algorithm.replace("-sess", "");

        try {
            MessageDigest md = MessageDigest.getInstance(digestName);

            // RFC 7616 section 3.4.4: if userhash is true, the username
            // sent is H(username:realm) instead of the cleartext username
            String displayUsername = username;
            if (userhash) {
                md.reset();
                displayUsername = ByteArrays.toHexString(
                        md.digest((username + ":" + realm)
                                .getBytes(StandardCharsets.UTF_8)));
            }

            md.reset();
            String ha1Input = username + ":" + realm + ":" + password;
            String ha1 = ByteArrays.toHexString(md.digest(ha1Input.getBytes(StandardCharsets.UTF_8)));

            String cnonce = null;
            if (algorithm.endsWith("-sess")) {
                cnonce = generateCnonce();
                md.reset();
                String sessInput = ha1 + ":" + nonce + ":" + cnonce;
                ha1 = ByteArrays.toHexString(md.digest(sessInput.getBytes(StandardCharsets.UTF_8)));
            }

            md.reset();
            String ha2Input = method + ":" + uri;
            String ha2 = ByteArrays.toHexString(md.digest(ha2Input.getBytes(StandardCharsets.UTF_8)));

            String response;
            String nc = "00000001";
            if (cnonce == null) {
                cnonce = generateCnonce();
            }

            if (qop != null && (qop.contains("auth") || qop.contains("auth-int"))) {
                md.reset();
                String responseInput = ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2;
                response = ByteArrays.toHexString(md.digest(responseInput.getBytes(StandardCharsets.UTF_8)));
            } else {
                md.reset();
                String responseInput = ha1 + ":" + nonce + ":" + ha2;
                response = ByteArrays.toHexString(md.digest(responseInput.getBytes(StandardCharsets.UTF_8)));
            }

            StringBuilder auth = new StringBuilder("Digest ");
            auth.append("username=\"");
            auth.append(displayUsername);
            auth.append("\", ");
            auth.append("realm=\"");
            auth.append(realm);
            auth.append("\", ");
            auth.append("nonce=\"");
            auth.append(nonce);
            auth.append("\", ");
            auth.append("uri=\"");
            auth.append(uri);
            auth.append("\", ");
            auth.append("response=\"");
            auth.append(response);
            auth.append("\"");

            if (qop != null) {
                auth.append(", qop=auth");
                auth.append(", nc=");
                auth.append(nc);
                auth.append(", cnonce=\"");
                auth.append(cnonce);
                auth.append("\"");
            }
            if (opaque != null) {
                auth.append(", opaque=\"");
                auth.append(opaque);
                auth.append("\"");
            }
            // RFC 7616 section 3.4: algorithm MUST be present when not MD5
            if (!"MD5".equals(algorithm)) {
                auth.append(", algorithm=");
                auth.append(algorithm);
            }
            if (userhash) {
                auth.append(", userhash=true");
            }

            return auth.toString();

        } catch (NoSuchAlgorithmException e) {
            LOGGER.warning(MessageFormat.format(L10N.getString("warn.unsupported_digest_algorithm"), algorithm));
            return null;
        }
    }

    private String parseDirective(String header, String directive) {
        String search = directive + "=";
        int pos = header.toLowerCase().indexOf(search.toLowerCase());
        if (pos < 0) {
            return null;
        }

        pos += search.length();
        if (pos >= header.length()) {
            return null;
        }

        if (header.charAt(pos) == '"') {
            pos++;
            int end = header.indexOf('"', pos);
            if (end > pos) {
                return header.substring(pos, end);
            }
            return null;
        } else {
            int end = pos;
            while (end < header.length() && header.charAt(end) != ',' && header.charAt(end) != ' ') {
                end++;
            }
            return header.substring(pos, end);
        }
    }

    private String generateCnonce() {
        byte[] bytes = new byte[8];
        ThreadLocalRandom.current().nextBytes(bytes);
        return ByteArrays.toHexString(bytes);
    }

    // RFC 9112 section 7.1 / 6.3: enter discard mode to consume the
    // response body before reusing the connection for a retry request
    private void startBodyDiscard() {
        discardingBody = true;
        String transferEncoding = responseHeaders.getValue("transfer-encoding");
        String contentLengthStr = responseHeaders.getValue("content-length");

        if (transferEncoding != null
                && transferEncoding.toLowerCase().contains("chunked")) {
            chunkedEncoding = true;
            parseState = ParseState.CHUNK_SIZE;
        } else if (contentLengthStr != null) {
            contentLength = Long.parseLong(contentLengthStr.trim());
            if (contentLength > 0) {
                bytesReceived = 0;
                parseState = ParseState.BODY;
            } else {
                completeBodyDiscard();
            }
        } else {
            completeBodyDiscard();
        }
    }

    private void completeBodyDiscard() {
        discardingBody = false;
        if (pendingAuthChallenge != null) {
            String challenge = pendingAuthChallenge;
            pendingAuthChallenge = null;
            attemptAuthentication(challenge);
        }
    }

    /**
     * Returns the host.
     *
     * @return the host
     */
    public String getHost() {
        return host;
    }

    /**
     * Returns the port.
     *
     * @return the port
     */
    public int getPort() {
        return port;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP/2 Initialization
    // ─────────────────────────────────────────────────────────────────────────

    // RFC 9113 section 3.4: initialize HTTP/2 connection state
    private void initializeHTTP2() {
        h2Parser = new H2Parser(this);
        h2Parser.setMaxFrameSize(maxFrameSize);
        h2Writer = new H2Writer(new ConnectionChannel());
        h2FlowControl = new H2FlowControl();
        hpackDecoder = new Decoder(headerTableSize);
        hpackEncoder = new Encoder(headerTableSize, maxHeaderListSize);
    }

    // RFC 9113 section 3.4: client connection preface starts with
    // the 24-octet magic string followed by a SETTINGS frame
    private void sendConnectionPreface() {
        byte[] prefaceBytes = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        endpoint.send(ByteBuffer.wrap(prefaceBytes));

        endpoint.getSelectorLoop().invokeLater(new SendSettingsTask());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // H2FrameHandler Implementation
    // ─────────────────────────────────────────────────────────────────────────

    // RFC 9113 section 6.1: DATA frame reception
    @Override
    public void dataFrameReceived(int streamId, boolean endStream, ByteBuffer data) {
        HTTPStream stream = activeStreams.get(streamId);
        if (stream == null) {
            LOGGER.warning(MessageFormat.format(L10N.getString("warn.data_for_unknown_stream"), streamId));
            sendRstStream(streamId, H2FrameHandler.ERROR_STREAM_CLOSED);
            return;
        }

        int dataLength = data.remaining();

        HTTPResponseHandler responseHandler = stream.getHandler();
        if (responseHandler != null) {
            try {
                responseHandler.responseBodyContent(data);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in response handler", e);
            }
        }

        // RFC 9113 section 6.9: receive-side flow control accounting;
        // send WINDOW_UPDATE to replenish the server's send window
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
            completeStream(stream, streamId);
        }
    }

    @Override
    public void headersFrameReceived(int streamId, boolean endStream, boolean endHeaders,
            int streamDependency, boolean exclusive, int weight,
            ByteBuffer headerBlockFragment) {
        HTTPStream stream = activeStreams.get(streamId);
        if (stream == null) {
            LOGGER.warning(MessageFormat.format(L10N.getString("warn.headers_for_unknown_stream"), streamId));
            sendRstStream(streamId, H2FrameHandler.ERROR_STREAM_CLOSED);
            return;
        }

        appendHeaderBlockFragment(streamId, headerBlockFragment);

        if (endHeaders) {
            processHeaders(stream, streamId, endStream);
        } else {
            continuationStreamId = streamId;
        }
    }

    // RFC 9113 section 5.3: priority signaling is deprecated
    @Override
    public void priorityFrameReceived(int streamId, int streamDependency,
            boolean exclusive, int weight) {
    }

    @Override
    public void rstStreamFrameReceived(int streamId, int errorCode) {
        HTTPStream stream = activeStreams.remove(streamId);
        if (stream != null) {
            streamIdByRequest.remove(stream);
            HTTPResponseHandler responseHandler = stream.getHandler();
            if (responseHandler != null) {
                try {
                    String errorName = H2FrameHandler.errorToString(errorCode);
                    String msg = MessageFormat.format(L10N.getString("err.stream_reset"), errorName);
                    responseHandler.failed(new IOException(msg));
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error in response handler", e);
                }
            }
            drainPendingRequests();
        }
    }

    // RFC 9113 section 6.5: SETTINGS frame reception
    @Override
    public void settingsFrameReceived(boolean ack, Map<Integer, Integer> settings) {
        if (parseState == ParseState.H2C_UPGRADE_PENDING) {
            parseState = ParseState.HTTP2;
            LOGGER.fine("HTTP/2 handshake complete, ready for requests");
        }

        if (!ack) {
            // RFC 9113 section 6.5.2: process peer's settings
            for (Map.Entry<Integer, Integer> entry : settings.entrySet()) {
                int identifier = entry.getKey();
                int value = entry.getValue();
                switch (identifier) {
                    case H2FrameHandler.SETTINGS_HEADER_TABLE_SIZE:
                        // RFC 7541 Section 6.3: dynamic table size update
                        headerTableSize = value;
                        break;
                    case H2FrameHandler.SETTINGS_ENABLE_PUSH:
                        serverPushEnabled = (value == 1);
                        break;
                    case H2FrameHandler.SETTINGS_MAX_CONCURRENT_STREAMS:
                        maxConcurrentStreams = value;
                        break;
                    case H2FrameHandler.SETTINGS_INITIAL_WINDOW_SIZE:
                        // RFC 9113 section 6.9.2: adjust all stream send windows
                        initialWindowSize = value;
                        if (h2FlowControl != null) {
                            if (h2FlowControl.onSettingsInitialWindowSize(value)) {
                                // RFC 9113 section 6.9.2: overflow is FLOW_CONTROL_ERROR
                                sendGoaway(H2FrameHandler.ERROR_FLOW_CONTROL_ERROR,
                                        "SETTINGS_INITIAL_WINDOW_SIZE overflow");
                                close();
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
            if (hpackEncoder != null) {
                hpackEncoder.setHeaderTableSize(headerTableSize);
                hpackEncoder.setMaxHeaderListSize(maxHeaderListSize);
            }
            // RFC 9113 section 6.5: ACK peer's SETTINGS
            sendSettingsAck();
        }
    }

    // RFC 9113 section 6.6: PUSH_PROMISE reception
    @Override
    public void pushPromiseFrameReceived(int streamId, int promisedStreamId,
            boolean endHeaders, ByteBuffer headerBlockFragment) {
        // RFC 9113 section 8.4: refuse server push if disabled
        if (!serverPushEnabled) {
            sendRstStream(promisedStreamId, H2FrameHandler.ERROR_REFUSED_STREAM);
            return;
        }

        // Track highest server-initiated stream ID for GOAWAY
        if (promisedStreamId > highestServerStreamId) {
            highestServerStreamId = promisedStreamId;
        }

        appendHeaderBlockFragment(promisedStreamId, headerBlockFragment);

        if (endHeaders) {
            processPushPromise(streamId, promisedStreamId);
        } else {
            continuationStreamId = promisedStreamId;
        }
    }

    private void processPushPromise(int associatedStreamId, int promisedStreamId) {
        if (headerBlockBuffer == null) {
            sendRstStream(promisedStreamId, H2FrameHandler.ERROR_REFUSED_STREAM);
            return;
        }

        headerBlockBuffer.flip();
        final Headers promisedHeaders = new Headers();
        try {
            hpackDecoder.decode(headerBlockBuffer, new HeaderHandler() {
                @Override
                public void header(Header header) {
                    promisedHeaders.add(header);
                }
            });
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "HPACK decode error in PUSH_PROMISE", e);
            sendGoaway(H2FrameHandler.ERROR_COMPRESSION_ERROR,
                    "HPACK decode error in PUSH_PROMISE");
            return;
        } finally {
            headerBlockBuffer = null;
        }

        HTTPStream associatedStream = activeStreams.get(associatedStreamId);
        HTTPResponseHandler responseHandler = (associatedStream != null)
                ? associatedStream.getHandler() : null;

        if (responseHandler == null) {
            sendRstStream(promisedStreamId, H2FrameHandler.ERROR_REFUSED_STREAM);
            return;
        }

        PushPromiseImpl promise = new PushPromiseImpl(
                promisedStreamId, promisedHeaders);
        try {
            responseHandler.pushPromise(promise);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error in pushPromise callback", e);
        }

        if (!promise.handled) {
            sendRstStream(promisedStreamId, H2FrameHandler.ERROR_REFUSED_STREAM);
        }
    }

    /**
     * PushPromise implementation delivered to handlers.
     */
    private class PushPromiseImpl implements PushPromise {
        private final int promisedStreamId;
        private final Headers headers;
        volatile boolean handled;

        PushPromiseImpl(int promisedStreamId, Headers headers) {
            this.promisedStreamId = promisedStreamId;
            this.headers = headers;
        }

        @Override public String getMethod() {
            return headers.getValue(":method");
        }
        @Override public String getPath() {
            return headers.getValue(":path");
        }
        @Override public String getAuthority() {
            return headers.getValue(":authority");
        }
        @Override public String getScheme() {
            return headers.getValue(":scheme");
        }
        @Override public Headers getHeaders() {
            return headers;
        }

        @Override
        public void accept(HTTPResponseHandler handler) {
            handled = true;
            HTTPStream promisedStream = new HTTPStream(
                    HTTPClientProtocolHandler.this,
                    getMethod() != null ? getMethod() : "GET",
                    getPath() != null ? getPath() : "/");
            promisedStream.send(handler);
            activeStreams.put(promisedStreamId, promisedStream);
            streamIdByRequest.put(promisedStream, promisedStreamId);
            if (h2FlowControl != null) {
                h2FlowControl.openStream(promisedStreamId);
            }
        }

        @Override
        public void reject() {
            handled = true;
            sendRstStream(promisedStreamId, H2FrameHandler.ERROR_REFUSED_STREAM);
        }
    }

    // RFC 9113 section 6.7: PING must be ACK'd with identical payload
    @Override
    public void pingFrameReceived(boolean ack, long opaqueData) {
        if (!ack) {
            try {
                h2Writer.writePing(opaqueData, true);
                h2Writer.flush();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error sending PING ACK", e);
            }
        }
    }

    // RFC 9113 section 6.8: GOAWAY initiates graceful shutdown;
    // streams with IDs greater than lastStreamId were not processed
    @Override
    public void goawayFrameReceived(int lastStreamId, int errorCode, ByteBuffer debugData) {
        LOGGER.info(MessageFormat.format(L10N.getString("info.goaway_received"),
                lastStreamId, H2FrameHandler.errorToString(errorCode)));

        for (Map.Entry<Integer, HTTPStream> entry : activeStreams.entrySet()) {
            if (entry.getKey() > lastStreamId) {
                HTTPStream stream = activeStreams.remove(entry.getKey());
                if (stream != null) {
                    HTTPResponseHandler responseHandler = stream.getHandler();
                    if (responseHandler != null) {
                        try {
                            responseHandler.failed(new IOException(L10N.getString("err.connection_closed_by_server")));
                        } catch (Exception e) {
                            LOGGER.log(Level.WARNING, "Error in response handler", e);
                        }
                    }
                }
            }
        }

        close();
    }

    // RFC 9113 section 6.9: WINDOW_UPDATE frame reception
    @Override
    public void windowUpdateFrameReceived(int streamId, int windowSizeIncrement) {
        if (h2FlowControl == null) {
            return;
        }
        boolean overflow = h2FlowControl.onWindowUpdate(streamId, windowSizeIncrement);
        // RFC 9113 section 6.9.1: window exceeding 2^31-1 is a flow-control error
        if (overflow) {
            if (streamId == 0) {
                sendGoaway(H2FrameHandler.ERROR_FLOW_CONTROL_ERROR,
                        "connection-level flow-control window overflow");
                close();
            } else {
                sendRstStream(streamId, H2FrameHandler.ERROR_FLOW_CONTROL_ERROR);
            }
            return;
        }
        if (streamId == 0) {
            for (Integer sid : new ArrayList<Integer>(h2PendingData.keySet())) {
                drainPendingData(sid);
            }
        } else {
            drainPendingData(streamId);
        }
    }

    @Override
    public void continuationFrameReceived(int streamId, boolean endHeaders,
            ByteBuffer headerBlockFragment) {
        HTTPStream stream = activeStreams.get(streamId);
        if (stream == null) {
            LOGGER.warning(MessageFormat.format(L10N.getString("warn.continuation_for_unknown_stream"), streamId));
            return;
        }

        appendHeaderBlockFragment(streamId, headerBlockFragment);

        if (endHeaders) {
            processHeaders(stream, streamId, false);
            continuationStreamId = 0;
        }
    }

    // RFC 9113 section 5.4: connection errors → GOAWAY, stream errors → RST_STREAM
    @Override
    public void frameError(int errorCode, int streamId, String message) {
        LOGGER.warning(MessageFormat.format(L10N.getString("warn.h2_frame_error"),
                message, H2FrameHandler.errorToString(errorCode) + ", stream=" + streamId));

        if (streamId == 0) {
            sendGoaway(errorCode, message);
        } else {
            sendRstStream(streamId, errorCode);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP/2 Helper Methods
    // ─────────────────────────────────────────────────────────────────────────

    private void appendHeaderBlockFragment(int streamId, ByteBuffer fragment) {
        if (headerBlockBuffer == null) {
            headerBlockBuffer = ByteBuffer.allocate(Math.max(4096, fragment.remaining()));
        }
        if (headerBlockBuffer.remaining() < fragment.remaining()) {
            int newCapacity = headerBlockBuffer.capacity() * 2 + fragment.remaining();
            ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);
            headerBlockBuffer.flip();
            newBuffer.put(headerBlockBuffer);
            headerBlockBuffer = newBuffer;
        }
        headerBlockBuffer.put(fragment);
    }

    // RFC 7541: HPACK decode; RFC 9113 section 4.3: decompression failure
    // is a connection error of type COMPRESSION_ERROR
    private void processHeaders(HTTPStream stream, int streamId, boolean endStream) {
        if (headerBlockBuffer == null) {
            return;
        }

        headerBlockBuffer.flip();
        final Headers headers = new Headers();

        try {
            hpackDecoder.decode(headerBlockBuffer, new HeaderHandler() {
                @Override
                public void header(Header header) {
                    headers.add(header);
                }
            });
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "HPACK decode error", e);
            sendGoaway(H2FrameHandler.ERROR_COMPRESSION_ERROR,
                    "HPACK decode error");
            return;
        } finally {
            headerBlockBuffer = null;
        }

        String statusStr = headers.getValue(":status");
        if (statusStr != null) {
            int statusCode = Integer.parseInt(statusStr);
            HTTPStatus status = HTTPStatus.fromCode(statusCode);
            HTTPResponseHandler responseHandler = stream.getHandler();
            if (responseHandler != null) {
                try {
                    HTTPResponse response = new HTTPResponse(status);
                    if (status.isSuccess()) {
                        responseHandler.ok(response);
                    } else {
                        responseHandler.error(response);
                    }

                    for (Header header : headers) {
                        String name = header.getName();
                        if (!name.startsWith(":")) {
                            responseHandler.header(name, header.getValue());
                        }
                    }

                    if (!endStream) {
                        responseHandler.startResponseBody();
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error in response handler", e);
                }
            }

            if (!altSvcNotified && altSvcListener != null) {
                String altSvc = headers.getValue("alt-svc");
                if (altSvc != null) {
                    altSvcNotified = true;
                    altSvcListener.altSvcReceived(altSvc);
                }
            }
        }

        if (endStream) {
            completeStream(stream, streamId);
        }
    }

    private void completeStream(HTTPStream stream, int streamId) {
        activeStreams.remove(streamId);
        streamIdByRequest.remove(stream);
        if (h2FlowControl != null) {
            h2FlowControl.closeStream(streamId);
        }
        PendingData removed = h2PendingData.remove(streamId);
        if (removed != null) {
            releasePendingData(removed);
        }
        HTTPResponseHandler responseHandler = stream.getHandler();
        if (responseHandler != null) {
            try {
                responseHandler.endResponseBody();
                responseHandler.close();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in response handler", e);
            }
        }
        drainPendingRequests();
    }

    // RFC 9113 section 5.1.2: dispatch queued requests when capacity
    // becomes available after a stream completes
    private void drainPendingRequests() {
        while (!pendingRequests.isEmpty()
                && activeStreams.size() < maxConcurrentStreams) {
            PendingRequest pending = pendingRequests.poll();
            if (pending != null) {
                dispatchRequest(pending.request, pending.hasBody);
            }
        }
    }

    private void sendSettingsAck() {
        endpoint.getSelectorLoop().invokeLater(new SendSettingsAckTask());
    }

    private void sendRstStream(int streamId, int errorCode) {
        endpoint.getSelectorLoop().invokeLater(new SendRstStreamTask(streamId, errorCode));
    }

    private void sendGoaway(int errorCode) {
        sendGoaway(errorCode, null);
    }

    // RFC 9113 section 6.8: GOAWAY with optional UTF-8 debug data
    void sendGoaway(int errorCode, String debugMessage) {
        final int fLastStreamId = highestServerStreamId;
        byte[] debugData = (debugMessage != null)
                ? debugMessage.getBytes(StandardCharsets.UTF_8)
                : new byte[0];

        endpoint.getSelectorLoop().invokeLater(
                new SendGoawayTask(fLastStreamId, errorCode, debugData));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WritableByteChannel Adapter for H2Writer
    // ─────────────────────────────────────────────────────────────────────────

    private class ConnectionChannel implements WritableByteChannel {

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
                endpoint.send(copy);
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

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP/2 DATA helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes DATA frames, splitting at maxFrameSize when necessary.
     */
    // RFC 9113 section 4.2: split DATA payloads exceeding SETTINGS_MAX_FRAME_SIZE
    private void writeDataFrames(int streamId, ByteBuffer buf, boolean endStream)
            throws IOException {
        while (buf.remaining() > maxFrameSize) {
            int savedLimit = buf.limit();
            buf.limit(buf.position() + maxFrameSize);
            ByteBuffer slice = buf.slice();
            buf.position(buf.position() + maxFrameSize);
            buf.limit(savedLimit);
            h2Writer.writeData(streamId, slice, false, 0);
        }
        h2Writer.writeData(streamId, buf, endStream, 0);
        h2Writer.flush();
    }

    // ── Pending data queue for flow-control blocked streams ──

    private static class PendingData {
        final ArrayDeque<ByteBuffer> buffers = new ArrayDeque<ByteBuffer>();
        boolean endStream;

        void enqueue(ByteBuffer data, boolean fin) {
            if (data.hasRemaining()) {
                ByteBuffer copy = ByteBuffer.allocate(data.remaining());
                copy.put(data);
                copy.flip();
                buffers.add(copy);
            }
            if (fin) {
                endStream = true;
            }
        }

        boolean isEmpty() {
            return buffers.isEmpty();
        }

        void reset() {
            buffers.clear();
            endStream = false;
        }
    }

    private static final int MAX_POOLED_PENDING = 32;
    private final Map<Integer, PendingData> h2PendingData =
            new LinkedHashMap<Integer, PendingData>();
    private final ArrayDeque<PendingData> pendingDataPool =
            new ArrayDeque<PendingData>();

    private PendingData acquirePendingData() {
        PendingData pd = pendingDataPool.poll();
        return pd != null ? pd : new PendingData();
    }

    private void releasePendingData(PendingData pd) {
        pd.reset();
        if (pendingDataPool.size() < MAX_POOLED_PENDING) {
            pendingDataPool.add(pd);
        }
    }

    private void queuePendingData(int streamId, ByteBuffer remaining, boolean endStream) {
        PendingData pending = h2PendingData.get(streamId);
        if (pending == null) {
            pending = acquirePendingData();
            h2PendingData.put(streamId, pending);
        }
        pending.enqueue(remaining, endStream);
    }

    // RFC 9113 section 6.9: drain queued DATA when send window opens
    private void drainPendingData(int streamId) {
        PendingData pending = h2PendingData.get(streamId);
        if (pending == null) {
            return;
        }
        int available = h2FlowControl.availableSendWindow(streamId);
        while (available > 0 && !pending.isEmpty()) {
            ByteBuffer head = pending.buffers.peek();
            int headRemaining = head.remaining();
            int toSend = Math.min(maxFrameSize, Math.min(available, headRemaining));
            if (toSend >= headRemaining) {
                pending.buffers.poll();
                h2FlowControl.consumeSendWindow(streamId, headRemaining);
                available -= headRemaining;
                boolean fin = pending.endStream && pending.isEmpty();
                try {
                    h2Writer.writeData(streamId, head, fin, 0);
                    h2Writer.flush();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error draining pending data", e);
                    return;
                }
            } else {
                h2FlowControl.consumeSendWindow(streamId, toSend);
                available -= toSend;
                int savedLimit = head.limit();
                head.limit(head.position() + toSend);
                ByteBuffer slice = head.slice();
                head.position(head.position() + toSend);
                head.limit(savedLimit);
                try {
                    h2Writer.writeData(streamId, slice, false, 0);
                    h2Writer.flush();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error draining pending data", e);
                    return;
                }
            }
        }
        if (pending.isEmpty()) {
            h2PendingData.remove(streamId);
            releasePendingData(pending);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP/2 Frame Sending Tasks
    // ─────────────────────────────────────────────────────────────────────────

    // RFC 9113 section 4.3 / 6.2: sends HEADERS frame, fragmenting
    // the header block into CONTINUATION frames if it exceeds
    // SETTINGS_MAX_FRAME_SIZE
    private class SendHeadersTask implements Runnable {
        private final int streamId;
        private final ByteBuffer headerBlock;
        private final boolean hasBody;
        private final HTTPStream request;

        SendHeadersTask(int streamId, ByteBuffer headerBlock, boolean hasBody, HTTPStream request) {
            this.streamId = streamId;
            this.headerBlock = headerBlock;
            this.hasBody = hasBody;
            this.request = request;
        }

        @Override
        public void run() {
            try {
                boolean endStream = !hasBody;
                int length = headerBlock.remaining();
                if (length <= maxFrameSize) {
                    h2Writer.writeHeaders(streamId, headerBlock, endStream, true, 0, 0, 0, false);
                } else {
                    int savedLimit = headerBlock.limit();
                    headerBlock.limit(headerBlock.position() + maxFrameSize);
                    ByteBuffer fragment = headerBlock.slice();
                    headerBlock.limit(savedLimit);
                    headerBlock.position(headerBlock.position() + maxFrameSize);
                    h2Writer.writeHeaders(streamId, fragment, endStream, false, 0, 0, 0, false);
                    length -= maxFrameSize;
                    while (length > maxFrameSize) {
                        headerBlock.limit(headerBlock.position() + maxFrameSize);
                        fragment = headerBlock.slice();
                        headerBlock.limit(savedLimit);
                        headerBlock.position(headerBlock.position() + maxFrameSize);
                        h2Writer.writeContinuation(streamId, fragment, false);
                        length -= maxFrameSize;
                    }
                    h2Writer.writeContinuation(streamId, headerBlock, true);
                }
                h2Writer.flush();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error sending HTTP/2 request", e);
                HTTPResponseHandler responseHandler = request.getHandler();
                if (responseHandler != null) {
                    try {
                        responseHandler.failed(e);
                    } catch (Exception ex) {
                        LOGGER.log(Level.WARNING, "Error in response handler", ex);
                    }
                }
                activeStreams.remove(streamId);
                streamIdByRequest.remove(request);
            }
        }
    }

    // RFC 9113 section 6.9: sends DATA frame, respecting both
    // SETTINGS_MAX_FRAME_SIZE (section 4.2) and flow control windows;
    // queues excess data for later drain on WINDOW_UPDATE
    private class SendDataTask implements Runnable {
        private final int streamId;
        private final ByteBuffer data;
        private final boolean endStream;

        SendDataTask(int streamId, ByteBuffer data, boolean endStream) {
            this.streamId = streamId;
            this.data = data;
            this.endStream = endStream;
        }

        @Override
        public void run() {
            try {
                if (h2FlowControl != null && data.hasRemaining()) {
                    if (h2PendingData.containsKey(streamId)) {
                        queuePendingData(streamId, data, endStream);
                        return;
                    }
                    ByteBuffer remaining = data;
                    while (remaining.hasRemaining()) {
                        int window = h2FlowControl.availableSendWindow(streamId);
                        if (window <= 0) {
                            break;
                        }
                        int toSend = Math.min(maxFrameSize, Math.min(window, remaining.remaining()));
                        h2FlowControl.consumeSendWindow(streamId, toSend);
                        int savedLimit = remaining.limit();
                        remaining.limit(remaining.position() + toSend);
                        ByteBuffer slice = remaining.slice();
                        remaining.position(remaining.position() + toSend);
                        remaining.limit(savedLimit);
                        boolean fin = endStream && !remaining.hasRemaining();
                        h2Writer.writeData(streamId, slice, fin, 0);
                        h2Writer.flush();
                    }
                    if (remaining.hasRemaining()) {
                        queuePendingData(streamId, remaining, endStream);
                    }
                } else {
                    writeDataFrames(streamId, data, endStream);
                }
            } catch (IOException e) {
                String msg = endStream ? "Error ending HTTP/2 stream" : "Error sending HTTP/2 data";
                LOGGER.log(Level.WARNING, msg, e);
            }
        }
    }

    private class SendSettingsTask implements Runnable {
        @Override
        public void run() {
            try {
                h2Writer.writeSettings(new LinkedHashMap<Integer, Integer>());
                h2Writer.flush();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error sending HTTP/2 connection preface", e);
                close();
            }
        }
    }

    private class SendSettingsAckTask implements Runnable {
        @Override
        public void run() {
            try {
                h2Writer.writeSettingsAck();
                h2Writer.flush();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error sending SETTINGS ACK", e);
            }
        }
    }

    private class SendRstStreamTask implements Runnable {
        private final int streamId;
        private final int errorCode;

        SendRstStreamTask(int streamId, int errorCode) {
            this.streamId = streamId;
            this.errorCode = errorCode;
        }

        @Override
        public void run() {
            try {
                h2Writer.writeRstStream(streamId, errorCode);
                h2Writer.flush();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error sending RST_STREAM", e);
            }
        }
    }

    private class SendGoawayTask implements Runnable {
        private final int lastStreamId;
        private final int errorCode;
        private final byte[] debugData;

        SendGoawayTask(int lastStreamId, int errorCode, byte[] debugData) {
            this.lastStreamId = lastStreamId;
            this.errorCode = errorCode;
            this.debugData = debugData;
        }

        // RFC 9113 section 6.8: GOAWAY carries last-stream-ID,
        // error code, and optional opaque debug data
        @Override
        public void run() {
            try {
                h2Writer.writeGoaway(lastStreamId, errorCode,
                        ByteBuffer.wrap(debugData));
                h2Writer.flush();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error sending GOAWAY", e);
            } finally {
                close();
            }
        }
    }

    private static class PendingRequest {
        final HTTPStream request;
        final boolean hasBody;

        PendingRequest(HTTPStream request, boolean hasBody) {
            this.request = request;
            this.hasBody = hasBody;
        }
    }
}
