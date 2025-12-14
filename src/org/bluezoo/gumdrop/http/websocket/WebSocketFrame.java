/*
 * WebSocketFrame.java
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

package org.bluezoo.gumdrop.http.websocket;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.logging.Logger;

/**
 * WebSocket frame implementation following RFC 6455.
 * Handles parsing and generation of WebSocket frames including
 * masking, different payload lengths, and all frame types.
 *
 * <p>Frame format (RFC 6455 Section 5.2):
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-------+-+-------------+-------------------------------+
 * |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
 * |I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
 * |N|V|V|V|       |S|             |   (if payload len==126/127)   |
 * | |1|2|3|       |K|             |                               |
 * +-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
 * |     Extended payload length continued, if payload len == 127  |
 * + - - - - - - - - - - - - - - - +-------------------------------+
 * |                               |Masking-key, if MASK set to 1  |
 * +-------------------------------+-------------------------------+
 * | Masking-key (continued)       |          Payload Data         |
 * +-------------------------------- - - - - - - - - - - - - - - - +
 * :                     Payload Data continued ...                :
 * + - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - +
 * |                     Payload Data continued ...                |
 * +---------------------------------------------------------------+
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://tools.ietf.org/html/rfc6455">RFC 6455: The WebSocket Protocol</a>
 */
public class WebSocketFrame {

    private static final Logger LOGGER = Logger.getLogger(WebSocketFrame.class.getName());
    private static final ResourceBundle L10N = ResourceBundle.getBundle(
        "org.bluezoo.gumdrop.http.websocket.L10N");

    // WebSocket frame opcodes (RFC 6455 Section 5.2)
    /** Continuation frame opcode */
    public static final int OPCODE_CONTINUATION = 0x0;
    /** Text frame opcode */
    public static final int OPCODE_TEXT = 0x1;
    /** Binary frame opcode */
    public static final int OPCODE_BINARY = 0x2;
    /** Connection close frame opcode */
    public static final int OPCODE_CLOSE = 0x8;
    /** Ping frame opcode */
    public static final int OPCODE_PING = 0x9;
    /** Pong frame opcode */
    public static final int OPCODE_PONG = 0xA;

    // Frame components
    private final boolean fin;
    private final boolean rsv1;
    private final boolean rsv2;
    private final boolean rsv3;
    private final int opcode;
    private final boolean masked;
    private final byte[] maskingKey;
    private final byte[] payload;

    /**
     * Creates a new WebSocket frame.
     *
     * @param fin true if this is the final fragment
     * @param rsv1 reserved bit 1 (must be false unless extension defines it)
     * @param rsv2 reserved bit 2 (must be false unless extension defines it) 
     * @param rsv3 reserved bit 3 (must be false unless extension defines it)
     * @param opcode the frame opcode
     * @param masked true if payload is masked
     * @param maskingKey the 4-byte masking key (null if not masked)
     * @param payload the frame payload data
     * @throws WebSocketProtocolException if the frame is invalid
     */
    public WebSocketFrame(boolean fin, boolean rsv1, boolean rsv2, boolean rsv3,
                         int opcode, boolean masked, byte[] maskingKey, byte[] payload) 
                         throws WebSocketProtocolException {
        this.fin = fin;
        this.rsv1 = rsv1;
        this.rsv2 = rsv2;
        this.rsv3 = rsv3;
        this.opcode = opcode;
        this.masked = masked;
        this.maskingKey = maskingKey;
        this.payload = payload != null ? payload : new byte[0];

        // Validate frame
        validateFrame();
    }

    /**
     * Creates a simple WebSocket frame (most common case).
     *
     * @param opcode the frame opcode
     * @param payload the frame payload
     * @param masked true if payload should be masked
     * @throws WebSocketProtocolException if the frame is invalid
     */
    public WebSocketFrame(int opcode, byte[] payload, boolean masked) throws WebSocketProtocolException {
        this(true, false, false, false, opcode, masked, 
             masked ? generateMaskingKey() : null, payload);
    }

