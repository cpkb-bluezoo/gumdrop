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
 * Abstract base class for WebSocket connections.
 * Provides the lifecycle management and message handling interface
 * for WebSocket connections after a successful upgrade.
 *
 * <p>Implementations should override the event handler methods to
 * provide application-specific WebSocket behaviour. The connection
 * handles frame parsing, protocol compliance, and provides methods
 * for sending different types of messages.
 *
 * <p>Connection states follow the WebSocket lifecycle:
 * <ol>
 * <li><strong>CONNECTING</strong> - Handshake in progress</li>
 * <li><strong>OPEN</strong> - Connection established and ready</li>
 * <li><strong>CLOSING</strong> - Close frame sent/received</li>
 * <li><strong>CLOSED</strong> - Connection terminated</li>
 * </ol>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://tools.ietf.org/html/rfc6455">RFC 6455: The WebSocket Protocol</a>
 */
public abstract class WebSocketConnection {

    private static final Logger LOGGER = Logger.getLogger(WebSocketConnection.class.getName());

    /**
     * WebSocket connection states as defined in RFC 6455.
     */
    public enum State {
        /** The connection is not yet open */
        CONNECTING,
        /** The WebSocket connection is established and communication is possible */
        OPEN,
        /** The connection is going through the closing handshake */
        CLOSING,
        /** The connection has been closed or could not be opened */
        CLOSED
    }

    /**
     * Standard WebSocket close codes as defined in RFC 6455 Section 7.4.1.
     */
    public static class CloseCodes {
        /** Normal closure; the connection successfully completed whatever purpose for which it was created */
        public static final int NORMAL_CLOSURE = 1000;
        /** The endpoint is going away (server shutdown or browser navigating away) */
        public static final int GOING_AWAY = 1001;
        /** The endpoint is terminating the connection due to a protocol error */
        public static final int PROTOCOL_ERROR = 1002;
        /** The connection is being terminated because the endpoint received data inconsistent with its type */
        public static final int UNSUPPORTED_DATA = 1003;
        /** The endpoint is terminating the connection because a message was received that is too big */
        public static final int MESSAGE_TOO_BIG = 1009;
        /** The client is terminating the connection because it expected a particular extension */
        public static final int MISSING_EXTENSION = 1010;
        /** The server is terminating the connection because it encountered an unexpected condition */
        public static final int INTERNAL_ERROR = 1011;
    }

    // Connection state
    private volatile State state = State.CONNECTING;
    private final AtomicBoolean closeFrameSent = new AtomicBoolean(false);
    private final AtomicBoolean closeFrameReceived = new AtomicBoolean(false);

    // Client mode: when true, outgoing frames are masked (RFC 6455 Section 5.1)
    private boolean clientMode;

    // Frame assembly for fragmented messages
    private ByteBuffer messageBuffer;
    private int messageOpcode = -1;

