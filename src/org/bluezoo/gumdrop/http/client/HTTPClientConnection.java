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
import org.bluezoo.gumdrop.ConnectionInfo;
import org.bluezoo.gumdrop.TLSInfo;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.http.HTTPVersion;
import org.bluezoo.gumdrop.http.h2.H2FrameHandler;
import org.bluezoo.gumdrop.http.h2.H2Parser;
import org.bluezoo.gumdrop.http.h2.H2Writer;
import org.bluezoo.gumdrop.http.hpack.Decoder;
import org.bluezoo.gumdrop.http.hpack.Encoder;
import org.bluezoo.gumdrop.http.hpack.HeaderHandler;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

/**
 * HTTP client connection that handles protocol-level communication.
 *
 * <p>This class manages a single HTTP connection to a server, supporting both
 * HTTP/1.1 and HTTP/2 protocols. It provides factory methods for creating
 * requests and handles the protocol-level details of sending requests and
 * receiving responses.
 *
 * <p>Instances are created internally by {@link HTTPClient} when a connection
 * is established. Most applications should use the {@link HTTPClient} class
 * directly rather than this class.
 *
 * <h3>Advanced Usage</h3>
 *
 * <p>For advanced scenarios, the connection can be accessed via
 * {@link HTTPClient#getActiveConnection()}:
 * <pre>{@code
 * HTTPClientConnection conn = client.getActiveConnection();
 * if (conn != null) {
 *     // Access connection-level details
 *     HTTPVersion version = conn.getVersion();
 * }
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTPClient
 * @see HTTPRequest
 */
public class HTTPClientConnection extends Connection implements H2FrameHandler {

    private static final ResourceBundle L10N = 
        ResourceBundle.getBundle("org.bluezoo.gumdrop.http.client.L10N");
    private static final Logger logger = Logger.getLogger(HTTPClientConnection.class.getName());

    // Parent client
    private final HTTPClient client;

    // Handler for connection events
    private final HTTPClientHandler handler;

    // Connection target
    private final String host;
    private final int port;

    // Protocol state
    private HTTPVersion negotiatedVersion;
    private volatile boolean open;

    // h2c upgrade state (for cleartext HTTP/2)
    // HTTP/2 via ALPN is enabled by default for TLS connections.
    // Set to false via setH2Enabled(false) to disable and use HTTP/1.1 over TLS.
    private boolean h2Enabled = true;

    // h2c upgrade is enabled by default for plaintext connections.
    // Most servers won't support it, but we try anyway (opportunistic upgrade).
    // Set to false via setH2cUpgradeEnabled(false) to disable.
    private boolean h2cUpgradeEnabled = true;
    private boolean h2cUpgradeAttempted; // True once we've tried h2c upgrade (don't retry)
    private boolean h2cUpgradeInFlight;  // True if we sent upgrade headers, waiting for response
    private HTTPStream h2cUpgradeRequest; // The request that triggered the upgrade

    // HTTP/2 with prior knowledge (skip h2c upgrade, send PRI directly)
    private boolean h2WithPriorKnowledge = false;

    // Active streams (by stream ID for HTTP/2, single entry for HTTP/1.1)
    private final Map<Integer, HTTPStream> activeStreams = new ConcurrentHashMap<>();
    private int nextStreamId = 1;

    // HTTP/2 frame parser and writer
    private H2Parser h2Parser;
    private H2Writer h2Writer;
    private Decoder hpackDecoder;
    private Encoder hpackEncoder;

    // HTTP/2 settings (defaults per RFC 7540)
    private int headerTableSize = 4096;
    private int maxConcurrentStreams = 100;
    private int initialWindowSize = 65535;
    private int maxFrameSize = 16384;
    private int maxHeaderListSize = 8192;
    private boolean serverPushEnabled = true;

    // HTTP/2 CONTINUATION state
    private int continuationStreamId;
    private ByteBuffer headerBlockBuffer;

    // HTTP/1.1 parsing state
    private HTTPStream currentStream;
    private ByteBuffer parseBuffer;
    private ParseState parseState = ParseState.IDLE;
    private HTTPStatus responseStatus;
    private Headers responseHeaders;
    private long contentLength = -1;
    private long bytesReceived = 0;
    private boolean chunkedEncoding;

    private enum ParseState {
        IDLE,
        STATUS_LINE,
        HEADERS,
        BODY,
        CHUNK_SIZE,
        CHUNK_DATA,
        CHUNK_TRAILER,
        H2C_UPGRADE_PENDING, // Waiting for server's SETTINGS after sending PRI preface
        HTTP2                 // HTTP/2 mode - processing frames
    }

    // Authentication
    private volatile String username;
    private volatile String password;
    private boolean authRetryPending;  // Tracks if we're retrying after a 401