    /**
     * Creates a text frame.
     *
     * @param text the text content
     * @param masked true if payload should be masked
     * @throws WebSocketProtocolException if the frame is invalid
     */
    public static WebSocketFrame createTextFrame(String text, boolean masked) throws WebSocketProtocolException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        return new WebSocketFrame(OPCODE_TEXT, payload, masked);
    }

    /**
     * Creates a binary frame.
     *
     * @param data the binary data
     * @param masked true if payload should be masked
     * @throws WebSocketProtocolException if the frame is invalid
     */
    public static WebSocketFrame createBinaryFrame(ByteBuffer data, boolean masked) throws WebSocketProtocolException {
        byte[] payload = new byte[data.remaining()];
        data.get(payload);
        return new WebSocketFrame(OPCODE_BINARY, payload, masked);
    }

    /**
     * Creates a close frame.
     *
     * @param closeCode the close code (1000-4999)
     * @param reason the reason text (optional, may be null)
     * @param masked true if payload should be masked
     * @throws WebSocketProtocolException if the frame is invalid
     */
    public static WebSocketFrame createCloseFrame(int closeCode, String reason, boolean masked) throws WebSocketProtocolException {
        ByteBuffer buffer = ByteBuffer.allocate(2 + (reason != null ? reason.getBytes(StandardCharsets.UTF_8).length : 0));
        buffer.putShort((short) closeCode);
        if (reason != null) {
            buffer.put(reason.getBytes(StandardCharsets.UTF_8));
        }
        return new WebSocketFrame(OPCODE_CLOSE, buffer.array(), masked);
    }

    /**
     * Creates a ping frame.
     *
     * @param payload optional payload (may be null)
     * @param masked true if payload should be masked
     * @throws WebSocketProtocolException if the frame is invalid
     */
    public static WebSocketFrame createPingFrame(ByteBuffer payload, boolean masked) throws WebSocketProtocolException {
        byte[] payloadBytes = null;
        if (payload != null && payload.hasRemaining()) {
            payloadBytes = new byte[payload.remaining()];
            payload.get(payloadBytes);
        }
        return new WebSocketFrame(OPCODE_PING, payloadBytes, masked);
    }

    /**
     * Creates a pong frame.
     *
     * @param payload optional payload (may be null)
     * @param masked true if payload should be masked
     * @throws WebSocketProtocolException if the frame is invalid
     */
    public static WebSocketFrame createPongFrame(ByteBuffer payload, boolean masked) throws WebSocketProtocolException {
        byte[] payloadBytes = null;
        if (payload != null && payload.hasRemaining()) {
            payloadBytes = new byte[payload.remaining()];
            payload.get(payloadBytes);
        }
        return new WebSocketFrame(OPCODE_PONG, payloadBytes, masked);
    }

    /**
     * Parses a WebSocket frame from the given ByteBuffer.
     * Returns null if there's insufficient data for a complete frame.
     * The buffer position will be unchanged if parsing fails.
     *
     * @param buffer the buffer containing frame data
     * @return the parsed frame, or null if insufficient data
     * @throws WebSocketProtocolException if the frame is malformed
     */
    public static WebSocketFrame parse(ByteBuffer buffer) throws WebSocketProtocolException {
        if (buffer.remaining() < 2) {
            return null; // Need at least 2 bytes for basic header
        }

        int position = buffer.position();

        try {
            // First byte: FIN, RSV1-3, Opcode
            byte firstByte = buffer.get();
            boolean fin = (firstByte & 0x80) != 0;
            boolean rsv1 = (firstByte & 0x40) != 0;
            boolean rsv2 = (firstByte & 0x20) != 0;
            boolean rsv3 = (firstByte & 0x10) != 0;
            int opcode = firstByte & 0x0F;

            // Second byte: MASK, Payload length
            byte secondByte = buffer.get();
            boolean masked = (secondByte & 0x80) != 0;
            int payloadLen = secondByte & 0x7F;

            // Extended payload length
            long extendedPayloadLength = payloadLen;
            if (payloadLen == 126) {
                if (buffer.remaining() < 2) {
                    buffer.position(position);
                    return null; // Need 2 more bytes
                }
                extendedPayloadLength = buffer.getShort() & 0xFFFF;
            } else if (payloadLen == 127) {
                if (buffer.remaining() < 8) {
                    buffer.position(position);
                    return null; // Need 8 more bytes
                }
                extendedPayloadLength = buffer.getLong();
                if (extendedPayloadLength < 0) {
                    throw new WebSocketProtocolException("Payload length too large: " + extendedPayloadLength);
                }
            }

            // Check payload length limits
            if (extendedPayloadLength > Integer.MAX_VALUE) {
                throw new WebSocketProtocolException("Payload too large: " + extendedPayloadLength);
            }
            int actualPayloadLength = (int) extendedPayloadLength;

            // Masking key
            byte[] maskingKey = null;
            if (masked) {
                if (buffer.remaining() < 4) {
                    buffer.position(position);
                    return null; // Need 4 more bytes for masking key
                }
                maskingKey = new byte[4];
                buffer.get(maskingKey);
            }

            // Payload data
            if (buffer.remaining() < actualPayloadLength) {
                buffer.position(position);
                return null; // Need more bytes for payload
            }

            byte[] payload = new byte[actualPayloadLength];
            buffer.get(payload);

            // Unmask payload if masked
            if (masked && maskingKey != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= maskingKey[i % 4];
                }
            }

            return new WebSocketFrame(fin, rsv1, rsv2, rsv3, opcode, masked, maskingKey, payload);

        } catch (Exception e) {
            // Reset position and re-throw as protocol exception
            buffer.position(position);
            if (e instanceof WebSocketProtocolException) {
                throw e;
            }
            throw new WebSocketProtocolException("Failed to parse WebSocket frame", e);
        }
    }

    /**
     * Encodes this frame into a ByteBuffer.
     *
     * @return ByteBuffer containing the encoded frame
     */
    public ByteBuffer encode() {
        int headerSize = 2; // Basic header
        long payloadLength = payload.length;

        // Extended payload length bytes
        if (payloadLength >= 126) {
            headerSize += (payloadLength > 65535) ? 8 : 2;
        }

        // Masking key bytes
        if (masked) {
            headerSize += 4;
        }

        ByteBuffer buffer = ByteBuffer.allocate(headerSize + payload.length);

        // First byte: FIN, RSV1-3, Opcode
        byte firstByte = (byte) opcode;
        if (fin) firstByte |= 0x80;
        if (rsv1) firstByte |= 0x40;
        if (rsv2) firstByte |= 0x20;
        if (rsv3) firstByte |= 0x10;
        buffer.put(firstByte);

        // Second byte: MASK, Payload length
        byte secondByte = 0;
        if (masked) secondByte |= 0x80;

        if (payloadLength < 126) {
            secondByte |= (byte) payloadLength;
            buffer.put(secondByte);
        } else if (payloadLength <= 65535) {
            secondByte |= 126;
            buffer.put(secondByte);
            buffer.putShort((short) payloadLength);
        } else {
            secondByte |= 127;
            buffer.put(secondByte);
            buffer.putLong(payloadLength);
        }

        // Masking key
        if (masked && maskingKey != null) {
            buffer.put(maskingKey);
        }

        // Payload (mask if needed)
        if (masked && maskingKey != null) {
            for (int i = 0; i < payload.length; i++) {
                buffer.put((byte) (payload[i] ^ maskingKey[i % 4]));
            }
        } else {
            buffer.put(payload);
        }

        buffer.flip();
        return buffer;
    }

    /**
     * Validates the frame according to RFC 6455 rules.
     *
     * @throws WebSocketProtocolException if the frame is invalid
     */
    private void validateFrame() throws WebSocketProtocolException {
        // Validate opcode
        if (opcode < 0 || opcode > 15) {
            throw new WebSocketProtocolException("Invalid opcode: " + opcode);
        }

        // Control frames validation (RFC 6455 Section 5.5)
        if (isControlFrame()) {
            if (!fin) {
                throw new WebSocketProtocolException(L10N.getString("err.control_frame_fragmented"));
            }
            if (payload.length > 125) {
                throw new WebSocketProtocolException(
                    MessageFormat.format(L10N.getString("err.control_frame_too_large"), payload.length));
            }
        }

        // Reserved bits must be false unless extension defines them
        if (rsv1 || rsv2 || rsv3) {
            LOGGER.fine(L10N.getString("log.reserved_bits"));
        }

        // Masking key validation
        if (masked && (maskingKey == null || maskingKey.length != 4)) {
            throw new WebSocketProtocolException(L10N.getString("err.invalid_masking_key"));
        }
    }

    /**
     * Generates a random 4-byte masking key.
     *
     * @return 4-byte random masking key
     */
    private static byte[] generateMaskingKey() {
        byte[] key = new byte[4];
        // Simple random key generation - could use SecureRandom for better security
        long time = System.nanoTime();
        key[0] = (byte) (time & 0xFF);
        key[1] = (byte) ((time >> 8) & 0xFF);
        key[2] = (byte) ((time >> 16) & 0xFF);
        key[3] = (byte) ((time >> 24) & 0xFF);
        return key;
    }

    // Getters

    public boolean isFin() { return fin; }
    public boolean isRsv1() { return rsv1; }
    public boolean isRsv2() { return rsv2; }
    public boolean isRsv3() { return rsv3; }
    public int getOpcode() { return opcode; }
    public boolean isMasked() { return masked; }
    public byte[] getMaskingKey() { return maskingKey; }
    
    /**
     * Returns the payload as a ByteBuffer (read-only).
     *
     * @return the payload data
     */
    public ByteBuffer getPayload() {
        return ByteBuffer.wrap(payload).asReadOnlyBuffer();
    }
    
    /**
     * Returns the raw payload bytes (for internal use).
     *
     * @return the payload byte array
     */
    byte[] getPayloadBytes() {
        return payload;
    }

    /**
     * Returns true if this is a control frame (close, ping, pong).
     */
    public boolean isControlFrame() {
        return opcode >= 0x8;
    }

    /**
     * Returns true if this is a data frame (text, binary, continuation).
     */
    public boolean isDataFrame() {
        return opcode <= 0x2;
    }

    /**
     * Returns the payload as a UTF-8 string (for text frames).
     *
     * @throws IllegalStateException if this is not a text frame
     */
    public String getTextPayload() {
        if (opcode != OPCODE_TEXT && opcode != OPCODE_CONTINUATION) {
            throw new IllegalStateException(L10N.getString("err.not_text_frame"));
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    /**
     * Returns the close code from a close frame payload.
     *
     * @return the close code, or -1 if not a close frame or no code present
     */
    public int getCloseCode() {
        if (opcode != OPCODE_CLOSE || payload.length < 2) {
            return -1;
        }
        return ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
    }

    /**
     * Returns the close reason from a close frame payload.
     *
     * @return the close reason, or null if not a close frame or no reason present
     */
    public String getCloseReason() {
        if (opcode != OPCODE_CLOSE || payload.length <= 2) {
            return null;
        }
        return new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        String opcodeStr;
        switch (opcode) {
            case OPCODE_CONTINUATION: opcodeStr = "CONTINUATION"; break;
            case OPCODE_TEXT: opcodeStr = "TEXT"; break;
            case OPCODE_BINARY: opcodeStr = "BINARY"; break;
            case OPCODE_CLOSE: opcodeStr = "CLOSE"; break;
            case OPCODE_PING: opcodeStr = "PING"; break;
            case OPCODE_PONG: opcodeStr = "PONG"; break;
            default: opcodeStr = "UNKNOWN(" + opcode + ")"; break;
        }

        return String.format("WebSocketFrame{fin=%s, opcode=%s, masked=%s, payloadLen=%d}",
                           fin, opcodeStr, masked, payload.length);
    }
}
