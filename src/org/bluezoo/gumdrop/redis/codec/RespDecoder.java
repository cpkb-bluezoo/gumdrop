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

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ResourceBundle;

/**
 * Reads the RESP wire format and reports what it holds as events.
 *
 * <p>Supports the RESP2 types (simple string {@code +}, error {@code -},
 * integer {@code :}, bulk string {@code $}, array {@code *}) and the RESP3
 * types (map, set, double, boolean, null, push, verbatim string, big number,
 * blob error, attributes, streamed strings and streamed aggregates).
 *
 * <p>The decoder is a push parser and an event source. It is given whatever
 * bytes have arrived, in any split, by {@link #receive}, and tells a
 * {@link RespEventHandler} what they hold: a scalar, the start of an
 * aggregate, the start of a string, the payload of the string as chunks that
 * are views onto the buffer it was given, and the end of each. Nothing is
 * assembled and no payload is held, whatever its size. What the decoder
 * keeps between calls is a header line that is not yet finished (at most
 * {@link #setMaxLine maxLine} bytes), a stack of one count for each open
 * aggregate (at most {@link #MAX_NESTING_DEPTH}) and a few counters. There is
 * no recursion, and no length that a server announces makes the decoder
 * reserve memory.
 *
 * <p>The handler steers the decoder with the {@link ReplyFlow} it returns.
 * {@link ReplyFlow#PAUSE} stops the decoder after the event, leaving the
 * unconsumed bytes in the caller's buffer; {@link ReplyFlow#SKIP} discards
 * the rest of that reply as it arrives, in constant memory, bounded by
 * {@link #setMaxSkip maxSkip}.
 *
 * <h4>Usage Pattern</h4>
 * <pre>{@code
 * RespDecoder decoder = new RespDecoder();
 *
 * // In receive callback:
 * boolean paused = decoder.receive(buffer, handler);
 * if (paused) {
 *     // stop reading; call receive again with the same buffer to go on
 * }
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://redis.io/docs/reference/protocol-spec/">RESP Protocol Specification</a>
 */
public class RespDecoder {

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.redis.codec.L10N");

    private static final Charset UTF_8 = StandardCharsets.UTF_8;

    /** Maximum nesting depth for compound RESP types. */
    public static final int MAX_NESTING_DEPTH = 32;
    /** Default longest header or simple-string line. */
    public static final int DEFAULT_MAX_LINE = 64 * 1024;
    /** Default most bytes of one reply that may be discarded. */
    public static final long DEFAULT_MAX_SKIP = 256L * 1024 * 1024;

    private static final int INITIAL_LINE_CAPACITY = 128;

    // the states of the machine
    private static final int STATE_TAG = 0;
    private static final int STATE_LINE = 1;
    private static final int STATE_PAYLOAD = 2;
    private static final int STATE_PAYLOAD_END = 3;

    private int maxLine = DEFAULT_MAX_LINE;
    private long maxSkip = DEFAULT_MAX_SKIP;

    private int state = STATE_TAG;
    // the type byte of the line being read
    private byte tag;
    // the line being read, up to and including its CRLF, bounded by maxLine
    private byte[] line = new byte[INITIAL_LINE_CAPACITY];
    private int lineLength;
    // the string being read: its type, the bytes of payload still to come
    // before its CRLF, and how much of the CRLF has been seen
    private RespType bodyType;
    private long payloadRemaining;
    private boolean sawCr;
    // inside a RESP3 streamed string: a ';' chunk header comes next
    private boolean streamedString;
    // a verbatim string's four-byte head ("txt:") as it is collected, and
    // the length announced for the whole
    private final byte[] verbatimHead = new byte[4];
    private int verbatimHeadLength;
    private long verbatimTotal;
    // the open aggregates, outermost first: counts and flags, never elements
    private final long[] remaining = new long[MAX_NESTING_DEPTH];
    private final boolean[] streamed = new boolean[MAX_NESTING_DEPTH];
    private final boolean[] attribute = new boolean[MAX_NESTING_DEPTH];
    private int depth;
    // attribute frames open: while there are any, no events are reported
    private int muted;
    // beginReply has been reported for the reply in progress
    private boolean inReply;
    // a handler chose SKIP for the reply in progress, and how much has
    // been discarded of it
    private boolean skipping;
    private long skipped;
    private boolean paused;
    // the handler for the call in progress
    private RespEventHandler handler;
    private boolean receiving;
    private boolean aborted;
    // set once the data is malformed: it stays so
    private RespException failure;

