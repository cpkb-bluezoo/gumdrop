/*
 * RespDecoder.java
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

import org.bluezoo.gumdrop.util.ByteBufferPool;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * Decodes RESP wire format to values (RESP spec — "RESP protocol description").
 *
 * <p>Supports RESP2 data types: Simple String (+), Error (-), Integer (:),
 * Bulk String ($), and Array (*), as well as RESP3 types: Map (%),
 * Set (~), Double (,), Boolean (#), Null (_), Push (&gt;), Verbatim
 * String (=), Big Number ((), and Blob Error (!).
 *
 * <p>This decoder handles streaming input, accumulating data until complete
 * RESP values can be parsed. It is designed for use with non-blocking I/O
 * where data arrives in arbitrary chunks.
 *
 * <h4>Usage Pattern</h4>
 * <pre>{@code
 * RespDecoder decoder = new RespDecoder();
 *
 * // In receive callback:
 * decoder.receive(buffer);
 *
 * RespValue value;
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
 * @see <a href="https://redis.io/docs/reference/protocol-spec/">RESP Protocol Specification</a>
 */
public class RespDecoder {

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.redis.codec.L10N");

    private static final Charset UTF_8 = StandardCharsets.UTF_8;
    private static final int DEFAULT_BUFFER_SIZE = 16384;
    private static final int MAX_INLINE_LENGTH = 65536;
    /** Default maximum elements in an array, map entry pairs, set, or push. */
    static final int DEFAULT_MAX_COLLECTION_ELEMENTS = 1024 * 1024;
    /** Maximum nesting depth for compound RESP types. */
    static final int MAX_NESTING_DEPTH = 32;
    /** Default maximum bytes buffered while awaiting a complete value. */
    static final int DEFAULT_MAX_ACCUMULATOR_BYTES = 128 * 1024 * 1024;
    /** Default maximum bulk / verbatim / blob payload length. */
    static final int DEFAULT_MAX_BULK_LENGTH = 64 * 1024 * 1024;

    /** Cap on up-front collection allocation, so a claimed count cannot force a huge allocation. */
    private static final int INITIAL_CAPACITY_CAP = 1024;

    private int maxCollectionElements = DEFAULT_MAX_COLLECTION_ELEMENTS;
    private int maxAccumulatorBytes = DEFAULT_MAX_ACCUMULATOR_BYTES;
    private int maxBulkLength = DEFAULT_MAX_BULK_LENGTH;

    private ByteBuffer buffer;
    private int parsePosition;

    // The state machine. Everything below is what has been parsed of the
    // value being read; none of it is parsed again.
    private static final int STATE_TYPE = 0;
    private static final int STATE_LINE = 1;
    private static final int STATE_BODY = 2;
    private static final int STATE_BODY_END = 3;
    /** A buffer larger than this is replaced by one of the default size when it has been consumed. */
    private static final int SHRINK_ABOVE = 64 * 1024;
    private static final int INITIAL_BODY_CAPACITY = 1024 * 1024;

    private int state = STATE_TYPE;
    // the type of the value whose line is being read, and where that line
    // is in the buffer once found; lineScanned is how much of an
    // incomplete one has been looked through already
    private RespType lineType;
    private int lineScanned;
    private int lineStart;
    private int lineLength;
    // whether the last lineNumber() call read a number
    private boolean numberValid;
    // the body of a bulk string, verbatim string or blob error
    private RespType bodyType;
    private byte[] bodyData;
    private int bodyLength;
    private int bodyFilled;
    private int bodyEndSeen;
    // the arrays, maps, sets and pushes being built, outermost first
    private final Frame[] frames = new Frame[MAX_NESTING_DEPTH];
    private int depth;
    // set once the data is malformed: it stays so
    private RespException failure;

    /**
     * Creates a new RESP decoder with default buffer size.
     */
    public RespDecoder() {
        this(DEFAULT_BUFFER_SIZE);
    }

    /**
     * Creates a new RESP decoder with the specified initial buffer size.
     *
     * @param initialCapacity the initial buffer capacity
     */
    public RespDecoder(int initialCapacity) {
        this.buffer = ByteBufferPool.acquire(initialCapacity);
        this.buffer.flip(); // Start in read mode with no data
        this.parsePosition = 0;
    }

