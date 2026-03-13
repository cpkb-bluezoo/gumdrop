/*
 * WebSocketConnection.java
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

package org.bluezoo.gumdrop.websocket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.telemetry.ErrorCategory;
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.SpanKind;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;

/**
 * Abstract base class for WebSocket connections (RFC 6455).
 * Provides the lifecycle management and message handling interface
 * for WebSocket connections after a successful upgrade.
 *
 * <p>Implementations should override the event handler methods to
 * provide application-specific WebSocket behaviour. The connection
 * handles frame parsing, protocol compliance, and provides methods
 * for sending different types of messages.
 *
 * <p>Connection states follow the WebSocket lifecycle (RFC 6455 §4, §7):
 * <ol>
 * <li><strong>CONNECTING</strong> - Opening handshake in progress (§4.1)</li>
 * <li><strong>OPEN</strong> - Connection established (§4.2.2)</li>
 * <li><strong>CLOSING</strong> - Closing handshake started (§7.1.1)</li>
 * <li><strong>CLOSED</strong> - Connection terminated (§7.1.4)</li>
 * </ol>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://tools.ietf.org/html/rfc6455">RFC 6455: The WebSocket Protocol</a>
 */
public abstract class WebSocketConnection {

    private static final Logger LOGGER = Logger.getLogger(WebSocketConnection.class.getName());

    /**
     * RFC 6455 §4/§7 — WebSocket connection states.
     */
    public enum State {
        /** RFC 6455 §4.1 — opening handshake in progress */
        CONNECTING,
        /** RFC 6455 §4.2.2 — connection established, communication possible */
        OPEN,
        /** RFC 6455 §7.1.1 — close frame sent or received, closing handshake in progress */
        CLOSING,
        /** RFC 6455 §7.1.4 — connection terminated */
        CLOSED
    }

    /**
     * RFC 6455 §7.4.1 — defined status codes for WebSocket close frames.
     */
    public static class CloseCodes {
        /** RFC 6455 §7.4.1 — 1000: normal closure */
        public static final int NORMAL_CLOSURE = 1000;
        /** RFC 6455 §7.4.1 — 1001: endpoint going away */
        public static final int GOING_AWAY = 1001;
        /** RFC 6455 §7.4.1 — 1002: protocol error */
        public static final int PROTOCOL_ERROR = 1002;
        /** RFC 6455 §7.4.1 — 1003: unsupported data type received */
        public static final int UNSUPPORTED_DATA = 1003;
        /** RFC 6455 §7.4.1 — 1009: message too big */
        public static final int MESSAGE_TOO_BIG = 1009;
        /** RFC 6455 §7.4.1 — 1010: client expected extension not negotiated */
        public static final int MISSING_EXTENSION = 1010;
        /** RFC 6455 §7.4.1 — 1011: unexpected server condition */
        public static final int INTERNAL_ERROR = 1011;
    }

    // Connection state
    private volatile State state = State.CONNECTING;
    private final AtomicBoolean closeFrameSent = new AtomicBoolean(false);
    private final AtomicBoolean closeFrameReceived = new AtomicBoolean(false);

    // RFC 6455 §5.1 — client MUST mask all frames sent to the server
    private boolean clientMode;

    // Frame assembly for fragmented messages
    private ByteBuffer messageBuffer;
    private int messageOpcode = -1;
    private boolean messageRsv1;
    private long messageSize;

    // The underlying transport mechanism (set by HTTPConnection after upgrade)
    private WebSocketTransport transport;

    // RFC 6455 §9 — negotiated extensions (applied in order)
    private List<WebSocketExtension> extensions = Collections.emptyList();

    // RFC 6455 §7.4.1 — configurable maximum assembled message size (0 = unlimited)
    private long maxMessageSize;
    
    // Telemetry support
    private static final ResourceBundle L10N = ResourceBundle.getBundle(
        "org.bluezoo.gumdrop.websocket.L10N");
    private TelemetryConfig telemetryConfig;
    private Span span;
    private long timestampOpened;
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final AtomicLong bytesReceived = new AtomicLong(0);
    private final AtomicLong bytesSent = new AtomicLong(0);

    // Optional server-level metrics (set by the adapter layer)
    private WebSocketServerMetrics serverMetrics;

    /**
     * Sets the transport mechanism for this WebSocket connection.
     * This is called by the HTTP connection after a successful upgrade.
     *
     * @param transport the transport mechanism
     */
    public void setTransport(WebSocketTransport transport) {
        this.transport = transport;
    }

