/*
 * RESPDecoder.java
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

package org.bluezoo.gumdrop.redis.codec;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Decodes RESP wire format to values.
 *
 * <p>This decoder handles streaming input, accumulating data until complete
 * RESP values can be parsed. It is designed for use with non-blocking I/O
 * where data arrives in arbitrary chunks.
 *
 * <h4>Usage Pattern</h4>
 * <pre>{@code
 * RESPDecoder decoder = new RESPDecoder();
 *
 * // In receive callback:
 * decoder.receive(buffer);
 *
 * RESPValue value;
 * while ((value = decoder.next()) != null) {
 *     // Process complete value
 *     if (value.isError()) {
 *         handleError(value.getErrorMessage());
 *     } else {
 *         handleReply(value);
 *     }
 * }
 * }</pre>
 *
 * <p>The decoder maintains internal state between calls, so partial
 * values are preserved across multiple {@code receive()} calls.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RESPDecoder {

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.redis.codec.L10N");

    private static final Charset UTF_8 = StandardCharsets.UTF_8;
    private static final int DEFAULT_BUFFER_SIZE = 16384;
    private static final int MAX_INLINE_LENGTH = 65536;

    private ByteBuffer buffer;
    private int parsePosition;

    /**
     * Creates a new RESP decoder with default buffer size.
     */
    public RESPDecoder() {
        this(DEFAULT_BUFFER_SIZE);
    }

    /**
     * Creates a new RESP decoder with the specified initial buffer size.
     *
     * @param initialCapacity the initial buffer capacity
     */
    public RESPDecoder(int initialCapacity) {
        this.buffer = ByteBuffer.allocate(initialCapacity);
        this.buffer.flip(); // Start in read mode with no data
        this.parsePosition = 0;
    }

    /**
     * Receives data for decoding.
     *
     * <p>The data is appended to any existing buffered data. Call
     * {@link #next()} to retrieve decoded values.
     *
     * @param data the data to decode
     */
    public void receive(ByteBuffer data) {
        if (!data.hasRemaining()) {
            return;
        }
        // Calculate how much unread data we have
        int remaining = buffer.remaining();
        int needed = remaining + data.remaining();
        if (needed > buffer.capacity()) {
            // Need to grow the buffer
            int newCapacity = buffer.capacity();
            while (newCapacity < needed) {
                newCapacity = newCapacity * 2;
            }
            ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);
            // Copy unread data to new buffer
            newBuffer.put(buffer);
            newBuffer.put(data);
            newBuffer.flip();
            buffer = newBuffer;
            parsePosition = 0;
        } else if (buffer.position() > 0 && parsePosition > 0) {
            // Compact: move unread data to beginning
            buffer.compact();
            buffer.put(data);
            buffer.flip();
            parsePosition = 0;
        } else {
            // Append to existing buffer
            int pos = buffer.limit();
            buffer.limit(buffer.capacity());
            buffer.position(pos);
            buffer.put(data);
            buffer.flip();
            buffer.position(parsePosition);
        }
    }

    /**
     * Attempts to decode the next complete RESP value.
     *
     * <p>If a complete value is available, it is returned and removed
     * from the buffer. If the data is incomplete, returns null and
     * the data is preserved for the next call.
     *
     * @return the next decoded value, or null if incomplete
     * @throws RESPException if the data is malformed
     */
    public RESPValue next() throws RESPException {
        if (!buffer.hasRemaining()) {
            return null;
        }
        buffer.position(parsePosition);
        RESPValue result = tryParse();
        if (result != null) {
            // Successfully parsed, update parse position
            parsePosition = buffer.position();
        } else {
            // Incomplete, reset position
            buffer.position(parsePosition);
        }
        return result;
    }

    /**
     * Attempts to parse a RESP value from the current buffer position.
     * Returns null if incomplete.
     */
    private RESPValue tryParse() throws RESPException {
        if (!buffer.hasRemaining()) {
            return null;
        }
        int startPos = buffer.position();
        byte prefix = buffer.get();
        RESPType type = RESPType.fromPrefix(prefix);
        RESPValue result;
        switch (type) {
            case SIMPLE_STRING:
                result = parseSimpleString();
                break;
            case ERROR:
                result = parseError();
                break;
            case INTEGER:
                result = parseInteger();
                break;
            case BULK_STRING:
                result = parseBulkString();
                break;
            case ARRAY:
                result = parseArray();
                break;
            default:
                String msg = MessageFormat.format(L10N.getString("err.unknown_type"), type);
                throw new RESPException(msg);
        }
        if (result == null) {
            // Incomplete, reset to start
            buffer.position(startPos);
        }
        return result;
    }

    /**
     * Parses a simple string (+...\r\n).
     */
    private RESPValue parseSimpleString() throws RESPException {
        String line = readLine();
        if (line == null) {
            return null;
        }
        return RESPValue.simpleString(line);
    }

    /**
     * Parses an error (-...\r\n).
     */
    private RESPValue parseError() throws RESPException {
        String line = readLine();
        if (line == null) {
            return null;
        }
        return RESPValue.error(line);
    }

    /**
     * Parses an integer (:...\r\n).
     */
    private RESPValue parseInteger() throws RESPException {
        String line = readLine();
        if (line == null) {
            return null;
        }
        try {
            long value = Long.parseLong(line);
            return RESPValue.integer(value);
        } catch (NumberFormatException e) {
            String msg = MessageFormat.format(L10N.getString("err.invalid_integer"), line);
            throw new RESPException(msg, e);
        }
    }

    /**
     * Parses a bulk string ($length\r\ndata\r\n).
     */
    private RESPValue parseBulkString() throws RESPException {
        String lengthLine = readLine();
        if (lengthLine == null) {
            return null;
        }
        int length;
        try {
            length = Integer.parseInt(lengthLine);
        } catch (NumberFormatException e) {
            String msg = MessageFormat.format(L10N.getString("err.invalid_bulk_string_length"), lengthLine);
            throw new RESPException(msg, e);
        }
        // Null bulk string
        if (length < 0) {
            return RESPValue.nullValue();
        }
        // Check if we have enough data
        if (buffer.remaining() < length + 2) {
            return null;
        }
        // Read the data
        byte[] data = new byte[length];
        buffer.get(data);
        // Read trailing CRLF
        if (buffer.remaining() < 2) {
            return null;
        }
        byte cr = buffer.get();
        byte lf = buffer.get();
        if (cr != '\r' || lf != '\n') {
            throw new RESPException(L10N.getString("err.no_crlf_after_bulk_string"));
        }
        return RESPValue.bulkString(data);
    }

    /**
     * Parses an array (*count\r\n...).
     */
    private RESPValue parseArray() throws RESPException {
        String countLine = readLine();
        if (countLine == null) {
            return null;
        }
        int count;
        try {
            count = Integer.parseInt(countLine);
        } catch (NumberFormatException e) {
            String msg = MessageFormat.format(L10N.getString("err.invalid_array_count"), countLine);
            throw new RESPException(msg, e);
        }
        // Null array
        if (count < 0) {
            return RESPValue.nullValue();
        }
        // Empty array
        if (count == 0) {
            return RESPValue.array(new ArrayList<RESPValue>(0));
        }
        // Parse array elements
        List<RESPValue> elements = new ArrayList<RESPValue>(count);
        for (int i = 0; i < count; i++) {
            RESPValue element = tryParse();
            if (element == null) {
                return null; // Incomplete
            }
            elements.add(element);
        }
        return RESPValue.array(elements);
    }

    /**
     * Reads a line terminated by CRLF.
     * Returns null if incomplete.
     */
    private String readLine() throws RESPException {
        int start = buffer.position();
        int limit = buffer.limit();
        for (int i = start; i < limit - 1; i++) {
            if (buffer.get(i) == '\r' && buffer.get(i + 1) == '\n') {
                // Found CRLF
                int length = i - start;
                if (length > MAX_INLINE_LENGTH) {
                    String msg = MessageFormat.format(L10N.getString("err.line_too_long"), length);
                    throw new RESPException(msg);
                }
                byte[] lineBytes = new byte[length];
                buffer.get(lineBytes);
                buffer.get(); // Skip CR
                buffer.get(); // Skip LF
                return new String(lineBytes, UTF_8);
            }
        }
        // Incomplete line
        return null;
    }

    /**
     * Resets the decoder, discarding any buffered data.
     */
    public void reset() {
        buffer.clear();
        buffer.flip();
        parsePosition = 0;
    }

    /**
     * Returns the number of bytes currently buffered.
     *
     * @return the buffered byte count
     */
    public int bufferedBytes() {
        return buffer.remaining();
    }

}

