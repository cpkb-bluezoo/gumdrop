/*
 * ProtobufParser.java
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

package org.bluezoo.gumdrop.telemetry.protobuf;

import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.ResourceBundle;

/**
 * Push-based protobuf parser.
 *
 * <p>This parser processes protobuf wire format data incrementally, calling
 * handler methods as fields are parsed. It uses an all-or-nothing approach
 * for each field: if there isn't enough data to parse a complete field,
 * the buffer position is left unchanged (at the start of the incomplete
 * field) and the parser enters an underflow state.
 *
 * <h3>Usage</h3>
 * <pre>
 * ProtobufHandler handler = new MyHandler();
 * ProtobufParser parser = new ProtobufParser(handler);
 *
 * while (channel.read(buffer) &gt; 0) {
 *     buffer.flip();
 *     parser.receive(buffer);
 *     buffer.compact();
 * }
 *
 * parser.close();
 * </pre>
 *
 * <h3>Buffer Management</h3>
 * <p>The caller is responsible for managing the input buffer. After
 * {@code receive()} returns, any unconsumed data (incomplete field)
 * remains in the buffer between position and limit. The caller should
 * call {@code compact()} before reading more data into the buffer.
 *
 * <h3>Underflow Handling</h3>
 * <p>When the parser cannot complete a field due to insufficient data,
 * it enters an underflow state. The next {@code receive()} call will
 * attempt to parse from the same position. If {@code close()} is called
 * while in underflow state, an exception is thrown.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ProtobufHandler
 */
public class ProtobufParser {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.telemetry.protobuf.L10N");

    /** Wire type for variable-length integers. */
    private static final int WIRETYPE_VARINT = 0;

    /** Wire type for 64-bit fixed values. */
    private static final int WIRETYPE_I64 = 1;

    /** Wire type for length-delimited values. */
    private static final int WIRETYPE_LEN = 2;

    /** Wire type for 32-bit fixed values. */
    private static final int WIRETYPE_I32 = 5;

    private final ProtobufHandler handler;

    // Message nesting - tracks remaining bytes at each level
    private final Deque<Integer> messageStack = new ArrayDeque<>();

    // Underflow state
    private boolean underflow;

    /**
     * Creates a new parser with the given handler.
     *
     * @param handler the handler to receive parse events
     */
    public ProtobufParser(ProtobufHandler handler) {
        this.handler = handler;
    }

    /**
     * Returns true if the parser is in underflow state.
     *
     * <p>Underflow means the last {@code receive()} call ended with an
     * incomplete field. More data is needed before parsing can continue.
     *
     * @return true if in underflow state
     */
    public boolean isUnderflow() {
        return underflow;
    }

    /**
     * Processes protobuf data from the buffer.
     *
     * <p>Parses as many complete fields as possible, calling handler methods
     * for each. If the buffer contains an incomplete field, the parser leaves
     * the position at the start of that field and enters underflow state.
     *
     * @param data the buffer to read from (must be in read mode)
     * @throws ProtobufParseException if the data is malformed
     */
    public void receive(ByteBuffer data) throws ProtobufParseException {
        underflow = false;

        while (data.hasRemaining()) {
            // Check if we've completed any nested messages
            while (!messageStack.isEmpty() && messageStack.peek() <= 0) {
                messageStack.pop();
                handler.endMessage();
            }

            // If no more data after closing messages, we're done
            if (!data.hasRemaining()) {
                break;
            }

            // Mark position before attempting to parse a field
            int fieldStart = data.position();

            // Try to read the tag
            long tagValue = tryReadVarint(data);
            if (tagValue < 0) {
                // Underflow - reset position and return
                data.position(fieldStart);
                underflow = true;
                return;
            }

            int tag = (int) tagValue;
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x07;

            if (fieldNumber == 0) {
                throw new ProtobufParseException(L10N.getString("err.invalid_field_number"));
            }

            int bytesConsumed;

            switch (wireType) {
                case WIRETYPE_VARINT: {
                    int valueStart = data.position();
                    long value = tryReadVarint(data);
                    if (value < 0) {
                        data.position(fieldStart);
                        underflow = true;
                        return;
                    }
                    bytesConsumed = data.position() - fieldStart;
                    handler.handleVarint(fieldNumber, value);
                    break;
                }

                case WIRETYPE_I64: {
                    if (data.remaining() < 8) {
                        data.position(fieldStart);
                        underflow = true;
                        return;
                    }
                    long value = readFixed64(data);
                    bytesConsumed = data.position() - fieldStart;
                    handler.handleFixed64(fieldNumber, value);
                    break;
                }

                case WIRETYPE_I32: {
                    if (data.remaining() < 4) {
                        data.position(fieldStart);
                        underflow = true;
                        return;
                    }
                    int value = readFixed32(data);
                    bytesConsumed = data.position() - fieldStart;
                    handler.handleFixed32(fieldNumber, value);
                    break;
                }

                case WIRETYPE_LEN: {
                    int lenStart = data.position();
                    long lengthValue = tryReadVarint(data);
                    if (lengthValue < 0) {
                        data.position(fieldStart);
                        underflow = true;
                        return;
                    }

                    int length = (int) lengthValue;
                    if (length < 0) {
                        String msg = MessageFormat.format(
                                L10N.getString("err.negative_length"), length);
                        throw new ProtobufParseException(msg);
                    }

                    if (handler.isMessage(fieldNumber)) {
                        // Embedded message - decrement parent levels by tag + length prefix
                        // BEFORE pushing the new message (so we don't wrongly decrement it)
                        bytesConsumed = data.position() - fieldStart;
                        decrementMessageBytes(bytesConsumed);
                        bytesConsumed = 0; // Already decremented, don't do it again below
                        handler.startMessage(fieldNumber);
                        messageStack.push(length);
                    } else {
                        // Bytes/string - need all content available
                        if (data.remaining() < length) {
                            data.position(fieldStart);
                            underflow = true;
                            return;
                        }

                        // Create a slice for the content
                        int contentStart = data.position();
                        ByteBuffer content = data.slice();
                        content.limit(length);
                        data.position(contentStart + length);

                        bytesConsumed = data.position() - fieldStart;
                        handler.handleBytes(fieldNumber, content.asReadOnlyBuffer());
                    }
                    break;
                }

                default:
                    String msg = MessageFormat.format(
                            L10N.getString("err.unknown_wire_type"), wireType);
                    throw new ProtobufParseException(msg);
            }

            // Decrement bytes remaining in nested messages
            decrementMessageBytes(bytesConsumed);
        }

        // Check for any completed messages at the end
        while (!messageStack.isEmpty() && messageStack.peek() <= 0) {
            messageStack.pop();
            handler.endMessage();
        }
    }