    /**
     * Sets the maximum number of elements accepted in one array, map, set
     * or push reply.
     *
     * @param max the limit, at least 1
     */
    public void setMaxCollectionElements(int max) {
        if (max < 1) {
            throw new IllegalArgumentException(Integer.toString(max));
        }
        maxCollectionElements = max;
    }

    /**
     * Sets the maximum length of one bulk string, verbatim string or blob
     * error payload.
     *
     * @param max the limit in bytes, at least 1
     */
    public void setMaxBulkLength(int max) {
        if (max < 1) {
            throw new IllegalArgumentException(Integer.toString(max));
        }
        maxBulkLength = max;
    }

    /**
     * Sets the maximum number of bytes buffered while waiting for the rest
     * of an incomplete reply. A reply larger than this fails regardless of
     * the other limits, so it should be at least the bulk limit plus
     * framing.
     *
     * @param max the limit in bytes, at least 1
     */
    public void setMaxAccumulatorBytes(int max) {
        if (max < 1) {
            throw new IllegalArgumentException(Integer.toString(max));
        }
        maxAccumulatorBytes = max;
    }

    /**
     * Receives data for decoding.
     *
     * <p>The data is appended to any existing buffered data. Call
     * {@link #next()} to retrieve decoded values.
     *
     * @param data the data to decode
     */
    public void receive(ByteBuffer data) throws RespException {
        if (!data.hasRemaining()) {
            return;
        }
        // Calculate how much unread data we have
        int remaining = buffer.remaining();
        int needed = remaining + data.remaining();
        if (needed > maxAccumulatorBytes) {
            throw new RespException(MessageFormat.format(
                    L10N.getString("err.buffer_too_large"), needed));
        }
        if (needed > buffer.capacity()) {
            // Need to grow the buffer
            int newCapacity = buffer.capacity();
            while (newCapacity < needed) {
                newCapacity = newCapacity * 2;
            }
            ByteBuffer newBuffer = ByteBufferPool.acquire(newCapacity);
            // Copy unread data to new buffer
            newBuffer.put(buffer);
            newBuffer.put(data);
            newBuffer.flip();
            ByteBufferPool.release(buffer);
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
     * <p>The decoder is a resumable state machine: what has been parsed of
     * an incomplete value (the elements of an array so far, the bytes of a
     * bulk string so far) is kept, and consumed from the buffer, so each
     * byte is parsed once however the reply is split across reads. A
     * line is consumed only when it is complete.
     *
     * @return the next decoded value, or null if incomplete
     * @throws RespException if the data is malformed; the decoder then
     * keeps failing with the same exception
     */
    public RespValue next() throws RespException {
        if (failure != null) {
            throw failure;
        }
        buffer.position(parsePosition);
        try {
            return advance();
        } catch (RespException e) {
            failure = e;
            throw e;
        } finally {
            parsePosition = buffer.position();
            shrinkIfIdle();
        }
    }

    // A reply larger than the usual makes receive() grow the buffer. Once
    // everything in it has been consumed, give it back and start again at
    // the default size, rather than hold it for the life of the connection.
    private void shrinkIfIdle() {
        if (buffer.capacity() > SHRINK_ABOVE && !buffer.hasRemaining()) {
            ByteBufferPool.release(buffer);
            buffer = ByteBufferPool.acquire(DEFAULT_BUFFER_SIZE);
            buffer.flip();
            parsePosition = 0;
        }
    }

    private void checkCollectionCount(int count) throws RespException {
        if (count > maxCollectionElements) {
            throw new RespException(MessageFormat.format(
                    L10N.getString("err.collection_too_large"), count));
        }
    }

    private void checkBulkLength(int length) throws RespException {
        if (length > maxBulkLength) {
            throw new RespException(MessageFormat.format(
                    L10N.getString("err.bulk_too_large"), length));
        }
    }

    /**
     * Runs the state machine over the buffered bytes until a top-level
     * value is complete or more bytes are needed.
     */
    private RespValue advance() throws RespException {
        while (true) {
            RespValue complete;
            switch (state) {
                case STATE_TYPE:
                    if (!buffer.hasRemaining()) {
                        return null;
                    }
                    lineType = RespType.fromPrefix(buffer.get());
                    lineScanned = 0;
                    state = STATE_LINE;
                    continue;
                case STATE_LINE:
                    if (!findLine()) {
                        return null;
                    }
                    complete = onLine();
                    break;
                case STATE_BODY:
                    if (!fillBody()) {
                        return null;
                    }
                    state = STATE_BODY_END;
                    bodyEndSeen = 0;
                    continue;
                default: // STATE_BODY_END
                    if (!readBodyEnd()) {
                        return null;
                    }
                    complete = finishBody();
                    break;
            }
            if (complete != null) {
                RespValue top = deliver(complete);
                if (top != null) {
                    return top;
                }
            }
        }
    }

    /**
     * Hands a completed value to the compound being built, completing
     * that and the ones enclosing it in turn; returns the value if none is
     * being built, null if more elements are due.
     */
    private RespValue deliver(RespValue value) {
        RespValue v = value;
        while (true) {
            if (depth == 0) {
                return v;
            }
            Frame frame = frames[depth - 1];
            if (frame.type == RespType.MAP) {
                if (frame.pendingKey == null) {
                    frame.pendingKey = v;
                    return null;
                }
                frame.entries.put(frame.pendingKey, v);
                frame.pendingKey = null;
            } else {
                frame.elements.add(v);
            }
            if (--frame.remaining > 0) {
                return null;
            }
            frames[--depth] = null;
            v = frame.build();
        }
    }

    /** One array, map, set or push being built. */
    private static final class Frame {
        final RespType type;
        int remaining;
        final List<RespValue> elements;
        final Map<RespValue, RespValue> entries;
        RespValue pendingKey;

        Frame(RespType type, int count) {
            this.type = type;
            this.remaining = count;
            int capacity = Math.min(count, INITIAL_CAPACITY_CAP);
            if (type == RespType.MAP) {
                this.elements = null;
                this.entries = new LinkedHashMap<RespValue, RespValue>(capacity);
            } else {
                this.elements = new ArrayList<RespValue>(capacity);
                this.entries = null;
            }
        }

        RespValue build() {
            switch (type) {
                case MAP:
                    return RespValue.map(entries);
                case SET:
                    return RespValue.set(elements);
                case PUSH:
                    return RespValue.push(elements);
                default:
                    return RespValue.array(elements);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lines
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Looks for the end of the current line, from where the last look
     * stopped. On success {@code lineStart} and {@code lineLength} locate
     * it (the buffer is not advanced). A line that is still incomplete is
     * left in the buffer.
     */
    private boolean findLine() throws RespException {
        int start = buffer.position();
        int limit = buffer.limit();
        int from = start + Math.max(0, lineScanned - 1);
        int crIndex = findCrLf(from, limit);
        if (crIndex < 0) {
            lineScanned = limit - start;
            if (lineScanned > MAX_INLINE_LENGTH) {
                throw new RespException(MessageFormat.format(
                        L10N.getString("err.line_too_long"), lineScanned));
            }
            return false;
        }
        int length = crIndex - start;
        if (length > MAX_INLINE_LENGTH) {
            throw new RespException(MessageFormat.format(
                    L10N.getString("err.line_too_long"), length));
        }
        lineStart = start;
        lineLength = length;
        return true;
    }

    /** Moves past the current line and its CRLF. */
    private void endLine() {
        buffer.position(lineStart + lineLength + 2);
        lineScanned = 0;
        state = STATE_TYPE;
    }

    private String lineString() {
        byte[] bytes = new byte[lineLength];
        for (int i = 0; i < lineLength; i++) {
            bytes[i] = buffer.get(lineStart + i);
        }
        return new String(bytes, UTF_8);
    }

    /**
     * The current line as a number, read from the buffer without making a
     * string of it. Whether it was one (the rules of {@link
     * Long#parseLong(String)}, for ASCII digits) is left in
     * {@code numberValid}.
     */
    private long lineNumber() {
        numberValid = false;
        int i = 0;
        int end = lineLength;
        boolean negative = false;
        if (end == 0) {
            return 0;
        }
        byte first = buffer.get(lineStart);
        if (first == '-' || first == '+') {
            negative = first == '-';
            i = 1;
            if (end == 1) {
                return 0;
            }
        }
        long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        long multmin = limit / 10;
        long result = 0;
        for (; i < end; i++) {
            int digit = buffer.get(lineStart + i) - '0';
            if (digit < 0 || digit > 9 || result < multmin) {
                return 0;
            }
            result *= 10;
            if (result < limit + digit) {
                return 0;
            }
            result -= digit;
        }
        numberValid = true;
        return negative ? result : -result;
    }

    /** The current line as an int, as for {@link Integer#parseInt(String)}; false if it is not one. */
    private boolean lineIsInt() {
        long n = lineNumber();
        return numberValid && n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE;
    }

    /** Handles the line of the type being read; returns the value if that completes one. */
    private RespValue onLine() throws RespException {
        switch (lineType) {
            case SIMPLE_STRING: {
                String line = lineString();
                endLine();
                return RespValue.simpleString(line);
            }
            case ERROR: {
                String line = lineString();
                endLine();
                return RespValue.error(line);
            }
            case INTEGER: {
                long n = lineNumber();
                if (!numberValid) {
                    String msg = MessageFormat.format(
                            L10N.getString("err.invalid_integer"), lineString());
                    throw new RespException(msg);
                }
                endLine();
                return RespValue.integer(n);
            }
            case BULK_STRING:
                return onLength(L10N.getString("err.invalid_bulk_string_length"), true);
            case VERBATIM_STRING:
                return onLength("Invalid verbatim string length: ", false);
            case BLOB_ERROR:
                return onLength("Invalid blob error length: ", false);
            case ARRAY:
                return onCount(L10N.getString("err.invalid_array_count"), true);
            case MAP:
                return onCount("Invalid map count: ", false);
            case SET:
                return onCount("Invalid set count: ", false);
            case PUSH:
                return onCount("Invalid push count: ", false);
            case DOUBLE: {
                String line = lineString();
                endLine();
                double val;
                if ("inf".equals(line)) {
                    val = Double.POSITIVE_INFINITY;
                } else if ("-inf".equals(line)) {
                    val = Double.NEGATIVE_INFINITY;
                } else if ("nan".equals(line)) {
                    val = Double.NaN;
                } else {
                    try {
                        val = Double.parseDouble(line);
                    } catch (NumberFormatException e) {
                        throw new RespException("Invalid double value: " + line, e);
                    }
                }
                return RespValue.doubleValue(val);
            }
            case BOOLEAN: {
                String line = lineString();
                endLine();
                if ("t".equals(line)) {
                    return RespValue.booleanValue(true);
                } else if ("f".equals(line)) {
                    return RespValue.booleanValue(false);
                }
                throw new RespException("Invalid boolean value: " + line);
            }
            case NULL:
                endLine();
                return RespValue.resp3Null();
            case BIG_NUMBER: {
                String line = lineString();
                endLine();
                return RespValue.bigNumber(line);
            }
            default:
                throw new RespException(MessageFormat.format(
                        L10N.getString("err.unknown_type"), lineType));
        }
    }

    /**
     * The length line of a bulk string, verbatim string or blob error:
     * starts the body, or completes the null value of a negative length.
     */
    private RespValue onLength(String invalidMessage, boolean localised) throws RespException {
        if (!lineIsInt()) {
            String line = lineString();
            String msg = localised ? MessageFormat.format(invalidMessage, line)
                    : invalidMessage + line;
            throw new RespException(msg);
        }
        int length = (int) lineNumber();
        endLine();
        if (length < 0) {
            return RespValue.nullValue();
        }
        checkBulkLength(length);
        bodyType = lineType;
        bodyLength = length;
        bodyFilled = 0;
        // when all of it is here, one array of the right size; otherwise
        // grow with what arrives, so a length claimed by a peer that sends
        // nothing costs nothing
        int capacity = buffer.remaining() >= length + 2 ? length
                : Math.min(length, INITIAL_BODY_CAPACITY);
        bodyData = new byte[capacity];
        state = STATE_BODY;
        return null;
    }

    /**
     * The count line of an array, map, set or push: starts building it,
     * or completes the null value of a negative count or an empty array.
     */
    private RespValue onCount(String invalidMessage, boolean localised) throws RespException {
        if (!lineIsInt()) {
            String line = lineString();
            String msg = localised ? MessageFormat.format(invalidMessage, line)
                    : invalidMessage + line;
            throw new RespException(msg);
        }
        int count = (int) lineNumber();
        endLine();
        if (count < 0) {
            return RespValue.nullValue();
        }
        checkCollectionCount(count);
        if (count == 0 && lineType == RespType.ARRAY) {
            return RespValue.array(new ArrayList<RespValue>(0));
        }
        if (depth + 1 > MAX_NESTING_DEPTH) {
            throw new RespException(MessageFormat.format(
                    L10N.getString("err.nesting_too_deep"), depth + 1));
        }
        Frame frame = new Frame(lineType, count);
        if (count == 0) {
            return frame.build();
        }
        frames[depth++] = frame;
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bodies
    // ─────────────────────────────────────────────────────────────────────────

    /** Copies what has arrived of the body; true once all of it has. */
    private boolean fillBody() {
        int n = Math.min(bodyLength - bodyFilled, buffer.remaining());
        if (n > 0) {
            if (bodyData.length - bodyFilled < n) {
                int capacity = (int) Math.min((long) bodyLength,
                        Math.max(2L * bodyData.length, (long) bodyFilled + n));
                byte[] grown = new byte[capacity];
                System.arraycopy(bodyData, 0, grown, 0, bodyFilled);
                bodyData = grown;
            }
            buffer.get(bodyData, bodyFilled, n);
            bodyFilled += n;
        }
        return bodyFilled == bodyLength;
    }

    /** Reads the CRLF after a body; true once both bytes have been read. */
    private boolean readBodyEnd() throws RespException {
        while (bodyEndSeen < 2) {
            if (!buffer.hasRemaining()) {
                return false;
            }
            byte b = buffer.get();
            if (b != (bodyEndSeen == 0 ? '\r' : '\n')) {
                throw new RespException(bodyType == RespType.BULK_STRING
                        ? L10N.getString("err.no_crlf_after_bulk_string")
                        : bodyType == RespType.VERBATIM_STRING
                                ? "Missing CRLF after verbatim string"
                                : "Missing CRLF after blob error");
            }
            bodyEndSeen++;
        }
        return true;
    }

    private RespValue finishBody() {
        byte[] data = bodyData;
        bodyData = null;
        state = STATE_TYPE;
        switch (bodyType) {
            case BLOB_ERROR:
                return RespValue.blobError(data);
            case VERBATIM_STRING:
                // 3-character encoding, ':' and the data
                if (data.length >= 4 && data[3] == ':') {
                    String encoding = new String(data, 0, 3, UTF_8);
                    byte[] text = new byte[data.length - 4];
                    System.arraycopy(data, 4, text, 0, text.length);
                    return RespValue.verbatimString(encoding, text);
                }
                return RespValue.verbatimString("txt", data);
            default:
                return RespValue.bulkString(data);
        }
    }

    /**
     * Returns the index within {@code [start, limit)} of a {@code '\r'}
     * immediately followed by {@code '\n'}, or -1 if none is buffered
     * yet. Every RESP line -- every simple string, error, integer, and
     * every bulk/array/map/set/push length prefix -- goes through this
     * under command pipelining, so it scans the buffer's backing array
     * directly when one is available (true for every buffer this class
     * uses -- {@link org.bluezoo.gumdrop.util.ByteBufferPool#acquire}
     * only ever hands out heap buffers) instead of two bounds-checked
     * {@link ByteBuffer#get(int)} calls per byte scanned.
     */
    private int findCrLf(int start, int limit) {
        if (buffer.hasArray()) {
            byte[] array = buffer.array();
            int arrayOffset = buffer.arrayOffset();
            int pos = arrayOffset + start;
            int end = arrayOffset + limit - 1;
            while (pos < end) {
                if (array[pos] == '\r' && array[pos + 1] == '\n') {
                    return pos - arrayOffset;
                }
                pos++;
            }
            return -1;
        }
        for (int i = start; i < limit - 1; i++) {
            if (buffer.get(i) == '\r' && buffer.get(i + 1) == '\n') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Resets the decoder, discarding any buffered data.
     */
    public void reset() {
        ByteBufferPool.release(buffer);
        buffer = ByteBufferPool.acquire(DEFAULT_BUFFER_SIZE);
        buffer.flip();
        parsePosition = 0;
        state = STATE_TYPE;
        lineScanned = 0;
        bodyData = null;
        java.util.Arrays.fill(frames, null);
        depth = 0;
        failure = null;
    }

    /** Test seam: the capacity of the buffer held. */
    int bufferCapacity() {
        return buffer.capacity();
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