    /**
     * Creates an HTTP client connection.
     *
     * <p>This constructor is called by {@link HTTPClient#newConnection} when
     * establishing a new connection.
     *
     * @param client the parent HTTPClient
     * @param channel the socket channel for the connection
     * @param engine the SSL engine for TLS connections, or null for plaintext
     * @param secure whether this is a secure (TLS) connection
     * @param handler the handler to receive connection events
     */
    public HTTPClientConnection(HTTPClient client, SocketChannel channel, SSLEngine engine,
            boolean secure, HTTPClientHandler handler) {
        super(engine, secure);
        this.client = client;
        this.handler = handler;
        this.host = client.getHost().getHostAddress();
        this.port = client.getPort();
        this.parseBuffer = ByteBuffer.allocate(8192);
        
        // Note: ALPN configuration is done in setH2Enabled() which is called
        // after construction but before the handshake starts
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
     * <p>Use this for non-standard methods or methods not covered by the
     * convenience methods (e.g., "PROPFIND" for WebDAV).
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
     * <p>Returns false after the connection is closed, after receiving a GOAWAY
     * frame (HTTP/2), or after a fatal connection error.
     *
     * @return true if the connection is open
     */
    @Override
    public boolean isOpen() {
        return open && super.isOpen();
    }

    /**
     * Returns the negotiated HTTP version, or null if not yet connected.
     *
     * <p>For TLS connections, the version is determined by ALPN negotiation
     * during the TLS handshake. For plaintext connections, it depends on
     * HTTP/2 upgrade negotiation or the server's response.
     *
     * @return the HTTP version, or null if not yet known
     */
    public HTTPVersion getVersion() {
        return negotiatedVersion;
    }

    /**
     * Closes the connection gracefully.
     *
     * <p>For HTTP/2, this sends a GOAWAY frame allowing outstanding requests
     * to complete. For HTTP/1.x, this closes the connection after any
     * in-progress request completes.
     */
    @Override
    public void close() {
        open = false;

        // Fail any active requests
        Exception closeException = new IOException(L10N.getString("err.connection_closed"));
        for (HTTPStream request : activeStreams.values()) {
            HTTPResponseHandler responseHandler = request.getHandler();
            if (responseHandler != null) {
                try {
                    responseHandler.failed(closeException);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error notifying handler of close", e);
                }
            }
        }
        activeStreams.clear();

        try {
            super.close();
        } catch (Exception e) {
            logger.log(Level.FINE, "Error closing connection", e);
        }
    }

    /**
     * Enables or disables HTTP/2 via ALPN for TLS connections.
     *
     * <p>When enabled (the default), TLS connections will offer "h2" in ALPN
     * negotiation, allowing the server to select HTTP/2 if it supports it.
     *
     * <p>Disable this if you specifically need to use HTTP/1.1 over TLS.
     *
     * <p>Note: This must be set before the connection is established. Changing
     * it after TLS handshake has no effect.
     *
     * @param enabled true to offer HTTP/2 in ALPN negotiation
     */
    public void setH2Enabled(boolean enabled) {
        this.h2Enabled = enabled;
        
        // Configure ALPN on the SSL engine if we have one
        if (engine != null) {
            SSLParameters sp = engine.getSSLParameters();
            if (enabled) {
                sp.setApplicationProtocols(new String[] { "h2", "http/1.1" });
            } else {
                sp.setApplicationProtocols(new String[] { "http/1.1" });
            }
            engine.setSSLParameters(sp);
        }
    }

    /**
     * Enables or disables h2c (HTTP/2 over cleartext) upgrade attempts.
     *
     * <p>When enabled, the first request on a non-TLS connection will include
     * h2c upgrade headers. If the server responds with 101 Switching Protocols,
     * the connection switches to HTTP/2.
     *
     * <p>Note: This is currently disabled by default because the server-side
     * h2c upgrade implementation is incomplete. Enable only for testing or
     * when connecting to servers with complete h2c support.
     *
     * @param enabled true to enable h2c upgrade attempts
     */
    public void setH2cUpgradeEnabled(boolean enabled) {
        this.h2cUpgradeEnabled = enabled;
        if (enabled) {
            // Reset attempted flag so we can try again if re-enabled
            h2cUpgradeAttempted = false;
        }
    }

    /**
     * Enables or disables HTTP/2 with prior knowledge.
     *
     * <p>When enabled on a plaintext (non-TLS) connection, the client will
     * immediately send the HTTP/2 connection preface (PRI * HTTP/2.0...) 
     * upon connection establishment, without first attempting an h2c upgrade.
     *
     * <p>This should only be enabled when you know the server supports HTTP/2,
     * as the connection will fail if the server doesn't understand HTTP/2.
     *
     * @param enabled true to use HTTP/2 with prior knowledge
     */
    public void setH2WithPriorKnowledge(boolean enabled) {
        this.h2WithPriorKnowledge = enabled;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Request creation and sending
    // ─────────────────────────────────────────────────────────────────────────

    private HTTPRequest createRequest(String method, String path) {
        if (!open) {
            throw new IllegalStateException(L10N.getString("err.connection_not_open"));
        }
        return new HTTPStream(this, method, path);
    }

    /**
     * Sends a request. Called by HTTPStream.
     *
     * @param request the request to send
     * @param hasBody true if the request will have a body
     */
    void sendRequest(HTTPStream request, boolean hasBody) {
        int streamId = nextStreamId;
        nextStreamId += 2; // Client streams are odd numbers

        activeStreams.put(streamId, request);
        currentStream = request;

        if (negotiatedVersion == HTTPVersion.HTTP_2_0) {
            sendHTTP2Request(request, streamId, hasBody);
        } else {
            sendHTTP11Request(request, hasBody);
        }
    }

    /**
     * Sends request body data. Called by HTTPStream.
     *
     * @param request the request
     * @param data the body data
     * @return the number of bytes consumed
     */
    int sendRequestBody(HTTPStream request, ByteBuffer data) {
        if (negotiatedVersion == HTTPVersion.HTTP_2_0) {
            return sendHTTP2Data(request, data);
        } else {
            return sendHTTP11Data(request, data);
        }
    }

    /**
     * Ends the request body. Called by HTTPStream.
     *
     * @param request the request
     */
    void endRequestBody(HTTPStream request) {
        if (negotiatedVersion == HTTPVersion.HTTP_2_0) {
            endHTTP2Data(request);
        } else {
            endHTTP11Data(request);
        }
    }

    /**
     * Cancels a request. Called by HTTPStream.
     *
     * @param request the request to cancel
     */
    void cancelRequest(HTTPStream request) {
        // Find and remove the request
        Integer streamId = null;
        for (Map.Entry<Integer, HTTPStream> entry : activeStreams.entrySet()) {
            if (entry.getValue() == request) {
                streamId = entry.getKey();
                break;
            }
        }

        if (streamId != null) {
            activeStreams.remove(streamId);

            if (negotiatedVersion == HTTPVersion.HTTP_2_0) {
                // Send RST_STREAM
                sendHTTP2Reset(streamId);
            }

            // Notify handler
            HTTPResponseHandler responseHandler = request.getHandler();
            if (responseHandler != null) {
                responseHandler.failed(new java.util.concurrent.CancellationException("Request cancelled"));
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP/1.1 implementation
    // ─────────────────────────────────────────────────────────────────────────

    private void sendHTTP11Request(HTTPStream request, boolean hasBody) {
        StringBuilder sb = new StringBuilder();

        // Request line
        sb.append(request.getMethod());
        sb.append(" ");
        sb.append(request.getPath());
        sb.append(" HTTP/1.1\r\n");

        // Host header (required for HTTP/1.1)
        String hostHeader = host;
        if ((secure && port != 443) || (!secure && port != 80)) {
            hostHeader = hostHeader + ":" + port;
        }
        sb.append("Host: ");
        sb.append(hostHeader);
        sb.append("\r\n");

        // User headers
        Headers headers = request.getHeaders();
        for (Header header : headers) {
            sb.append(header.getName());
            sb.append(": ");
            sb.append(header.getValue());
            sb.append("\r\n");
        }

        // h2c upgrade: Add upgrade headers with first request on non-TLS connection
        boolean attemptingH2cUpgrade = false;
        if (h2cUpgradeEnabled && !h2cUpgradeAttempted && !h2cUpgradeInFlight && !secure) {
            // Add h2c upgrade headers
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
            // Connection keep-alive (only if not doing h2c upgrade)
            sb.append("Connection: keep-alive\r\n");
        }

        // For HTTP/1.1 with body but no Content-Length, add Transfer-Encoding: chunked
        boolean usingChunked = false;
        if (hasBody && !headers.containsName("Content-Length") && !headers.containsName("Transfer-Encoding")) {
            sb.append("Transfer-Encoding: chunked\r\n");
            usingChunked = true;
            // Also add to request headers so sendHTTP11Data knows to use chunked format
            request.getHeaders().add("Transfer-Encoding", "chunked");
        }

        // End of headers
        sb.append("\r\n");

        // Send
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        send(ByteBuffer.wrap(bytes));

        // Prepare to receive response
        parseState = ParseState.STATUS_LINE;
        responseStatus = null;
        responseHeaders = new Headers();
        contentLength = -1;
        bytesReceived = 0;
        chunkedEncoding = false;

        if (attemptingH2cUpgrade) {
            logger.fine("Sent HTTP/1.1 request with h2c upgrade: " + request.getMethod() + " " + request.getPath());
        } else {
            logger.fine("Sent HTTP/1.1 request: " + request.getMethod() + " " + request.getPath());
        }
    }

    /**
     * Creates the value for the HTTP2-Settings header for h2c upgrade.
     * This is the base64url-encoded payload of an HTTP/2 SETTINGS frame.
     */
    private String createHTTP2SettingsHeaderValue() {
        // Create a SETTINGS frame with default client settings
        // We only include non-default settings to keep the header small
        // Default settings are defined in RFC 7540 Section 6.5.2
        
        // Allocate buffer for settings payload (6 bytes per setting)
        // We'll include: ENABLE_PUSH=0 (clients typically disable server push)
        ByteBuffer payload = ByteBuffer.allocate(6);
        
        // SETTINGS_ENABLE_PUSH = 0x2, value = 0 (disable server push for client)
        payload.put((byte) 0x00);
        payload.put((byte) 0x02); // SETTINGS_ENABLE_PUSH
        payload.put((byte) 0x00);
        payload.put((byte) 0x00);
        payload.put((byte) 0x00);
        payload.put((byte) 0x00); // value = 0
        
        payload.flip();
        byte[] settingsBytes = new byte[payload.remaining()];
        payload.get(settingsBytes);
        
        // Base64url encode (no padding)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(settingsBytes);
    }

    private int sendHTTP11Data(HTTPStream request, ByteBuffer data) {
        int bytes = data.remaining();

        // For chunked encoding, wrap in chunk format
        if (request.getHeaders().containsName("Transfer-Encoding")) {
            String chunkHeader = Integer.toHexString(bytes) + "\r\n";
            send(ByteBuffer.wrap(chunkHeader.getBytes(StandardCharsets.US_ASCII)));
            send(data);
            send(ByteBuffer.wrap("\r\n".getBytes(StandardCharsets.US_ASCII)));
        } else {
            send(data);
        }

        return bytes;
    }

    private void endHTTP11Data(HTTPStream request) {
        // For chunked encoding, send final chunk
        if (request.getHeaders().containsName("Transfer-Encoding")) {
            send(ByteBuffer.wrap("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP/2 Request Sending
    // ─────────────────────────────────────────────────────────────────────────

    private void sendHTTP2Request(HTTPStream request, int streamId, boolean hasBody) {
        try {
            // Build headers block
            ByteBuffer headerBlock = encodeRequestHeaders(request);

            // Send HEADERS frame on SelectorLoop thread
            final int fStreamId = streamId;
            final boolean fHasBody = hasBody;
            getSelectorLoop().invokeLater(new SendHeadersTask(fStreamId, headerBlock, fHasBody, request));
        } catch (IOException e) {
            logger.log(Level.WARNING, "Error encoding HTTP/2 request headers", e);
            HTTPResponseHandler responseHandler = request.getHandler();
            if (responseHandler != null) {
                try {
                    responseHandler.failed(e);
                } catch (Exception ex) {
                    logger.log(Level.WARNING, "Error in response handler", ex);
                }
            }
            activeStreams.remove(streamId);
        }
    }

    private int sendHTTP2Data(HTTPStream request, ByteBuffer data) {
        int streamId = findStreamId(request);
        if (streamId < 0) {
            logger.warning(L10N.getString("warn.unknown_stream_data"));
            return 0;
        }

        // Calculate bytes to write and copy buffer for async dispatch
        int written = data.remaining();
        if (written == 0) {
            return 0;
        }
        
        ByteBuffer copy = ByteBuffer.allocate(written);
        copy.put(data);
        copy.flip();
        
        final int fStreamId = streamId;
        getSelectorLoop().invokeLater(new SendDataTask(fStreamId, copy, false));
        
        return written;
    }

    private void endHTTP2Data(HTTPStream request) {
        int streamId = findStreamId(request);
        if (streamId < 0) {
            return;
        }

        final int fStreamId = streamId;
        getSelectorLoop().invokeLater(new SendDataTask(fStreamId, ByteBuffer.allocate(0), true));
    }

    private void sendHTTP2Reset(int streamId) {
        sendRstStream(streamId, H2FrameHandler.ERROR_CANCEL);
    }

    private ByteBuffer encodeRequestHeaders(HTTPStream request) throws IOException {
        // Build header list with pseudo-headers first
        java.util.List<Header> headerList = new java.util.ArrayList<Header>();

        // Pseudo-headers (must come first)
        headerList.add(new Header(":method", request.getMethod()));
        headerList.add(new Header(":scheme", secure ? "https" : "http"));
        headerList.add(new Header(":authority", host + ":" + port));
        headerList.add(new Header(":path", request.getPath()));

        // Regular headers
        Headers headers = request.getHeaders();
        if (headers != null) {
            for (Header header : headers) {
                String name = header.getName().toLowerCase();
                // Skip connection-specific headers
                if ("connection".equals(name) || "transfer-encoding".equals(name) ||
                    "upgrade".equals(name) || "host".equals(name)) {
                    continue;
                }
                headerList.add(new Header(name, header.getValue()));
            }
        }

        // Encode headers
        ByteBuffer buffer = ByteBuffer.allocate(headerTableSize);
        boolean success = false;

        while (!success) {
            try {
                buffer.clear();
                hpackEncoder.encode(buffer, headerList);
                success = true;
            } catch (BufferOverflowException e) {
                // Grow buffer and retry
                buffer = ByteBuffer.allocate(buffer.capacity() * 2);
            }
        }

        buffer.flip();
        return buffer;
    }

    private int findStreamId(HTTPStream request) {
        for (Map.Entry<Integer, HTTPStream> entry : activeStreams.entrySet()) {
            if (entry.getValue() == request) {
                return entry.getKey();
            }
        }
        return -1;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Connection lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void connected() {
        super.connected();

        // For non-TLS connections, we're ready immediately
        if (!secure) {
            open = true;
            
            // Check for HTTP/2 with prior knowledge
            if (h2WithPriorKnowledge) {
                // Skip h2c upgrade - send HTTP/2 preface directly
                negotiatedVersion = HTTPVersion.HTTP_2_0;
                initializeHTTP2();
                sendConnectionPreface();
                parseState = ParseState.H2C_UPGRADE_PENDING; // Wait for server's SETTINGS
                logger.fine("HTTP/2 connection established to " + host + ":" + port + " (prior knowledge)");
            } else {
            negotiatedVersion = HTTPVersion.HTTP_1_1;
            if (h2cUpgradeEnabled) {
                    logger.fine("HTTP/1.1 connection established to " + host + ":" + port + ", will attempt h2c upgrade");
            } else {
                logger.fine("HTTP/1.1 connection established to " + host + ":" + port);
            }
        }

        // Notify handler
        if (handler != null) {
            handler.onConnected(createConnectionInfo());
            }
        } else {
            // For TLS connections, initiate the handshake
            // The connection will become 'open' after handshakeComplete()
            logger.fine("TCP connected to " + host + ":" + port + ", initiating TLS handshake");
            initiateClientTLSHandshake();

            // Notify handler of TCP connect (TLS not yet complete)
            if (handler != null) {
                handler.onConnected(createConnectionInfo());
            }
        }
    }

    @Override
    protected void handshakeComplete(String protocol) {
        super.handshakeComplete(protocol);

        // Connection is now ready for requests
        open = true;

        // Determine version from ALPN
        if ("h2".equals(protocol)) {
            negotiatedVersion = HTTPVersion.HTTP_2_0;
            initializeHTTP2();
            sendConnectionPreface();
            parseState = ParseState.H2C_UPGRADE_PENDING; // Wait for server's SETTINGS frame
            logger.fine("HTTP/2 (ALPN) connection established to " + host + ":" + port);
        } else {
            negotiatedVersion = HTTPVersion.HTTP_1_1;
            logger.fine("HTTP/1.1 connection established to " + host + ":" + port);
        }

        // Notify handler
        if (handler != null) {
            handler.onTLSStarted(createTLSInfo());
        }
    }

    /**
     * Completes the h2c upgrade after receiving 101 Switching Protocols.
     * Sends the HTTP/2 connection preface and switches to HTTP/2 mode.
     */
    private void completeH2cUpgrade() {
        // Switch to HTTP/2 mode
        negotiatedVersion = HTTPVersion.HTTP_2_0;
        h2cUpgradeInFlight = false;
        
        // Initialize HTTP/2 and send connection preface
        initializeHTTP2();
        sendConnectionPreface();
        
        // The upgrade request becomes stream 1
        // Per RFC 7540 Section 3.2, the request that triggered the upgrade
        // has an implicit stream identifier of 1
        if (h2cUpgradeRequest != null) {
            activeStreams.put(1, h2cUpgradeRequest);
            nextStreamId = 3; // Next client-initiated stream is 3
        }
        h2cUpgradeRequest = null;
        
        // Now we're in HTTP/2 mode, wait for server's SETTINGS frame
        parseState = ParseState.H2C_UPGRADE_PENDING;
        
        logger.fine("HTTP/2 connection preface sent, h2c upgrade complete to " + host + ":" + port);
    }

    @Override
    protected void disconnected() throws IOException {
        open = false;

        // Fail any active requests
        Exception disconnectException = new IOException(L10N.getString("err.connection_disconnected"));
        for (HTTPStream request : activeStreams.values()) {
            HTTPResponseHandler responseHandler = request.getHandler();
            if (responseHandler != null) {
                try {
                    responseHandler.failed(disconnectException);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error notifying handler of disconnect", e);
                }
            }
        }
        activeStreams.clear();

        // Notify handler
        if (handler != null) {
            handler.onDisconnected();
        }
    }

    /**
     * Called when the connection attempt fails.
     *
     * @param cause the exception that caused the failure
     */
    @Override
    public void finishConnectFailed(IOException cause) {
        open = false;

        // Fail any active requests - this ensures errors reach response handlers
        // even when no HTTPClientHandler is registered
        for (HTTPStream request : activeStreams.values()) {
            HTTPResponseHandler responseHandler = request.getHandler();
            if (responseHandler != null) {
                try {
                    responseHandler.failed(cause);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error notifying handler of connection error", e);
                }
            }
        }
        activeStreams.clear();

        // Notify connection handler (if any)
        if (handler != null) {
            handler.onError(cause);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Response parsing
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void receive(ByteBuffer data) {
        // Append to parse buffer
        if (parseBuffer.remaining() < data.remaining()) {
            // Expand buffer
            int newSize = parseBuffer.position() + data.remaining() + 4096;
            ByteBuffer newBuffer = ByteBuffer.allocate(newSize);
            parseBuffer.flip();
            newBuffer.put(parseBuffer);
            parseBuffer = newBuffer;
        }
        parseBuffer.put(data);

        // Process based on protocol
        if (negotiatedVersion == HTTPVersion.HTTP_2_0 || 
            parseState == ParseState.H2C_UPGRADE_PENDING ||
            parseState == ParseState.HTTP2) {
            processHTTP2Response();
        } else {
            processHTTP11Response();
        }
    }

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
                        // After h2c upgrade, switch to HTTP/2 processing
                        parseBuffer.compact();
                        processHTTP2Response();
                        return;
                }
            }
        } finally {
            // Always compact to discard consumed data and prepare for next append
            // (except IDLE which doesn't expect more data on this connection)
            if (parseState != ParseState.IDLE) {
                parseBuffer.compact();
            }
        }
    }

    private boolean parseStatusLine() {
        int lineEnd = findCRLF(parseBuffer);
        if (lineEnd < 0) {
            return false;
        }

        // Read status line
        byte[] lineBytes = new byte[lineEnd];
        parseBuffer.get(lineBytes);
        parseBuffer.get(); // CR
        parseBuffer.get(); // LF

        String line = new String(lineBytes, StandardCharsets.UTF_8);

        // Parse: HTTP/1.1 200 OK
        int firstSpace = line.indexOf(' ');
        int secondSpace = line.indexOf(' ', firstSpace + 1);

        if (firstSpace < 0) {
            logger.warning(MessageFormat.format(L10N.getString("warn.invalid_status_line"), line));
            responseStatus = HTTPStatus.UNKNOWN;
        } else {
            int statusCode;
            try {
                String codeStr;
                if (secondSpace > 0) {
                    codeStr = line.substring(firstSpace + 1, secondSpace);
                } else {
                    codeStr = line.substring(firstSpace + 1);
                }
                statusCode = Integer.parseInt(codeStr.trim());
                responseStatus = HTTPStatus.fromCode(statusCode);
            } catch (NumberFormatException e) {
                responseStatus = HTTPStatus.UNKNOWN;
            }
        }

        parseState = ParseState.HEADERS;
        return true;
    }

    private boolean parseHeaders() {
        while (true) {
            int lineEnd = findCRLF(parseBuffer);
            if (lineEnd < 0) {
                return false;
            }

            if (lineEnd == 0) {
                // Empty line = end of headers
                parseBuffer.get(); // CR
                parseBuffer.get(); // LF

                // Handle h2c upgrade response (101 Switching Protocols)
                if (h2cUpgradeInFlight && responseStatus == HTTPStatus.SWITCHING_PROTOCOLS) {
                    String upgrade = responseHeaders.getValue("upgrade");
                    if (upgrade != null && upgrade.equalsIgnoreCase("h2c")) {
                        // Server accepted h2c upgrade
                        logger.fine("h2c upgrade accepted, switching to HTTP/2");
                        
                        // The 101 is just the upgrade acknowledgment.
                        // The actual response to the request will come over HTTP/2 on stream 1.
                        // Do NOT notify the handler here - the response comes over HTTP/2.
                        
                        completeH2cUpgrade();
                        return true;
                    } else {
                        // Not h2c upgrade, treat as error
                        logger.warning(L10N.getString("warn.unexpected_101_response"));
                        h2cUpgradeInFlight = false;
                    }
                } else if (h2cUpgradeInFlight) {
                    // Server didn't upgrade - that's fine, continue with HTTP/1.1
                    logger.fine("Server declined h2c upgrade, continuing with HTTP/1.1");
                    h2cUpgradeInFlight = false;
                    h2cUpgradeRequest = null;
                }

                // Handle authentication challenge (401)
                if (responseStatus == HTTPStatus.UNAUTHORIZED &&
                    username != null && password != null && !authRetryPending) {

                    String wwwAuth = responseHeaders.getValue("www-authenticate");
                    if (wwwAuth != null && attemptAuthentication(wwwAuth)) {
                        // Auth retry initiated, don't notify handler yet
                        // The body (if any) will be discarded and the request resent
                        discardResponseBody();
                        return true;
                    }
                }

                // Reset auth retry flag after response is processed
                authRetryPending = false;

                // Notify handler
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

                    // Send headers
                    for (Header header : responseHeaders) {
                        responseHandler.header(header.getName(), header.getValue());
                    }
                }

                // Determine body handling
                String transferEncoding = responseHeaders.getValue("transfer-encoding");
                String contentLengthStr = responseHeaders.getValue("content-length");

                if (transferEncoding != null && transferEncoding.toLowerCase().contains("chunked")) {
                    chunkedEncoding = true;
                    parseState = ParseState.CHUNK_SIZE;
                    if (responseHandler != null) {
                        responseHandler.startResponseBody();
                    }
                } else if (contentLengthStr != null) {
                    contentLength = Long.parseLong(contentLengthStr.trim());
                    if (contentLength > 0) {
                        parseState = ParseState.BODY;
                        if (responseHandler != null) {
                            responseHandler.startResponseBody();
                        }
                    } else {
                        completeResponse();
                    }
                } else if (responseStatus == HTTPStatus.NO_CONTENT || responseStatus == HTTPStatus.NOT_MODIFIED) {
                    completeResponse();
                } else {
                    // Read until connection close
                    contentLength = -1;
                    parseState = ParseState.BODY;
                    if (responseHandler != null) {
                        responseHandler.startResponseBody();
                    }
                }

                return true;
            }

            // Parse header
            byte[] lineBytes = new byte[lineEnd];
            parseBuffer.get(lineBytes);
            parseBuffer.get(); // CR
            parseBuffer.get(); // LF

            String line = new String(lineBytes, StandardCharsets.UTF_8);
            int colonPos = line.indexOf(':');
            if (colonPos > 0) {
                String name = line.substring(0, colonPos).trim();
                String value = line.substring(colonPos + 1).trim();
                responseHeaders.add(name, value);
            }
        }
    }

    private boolean parseBody() {
        HTTPResponseHandler responseHandler = null;
        if (currentStream != null) {
            responseHandler = currentStream.getHandler();
        }

        if (contentLength >= 0) {
            // Fixed length body
            long remaining = contentLength - bytesReceived;
            int available = parseBuffer.remaining();
            int toRead = (int) Math.min(remaining, available);

            if (toRead > 0 && responseHandler != null) {
                ByteBuffer bodyData = ByteBuffer.allocate(toRead);
                int oldLimit = parseBuffer.limit();
                parseBuffer.limit(parseBuffer.position() + toRead);
                bodyData.put(parseBuffer);
                parseBuffer.limit(oldLimit);
                bodyData.flip();

                responseHandler.responseBodyContent(bodyData);
                bytesReceived += toRead;
            }

            if (bytesReceived >= contentLength) {
                if (responseHandler != null) {
                    responseHandler.endResponseBody();
                }
                completeResponse();
            }
        } else {
            // Unknown length - deliver all available
            if (parseBuffer.hasRemaining() && responseHandler != null) {
                ByteBuffer bodyData = ByteBuffer.allocate(parseBuffer.remaining());
                bodyData.put(parseBuffer);
                bodyData.flip();
                responseHandler.responseBodyContent(bodyData);
            }
        }

        return true;
    }

    private boolean parseChunkSize() {
        int lineEnd = findCRLF(parseBuffer);
        if (lineEnd < 0) {
            return false;
        }

        byte[] lineBytes = new byte[lineEnd];
        parseBuffer.get(lineBytes);
        parseBuffer.get(); // CR
        parseBuffer.get(); // LF

        String line = new String(lineBytes, StandardCharsets.US_ASCII);
        // Remove chunk extensions if any
        int semi = line.indexOf(';');
        if (semi >= 0) {
            line = line.substring(0, semi);
        }

        try {
            contentLength = Long.parseLong(line.trim(), 16);
        } catch (NumberFormatException e) {
            logger.warning(MessageFormat.format(L10N.getString("warn.invalid_chunk_size"), line));
            contentLength = 0;
        }

        if (contentLength == 0) {
            // Final chunk
            parseState = ParseState.CHUNK_TRAILER;
        } else {
            bytesReceived = 0;
            parseState = ParseState.CHUNK_DATA;
        }

        return true;
    }

    private boolean parseChunkData() {
        HTTPResponseHandler responseHandler = null;
        if (currentStream != null) {
            responseHandler = currentStream.getHandler();
        }

        long remaining = contentLength - bytesReceived;
        int available = parseBuffer.remaining();
        int toRead = (int) Math.min(remaining, available);

        if (toRead > 0 && responseHandler != null) {
            ByteBuffer bodyData = ByteBuffer.allocate(toRead);
            int oldLimit = parseBuffer.limit();
            parseBuffer.limit(parseBuffer.position() + toRead);
            bodyData.put(parseBuffer);
            parseBuffer.limit(oldLimit);
            bodyData.flip();

            responseHandler.responseBodyContent(bodyData);
            bytesReceived += toRead;
        }

        if (bytesReceived >= contentLength) {
            // Skip trailing CRLF
            if (parseBuffer.remaining() >= 2) {
                parseBuffer.get(); // CR
                parseBuffer.get(); // LF
                parseState = ParseState.CHUNK_SIZE;
                return true;
            }
            return false;
        }

        return true;
    }

    private boolean parseChunkTrailer() {
        // Read trailer headers until empty line
        int lineEnd = findCRLF(parseBuffer);
        if (lineEnd < 0) {
            return false;
        }

        HTTPResponseHandler responseHandler = null;
        if (currentStream != null) {
            responseHandler = currentStream.getHandler();
        }

        if (lineEnd == 0) {
            // Empty line = end of trailers
            parseBuffer.get(); // CR
            parseBuffer.get(); // LF

            if (responseHandler != null) {
                responseHandler.endResponseBody();
            }
            completeResponse();
            return true;
        }

        // Parse trailer header
        byte[] lineBytes = new byte[lineEnd];
        parseBuffer.get(lineBytes);
        parseBuffer.get(); // CR
        parseBuffer.get(); // LF

        String line = new String(lineBytes, StandardCharsets.UTF_8);
        int colonPos = line.indexOf(':');
        if (colonPos > 0 && responseHandler != null) {
            String name = line.substring(0, colonPos).trim();
            String value = line.substring(colonPos + 1).trim();
            responseHandler.header(name, value);
        }

        return true;
    }

    private void completeResponse() {
        HTTPResponseHandler responseHandler = null;
        if (currentStream != null) {
            responseHandler = currentStream.getHandler();
        }

        if (responseHandler != null) {
            responseHandler.close();
        }

        // Remove from active requests
        Integer streamId = null;
        for (Map.Entry<Integer, HTTPStream> entry : activeStreams.entrySet()) {
            if (entry.getValue() == currentStream) {
                streamId = entry.getKey();
                break;
            }
        }
        if (streamId != null) {
            activeStreams.remove(streamId);
        }

        currentStream = null;
        parseState = ParseState.IDLE;
        parseBuffer.clear();

        logger.fine("Response complete");
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

    /**
     * Attempts to authenticate based on the WWW-Authenticate header.
     *
     * @param wwwAuthenticate the WWW-Authenticate header value
     * @return true if authentication retry was initiated
     */
    private boolean attemptAuthentication(String wwwAuthenticate) {
        if (currentStream == null) {
            return false;
        }

        String scheme = parseAuthScheme(wwwAuthenticate);
        String authHeader = null;

        if ("basic".equalsIgnoreCase(scheme)) {
            authHeader = computeBasicAuth();
        } else if ("digest".equalsIgnoreCase(scheme)) {
            authHeader = computeDigestAuth(wwwAuthenticate, currentStream.getMethod(), currentStream.getPath());
        }

        if (authHeader != null) {
            // Create a new stream with the authorization header
            authRetryPending = true;

            HTTPStream retryStream = new HTTPStream(this, currentStream.getMethod(), currentStream.getPath());

            // Copy original headers
            for (Header h : currentStream.getHeaders()) {
                retryStream.header(h.getName(), h.getValue());
            }

            // Add authorization header
            retryStream.header("Authorization", authHeader);

            // Transfer handler to retry stream
            HTTPResponseHandler responseHandler = currentStream.getHandler();

            // Remove old stream
            Integer oldStreamId = null;
            for (Map.Entry<Integer, HTTPStream> entry : activeStreams.entrySet()) {
                if (entry.getValue() == currentStream) {
                    oldStreamId = entry.getKey();
                    break;
                }
            }
            if (oldStreamId != null) {
                activeStreams.remove(oldStreamId);
            }

            // Send the retry request
            currentStream = null;
            parseState = ParseState.IDLE;
            parseBuffer.clear();

            // The retry stream sends itself
            retryStream.send(responseHandler);

            logger.fine("Authentication retry initiated with " + scheme);
            return true;
        }

        return false;
    }

    /**
     * Parses the authentication scheme from a WWW-Authenticate header.
     */
    private String parseAuthScheme(String wwwAuthenticate) {
        int space = wwwAuthenticate.indexOf(' ');
        if (space > 0) {
            return wwwAuthenticate.substring(0, space);
        }
        return wwwAuthenticate;
    }

    /**
     * Computes the Basic authentication header value.
     */
    private String computeBasicAuth() {
        String credentials = username + ":" + password;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    /**
     * Computes the Digest authentication header value.
     */
    private String computeDigestAuth(String wwwAuthenticate, String method, String uri) {
        // Parse digest challenge
        String realm = parseDirective(wwwAuthenticate, "realm");
        String nonce = parseDirective(wwwAuthenticate, "nonce");
        String qop = parseDirective(wwwAuthenticate, "qop");
        String opaque = parseDirective(wwwAuthenticate, "opaque");
        String algorithm = parseDirective(wwwAuthenticate, "algorithm");

        if (realm == null || nonce == null) {
            logger.warning(L10N.getString("warn.invalid_digest_challenge"));
            return null;
        }

        // Default to MD5
        if (algorithm == null) {
            algorithm = "MD5";
        }

        try {
            MessageDigest md = MessageDigest.getInstance(algorithm.replace("-sess", ""));

            // Compute HA1 = MD5(username:realm:password)
            String ha1Input = username + ":" + realm + ":" + password;
            String ha1 = hex(md.digest(ha1Input.getBytes(StandardCharsets.UTF_8)));

            // For algorithm ending in -sess, HA1 = MD5(HA1:nonce:cnonce)
            String cnonce = null;
            if (algorithm.endsWith("-sess")) {
                cnonce = generateCnonce();
                md.reset();
                String sessInput = ha1 + ":" + nonce + ":" + cnonce;
                ha1 = hex(md.digest(sessInput.getBytes(StandardCharsets.UTF_8)));
            }

            // Compute HA2 = MD5(method:uri)
            md.reset();
            String ha2Input = method + ":" + uri;
            String ha2 = hex(md.digest(ha2Input.getBytes(StandardCharsets.UTF_8)));

            // Compute response
            String response;
            String nc = "00000001";
            if (cnonce == null) {
                cnonce = generateCnonce();
            }

            if (qop != null && (qop.contains("auth") || qop.contains("auth-int"))) {
                // response = MD5(HA1:nonce:nc:cnonce:qop:HA2)
                md.reset();
                String responseInput = ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2;
                response = hex(md.digest(responseInput.getBytes(StandardCharsets.UTF_8)));
            } else {
                // response = MD5(HA1:nonce:HA2)
                md.reset();
                String responseInput = ha1 + ":" + nonce + ":" + ha2;
                response = hex(md.digest(responseInput.getBytes(StandardCharsets.UTF_8)));
            }

            // Build authorization header
            StringBuilder auth = new StringBuilder("Digest ");
            auth.append("username=\"");
            auth.append(username);
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
            if (algorithm != null && !"MD5".equals(algorithm)) {
                auth.append(", algorithm=");
                auth.append(algorithm);
            }

            return auth.toString();

        } catch (NoSuchAlgorithmException e) {
            logger.warning(MessageFormat.format(L10N.getString("warn.unsupported_digest_algorithm"), algorithm));
            return null;
        }
    }

    /**
     * Parses a directive value from an authentication header.
     */
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
            // Quoted value
            pos++;
            int end = header.indexOf('"', pos);
            if (end > pos) {
                return header.substring(pos, end);
            }
            return null;
        } else {
            // Unquoted value
            int end = pos;
            while (end < header.length() && header.charAt(end) != ',' && header.charAt(end) != ' ') {
                end++;
            }
            return header.substring(pos, end);
        }
    }

    /**
     * Generates a client nonce for Digest authentication.
     */
    private String generateCnonce() {
        byte[] bytes = new byte[8];
        new Random().nextBytes(bytes);
        return hex(bytes);
    }

    /**
     * Converts bytes to hexadecimal string.
     */
    private String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            byte b = bytes[i];
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /**
     * Discards the response body (used when retrying with authentication).
     */
    private void discardResponseBody() {
        // For 401 responses, we just skip to complete and reset
        // The body (if any) will be ignored as we reset parsing state
        String transferEncoding = responseHeaders.getValue("transfer-encoding");
        String contentLengthStr = responseHeaders.getValue("content-length");

        if (transferEncoding != null && transferEncoding.toLowerCase().contains("chunked")) {
            // For chunked, we'd need to read chunks - for simplicity, just reset
            // A more robust implementation would consume the chunks
        } else if (contentLengthStr != null) {
            contentLength = Long.parseLong(contentLengthStr.trim());
            // Skip content-length bytes - for simplicity, assume small 401 bodies
        }

        // Reset parsing state - the retry request will start fresh
        parseState = ParseState.IDLE;
        parseBuffer.clear();
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

    /**
     * Initializes HTTP/2 parser, writer, and HPACK codec.
     */
    private void initializeHTTP2() {
        h2Parser = new H2Parser(this);
        h2Parser.setMaxFrameSize(maxFrameSize);
        h2Writer = new H2Writer(new ConnectionChannel());
        hpackDecoder = new Decoder(headerTableSize);
        hpackEncoder = new Encoder(headerTableSize, maxHeaderListSize);
    }

    /**
     * Sends the HTTP/2 connection preface (PRI string + SETTINGS frame).
     */
    private void sendConnectionPreface() {
        // Send PRI preface
        byte[] prefaceBytes = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        send(ByteBuffer.wrap(prefaceBytes));

        // Send empty SETTINGS frame on SelectorLoop thread
        getSelectorLoop().invokeLater(new SendSettingsTask());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // H2FrameHandler Implementation
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void dataFrameReceived(int streamId, boolean endStream, ByteBuffer data) {
        HTTPStream stream = activeStreams.get(streamId);
        if (stream == null) {
            logger.warning(MessageFormat.format(L10N.getString("warn.data_for_unknown_stream"), streamId));
            sendRstStream(streamId, H2FrameHandler.ERROR_STREAM_CLOSED);
            return;
        }

        HTTPResponseHandler responseHandler = stream.getHandler();
        if (responseHandler != null) {
            try {
                responseHandler.responseBodyContent(data);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error in response handler", e);
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
            logger.warning(MessageFormat.format(L10N.getString("warn.headers_for_unknown_stream"), streamId));
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

    @Override
    public void priorityFrameReceived(int streamId, int streamDependency,
            boolean exclusive, int weight) {
        // Client doesn't need to act on PRIORITY frames from server
    }

    @Override
    public void rstStreamFrameReceived(int streamId, int errorCode) {
        HTTPStream stream = activeStreams.remove(streamId);
        if (stream != null) {
            HTTPResponseHandler responseHandler = stream.getHandler();
            if (responseHandler != null) {
                try {
                    String errorName = H2FrameHandler.errorToString(errorCode);
                    String msg = MessageFormat.format(L10N.getString("err.stream_reset"), errorName);
                    responseHandler.failed(new IOException(msg));
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error in response handler", e);
                }
            }
        }
    }

    @Override
    public void settingsFrameReceived(boolean ack, Map<Integer, Integer> settings) {
        // Transition from H2C_UPGRADE_PENDING to HTTP2 on first SETTINGS
        if (parseState == ParseState.H2C_UPGRADE_PENDING) {
            parseState = ParseState.HTTP2;
            logger.fine("HTTP/2 handshake complete, ready for requests");
        }
        
        if (!ack) {
            // Apply server settings
            for (Map.Entry<Integer, Integer> entry : settings.entrySet()) {
                int identifier = entry.getKey();
                int value = entry.getValue();
                switch (identifier) {
                    case H2FrameHandler.SETTINGS_HEADER_TABLE_SIZE:
                        headerTableSize = value;
                        break;
                    case H2FrameHandler.SETTINGS_ENABLE_PUSH:
                        serverPushEnabled = (value == 1);
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
            if (hpackEncoder != null) {
                hpackEncoder.setHeaderTableSize(headerTableSize);
                hpackEncoder.setMaxHeaderListSize(maxHeaderListSize);
            }
            // Send SETTINGS ACK
            sendSettingsAck();
        }
    }

    @Override
    public void pushPromiseFrameReceived(int streamId, int promisedStreamId,
            boolean endHeaders, ByteBuffer headerBlockFragment) {
        if (!serverPushEnabled) {
            sendRstStream(promisedStreamId, H2FrameHandler.ERROR_REFUSED_STREAM);
            return;
        }
        // TODO: Handle server push - create a new stream for the promised response
        logger.fine("Received PUSH_PROMISE for stream " + promisedStreamId);
    }

    @Override
    public void pingFrameReceived(boolean ack, long opaqueData) {
        if (!ack) {
            // Respond to PING with ACK - already on SelectorLoop thread
            try {
                h2Writer.writePing(opaqueData, true);
                h2Writer.flush();
            } catch (IOException e) {
                logger.log(Level.WARNING, "Error sending PING ACK", e);
            }
        }
    }

    @Override
    public void goawayFrameReceived(int lastStreamId, int errorCode, ByteBuffer debugData) {
        logger.info(MessageFormat.format(L10N.getString("info.goaway_received"), 
            lastStreamId, H2FrameHandler.errorToString(errorCode)));

        // Fail streams with ID > lastStreamId
        for (Map.Entry<Integer, HTTPStream> entry : activeStreams.entrySet()) {
            if (entry.getKey() > lastStreamId) {
                HTTPStream stream = activeStreams.remove(entry.getKey());
                if (stream != null) {
                    HTTPResponseHandler responseHandler = stream.getHandler();
                    if (responseHandler != null) {
                        try {
                            responseHandler.failed(new IOException(L10N.getString("err.connection_closed_by_server")));
                        } catch (Exception e) {
                            logger.log(Level.WARNING, "Error in response handler", e);
                        }
                    }
                }
            }
        }

        // Close connection after GOAWAY
        close();
    }

    @Override
    public void windowUpdateFrameReceived(int streamId, int windowSizeIncrement) {
        // TODO: Implement flow control
    }

    @Override
    public void continuationFrameReceived(int streamId, boolean endHeaders,
            ByteBuffer headerBlockFragment) {
        HTTPStream stream = activeStreams.get(streamId);
        if (stream == null) {
            logger.warning(MessageFormat.format(L10N.getString("warn.continuation_for_unknown_stream"), streamId));
            return;
        }

        appendHeaderBlockFragment(streamId, headerBlockFragment);

        if (endHeaders) {
            processHeaders(stream, streamId, false);
            continuationStreamId = 0;
        }
    }

    @Override
    public void frameError(int errorCode, int streamId, String message) {
        logger.warning(MessageFormat.format(L10N.getString("warn.h2_frame_error"), 
            message, H2FrameHandler.errorToString(errorCode) + ", stream=" + streamId));

        if (streamId == 0) {
            // Connection error
            sendGoaway(errorCode);
        } else {
            // Stream error
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
            // Grow buffer
            int newCapacity = headerBlockBuffer.capacity() * 2 + fragment.remaining();
            ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);
            headerBlockBuffer.flip();
            newBuffer.put(headerBlockBuffer);
            headerBlockBuffer = newBuffer;
        }
        headerBlockBuffer.put(fragment);
    }

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
            logger.log(Level.WARNING, "HPACK decode error", e);
            sendGoaway(H2FrameHandler.ERROR_COMPRESSION_ERROR);
            return;
        } finally {
            headerBlockBuffer = null;
        }

        // Extract status from :status pseudo-header
        String statusStr = headers.getValue(":status");
        if (statusStr != null) {
            int statusCode = Integer.parseInt(statusStr);
            HTTPStatus status = HTTPStatus.fromCode(statusCode);
            HTTPResponseHandler responseHandler = stream.getHandler();
            if (responseHandler != null) {
                try {
                    // Create response object
                    HTTPResponse response = new HTTPResponse(status);
                    if (status.isSuccess()) {
                        responseHandler.ok(response);
                    } else {
                        responseHandler.error(response);
                    }

                    // Send individual headers
                    for (Header header : headers) {
                        String name = header.getName();
                        if (!name.startsWith(":")) { // Skip pseudo-headers
                            responseHandler.header(name, header.getValue());
                        }
                    }

                    // If not end of stream, body data will follow
                    if (!endStream) {
                        responseHandler.startResponseBody();
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error in response handler", e);
                }
            }
        }

        if (endStream) {
            completeStream(stream, streamId);
        }
    }

    private void completeStream(HTTPStream stream, int streamId) {
        activeStreams.remove(streamId);
        HTTPResponseHandler responseHandler = stream.getHandler();
        if (responseHandler != null) {
            try {
                responseHandler.endResponseBody();
                responseHandler.close();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error in response handler", e);
            }
        }
    }

    private void sendSettingsAck() {
        // Use invokeLater - executes immediately if already on SelectorLoop
        getSelectorLoop().invokeLater(new SendSettingsAckTask());
    }

    private void sendRstStream(int streamId, int errorCode) {
        // Use invokeLater - executes immediately if already on SelectorLoop
        getSelectorLoop().invokeLater(new SendRstStreamTask(streamId, errorCode));
    }

    private void sendGoaway(int errorCode) {
        // Calculate lastStreamId before queuing
        int lastStreamId = 0;
        for (int id : activeStreams.keySet()) {
            if (id > lastStreamId) {
                lastStreamId = id;
            }
        }
        final int fLastStreamId = lastStreamId;
        
        // Use invokeLater - executes immediately if already on SelectorLoop
        getSelectorLoop().invokeLater(new SendGoawayTask(fLastStreamId, errorCode));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WritableByteChannel Adapter for H2Writer
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Adapts the Connection's send() method to WritableByteChannel interface
     * for use with H2Writer.
     */
    private class ConnectionChannel implements WritableByteChannel {

        private boolean open = true;

        @Override
        public int write(ByteBuffer src) {
            if (!open) {
                return 0;
            }
            int written = src.remaining();
            if (written > 0) {
                // Create a copy since send() may be asynchronous
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

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP/2 Frame Sending Tasks
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Task that sends HTTP/2 HEADERS frame on the SelectorLoop thread.
     */
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
                // endStream is true only if there's no body
                h2Writer.writeHeaders(streamId, headerBlock, !hasBody, true, 0, 0, 0, false);
                h2Writer.flush();
            } catch (IOException e) {
                logger.log(Level.WARNING, "Error sending HTTP/2 request", e);
                HTTPResponseHandler responseHandler = request.getHandler();
                if (responseHandler != null) {
                    try {
                        responseHandler.failed(e);
                    } catch (Exception ex) {
                        logger.log(Level.WARNING, "Error in response handler", ex);
                    }
                }
                activeStreams.remove(streamId);
            }
        }
    }

    /**
     * Task that sends HTTP/2 DATA frame on the SelectorLoop thread.
     */
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
                h2Writer.writeData(streamId, data, endStream, 0);
                h2Writer.flush();
            } catch (IOException e) {
                String msg = endStream ? "Error ending HTTP/2 stream" : "Error sending HTTP/2 data";
                logger.log(Level.WARNING, msg, e);
            }
        }
    }

    /**
     * Task that sends HTTP/2 SETTINGS frame on the SelectorLoop thread.
     */
    private class SendSettingsTask implements Runnable {
        @Override
        public void run() {
            try {
                h2Writer.writeSettings(new LinkedHashMap<Integer, Integer>());
                h2Writer.flush();
            } catch (IOException e) {
                logger.log(Level.WARNING, "Error sending HTTP/2 connection preface", e);
                close();
            }
        }
    }

    /**
     * Task that sends HTTP/2 SETTINGS ACK frame on the SelectorLoop thread.
     */
    private class SendSettingsAckTask implements Runnable {
        @Override
        public void run() {
            try {
                h2Writer.writeSettingsAck();
                h2Writer.flush();
            } catch (IOException e) {
                logger.log(Level.WARNING, "Error sending SETTINGS ACK", e);
            }
        }
    }

    /**
     * Task that sends HTTP/2 RST_STREAM frame on the SelectorLoop thread.
     */
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
                logger.log(Level.WARNING, "Error sending RST_STREAM", e);
            }
        }
    }

    /**
     * Task that sends HTTP/2 GOAWAY frame on the SelectorLoop thread.
     */
    private class SendGoawayTask implements Runnable {
        private final int lastStreamId;
        private final int errorCode;

        SendGoawayTask(int lastStreamId, int errorCode) {
            this.lastStreamId = lastStreamId;
            this.errorCode = errorCode;
        }

        @Override
        public void run() {
            try {
                h2Writer.writeGoaway(lastStreamId, errorCode, ByteBuffer.allocate(0));
                h2Writer.flush();
            } catch (IOException e) {
                logger.log(Level.WARNING, "Error sending GOAWAY", e);
            } finally {
                close();
            }
        }
    }
}