    /**
     * RFC 6455 §5.1 — sets whether this connection operates in client mode.
     * In client mode, all outgoing frames are masked. Server connections
     * leave this as false (the default).
     *
     * @param clientMode true for client connections
     */
    public void setClientMode(boolean clientMode) {
        this.clientMode = clientMode;
    }

    /**
     * RFC 6455 §9 — sets the negotiated extensions for this connection.
     * Extensions are applied in order: first extension in the list is
     * applied first for encoding and last for decoding.
     *
     * @param extensions the negotiated extensions (must not be null)
     */
    public void setExtensions(List<WebSocketExtension> extensions) {
        this.extensions = extensions != null ? extensions : Collections.emptyList();
    }

    /**
     * RFC 6455 §7.4.1 — sets the maximum assembled message size in bytes.
     * Messages exceeding this limit cause the connection to be closed with
     * code 1009 (Message Too Big). A value of 0 means unlimited.
     *
     * @param maxMessageSize the maximum message size in bytes, or 0 for unlimited
     */
    public void setMaxMessageSize(long maxMessageSize) {
        this.maxMessageSize = maxMessageSize;
    }

    /**
     * Sets optional server-level metrics for frame and message tracking.
     *
     * @param metrics the metrics instance (may be null)
     */
    public void setServerMetrics(WebSocketServerMetrics metrics) {
        this.serverMetrics = metrics;
    }
    
    /**
     * Sets the telemetry configuration for this WebSocket connection.
     * When telemetry is enabled, the connection will track lifecycle events,
     * message counts, and error conditions.
     * 
     * @param config the telemetry configuration (may be null to disable)
     */
    public void setTelemetryConfig(TelemetryConfig config) {
        this.telemetryConfig = config;
    }
    
    /**
     * Sets the parent span for this WebSocket connection.
     * The WebSocket span will be created as a child of this span.
     * 
     * @param parentSpan the parent span (typically from the HTTP stream)
     */
    public void setParentSpan(Span parentSpan) {
        if (telemetryConfig != null && parentSpan != null) {
            this.span = parentSpan.startChild(
                L10N.getString("telemetry.websocket_session"),
                SpanKind.SERVER);
        }
    }
    
    /**
     * Creates a new span for this WebSocket connection.
     * Use this when there is no parent span available.
     * 
     * @param spanName optional custom name (uses default if null)
     */
    public void createSpan(String spanName) {
        if (telemetryConfig != null) {
            String name = spanName != null ? spanName : 
                L10N.getString("telemetry.websocket_session");
            this.span = telemetryConfig.createTrace(name, SpanKind.SERVER).getRootSpan();
        }
    }

    /**
     * Gets the current connection state.
     *
     * @return the current state
     */
    public final State getState() {
        return state;
    }

    /**
     * Returns true if the connection is open and ready for communication.
     *
     * @return true if the connection is open
     */
    public final boolean isOpen() {
        return state == State.OPEN;
    }

    /**
     * Called when the WebSocket connection is successfully established.
     * Override this method to handle connection open events.
     */
    protected abstract void opened();

    /**
     * Called when a text message is received.
     * Override this method to handle incoming text messages.
     *
     * @param message the received text message
     */
    protected abstract void textMessageReceived(String message);

    /**
     * Called when a binary message is received.
     * Override this method to handle incoming binary messages.
     *
     * @param data the received binary data
     */
    protected abstract void binaryMessageReceived(ByteBuffer data);

    /**
     * Called when the WebSocket connection is closed.
     * Override this method to handle connection close events.
     *
     * @param code the close code
     * @param reason the close reason (may be null or empty)
     */
    protected abstract void closed(int code, String reason);

    /**
     * Called when an error occurs on the WebSocket connection.
     * Override this method to handle error conditions.
     *
     * @param cause the error that occurred
     */
    protected abstract void error(Throwable cause);
    
    /**
     * Records an error in telemetry.
     * This is called internally and can also be called by subclasses
     * to record application-level errors.
     * 
     * @param error the error to record
     */
    protected final void recordTelemetryError(Throwable error) {
        if (span != null) {
            span.recordException(error);
        }
    }
    
    /**
     * Records an error with a specific category in telemetry.
     * 
     * @param category the error category
     * @param message the error message
     */
    protected final void recordTelemetryError(ErrorCategory category, String message) {
        if (span != null) {
            span.recordError(category, message);
        }
    }

