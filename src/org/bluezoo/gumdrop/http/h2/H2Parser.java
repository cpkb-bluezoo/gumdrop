/*
 * H2Parser.java
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

package org.bluezoo.gumdrop.http.h2;

import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Push-parser for HTTP/2 frames.
 *
 * <p>This parser consumes complete HTTP/2 frames from a ByteBuffer and delivers
 * them to a registered {@link H2FrameHandler} via typed callback methods.
 * No Frame objects are allocated - data flows directly to the handler.
 *
 * <p>Key features:
 * <ul>
 *   <li>Zero allocation - no Frame objects created</li>
 *   <li>Zero-copy parsing - uses ByteBuffer slices for payloads</li>
 *   <li>Handles partial frames - leaves incomplete data in buffer</li>
 *   <li>Validates frame constraints before delivery</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * H2Parser parser = new H2Parser(handler);
 * parser.setMaxFrameSize(16384);
 *
 * // Push data as it arrives
 * while (hasData) {
 *     parser.receive(buffer);
 *     buffer.compact(); // Preserve any partial frame data
 * }
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see H2FrameHandler
 */
public class H2Parser {

    private static final ResourceBundle L10N = 
        ResourceBundle.getBundle("org.bluezoo.gumdrop.http.h2.L10N");
    private static final Logger LOGGER = Logger.getLogger(H2Parser.class.getName());

    /** Frame header is always 9 bytes: length(3) + type(1) + flags(1) + streamId(4) */
    public static final int FRAME_HEADER_LENGTH = 9;

    /** Default maximum frame size per RFC 7540 */
    public static final int DEFAULT_MAX_FRAME_SIZE = 16384;

    /** Minimum allowed max frame size */
    public static final int MIN_MAX_FRAME_SIZE = 16384;

    /** Maximum allowed max frame size per RFC 7540 */
    public static final int MAX_MAX_FRAME_SIZE = 16777215; // 2^24 - 1

    private final H2FrameHandler handler;
    private int maxFrameSize = DEFAULT_MAX_FRAME_SIZE;