    /**
     * Creates a new RESP decoder.
     */
    public RespDecoder() {
    }

    /**
     * Sets the longest header or simple-string line accepted. A longer one
     * is a protocol error.
     *
     * @param max the limit in bytes, at least 1
     */
    public void setMaxLine(int max) {
        if (max < 1) {
            throw new IllegalArgumentException(Integer.toString(max));
        }
        maxLine = max;
    }

    /**
     * Sets the most bytes of one reply that may be discarded: those of a
     * reply a handler chose to {@link ReplyFlow#SKIP}, and of RESP3
     * attributes. Past it the data is treated as malformed, as reading on
     * only to discard has a cost of its own, and the connection should be
     * closed.
     *
     * @param max the limit in bytes, at least 1
     */
    public void setMaxSkip(long max) {
        if (max < 1) {
            throw new IllegalArgumentException(Long.toString(max));
        }
        maxSkip = max;
    }

    /**
     * Returns whether the decoder is between replies: nothing of the next
     * one has arrived.
     *
     * @return true if idle
     */
    public boolean isIdle() {
        return state == STATE_TAG && depth == 0 && !streamedString && !inReply;
    }

    /**
     * Consumes data, telling the handler what it holds.
     *
     * <p>All of the data is consumed, however it is split, unless this
     * returns true: the buffer's position is advanced past what was consumed.
     * What has not yet made a whole token (a header line) is kept inside the
     * decoder, and that is all it keeps.
     *
     * @param data the bytes that have arrived
     * @param eventHandler told what they hold
     * @return true if a handler asked for {@link ReplyFlow#PAUSE}: the
     * rest of the data is left in the buffer, to be given again when the
     * caller can take more
     * @throws RespException if the data is malformed; the decoder then
     * keeps failing with the same exception
     */
    public boolean receive(ByteBuffer data, RespEventHandler eventHandler) throws RespException {
        if (failure != null) {
            throw failure;
        }
        handler = eventHandler;
        receiving = true;
        aborted = false;
        try {
            run(data);
        } catch (RespException e) {
            failure = e;
            throw e;
        } finally {
            receiving = false;
            handler = null;
        }
        if (aborted) {
            // reset() was called by a handler: what is left is not wanted
            data.position(data.limit());
            return false;
        }
        boolean wasPaused = paused;
        paused = false;
        return wasPaused;
    }

    /**
     * Resets the decoder, discarding any partly read reply. It may be called
     * from a handler, in which case the data being decoded is dropped.
     */
    public void reset() {
        state = STATE_TAG;
        lineLength = 0;
        payloadRemaining = 0;
        sawCr = false;
        streamedString = false;
        verbatimHeadLength = 0;
        depth = 0;
        muted = 0;
        inReply = false;
        skipping = false;
        skipped = 0;
        paused = false;
        failure = null;
        if (receiving) {
            aborted = true;
        }
    }

