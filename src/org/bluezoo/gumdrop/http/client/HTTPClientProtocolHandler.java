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

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.ResourceBundle;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
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

/**
 * HTTP client endpoint handler that manages protocol-level communication.
 *
 * <p>Implements {@link ProtocolHandler} and {@link H2FrameHandler}, storing
 * an {@link Endpoint} field set in {@link #connected(Endpoint)} and delegating
 * all I/O to the endpoint.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPClientProtocolHandler implements ProtocolHandler, H2FrameHandler, HTTPClientConnectionOps {

    private static final ResourceBundle L10N =
        ResourceBundle.getBundle("org.bluezoo.gumdrop.http.client.L10N");
    private static final Logger LOGGER = Logger.getLogger(HTTPClientProtocolHandler.class.getName());

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
    private int nextStreamId = 1;

    // HTTP/2
    private H2Parser h2Parser;
    private H2Writer h2Writer;
    private Decoder hpackDecoder;
    private Encoder hpackEncoder;

    // HTTP/2 settings
    private int headerTableSize = 4096;
    private int maxConcurrentStreams = 100;
    private int initialWindowSize = 65535;
    private int maxFrameSize = 16384;
    private int maxHeaderListSize = 8192;
    private boolean serverPushEnabled = true;

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
            if (handler != null) {
                handler.onConnected(ep);
            }
        }
        // For secure connections, wait for securityEstablished()
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
        open = true;
        String alpn = info.getApplicationProtocol();
        if ("h2".equals(alpn)) {
            negotiatedVersion = HTTPVersion.HTTP_2_0;
            initializeHTTP2();
            sendConnectionPreface();
            parseState = ParseState.H2C_UPGRADE_PENDING;
            LOGGER.fine("HTTP/2 (ALPN) connection established to " + host + ":" + port);
        } else {
            negotiatedVersion = HTTPVersion.HTTP_1_1;
            LOGGER.fine("HTTP/1.1 connection established to " + host + ":" + port);
        }
        if (handler != null) {
            handler.onSecurityEstablished(info);
        }
    }

    @Override
    public void receive(ByteBuffer data) {
        // Append to parse buffer
        if (parseBuffer.remaining() < data.remaining()) {
            int newSize = parseBuffer.position() + data.remaining() + 4096;
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
        for (HTTPStream request : activeStreams.values()) {
            HTTPResponseHandler responseHandler = request.getHandler();
            if (responseHandler != null) {
                try {
                    responseHandler.failed(disconnectException);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error notifying handler of disconnect", e);
                }
            }
        }
        activeStreams.clear();

        if (handler != null) {
            handler.onDisconnected();
        }
    }

    @Override
    public void error(Exception cause) {
        open = false;

        for (HTTPStream request : activeStreams.values()) {
            HTTPResponseHandler responseHandler = request.getHandler();
            if (responseHandler != null) {
                try {
                    responseHandler.failed(cause);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error notifying handler of connection error", e);
                }
            }
        }
        activeStreams.clear();

        if (handler != null) {
            handler.onError(cause);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Extension hooks
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called when a 101 Switching Protocols response is received that is
     * not an h2c upgrade. Subclasses can override this to handle
     * protocol switches (e.g. WebSocket).
     *
     * <p>When this method returns {@code true}, the caller assumes the
     * protocol switch has been fully handled and the HTTP parsing state
     * machine will not process the response further. The subclass is
     * responsible for managing all subsequent data on the connection.
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

        Exception closeException = new IOException(L10N.getString("err.connection_closed"));
        for (HTTPStream request : activeStreams.values()) {
            HTTPResponseHandler responseHandler = request.getHandler();
            if (responseHandler != null) {
                try {
                    responseHandler.failed(closeException);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error notifying handler of close", e);
                }
            }
        }
        activeStreams.clear();

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
     * @param enabled true to use HTTP/2 with prior knowledge
     */
    public void setH2WithPriorKnowledge(boolean enabled) {
        this.h2WithPriorKnowledge = enabled;
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

    @Override
    public void sendRequest(HTTPStream request, boolean hasBody) {
        int streamId = nextStreamId;
        nextStreamId += 2;

        activeStreams.put(streamId, request);
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
                sendHTTP2Reset(streamId);
            }

            HTTPResponseHandler responseHandler = request.getHandler();
            if (responseHandler != null) {
                responseHandler.failed(new CancellationException("Request cancelled"));
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP/1.1 implementation
    // ─────────────────────────────────────────────────────────────────────────

    private void sendHTTP11Request(HTTPStream request, boolean hasBody) {
        StringBuilder sb = new StringBuilder();

        sb.append(request.getMethod());
        sb.append(" ");
        sb.append(request.getPath());
        sb.append(" HTTP/1.1\r\n");

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
            sb.append("Connection: keep-alive\r\n");
        }

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

        ByteBuffer copy = ByteBuffer.allocate(written);
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
        endpoint.getSelectorLoop().invokeLater(new SendDataTask(fStreamId, ByteBuffer.allocate(0), true));
    }

    private void sendHTTP2Reset(int streamId) {
        sendRstStream(streamId, H2FrameHandler.ERROR_CANCEL);
    }

    private ByteBuffer encodeRequestHeaders(HTTPStream request) throws IOException {
        List<Header> headerList = new ArrayList<Header>();

        headerList.add(new Header(":method", request.getMethod()));
        headerList.add(new Header(":scheme", secure ? "https" : "http"));
        headerList.add(new Header(":authority", host + ":" + port));
        headerList.add(new Header(":path", request.getPath()));

        Headers headers = request.getHeaders();
        if (headers != null) {
            for (Header header : headers) {
                String name = header.getName().toLowerCase();
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
        for (Map.Entry<Integer, HTTPStream> entry : activeStreams.entrySet()) {
            if (entry.getValue() == request) {
                return entry.getKey();
            }
        }
        return -1;
    }

    /**
     * Completes the h2c upgrade after receiving 101 Switching Protocols.
     */
    private void completeH2cUpgrade() {
        negotiatedVersion = HTTPVersion.HTTP_2_0;
        h2cUpgradeInFlight = false;

        initializeHTTP2();
        sendConnectionPreface();

        if (h2cUpgradeRequest != null) {
            activeStreams.put(1, h2cUpgradeRequest);
            nextStreamId = 3;
        }
        h2cUpgradeRequest = null;

        parseState = ParseState.H2C_UPGRADE_PENDING;

        LOGGER.fine("HTTP/2 connection preface sent, h2c upgrade complete to " + host + ":" + port);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Response parsing
    // ─────────────────────────────────────────────────────────────────────────

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

    private boolean parseStatusLine() {
        int lineEnd = findCRLF(parseBuffer);
        if (lineEnd < 0) {
            return false;
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

    private boolean parseHeaders() {
        while (true) {
            int lineEnd = findCRLF(parseBuffer);
            if (lineEnd < 0) {
                return false;
            }

            if (lineEnd == 0) {
                parseBuffer.get();
                parseBuffer.get();

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

                if (responseStatus == HTTPStatus.UNAUTHORIZED
                        && username != null && password != null && !authRetryPending) {

                    String wwwAuth = responseHeaders.getValue("www-authenticate");
                    if (wwwAuth != null && attemptAuthentication(wwwAuth)) {
                        discardResponseBody();
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

                boolean headRequest = currentStream != null
                        && "HEAD".equals(currentStream.getMethod());

                if (headRequest
                        || responseStatus == HTTPStatus.NO_CONTENT
                        || responseStatus == HTTPStatus.NOT_MODIFIED) {
                    completeResponse();
                } else {
                    String transferEncoding =
                            responseHeaders.getValue("transfer-encoding");
                    String contentLengthStr =
                            responseHeaders.getValue("content-length");

                    if (transferEncoding != null
                            && transferEncoding.toLowerCase()
                                    .contains("chunked")) {
                        chunkedEncoding = true;
                        parseState = ParseState.CHUNK_SIZE;
                        if (responseHandler != null) {
                            responseHandler.startResponseBody();
                        }
                    } else if (contentLengthStr != null) {
                        contentLength =
                                Long.parseLong(contentLengthStr.trim());
                        if (contentLength > 0) {
                            parseState = ParseState.BODY;
                            if (responseHandler != null) {
                                responseHandler.startResponseBody();
                            }
                        } else {
                            completeResponse();
                        }
                    } else {
                        contentLength = -1;
                        parseState = ParseState.BODY;
                        if (responseHandler != null) {
                            responseHandler.startResponseBody();
                        }
                    }
                }

                return true;
            }

            byte[] lineBytes = new byte[lineEnd];
            parseBuffer.get(lineBytes);
            parseBuffer.get();
            parseBuffer.get();

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
        parseBuffer.get();
        parseBuffer.get();

        String line = new String(lineBytes, StandardCharsets.US_ASCII);
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

    private boolean parseChunkTrailer() {
        int lineEnd = findCRLF(parseBuffer);
        if (lineEnd < 0) {
            return false;
        }

        HTTPResponseHandler responseHandler = null;
        if (currentStream != null) {
            responseHandler = currentStream.getHandler();
        }

        if (lineEnd == 0) {
            parseBuffer.get();
            parseBuffer.get();

            if (responseHandler != null) {
                responseHandler.endResponseBody();
            }
            completeResponse();
            return true;
        }

        byte[] lineBytes = new byte[lineEnd];
        parseBuffer.get(lineBytes);
        parseBuffer.get();
        parseBuffer.get();

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

        LOGGER.fine("Response complete");
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
            authRetryPending = true;

            HTTPStream retryStream = new HTTPStream(this, currentStream.getMethod(), currentStream.getPath());

            for (Header h : currentStream.getHeaders()) {
                retryStream.header(h.getName(), h.getValue());
            }

            retryStream.header("Authorization", authHeader);

            HTTPResponseHandler responseHandler = currentStream.getHandler();

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

            currentStream = null;
            parseState = ParseState.IDLE;
            parseBuffer.clear();

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

    private String computeBasicAuth() {
        String credentials = username + ":" + password;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    private String computeDigestAuth(String wwwAuthenticate, String method, String uri) {
        String realm = parseDirective(wwwAuthenticate, "realm");
        String nonce = parseDirective(wwwAuthenticate, "nonce");
        String qop = parseDirective(wwwAuthenticate, "qop");
        String opaque = parseDirective(wwwAuthenticate, "opaque");
        String algorithm = parseDirective(wwwAuthenticate, "algorithm");

        if (realm == null || nonce == null) {
            LOGGER.warning(L10N.getString("warn.invalid_digest_challenge"));
            return null;
        }

        if (algorithm == null) {
            algorithm = "MD5";
        }

        try {
            MessageDigest md = MessageDigest.getInstance(algorithm.replace("-sess", ""));

            String ha1Input = username + ":" + realm + ":" + password;
            String ha1 = hex(md.digest(ha1Input.getBytes(StandardCharsets.UTF_8)));

            String cnonce = null;
            if (algorithm.endsWith("-sess")) {
                cnonce = generateCnonce();
                md.reset();
                String sessInput = ha1 + ":" + nonce + ":" + cnonce;
                ha1 = hex(md.digest(sessInput.getBytes(StandardCharsets.UTF_8)));
            }

            md.reset();
            String ha2Input = method + ":" + uri;
            String ha2 = hex(md.digest(ha2Input.getBytes(StandardCharsets.UTF_8)));

            String response;
            String nc = "00000001";
            if (cnonce == null) {
                cnonce = generateCnonce();
            }

            if (qop != null && (qop.contains("auth") || qop.contains("auth-int"))) {
                md.reset();
                String responseInput = ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2;
                response = hex(md.digest(responseInput.getBytes(StandardCharsets.UTF_8)));
            } else {
                md.reset();
                String responseInput = ha1 + ":" + nonce + ":" + ha2;
                response = hex(md.digest(responseInput.getBytes(StandardCharsets.UTF_8)));
            }

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
        new Random().nextBytes(bytes);
        return hex(bytes);
    }

    private String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            byte b = bytes[i];
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private void discardResponseBody() {
        String transferEncoding = responseHeaders.getValue("transfer-encoding");
        String contentLengthStr = responseHeaders.getValue("content-length");

        if (transferEncoding != null && transferEncoding.toLowerCase().contains("chunked")) {
            // For chunked, we'd need to read chunks
        } else if (contentLengthStr != null) {
            contentLength = Long.parseLong(contentLengthStr.trim());
        }

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

    private void initializeHTTP2() {
        h2Parser = new H2Parser(this);
        h2Parser.setMaxFrameSize(maxFrameSize);
        h2Writer = new H2Writer(new ConnectionChannel());
        hpackDecoder = new Decoder(headerTableSize);
        hpackEncoder = new Encoder(headerTableSize, maxHeaderListSize);
    }

    private void sendConnectionPreface() {
        byte[] prefaceBytes = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        endpoint.send(ByteBuffer.wrap(prefaceBytes));

        endpoint.getSelectorLoop().invokeLater(new SendSettingsTask());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // H2FrameHandler Implementation
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void dataFrameReceived(int streamId, boolean endStream, ByteBuffer data) {
        HTTPStream stream = activeStreams.get(streamId);
        if (stream == null) {
            LOGGER.warning(MessageFormat.format(L10N.getString("warn.data_for_unknown_stream"), streamId));
            sendRstStream(streamId, H2FrameHandler.ERROR_STREAM_CLOSED);
            return;
        }

        HTTPResponseHandler responseHandler = stream.getHandler();
        if (responseHandler != null) {
            try {
                responseHandler.responseBodyContent(data);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in response handler", e);
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
                    LOGGER.log(Level.WARNING, "Error in response handler", e);
                }
            }
        }
    }

    @Override
    public void settingsFrameReceived(boolean ack, Map<Integer, Integer> settings) {
        if (parseState == ParseState.H2C_UPGRADE_PENDING) {
            parseState = ParseState.HTTP2;
            LOGGER.fine("HTTP/2 handshake complete, ready for requests");
        }

        if (!ack) {
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
        LOGGER.fine("Received PUSH_PROMISE for stream " + promisedStreamId);
    }

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

    @Override
    public void windowUpdateFrameReceived(int streamId, int windowSizeIncrement) {
        // Flow control: window size updates are tracked by H2Writer for sending.
        // Receiving WINDOW_UPDATE allows more data to be sent. No action needed
        // for basic client operation.
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

    @Override
    public void frameError(int errorCode, int streamId, String message) {
        LOGGER.warning(MessageFormat.format(L10N.getString("warn.h2_frame_error"),
                message, H2FrameHandler.errorToString(errorCode) + ", stream=" + streamId));

        if (streamId == 0) {
            sendGoaway(errorCode);
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
            sendGoaway(H2FrameHandler.ERROR_COMPRESSION_ERROR);
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
        HTTPResponseHandler responseHandler = stream.getHandler();
        if (responseHandler != null) {
            try {
                responseHandler.endResponseBody();
                responseHandler.close();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in response handler", e);
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
        int lastStreamId = 0;
        for (Integer id : activeStreams.keySet()) {
            if (id.intValue() > lastStreamId) {
                lastStreamId = id.intValue();
            }
        }
        final int fLastStreamId = lastStreamId;

        endpoint.getSelectorLoop().invokeLater(new SendGoawayTask(fLastStreamId, errorCode));
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
                ByteBuffer copy = ByteBuffer.allocate(written);
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
    // HTTP/2 Frame Sending Tasks
    // ─────────────────────────────────────────────────────────────────────────

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
                h2Writer.writeHeaders(streamId, headerBlock, !hasBody, true, 0, 0, 0, false);
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
            }
        }
    }

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
                LOGGER.log(Level.WARNING, "Error sending GOAWAY", e);
            } finally {
                close();
            }
        }
    }
}