    /**
     * RFC 6455 §5.5.2/§5.5.3 — called when a ping frame is received.
     * Default implementation automatically sends a pong with the same payload.
     *
     * @param payload the ping payload (may be empty)
     */
    protected void pingReceived(ByteBuffer payload) {
        try {
            sendPong(payload);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send pong response", e);
            error(e);
        }
    }

    /**
     * RFC 6455 §5.5.3 — called when a pong frame is received.
     *
     * @param payload the pong payload (may be empty)
     */
    protected void pongReceived(ByteBuffer payload) {
        // Default implementation does nothing
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(L10N.getString("log.received_pong"), payload.remaining()));
        }
    }

    /**
     * RFC 6455 §5.6 — sends a text message (opcode 0x1) to the peer.
     * If extensions are active, the payload is encoded (e.g. compressed)
     * and the appropriate RSV bits are set (§9).
     *
     * @param message the text message to send
     * @throws IOException if an I/O error occurs
     * @throws IllegalStateException if the connection is not open
     */
    public final void sendText(String message) throws IOException {
        checkConnectionOpen();
        byte[] payload = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        sendDataFrame(WebSocketFrame.OPCODE_TEXT, payload);
    }

    /**
     * RFC 6455 §5.6 — sends a binary message (opcode 0x2) to the peer.
     * If extensions are active, the payload is encoded (e.g. compressed)
     * and the appropriate RSV bits are set (§9).
     *
     * @param data the binary data to send
     * @throws IOException if an I/O error occurs
     * @throws IllegalStateException if the connection is not open
     */
    public final void sendBinary(ByteBuffer data) throws IOException {
        checkConnectionOpen();
        byte[] payload = new byte[data.remaining()];
        data.get(payload);
        sendDataFrame(WebSocketFrame.OPCODE_BINARY, payload);
    }

    /**
     * RFC 6455 §9 — sends a data frame, applying extension encoding.
     */
    private void sendDataFrame(int opcode, byte[] payload) throws IOException {
        boolean rsv1 = false;
        byte[] encoded = payload;
        for (WebSocketExtension ext : extensions) {
            encoded = ext.encode(encoded);
            rsv1 |= ext.usesRsv1();
        }
        WebSocketFrame frame = new WebSocketFrame(opcode, encoded, clientMode, rsv1);
        sendFrame(frame);
    }

    /**
     * RFC 6455 §5.5.2 — sends a ping frame to the peer.
     *
     * @param payload optional payload (may be null, max 125 bytes per §5.5)
     * @throws IOException if an I/O error occurs
     * @throws IllegalStateException if the connection is not open
     */
    public final void sendPing(ByteBuffer payload) throws IOException {
        checkConnectionOpen();
        if (payload != null && payload.remaining() > 125) {
            throw new IllegalArgumentException("Ping payload too large: " + payload.remaining());
        }
        WebSocketFrame frame = WebSocketFrame.createPingFrame(payload, clientMode);
        sendFrame(frame);
    }

    /**
     * RFC 6455 §5.5.3 — sends a pong frame to the peer.
     *
     * @param payload optional payload (may be null, max 125 bytes per §5.5)
     * @throws IOException if an I/O error occurs
     */
    public final void sendPong(ByteBuffer payload) throws IOException {
        // Pong can be sent even if connection is closing
        if (state == State.CLOSED) {
            throw new IllegalStateException(L10N.getString("err.connection_closed"));
        }
        if (payload != null && payload.remaining() > 125) {
            throw new IllegalArgumentException("Pong payload too large: " + payload.remaining());
        }
        WebSocketFrame frame = WebSocketFrame.createPongFrame(payload, clientMode);
        sendFrame(frame);
    }

    /**
     * RFC 6455 §7.1 — initiates closing handshake with normal closure (1000).
     *
     * @throws IOException if an I/O error occurs
     */
    public final void close() throws IOException {
        close(CloseCodes.NORMAL_CLOSURE, null);
    }

    /**
     * RFC 6455 §7.1 — initiates closing handshake with specified code and reason.
     *
     * @param code the close code (RFC 6455 §7.4, 1000-4999)
     * @param reason optional close reason (may be null)
     * @throws IOException if an I/O error occurs
     */
    public final void close(int code, String reason) throws IOException {
        if (state == State.CLOSED) {
            return; // Already closed
        }

        if (closeFrameSent.compareAndSet(false, true)) {
            state = State.CLOSING;
            WebSocketFrame closeFrame = WebSocketFrame.createCloseFrame(code, reason, clientMode);
            sendFrame(closeFrame);

            // If we've already received a close frame, complete the closing handshake
            if (closeFrameReceived.get()) {
                completeClose(code, reason);
            }
        }
    }

    /**
     * RFC 6455 §5 — processes incoming WebSocket frame data from the transport.
     *
     * @param buffer the buffer containing frame data
     * @throws IOException if an I/O error occurs
     */
    public final void processIncomingData(ByteBuffer buffer) throws IOException {
        try {
            while (buffer.hasRemaining()) {
                WebSocketFrame frame = WebSocketFrame.parse(buffer);
                if (frame == null) {
                    break; // Insufficient data for complete frame
                }
                processFrame(frame);
            }
        } catch (WebSocketProtocolException e) {
            LOGGER.log(Level.WARNING, "WebSocket protocol error", e);
            recordTelemetryError(e);
            close(CloseCodes.PROTOCOL_ERROR, "Protocol error");
            error(e);
        }
    }

    /**
     * RFC 6455 §4.2.2 — transitions from CONNECTING to OPEN after successful
     * handshake. Called by the HTTP layer after sending the 101 response.
     */
    public final void notifyConnectionOpen() {
        if (state == State.CONNECTING) {
            state = State.OPEN;
            timestampOpened = System.currentTimeMillis();
            
            // Record telemetry event
            if (span != null) {
                span.addEvent(L10N.getString("telemetry.event_connection_opened"));
            }
            
            try {
                opened();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error in opened handler", e);
                error(e);
            }
        }
    }

    /**
     * RFC 6455 §5 — dispatches a received frame by opcode.
     * Validates RSV bits against negotiated extensions (§5.2, §9).
     *
     * @param frame the received frame
     * @throws IOException if an I/O error occurs
     */
    private void processFrame(WebSocketFrame frame) throws IOException {
        // RFC 6455 §5.2 — validate RSV bits against active extensions
        if (!validateRsvBits(frame)) {
            close(CloseCodes.PROTOCOL_ERROR, "Unexpected RSV bits");
            return;
        }

        if (serverMetrics != null) {
            serverMetrics.frameReceived(opcodeToString(frame.getOpcode()));
        }
        switch (frame.getOpcode()) {
            case WebSocketFrame.OPCODE_TEXT:
            case WebSocketFrame.OPCODE_BINARY:
                processDataFrame(frame);
                break;
            case WebSocketFrame.OPCODE_CONTINUATION:
                processContinuationFrame(frame);
                break;
            case WebSocketFrame.OPCODE_CLOSE:
                processCloseFrame(frame);
                break;
            case WebSocketFrame.OPCODE_PING:
                pingReceived(frame.getPayload());
                break;
            case WebSocketFrame.OPCODE_PONG:
                pongReceived(frame.getPayload());
                break;
            default:
                LOGGER.warning(MessageFormat.format(L10N.getString("log.unknown_opcode"), frame.getOpcode()));
                close(CloseCodes.PROTOCOL_ERROR, "Unknown opcode");
        }
    }

    /**
     * RFC 6455 §5.4/§5.6 — processes a data frame (text or binary),
     * starting fragmentation if not FIN.
     */
    private void processDataFrame(WebSocketFrame frame) throws IOException {
        if (messageOpcode != -1) {
            close(CloseCodes.PROTOCOL_ERROR, "Unexpected data frame during fragmented message");
            return;
        }

        byte[] payloadBytes = frame.getPayloadBytes();

        // RFC 6455 §7.4.1 — enforce maximum message size
        if (maxMessageSize > 0 && payloadBytes.length > maxMessageSize) {
            close(CloseCodes.MESSAGE_TOO_BIG,
                    "Message exceeds maximum size of " + maxMessageSize + " bytes");
            return;
        }

        if (frame.isFin()) {
            deliverMessage(frame.getOpcode(), payloadBytes, frame.isRsv1());
        } else {
            messageOpcode = frame.getOpcode();
            messageRsv1 = frame.isRsv1();
            messageSize = payloadBytes.length;
            messageBuffer = ByteBuffer.allocate(payloadBytes.length);
            messageBuffer.put(payloadBytes);
        }
    }

    /**
     * RFC 6455 §5.4 — processes a continuation frame, assembling the
     * fragmented message until FIN is set. Enforces the configurable
     * maximum message size (§7.4.1, close code 1009).
     */
    private void processContinuationFrame(WebSocketFrame frame) throws IOException {
        if (messageOpcode == -1) {
            close(CloseCodes.PROTOCOL_ERROR, "Unexpected continuation frame");
            return;
        }

        byte[] payloadBytes = frame.getPayloadBytes();

        // RFC 6455 §7.4.1 — enforce maximum assembled message size
        messageSize += payloadBytes.length;
        if (maxMessageSize > 0 && messageSize > maxMessageSize) {
            messageOpcode = -1;
            messageBuffer = null;
            close(CloseCodes.MESSAGE_TOO_BIG,
                    "Message exceeds maximum size of " + maxMessageSize + " bytes");
            return;
        }

        if (messageBuffer.remaining() < payloadBytes.length) {
            ByteBuffer newBuffer = ByteBuffer.allocate(messageBuffer.capacity() + payloadBytes.length);
            messageBuffer.flip();
            newBuffer.put(messageBuffer);
            messageBuffer = newBuffer;
        }
        messageBuffer.put(payloadBytes);

        if (frame.isFin()) {
            messageBuffer.flip();
            byte[] completeMessage = new byte[messageBuffer.remaining()];
            messageBuffer.get(completeMessage);

            deliverMessage(messageOpcode, completeMessage, messageRsv1);

            messageOpcode = -1;
            messageRsv1 = false;
            messageBuffer = null;
        }
    }

    /**
     * RFC 6455 §7.1 — processes a close frame. Validates the close code
     * against RFC 6455 §7.4 allowed ranges before echoing or completing
     * the handshake.
     */
    private void processCloseFrame(WebSocketFrame frame) throws IOException {
        if (closeFrameReceived.compareAndSet(false, true)) {
            int closeCode = frame.getCloseCode();
            String closeReason = frame.getCloseReason();

            if (closeCode == -1) {
                closeCode = CloseCodes.NORMAL_CLOSURE;
            } else if (!isValidCloseCode(closeCode)) {
                // RFC 6455 §7.4 — invalid close code → protocol error
                close(CloseCodes.PROTOCOL_ERROR, "Invalid close code: " + closeCode);
                return;
            }

            if (!closeFrameSent.get()) {
                close(closeCode, closeReason);
            } else {
                completeClose(closeCode, closeReason);
            }
        }
    }

    /**
     * RFC 6455 §7.4, §7.4.1, §7.4.2 — validates a received close code.
     * Codes 1004, 1005, 1006, and 1015 are reserved and MUST NOT appear
     * on the wire. Valid ranges are 1000–1003, 1007–1014, and 3000–4999.
     */
    private static boolean isValidCloseCode(int code) {
        if (code >= 3000 && code <= 4999) {
            return true;  // RFC 6455 §7.4.2 — private use / registered
        }
        switch (code) {
            case 1000: case 1001: case 1002: case 1003:
            case 1007: case 1008: case 1009: case 1010:
            case 1011: case 1012: case 1013: case 1014:
                return true;
            default:
                return false;  // 1004, 1005, 1006, 1015, or out of range
        }
    }

    /**
     * Delivers a complete message to the application, applying extension
     * decoding (RFC 6455 §9) if the RSV1 bit indicates compression.
     */
    private void deliverMessage(int opcode, byte[] payload, boolean rsv1) {
        messagesReceived.incrementAndGet();
        bytesReceived.addAndGet(payload.length);

        try {
            // RFC 6455 §9 — apply extension decoding (reverse order)
            byte[] decoded = payload;
            if (rsv1 && !extensions.isEmpty()) {
                for (int i = extensions.size() - 1; i >= 0; i--) {
                    decoded = extensions.get(i).decode(decoded);
                }
            }

            if (opcode == WebSocketFrame.OPCODE_TEXT) {
                String message = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
                textMessageReceived(message);
            } else if (opcode == WebSocketFrame.OPCODE_BINARY) {
                binaryMessageReceived(ByteBuffer.wrap(decoded));
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error delivering WebSocket message", e);
            error(e);
        }
    }

    /**
     * RFC 6455 §7.1.4 — completes the closing handshake and transitions
     * to CLOSED state.
     */
    private void completeClose(int code, String reason) {
        if (state != State.CLOSED) {
            state = State.CLOSED;
            
            // End telemetry span with statistics
            if (span != null) {
                try {
                    long duration = System.currentTimeMillis() - timestampOpened;
                    span.addAttribute("websocket.close_code", (long) code);
                    if (reason != null) {
                        span.addAttribute("websocket.close_reason", reason);
                    }
                    span.addAttribute("websocket.messages_received", messagesReceived.get());
                    span.addAttribute("websocket.messages_sent", messagesSent.get());
                    span.addAttribute("websocket.bytes_received", bytesReceived.get());
                    span.addAttribute("websocket.bytes_sent", bytesSent.get());
                    span.addAttribute("websocket.duration_ms", duration);
                    
                    // Add close event
                    span.addEvent(MessageFormat.format(
                        L10N.getString("telemetry.event_connection_closed"),
                        code, reason != null ? reason : ""));
                    
                    // Set status based on close code
                    if (code == CloseCodes.NORMAL_CLOSURE || code == CloseCodes.GOING_AWAY) {
                        span.setStatusOk();
                    } else {
                        span.recordError(ErrorCategory.PROTOCOL_ERROR, 
                            "WebSocket closed with code " + code);
                    }
                    
                    span.end();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error recording telemetry", e);
                }
            }
            
            // Release extension resources
            for (WebSocketExtension ext : extensions) {
                try { ext.close(); } catch (Exception ignored) { /* best-effort */ }
            }

            try {
                if (transport != null) {
                    boolean normalClose = (code == CloseCodes.NORMAL_CLOSURE || code == CloseCodes.GOING_AWAY);
                    transport.close(normalClose);
                }
                closed(code, reason);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error in closed handler", e);
            }
        }
    }

    /**
     * Sends a WebSocket frame to the peer.
     */
    private void sendFrame(WebSocketFrame frame) throws IOException {
        if (transport == null) {
            throw new IllegalStateException(L10N.getString("err.transport_not_set"));
        }
        
        ByteBuffer encoded = frame.encode();
        
        // Track telemetry for data frames
        if (frame.isDataFrame()) {
            messagesSent.incrementAndGet();
            bytesSent.addAndGet(frame.getPayloadBytes().length);
        }
        
        if (serverMetrics != null) {
            serverMetrics.frameSent(opcodeToString(frame.getOpcode()));
            if (frame.getOpcode() == WebSocketFrame.OPCODE_TEXT) {
                serverMetrics.textMessageSent();
            } else if (frame.getOpcode() == WebSocketFrame.OPCODE_BINARY) {
                serverMetrics.binaryMessageSent();
            }
        }
        
        transport.sendFrame(encoded);
    }

    /**
     * Checks that the connection is open for sending.
     */
    private void checkConnectionOpen() {
        if (state != State.OPEN) {
            throw new IllegalStateException("WebSocket connection is not open: " + state);
        }
    }

    /**
     * RFC 6455 §5.2/§9 — validates RSV bits against negotiated extensions.
     * Returns false if any set RSV bit is not claimed by an active extension.
     */
    private boolean validateRsvBits(WebSocketFrame frame) {
        boolean rsv1 = frame.isRsv1();
        boolean rsv2 = frame.isRsv2();
        boolean rsv3 = frame.isRsv3();
        if (!rsv1 && !rsv2 && !rsv3) {
            return true;
        }
        for (WebSocketExtension ext : extensions) {
            if (rsv1 && ext.usesRsv1()) { rsv1 = false; }
            if (rsv2 && ext.usesRsv2()) { rsv2 = false; }
            if (rsv3 && ext.usesRsv3()) { rsv3 = false; }
        }
        return !rsv1 && !rsv2 && !rsv3;
    }

    private static String opcodeToString(int opcode) {
        switch (opcode) {
            case WebSocketFrame.OPCODE_CONTINUATION: return "continuation";
            case WebSocketFrame.OPCODE_TEXT:          return "text";
            case WebSocketFrame.OPCODE_BINARY:        return "binary";
            case WebSocketFrame.OPCODE_CLOSE:         return "close";
            case WebSocketFrame.OPCODE_PING:          return "ping";
            case WebSocketFrame.OPCODE_PONG:          return "pong";
            default:                                  return String.valueOf(opcode);
        }
    }

    /**
     * Interface for the underlying transport mechanism.
     * This allows the WebSocket connection to be independent of the HTTP transport.
     */
    public interface WebSocketTransport {
        /**
         * Sends a WebSocket frame to the peer.
         *
         * @param frameData the encoded frame data
         * @throws IOException if an I/O error occurs
         */
        void sendFrame(ByteBuffer frameData) throws IOException;

        /**
         * Closes the underlying transport.
         *
         * @param normalClose true if this is a normal close (codes 1000 or 1001),
         *                    false if abnormal
         * @throws IOException if an I/O error occurs
         */
        void close(boolean normalClose) throws IOException;
    }
}