    /**
     * Creates a new HTTP/2 frame parser.
     *
     * @param handler the handler to receive parsed frames
     */
    public H2Parser(H2FrameHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException(L10N.getString("err.handler_null"));
        }
        this.handler = handler;
    }

    /**
     * Returns the maximum frame size this parser will accept.
     *
     * @return the maximum frame size in bytes
     */
    public int getMaxFrameSize() {
        return maxFrameSize;
    }

    /**
     * Sets the maximum frame size this parser will accept.
     *
     * <p>This should be updated when SETTINGS_MAX_FRAME_SIZE is received
     * from the peer.
     *
     * @param maxFrameSize the maximum frame size in bytes
     * @throws IllegalArgumentException if size is out of valid range
     */
    public void setMaxFrameSize(int maxFrameSize) {
        if (maxFrameSize < MIN_MAX_FRAME_SIZE || maxFrameSize > MAX_MAX_FRAME_SIZE) {
            String msg = MessageFormat.format(L10N.getString("err.max_frame_size_range"),
                MIN_MAX_FRAME_SIZE, MAX_MAX_FRAME_SIZE);
            throw new IllegalArgumentException(msg);
        }
        this.maxFrameSize = maxFrameSize;
    }

    /**
     * Parses complete HTTP/2 frames from the buffer.
     *
     * <p>This method consumes as many complete frames as possible from the
     * buffer. For each complete frame, the appropriate typed callback on
     * the handler is invoked.
     *
     * <p>If the buffer contains a partial frame at the end, the buffer
     * position is left at the start of that partial frame. The caller
     * should compact the buffer to preserve this data for the next read.
     *
     * <p>The buffer should be in read mode (flipped) when passed to this method.
     *
     * @param buf the buffer containing frame data (in read mode)
     */
    public void receive(ByteBuffer buf) {
        while (buf.hasRemaining()) {
            // Need at least FRAME_HEADER_LENGTH bytes for frame header
            if (buf.remaining() < FRAME_HEADER_LENGTH) {
                return; // Underflow - wait for more data
            }

            // Parse frame header (peek without consuming yet)
            int pos = buf.position();
            int length = ((buf.get(pos) & 0xff) << 16)
                       | ((buf.get(pos + 1) & 0xff) << 8)
                       | (buf.get(pos + 2) & 0xff);
            int type = buf.get(pos + 3) & 0xff;
            int flags = buf.get(pos + 4) & 0xff;
            int streamId = ((buf.get(pos + 5) & 0x7f) << 24) // mask reserved bit
                         | ((buf.get(pos + 6) & 0xff) << 16)
                         | ((buf.get(pos + 7) & 0xff) << 8)
                         | (buf.get(pos + 8) & 0xff);

            // Validate frame size
            if (length > maxFrameSize) {
                handler.frameError(H2FrameHandler.ERROR_FRAME_SIZE_ERROR, streamId,
                    "Frame size " + length + " exceeds maximum " + maxFrameSize);
                return;
            }

            // Check if we have complete frame (header + payload)
            if (buf.remaining() < FRAME_HEADER_LENGTH + length) {
                return; // Underflow - wait for more data
            }

            // Consume header, position at start of payload
            buf.position(pos + FRAME_HEADER_LENGTH);

            // Create a slice for the payload (zero-copy)
            int savedLimit = buf.limit();
            buf.limit(buf.position() + length);
            ByteBuffer payload = buf.slice();
            buf.limit(savedLimit);
            buf.position(buf.position() + length); // Consume payload

            // Dispatch to typed handler method
            if (LOGGER.isLoggable(Level.FINEST)) {
                LOGGER.finest("Parsing frame: type=" + type + ", flags=" + flags +
                    ", stream=" + streamId + ", length=" + length);
            }
            dispatchFrame(type, flags, streamId, payload);
        }
    }

    /**
     * Dispatches a parsed frame to the appropriate handler method.
     */
    private void dispatchFrame(int type, int flags, int streamId, ByteBuffer payload) {
        switch (type) {
            case H2FrameHandler.TYPE_DATA:
                parseDataFrame(flags, streamId, payload);
                break;
            case H2FrameHandler.TYPE_HEADERS:
                parseHeadersFrame(flags, streamId, payload);
                break;
            case H2FrameHandler.TYPE_PRIORITY:
                parsePriorityFrame(streamId, payload);
                break;
            case H2FrameHandler.TYPE_RST_STREAM:
                parseRstStreamFrame(streamId, payload);
                break;
            case H2FrameHandler.TYPE_SETTINGS:
                parseSettingsFrame(flags, streamId, payload);
                break;
            case H2FrameHandler.TYPE_PUSH_PROMISE:
                parsePushPromiseFrame(flags, streamId, payload);
                break;
            case H2FrameHandler.TYPE_PING:
                parsePingFrame(flags, streamId, payload);
                break;
            case H2FrameHandler.TYPE_GOAWAY:
                parseGoawayFrame(streamId, payload);
                break;
            case H2FrameHandler.TYPE_WINDOW_UPDATE:
                parseWindowUpdateFrame(streamId, payload);
                break;
            case H2FrameHandler.TYPE_CONTINUATION:
                parseContinuationFrame(flags, streamId, payload);
                break;
            default:
                // Unknown frame types should be ignored per RFC 7540
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Ignoring unknown frame type: " + type);
                }
                break;
        }
    }

    private void parseDataFrame(int flags, int streamId, ByteBuffer payload) {
        if (streamId == 0) {
            handler.frameError(H2FrameHandler.ERROR_PROTOCOL_ERROR, 0,
                "DATA frame with stream ID 0");
            return;
        }

        boolean padded = (flags & H2FrameHandler.FLAG_PADDED) != 0;
        boolean endStream = (flags & H2FrameHandler.FLAG_END_STREAM) != 0;

        ByteBuffer data;
        if (padded) {
            int padLength = payload.get() & 0xff;
            int dataLength = payload.remaining() - padLength;
            if (dataLength < 0) {
                handler.frameError(H2FrameHandler.ERROR_PROTOCOL_ERROR, streamId,
                    "DATA frame padding exceeds payload");
                return;
            }
            int savedLimit = payload.limit();
            payload.limit(payload.position() + dataLength);
            data = payload.slice();
            payload.limit(savedLimit);
        } else {
            data = payload.slice();
        }

        handler.dataFrameReceived(streamId, endStream, data);
    }

    private void parseHeadersFrame(int flags, int streamId, ByteBuffer payload) {
        if (streamId == 0) {
            handler.frameError(H2FrameHandler.ERROR_PROTOCOL_ERROR, 0,
                "HEADERS frame with stream ID 0");
            return;
        }

        boolean padded = (flags & H2FrameHandler.FLAG_PADDED) != 0;
        boolean endStream = (flags & H2FrameHandler.FLAG_END_STREAM) != 0;
        boolean endHeaders = (flags & H2FrameHandler.FLAG_END_HEADERS) != 0;
        boolean priority = (flags & H2FrameHandler.FLAG_PRIORITY) != 0;

        int endPos = payload.limit();
        int padLength = 0;

        if (padded) {
            padLength = payload.get() & 0xff;
            endPos -= padLength;
        }

        int streamDependency = 0;
        boolean exclusive = false;
        int weight = 16; // default weight

        if (priority) {
            int sd1 = payload.get() & 0xff;
            exclusive = (sd1 & 0x80) != 0;
            streamDependency = ((sd1 & 0x7f) << 24)
                | ((payload.get() & 0xff) << 16)
                | ((payload.get() & 0xff) << 8)
                | (payload.get() & 0xff);
            weight = (payload.get() & 0xff) + 1; // weight is 1-256
        }

        int headerBlockLength = endPos - payload.position();
        if (headerBlockLength < 0) {
            handler.frameError(H2FrameHandler.ERROR_PROTOCOL_ERROR, streamId,
                "HEADERS frame padding exceeds payload");
            return;
        }

        int savedLimit = payload.limit();
        payload.limit(payload.position() + headerBlockLength);
        ByteBuffer headerBlockFragment = payload.slice();
        payload.limit(savedLimit);

        handler.headersFrameReceived(streamId, endStream, endHeaders,
            streamDependency, exclusive, weight, headerBlockFragment);
    }

    private void parsePriorityFrame(int streamId, ByteBuffer payload) {
        if (streamId == 0) {
            handler.frameError(H2FrameHandler.ERROR_PROTOCOL_ERROR, 0,
                "PRIORITY frame with stream ID 0");
            return;
        }
        if (payload.remaining() != 5) {
            handler.frameError(H2FrameHandler.ERROR_FRAME_SIZE_ERROR, streamId,
                "PRIORITY frame must be 5 bytes");
            return;
        }

        int sd1 = payload.get() & 0xff;
        boolean exclusive = (sd1 & 0x80) != 0;
        int streamDependency = ((sd1 & 0x7f) << 24)
            | ((payload.get() & 0xff) << 16)
            | ((payload.get() & 0xff) << 8)
            | (payload.get() & 0xff);
        int weight = (payload.get() & 0xff) + 1;

        handler.priorityFrameReceived(streamId, streamDependency, exclusive, weight);
    }

    private void parseRstStreamFrame(int streamId, ByteBuffer payload) {
        if (streamId == 0) {
            handler.frameError(H2FrameHandler.ERROR_PROTOCOL_ERROR, 0,
                "RST_STREAM frame with stream ID 0");
            return;
        }
        if (payload.remaining() != 4) {
            handler.frameError(H2FrameHandler.ERROR_FRAME_SIZE_ERROR, streamId,
                "RST_STREAM frame must be 4 bytes");
            return;
        }

        int errorCode = ((payload.get() & 0xff) << 24)
            | ((payload.get() & 0xff) << 16)
            | ((payload.get() & 0xff) << 8)
            | (payload.get() & 0xff);

        handler.rstStreamFrameReceived(streamId, errorCode);
    }

    private void parseSettingsFrame(int flags, int streamId, ByteBuffer payload) {
        if (streamId != 0) {
            handler.frameError(H2FrameHandler.ERROR_PROTOCOL_ERROR, streamId,
                "SETTINGS frame with non-zero stream ID");
            return;
        }

        boolean ack = (flags & H2FrameHandler.FLAG_ACK) != 0;

        if (ack && payload.remaining() != 0) {
            handler.frameError(H2FrameHandler.ERROR_FRAME_SIZE_ERROR, 0,
                "SETTINGS ACK frame must be empty");
            return;
        }

        if (payload.remaining() % 6 != 0) {
            handler.frameError(H2FrameHandler.ERROR_FRAME_SIZE_ERROR, 0,
                "SETTINGS frame size must be multiple of 6");
            return;
        }

        Map<Integer, Integer> settings = new LinkedHashMap<Integer, Integer>();
        while (payload.hasRemaining()) {
            int identifier = ((payload.get() & 0xff) << 8)
                | (payload.get() & 0xff);
            int value = ((payload.get() & 0xff) << 24)
                | ((payload.get() & 0xff) << 16)
                | ((payload.get() & 0xff) << 8)
                | (payload.get() & 0xff);

            // Validate specific settings
            if (identifier == H2FrameHandler.SETTINGS_ENABLE_PUSH) {
                if (value != 0 && value != 1) {
                    handler.frameError(H2FrameHandler.ERROR_PROTOCOL_ERROR, 0,
                        "SETTINGS_ENABLE_PUSH must be 0 or 1");
                    return;
                }
            } else if (identifier == H2FrameHandler.SETTINGS_MAX_FRAME_SIZE) {
                if (value < MIN_MAX_FRAME_SIZE || value > MAX_MAX_FRAME_SIZE) {
                    handler.frameError(H2FrameHandler.ERROR_PROTOCOL_ERROR, 0,
                        "SETTINGS_MAX_FRAME_SIZE out of range");
                    return;
                }
            } else if (identifier == H2FrameHandler.SETTINGS_INITIAL_WINDOW_SIZE) {
                if (value > 0x7FFFFFFF) {
                    handler.frameError(H2FrameHandler.ERROR_FLOW_CONTROL_ERROR, 0,
                        "SETTINGS_INITIAL_WINDOW_SIZE too large");
                    return;
                }
            }

            settings.put(identifier, value);
        }

        handler.settingsFrameReceived(ack, settings);
    }

    private void parsePushPromiseFrame(int flags, int streamId, ByteBuffer payload) {
        if (streamId == 0) {
            handler.frameError(H2FrameHandler.ERROR_PROTOCOL_ERROR, 0,
                "PUSH_PROMISE frame with stream ID 0");
            return;
        }

        boolean padded = (flags & H2FrameHandler.FLAG_PADDED) != 0;
        boolean endHeaders = (flags & H2FrameHandler.FLAG_END_HEADERS) != 0;

        int endPos = payload.limit();

        if (padded) {
            int padLength = payload.get() & 0xff;
            endPos -= padLength;
        }

        int promisedStreamId = ((payload.get() & 0x7f) << 24)
            | ((payload.get() & 0xff) << 16)
            | ((payload.get() & 0xff) << 8)
            | (payload.get() & 0xff);

        int headerBlockLength = endPos - payload.position();
        if (headerBlockLength < 0) {
            handler.frameError(H2FrameHandler.ERROR_PROTOCOL_ERROR, streamId,
                "PUSH_PROMISE frame padding exceeds payload");
            return;
        }

        int savedLimit = payload.limit();
        payload.limit(payload.position() + headerBlockLength);
        ByteBuffer headerBlockFragment = payload.slice();
        payload.limit(savedLimit);

        handler.pushPromiseFrameReceived(streamId, promisedStreamId,
            endHeaders, headerBlockFragment);
    }

    private void parsePingFrame(int flags, int streamId, ByteBuffer payload) {
        if (streamId != 0) {
            handler.frameError(H2FrameHandler.ERROR_PROTOCOL_ERROR, streamId,
                "PING frame with non-zero stream ID");
            return;
        }
        if (payload.remaining() != 8) {
            handler.frameError(H2FrameHandler.ERROR_FRAME_SIZE_ERROR, 0,
                "PING frame must be 8 bytes");
            return;
        }

        boolean ack = (flags & H2FrameHandler.FLAG_ACK) != 0;

        long opaqueData = ((long) (payload.get() & 0xff) << 56)
            | ((long) (payload.get() & 0xff) << 48)
            | ((long) (payload.get() & 0xff) << 40)
            | ((long) (payload.get() & 0xff) << 32)
            | ((long) (payload.get() & 0xff) << 24)
            | ((long) (payload.get() & 0xff) << 16)
            | ((long) (payload.get() & 0xff) << 8)
            | ((long) (payload.get() & 0xff));

        handler.pingFrameReceived(ack, opaqueData);
    }

    private void parseGoawayFrame(int streamId, ByteBuffer payload) {
        if (streamId != 0) {
            handler.frameError(H2FrameHandler.ERROR_PROTOCOL_ERROR, streamId,
                "GOAWAY frame with non-zero stream ID");
            return;
        }
        if (payload.remaining() < 8) {
            handler.frameError(H2FrameHandler.ERROR_FRAME_SIZE_ERROR, 0,
                "GOAWAY frame must be at least 8 bytes");
            return;
        }

        int lastStreamId = ((payload.get() & 0x7f) << 24)
            | ((payload.get() & 0xff) << 16)
            | ((payload.get() & 0xff) << 8)
            | (payload.get() & 0xff);

        int errorCode = ((payload.get() & 0xff) << 24)
            | ((payload.get() & 0xff) << 16)
            | ((payload.get() & 0xff) << 8)
            | (payload.get() & 0xff);

        ByteBuffer debugData = payload.slice();

        handler.goawayFrameReceived(lastStreamId, errorCode, debugData);
    }

    private void parseWindowUpdateFrame(int streamId, ByteBuffer payload) {
        if (payload.remaining() != 4) {
            handler.frameError(H2FrameHandler.ERROR_FRAME_SIZE_ERROR, streamId,
                "WINDOW_UPDATE frame must be 4 bytes");
            return;
        }

        int windowSizeIncrement = ((payload.get() & 0x7f) << 24)
            | ((payload.get() & 0xff) << 16)
            | ((payload.get() & 0xff) << 8)
            | (payload.get() & 0xff);

        if (windowSizeIncrement == 0) {
            handler.frameError(H2FrameHandler.ERROR_PROTOCOL_ERROR, streamId,
                "WINDOW_UPDATE increment must be non-zero");
            return;
        }

        handler.windowUpdateFrameReceived(streamId, windowSizeIncrement);
    }

    private void parseContinuationFrame(int flags, int streamId, ByteBuffer payload) {
        if (streamId == 0) {
            handler.frameError(H2FrameHandler.ERROR_PROTOCOL_ERROR, 0,
                "CONTINUATION frame with stream ID 0");
            return;
        }

        boolean endHeaders = (flags & H2FrameHandler.FLAG_END_HEADERS) != 0;
        ByteBuffer headerBlockFragment = payload.slice();

        handler.continuationFrameReceived(streamId, endHeaders, headerBlockFragment);
    }
}