    private void run(ByteBuffer data) throws RespException {
        while (data.hasRemaining() && !paused && !aborted) {
            switch (state) {
                case STATE_TAG:
                    readTag(data);
                    break;
                case STATE_LINE:
                    readLine(data);
                    break;
                case STATE_PAYLOAD:
                    readPayload(data);
                    break;
                default: // STATE_PAYLOAD_END
                    readPayloadEnd(data);
                    break;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tokens
    // ─────────────────────────────────────────────────────────────────────────

    private void readTag(ByteBuffer data) throws RespException {
        byte b = data.get();
        if (streamedString != (b == ';')) {
            throw error("err.streamed_string_interrupted");
        }
        switch (b) {
            case '+': case '-': case ':': case ',': case '#': case '_':
            case '(': case '$': case '!': case '=': case '*': case '~':
            case '>': case '%': case '|': case ';':
                break;
            case '.':
                if (depth > 0 && streamed[depth - 1]) {
                    break;
                }
                // fall through
            default:
                throw new RespException(MessageFormat.format(
                        L10N.getString("err.unknown_type_prefix"), (char) (b & 0xff)));
        }
        tag = b;
        lineLength = 0;
        state = STATE_LINE;
    }

    private void readLine(ByteBuffer data) throws RespException {
        int position = data.position();
        int limit = data.limit();
        int end = indexOfLf(data, position, limit);
        int take = end < 0 ? limit - position : end - position + 1;
        if (lineLength + take > maxLine + 2) {
            throw new RespException(MessageFormat.format(
                    L10N.getString("err.line_too_long"), Integer.valueOf(lineLength + take)));
        }
        if (lineLength + take > line.length) {
            int capacity = Math.max(lineLength + take, Math.min(line.length * 2, maxLine + 2));
            byte[] grown = new byte[capacity];
            System.arraycopy(line, 0, grown, 0, lineLength);
            line = grown;
        }
        data.get(line, lineLength, take);
        lineLength += take;
        if (end < 0) {
            return;
        }
        if (lineLength < 2 || line[lineLength - 2] != '\r') {
            throw error("err.line_no_crlf");
        }
        int length = lineLength - 2;
        discarding(length + 2);
        state = STATE_TAG;
        header(length);
    }

    private static int indexOfLf(ByteBuffer data, int from, int limit) {
        if (data.hasArray()) {
            byte[] array = data.array();
            int offset = data.arrayOffset();
            for (int i = from; i < limit; i++) {
                if (array[offset + i] == '\n') {
                    return i;
                }
            }
            return -1;
        }
        for (int i = from; i < limit; i++) {
            if (data.get(i) == '\n') {
                return i;
            }
        }
        return -1;
    }

    private void readPayload(ByteBuffer data) throws RespException {
        // a verbatim string's first four bytes are its format tag
        if (bodyType == RespType.VERBATIM_STRING && verbatimHeadLength < 4) {
            int head = (int) Math.min(Math.min(payloadRemaining, data.remaining()),
                    4 - verbatimHeadLength);
            data.get(verbatimHead, verbatimHeadLength, head);
            verbatimHeadLength += head;
            payloadRemaining -= head;
            if (verbatimHeadLength < 4) {
                return;
            }
            if (verbatimHead[3] != ':') {
                throw error("err.verbatim_no_format");
            }
            String format = new String(verbatimHead, 0, 3, StandardCharsets.US_ASCII);
            stringBegin(RespType.VERBATIM_STRING, verbatimTotal - 4, format);
        }
        int take = (int) Math.min(payloadRemaining, data.remaining());
        if (take > 0) {
            discarding(take);
            stringChunk(data, take);
            payloadRemaining -= take;
        }
        if (payloadRemaining == 0) {
            state = STATE_PAYLOAD_END;
            sawCr = false;
        }
    }

    private void readPayloadEnd(ByteBuffer data) throws RespException {
        byte b = data.get();
        if (!sawCr) {
            if (b != '\r') {
                throw error("err.no_crlf_after_bulk_string");
            }
            sawCr = true;
            return;
        }
        if (b != '\n') {
            throw error("err.no_crlf_after_bulk_string");
        }
        state = STATE_TAG;
        if (streamedString) {
            // another chunk header, or ;0, comes next
            return;
        }
        stringEnd();
        valueComplete();
    }

    private RespException error(String key) {
        return new RespException(L10N.getString(key));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Headers
    // ─────────────────────────────────────────────────────────────────────────

    // A header or simple line is complete in line[0, length).
    private void header(int length) throws RespException {
        switch (tag) {
            case '+':
                scalarFlow(handlerLive() ? handler.simpleString(text(length)) : null);
                valueComplete();
                break;
            case '-':
                scalarFlow(handlerLive() ? handler.error(text(length)) : null);
                valueComplete();
                break;
            case ':': {
                long n = number(length);
                openReply(false);
                scalarFlow(live() ? handler.integer(n) : null);
                valueComplete();
                break;
            }
            case ',': {
                double d = doubleNumber(length);
                openReply(false);
                scalarFlow(live() ? handler.doubleValue(d) : null);
                valueComplete();
                break;
            }
            case '#': {
                boolean b;
                if (length == 1 && line[0] == 't') {
                    b = true;
                } else if (length == 1 && line[0] == 'f') {
                    b = false;
                } else {
                    throw new RespException(MessageFormat.format(
                            L10N.getString("err.invalid_boolean"), text(length)));
                }
                openReply(false);
                scalarFlow(live() ? handler.booleanValue(b) : null);
                valueComplete();
                break;
            }
            case '_':
                if (length != 0) {
                    throw error("err.invalid_null");
                }
                openReply(false);
                scalarFlow(live() ? handler.nullValue() : null);
                valueComplete();
                break;
            case '(':
                bigNumber(length);
                valueComplete();
                break;
            case '$':
            case '!':
            case '=':
                stringHeader(length);
                break;
            case ';':
                chunkHeader(length);
                break;
            case '.':
                if (length != 0) {
                    throw error("err.invalid_stream_end");
                }
                depth--;
                aggregateEnd();
                valueComplete();
                break;
            default: // * ~ > % |
                aggregateHeader(length);
                break;
        }
    }

    private boolean handlerLive() {
        openReply(false);
        return live();
    }

    private String text(int length) {
        return new String(line, 0, length, UTF_8);
    }

    private void bigNumber(int length) throws RespException {
        boolean valid = length > 0;
        for (int i = 0; i < length && valid; i++) {
            byte b = line[i];
            valid = (b >= '0' && b <= '9') || (i == 0 && b == '-' && length > 1);
        }
        if (!valid) {
            throw new RespException(MessageFormat.format(
                    L10N.getString("err.invalid_big_number"), text(length)));
        }
        openReply(false);
        scalarFlow(live() ? handler.bigNumber(text(length)) : null);
    }

    private double doubleNumber(int length) throws RespException {
        String s = text(length);
        if ("inf".equals(s)) {
            return Double.POSITIVE_INFINITY;
        } else if ("-inf".equals(s)) {
            return Double.NEGATIVE_INFINITY;
        } else if ("nan".equals(s)) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new RespException(MessageFormat.format(
                    L10N.getString("err.invalid_double"), s), e);
        }
    }

    // The line as a number: the rules of Long.parseLong for ASCII digits,
    // read from the bytes without making a string of them.
    private long number(int length) throws RespException {
        if (length == 0) {
            throw invalidNumber(length);
        }
        int i = 0;
        boolean negative = false;
        byte first = line[0];
        if (first == '-' || first == '+') {
            negative = first == '-';
            i = 1;
            if (length == 1) {
                throw invalidNumber(length);
            }
        }
        long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        long multmin = limit / 10;
        long result = 0;
        for (; i < length; i++) {
            int digit = line[i] - '0';
            if (digit < 0 || digit > 9 || result < multmin) {
                throw invalidNumber(length);
            }
            result *= 10;
            if (result < limit + digit) {
                throw invalidNumber(length);
            }
            result -= digit;
        }
        return negative ? result : -result;
    }

    private RespException invalidNumber(int length) {
        return new RespException(MessageFormat.format(
                L10N.getString("err.invalid_integer"), text(length)));
    }

    private void stringHeader(int length) throws RespException {
        RespType type;
        switch (tag) {
            case '$':
                type = RespType.BULK_STRING;
                break;
            case '!':
                type = RespType.BLOB_ERROR;
                break;
            default:
                type = RespType.VERBATIM_STRING;
                break;
        }
        if (length == 1 && line[0] == '?') {
            if (type != RespType.BULK_STRING) {
                throw error("err.streamed_type");
            }
            streamedString = true;
            stringBegin(type, -1, null);
            return;
        }
        long n = number(length);
        if (n < 0) {
            // RESP2's null bulk string
            if (type == RespType.BULK_STRING && n == -1) {
                openReply(false);
                scalarFlow(live() ? handler.nullValue() : null);
                valueComplete();
                return;
            }
            throw new RespException(MessageFormat.format(
                    L10N.getString("err.invalid_length"), Long.valueOf(n)));
        }
        bodyType = type;
        payloadRemaining = n;
        if (type == RespType.VERBATIM_STRING) {
            if (n < 4) {
                throw error("err.verbatim_no_format");
            }
            // reported once its format tag has arrived
            verbatimHeadLength = 0;
            verbatimTotal = n;
        } else {
            stringBegin(type, n, null);
        }
        state = n == 0 ? STATE_PAYLOAD_END : STATE_PAYLOAD;
        sawCr = false;
    }

    // The header of a chunk of a streamed string.
    private void chunkHeader(int length) throws RespException {
        long n = number(length);
        if (n < 0) {
            throw new RespException(MessageFormat.format(
                    L10N.getString("err.invalid_length"), Long.valueOf(n)));
        }
        if (n == 0) {
            streamedString = false;
            stringEnd();
            valueComplete();
            return;
        }
        bodyType = RespType.BULK_STRING;
        payloadRemaining = n;
        state = STATE_PAYLOAD;
        sawCr = false;
    }

    private void aggregateHeader(int length) throws RespException {
        if (length == 1 && line[0] == '?') {
            RespType kind;
            switch (tag) {
                case '*':
                    kind = RespType.ARRAY;
                    break;
                case '~':
                    kind = RespType.SET;
                    break;
                case '%':
                    kind = RespType.MAP;
                    break;
                default:
                    throw error("err.streamed_type");
            }
            checkDepth();
            aggregateBegin(kind, -1);
            push(0, true, false);
            return;
        }
        long n = number(length);
        if (n < 0) {
            // RESP2's null array
            if (tag == '*' && n == -1) {
                openReply(false);
                scalarFlow(live() ? handler.nullValue() : null);
                valueComplete();
                return;
            }
            throw new RespException(MessageFormat.format(
                    L10N.getString("err.invalid_length"), Long.valueOf(n)));
        }
        checkDepth();
        if (tag == '|') {
            // attributes describe the value that follows; they are read and
            // dropped, and are not it
            if (n > 0) {
                push(doubled(n), false, true);
                muted++;
            }
            return;
        }
        RespType kind;
        switch (tag) {
            case '*':
                kind = RespType.ARRAY;
                break;
            case '~':
                kind = RespType.SET;
                break;
            case '>':
                kind = RespType.PUSH;
                break;
            default:
                kind = RespType.MAP;
                break;
        }
        aggregateBegin(kind, n);
        if (n == 0) {
            // an empty aggregate is complete at once
            aggregateEnd();
            valueComplete();
            return;
        }
        push(kind == RespType.MAP ? doubled(n) : n, false, false);
    }

    private static long doubled(long n) {
        return n > (Long.MAX_VALUE >> 1) ? Long.MAX_VALUE : n << 1;
    }

    private void checkDepth() throws RespException {
        if (depth >= MAX_NESTING_DEPTH) {
            throw new RespException(MessageFormat.format(
                    L10N.getString("err.nesting_too_deep"), Integer.valueOf(depth + 1)));
        }
    }

    private void push(long count, boolean isStreamed, boolean isAttribute) {
        remaining[depth] = count;
        streamed[depth] = isStreamed;
        attribute[depth] = isAttribute;
        depth++;
    }

    // A value is complete: count it in the aggregate it belongs to, closing
    // each that it fills, and the reply when none is left open.
    private void valueComplete() {
        while (true) {
            if (depth == 0) {
                finishReply();
                return;
            }
            int top = depth - 1;
            if (streamed[top]) {
                return;
            }
            if (--remaining[top] > 0) {
                return;
            }
            depth--;
            if (attribute[top]) {
                muted--;
                return;
            }
            aggregateEnd();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // What the handler hears
    // ─────────────────────────────────────────────────────────────────────────

    // Counts bytes against the discard limit, if they are being discarded.
    private void discarding(long n) throws RespException {
        if (skipping || muted > 0) {
            skipped += n;
            if (skipped > maxSkip || skipped < 0) {
                throw new RespException(MessageFormat.format(
                        L10N.getString("err.skip_limit"), Long.valueOf(maxSkip)));
            }
        }
    }

    private boolean live() {
        return muted == 0 && !skipping;
    }

    private void apply(ReplyFlow flow) {
        if (flow == ReplyFlow.PAUSE) {
            paused = true;
        } else if (flow == ReplyFlow.SKIP) {
            skipping = true;
        }
    }

    // flow is null if the event was not reported
    private void scalarFlow(ReplyFlow flow) {
        if (flow != null) {
            apply(flow);
        }
    }

    private void openReply(boolean push) {
        if (!inReply && muted == 0) {
            inReply = true;
            skipping = false;
            skipped = 0;
            handler.beginReply(push);
        }
    }

    private void finishReply() {
        if (inReply) {
            boolean wasSkipped = skipping;
            inReply = false;
            skipping = false;
            skipped = 0;
            handler.endReply(wasSkipped);
        }
    }

    private void aggregateBegin(RespType kind, long length) {
        openReply(kind == RespType.PUSH);
        if (live()) {
            apply(handler.beginAggregate(kind, length));
        }
    }

    private void aggregateEnd() {
        if (live()) {
            apply(handler.endAggregate());
        }
    }

    private void stringBegin(RespType kind, long length, String format) {
        openReply(false);
        if (live()) {
            apply(handler.beginString(kind, length, format));
        }
    }

    // Reports the next take bytes of data as a chunk, and moves past them.
    private void stringChunk(ByteBuffer data, int take) {
        int position = data.position();
        if (live()) {
            int limit = data.limit();
            data.limit(position + take);
            ReplyFlow flow;
            try {
                flow = handler.stringChunk(data);
            } finally {
                data.limit(limit);
            }
            apply(flow);
        }
        data.position(position + take);
    }

    private void stringEnd() {
        if (live()) {
            apply(handler.endString());
        }
    }

}