    // The underlying transport mechanism (set by HTTPConnection after upgrade)
    private WebSocketTransport transport;
    
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
     * Sets whether this connection operates in client mode.
     * In client mode, all outgoing frames are masked as required by
     * RFC 6455 Section 5.1. Server connections leave this as false
     * (the default).
     *
     * @param clientMode true for client connections
     */
    public void setClientMode(boolean clientMode) {
        this.clientMode = clientMode;
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
     * Called when a ping frame is received.
     * Default implementation automatically sends a pong response.
     * Override to customize ping handling.
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
     * Called when a pong frame is received.
     * Override this method to handle pong frames (typically for keep-alive).
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
     * Sends a text message to the peer.
     *
     * @param message the text message to send
     * @throws IOException if an I/O error occurs
     * @throws IllegalStateException if the connection is not open
     */
    public final void sendText(String message) throws IOException {
        checkConnectionOpen();
        WebSocketFrame frame = WebSocketFrame.createTextFrame(message, clientMode);
        sendFrame(frame);
    }

    /**
     * Sends a binary message to the peer.
     *
     * @param data the binary data to send
     * @throws IOException if an I/O error occurs
     * @throws IllegalStateException if the connection is not open
     */
    public final void sendBinary(ByteBuffer data) throws IOException {
        checkConnectionOpen();
        WebSocketFrame frame = WebSocketFrame.createBinaryFrame(data, clientMode);
        sendFrame(frame);
    }

    /**
     * Sends a ping frame to the peer.
     *
     * @param payload optional payload (may be null, max 125 bytes)
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
     * Sends a pong frame to the peer.
     *
     * @param payload optional payload (may be null, max 125 bytes)
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
     * Closes the WebSocket connection with a normal closure code.
     *
     * @throws IOException if an I/O error occurs
     */
    public final void close() throws IOException {
        close(CloseCodes.NORMAL_CLOSURE, null);
    }

    /**
     * Closes the WebSocket connection with the specified close code and reason.
     *
     * @param code the close code (1000-4999)
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
     * Processes incoming WebSocket frame data.
     * This method is called by the HTTP connection when WebSocket data is received.
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
     * Notifies the connection that the upgrade is complete and the connection is now open.
     * This is called by the HTTP connection after sending the 101 response.
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
     * Processes a received WebSocket frame.
     *
     * @param frame the received frame
     * @throws IOException if an I/O error occurs
     */
    private void processFrame(WebSocketFrame frame) throws IOException {
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
     * Processes a data frame (text or binary).
     */
    private void processDataFrame(WebSocketFrame frame) throws IOException {
        if (messageOpcode != -1) {
            // Already in middle of fragmented message
            close(CloseCodes.PROTOCOL_ERROR, "Unexpected data frame during fragmented message");
            return;
        }

        byte[] payloadBytes = frame.getPayloadBytes();
        if (frame.isFin()) {
            // Complete message in single frame
            deliverMessage(frame.getOpcode(), payloadBytes);
        } else {
            // Start of fragmented message
            messageOpcode = frame.getOpcode();
            messageBuffer = ByteBuffer.allocate(payloadBytes.length);
            messageBuffer.put(payloadBytes);
        }
    }

    /**
     * Processes a continuation frame.
     */
    private void processContinuationFrame(WebSocketFrame frame) throws IOException {
        if (messageOpcode == -1) {
            // No message in progress
            close(CloseCodes.PROTOCOL_ERROR, "Unexpected continuation frame");
            return;
        }

        byte[] payloadBytes = frame.getPayloadBytes();
        // Append to message buffer
        if (messageBuffer.remaining() < payloadBytes.length) {
            // Expand buffer
            ByteBuffer newBuffer = ByteBuffer.allocate(messageBuffer.capacity() + payloadBytes.length);
            messageBuffer.flip();
            newBuffer.put(messageBuffer);
            messageBuffer = newBuffer;
        }
        messageBuffer.put(payloadBytes);

        if (frame.isFin()) {
            // Message complete
            messageBuffer.flip();
            byte[] completeMessage = new byte[messageBuffer.remaining()];
            messageBuffer.get(completeMessage);
            
            deliverMessage(messageOpcode, completeMessage);
            
            // Reset for next message
            messageOpcode = -1;
            messageBuffer = null;
        }
    }

    /**
     * Processes a close frame.
     */
    private void processCloseFrame(WebSocketFrame frame) throws IOException {
        if (closeFrameReceived.compareAndSet(false, true)) {
            int closeCode = frame.getCloseCode();
            String closeReason = frame.getCloseReason();

            if (closeCode == -1) {
                closeCode = CloseCodes.NORMAL_CLOSURE;
            }

            if (!closeFrameSent.get()) {
                // Send close response
                close(closeCode, closeReason);
            } else {
                // Complete closing handshake
                completeClose(closeCode, closeReason);
            }
        }
    }

    /**
     * Delivers a complete message to the application.
     */
    private void deliverMessage(int opcode, byte[] payload) {
        // Track telemetry
        messagesReceived.incrementAndGet();
        bytesReceived.addAndGet(payload.length);
        
        try {
            if (opcode == WebSocketFrame.OPCODE_TEXT) {
                String message = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
                textMessageReceived(message);
            } else if (opcode == WebSocketFrame.OPCODE_BINARY) {
                binaryMessageReceived(ByteBuffer.wrap(payload));
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error delivering WebSocket message", e);
            error(e);
        }
    }

    /**
     * Completes the connection close process.
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