    /**
     * Completes parsing and validates state.
     *
     * @throws ProtobufParseException if in underflow state or unclosed messages
     */
    public void close() throws ProtobufParseException {
        if (underflow) {
            throw new ProtobufParseException(L10N.getString("err.incomplete_field"));
        }
        if (!messageStack.isEmpty()) {
            String msg = MessageFormat.format(
                    L10N.getString("err.unclosed_messages"), messageStack.size());
            throw new ProtobufParseException(msg);
        }
    }

    /**
     * Resets the parser to initial state.
     *
     * <p>Call this to reuse the parser for a new independent message.
     */
    public void reset() {
        messageStack.clear();
        underflow = false;
    }

    // -- Private helper methods --

    /**
     * Attempts to read a varint from the buffer.
     *
     * @param data the buffer
     * @return the varint value, or -1 if not enough data (position unchanged on failure)
     * @throws ProtobufParseException if the varint is malformed
     */
    private long tryReadVarint(ByteBuffer data) throws ProtobufParseException {
        int startPos = data.position();
        long result = 0;
        int shift = 0;

        while (data.hasRemaining()) {
            byte b = data.get();
            result |= (long) (b & 0x7F) << shift;

            if ((b & 0x80) == 0) {
                return result; // Complete
            }

            shift += 7;
            if (shift >= 64) {
                throw new ProtobufParseException(L10N.getString("err.varint_too_long"));
            }
        }

        // Not enough data - reset position
        data.position(startPos);
        return -1;
    }

    /**
     * Reads a fixed 64-bit value in little-endian order.
     * Caller must ensure 8 bytes are available.
     */
    private long readFixed64(ByteBuffer data) {
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result |= ((long) (data.get() & 0xFF)) << (i * 8);
        }
        return result;
    }

    /**
     * Reads a fixed 32-bit value in little-endian order.
     * Caller must ensure 4 bytes are available.
     */
    private int readFixed32(ByteBuffer data) {
        int result = 0;
        for (int i = 0; i < 4; i++) {
            result |= (data.get() & 0xFF) << (i * 8);
        }
        return result;
    }

    /**
     * Decrements the remaining byte count for all nested messages.
     * When bytes are consumed, they count against all enclosing message boundaries.
     */
    private void decrementMessageBytes(int count) {
        if (messageStack.isEmpty() || count == 0) {
            return;
        }
        
        // Decrement all levels - use temporary array to modify
        Integer[] levels = messageStack.toArray(new Integer[0]);
        messageStack.clear();
        for (int i = 0; i < levels.length; i++) {
            levels[i] = levels[i] - count;
        }
        for (Integer level : levels) {
            messageStack.addLast(level);
        }
    }
}
