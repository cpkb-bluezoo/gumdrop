/*
 * ReplyAdapter.java
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

package org.bluezoo.gumdrop.redis.client;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;

import org.bluezoo.gumdrop.redis.codec.ReplyFlow;
import org.bluezoo.gumdrop.redis.codec.RespEventHandler;
import org.bluezoo.gumdrop.redis.codec.RespType;

/**
 * Turns the events the decoder reads for one reply into the calls of the
 * handler the command was sent with. There is one kind for each shape of
 * handler; none of them holds a payload. An identifier (a key, a field name,
 * a cursor) is collected whole, bounded by the maximum field length, and a
 * scalar is bounded by the same.
 *
 * <p>An adapter completes its handler exactly once: with the error the
 * server sent, or one of its own (a reply of the wrong shape, an identifier
 * too long, a reply the handler chose to skip), or when the reply ends. After
 * that it discards what is left of the reply.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
abstract class ReplyAdapter implements RespEventHandler {

    private static final Charset UTF_8 = StandardCharsets.UTF_8;

    /** The error given to a handler whose reply it skipped or cancelled. */
    static final String CANCELLED = "CANCELLED The reply was not wanted";

    final RedisSession session;
    final int maxField;
    ReplyStream stream = ReplyStream.NONE;

    // elements open: 0 outside the reply, 1 inside the outer aggregate
    int depth;
    private boolean done;
    // the first reason the reply cannot go to the handler as it is
    private String failure;
    // the handler chose to skip, or the consumer cancelled
    private boolean skipped;
    private volatile boolean cancelRequested;
    // identifier or scalar collected so far
    private byte[] text = new byte[64];
    private int textLength;
    private boolean collecting;

    ReplyAdapter(RedisSession session, int maxField) {
        this.session = session;
        this.maxField = maxField;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // What the connection asks of every adapter
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the adapter for a handler.
     *
     * @param callback the handler, or null if no reply is wanted
     * @param session the session passed to completions
     * @param maxField the longest identifier or scalar to collect
     * @return the adapter, or null for a null handler or one of an unknown kind
     */
    static ReplyAdapter forHandler(Object callback, RedisSession session, int maxField) {
        if (callback instanceof ReplyEventHandler) {
            return new Events((ReplyEventHandler) callback, session, maxField);
        } else if (callback instanceof BulkResultHandler) {
            return new Bulk((BulkResultHandler) callback, session, maxField);
        } else if (callback instanceof ArrayResultHandler) {
            return new PayloadArray((ArrayResultHandler) callback, session, maxField);
        } else if (callback instanceof KeyArrayResultHandler) {
            return new KeyArray((KeyArrayResultHandler) callback, session, maxField);
        } else if (callback instanceof MapResultHandler) {
            return new PairMap((MapResultHandler) callback, session, maxField);
        } else if (callback instanceof ScanResultHandler) {
            return new Scan((ScanResultHandler) callback, session, maxField);
        } else if (callback instanceof IntegerResultHandler) {
            return new IntegerScalar((IntegerResultHandler) callback, session, maxField);
        } else if (callback instanceof BooleanResultHandler) {
            return new BooleanScalar((BooleanResultHandler) callback, session, maxField);
        } else if (callback instanceof StringResultHandler) {
            return new StringScalar((StringResultHandler) callback, session, maxField);
        }
        return null;
    }

    /**
     * Gives the handle by which the handler pauses, resumes or cancels.
     *
     * @param handle the handle
     */
    final void setStream(ReplyStream handle) {
        stream = handle;
    }

    /** Asks that the rest of the reply be discarded; thread-safe. */
    final void requestCancel() {
        cancelRequested = true;
    }

    /** Returns whether the handler has been completed. */
    final boolean isDone() {
        return done;
    }

    /**
     * Completes the handler with an error, now, if it has not been
     * completed. The events that follow are discarded.
     *
     * @param error the error
     */
    final void abort(String error) {
        complete(error);
    }

    /** Forgets the reply without telling the handler: it will be sent again. */
    final void abandon() {
        done = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Completion
    // ─────────────────────────────────────────────────────────────────────────

    /** The reply ended without anything going wrong: complete the handler. */
    abstract void succeed();

    /** Tells the handler the command failed. */
    abstract void fail(String error);

    private void complete(String error) {
        if (done) {
            return;
        }
        done = true;
        if (error == null) {
            succeed();
        } else {
            fail(error);
        }
    }

    @Override
    public void beginReply(boolean push) {
    }

    @Override
    public void endReply(boolean wasSkipped) {
        if (done) {
            return;
        }
        if (failure != null) {
            complete(failure);
        } else if (wasSkipped || skipped || cancelRequested) {
            complete(CANCELLED);
        } else {
            complete(null);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // For the subclasses
    // ─────────────────────────────────────────────────────────────────────────

    /** Records what the handler decided, and returns it. */
    final ReplyFlow flow(ReplyFlow flow) {
        if (flow == ReplyFlow.SKIP) {
            skipped = true;
        }
        return flow;
    }

    /** True once nothing more of the reply should reach the handler. */
    final boolean discarding() {
        return done || failure != null || skipped || cancelRequested;
    }

    /** Fails the reply: the rest of it is discarded and the handler told at the end. */
    final ReplyFlow reject(String error) {
        if (failure == null) {
            failure = error;
        }
        return ReplyFlow.SKIP;
    }

    final ReplyFlow wrongShape() {
        return reject(RedisClientProtocolHandler.L10N.getString("err.reply_shape"));
    }

    final ReplyFlow tooLong(long length) {
        return reject(MessageFormat.format(
                RedisClientProtocolHandler.L10N.getString("err.identifier_too_long"),
                Long.valueOf(length), Integer.valueOf(maxField)));
    }

    /** The error line that is the whole reply. */
    final ReplyFlow topLevelError(String message) {
        if (failure == null) {
            failure = message;
        }
        return ReplyFlow.SKIP;
    }

    // Starts collecting an identifier or scalar string; false if it is too long.
    final boolean startText(long length) {
        textLength = 0;
        collecting = true;
        return length < 0 || length <= maxField;
    }

    // Adds a chunk; false if it takes the text past the maximum field length.
    final boolean addText(ByteBuffer chunk) {
        int n = chunk.remaining();
        if ((long) textLength + n > maxField) {
            return false;
        }
        if (textLength + n > text.length) {
            byte[] grown = new byte[Math.max(textLength + n, text.length * 2)];
            System.arraycopy(text, 0, grown, 0, textLength);
            text = grown;
        }
        chunk.get(text, textLength, n);
        textLength += n;
        return true;
    }

    final String endText() {
        collecting = false;
        return new String(text, 0, textLength, UTF_8);
    }

    final boolean isCollecting() {
        return collecting;
    }

    static byte[] bytesOf(String s) {
        return s.getBytes(UTF_8);
    }

    // The text of a scalar, as the payload it is delivered as.
    static String scalarText(long value) {
        return Long.toString(value);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Whole-reply handler
    // ─────────────────────────────────────────────────────────────────────────

    /** Forwards the events to a {@link ReplyEventHandler}. */
    static final class Events extends ReplyAdapter {
        private final ReplyEventHandler handler;
        private boolean begun;
        private boolean push;

        Events(ReplyEventHandler handler, RedisSession session, int maxField) {
            super(session, maxField);
            this.handler = handler;
        }

        @Override
        public void beginReply(boolean isPush) {
            push = isPush;
        }

        // Delivered with the first event, so that a reply that is an error
        // as a whole is only ever its completion.
        private void begin() {
            if (!begun) {
                begun = true;
                handler.handleStream(stream);
                handler.beginReply(push);
            }
        }

        @Override
        public void endReply(boolean wasSkipped) {
            if (begun && !isDone()) {
                handler.endReply(wasSkipped);
            }
            super.endReply(wasSkipped);
        }

        private ReplyFlow pass(ReplyFlow flow) {
            return flow(flow);
        }

        @Override
        public ReplyFlow nullValue() {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            begin();
            return pass(handler.nullValue());
        }

        @Override
        public ReplyFlow integer(long value) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            begin();
            return pass(handler.integer(value));
        }

        @Override
        public ReplyFlow doubleValue(double value) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            begin();
            return pass(handler.doubleValue(value));
        }

        @Override
        public ReplyFlow booleanValue(boolean value) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            begin();
            return pass(handler.booleanValue(value));
        }

        @Override
        public ReplyFlow bigNumber(String digits) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            begin();
            return pass(handler.bigNumber(digits));
        }

        @Override
        public ReplyFlow simpleString(String value) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            begin();
            return pass(handler.simpleString(value));
        }

        @Override
        public ReplyFlow error(String message) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            if (depth == 0 && !begun) {
                return topLevelError(message);
            }
            begin();
            return pass(handler.error(message));
        }

        @Override
        public ReplyFlow beginAggregate(RespType kind, long length) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            begin();
            depth++;
            return pass(handler.beginAggregate(kind, length));
        }

        @Override
        public ReplyFlow endAggregate() {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            depth--;
            return pass(handler.endAggregate());
        }

        @Override
        public ReplyFlow beginString(RespType kind, long length, String format) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            if (kind == RespType.BLOB_ERROR && depth == 0 && !begun) {
                return reject(RedisClientProtocolHandler.L10N.getString("err.blob_error"));
            }
            begin();
            return pass(handler.beginString(kind, length, format));
        }

        @Override
        public ReplyFlow stringChunk(ByteBuffer chunk) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            return pass(handler.stringChunk(chunk));
        }

        @Override
        public ReplyFlow endString() {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            return pass(handler.endString());
        }

        @Override
        void succeed() {
            handler.handleComplete(null, session);
        }

        @Override
        void fail(String error) {
            handler.handleComplete(error, session);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Payloads: a string, delivered as chunks
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * What a handler of payloads does with the strings. Shared by the single
     * string, the array of strings and the values of a map.
     */
    abstract static class Payloads extends ReplyAdapter {

        Payloads(RedisSession session, int maxField) {
            super(session, maxField);
        }

        abstract ReplyFlow payloadNull();

        abstract ReplyFlow payloadBegin(long length);

        abstract ReplyFlow payloadContent(ByteBuffer chunk);

        abstract ReplyFlow payloadEnd();

        // A scalar is a payload of its text, delivered whole.
        final ReplyFlow scalarPayload(String text) {
            byte[] bytes = bytesOf(text);
            ReplyFlow result = payloadBegin(bytes.length);
            if (result == ReplyFlow.SKIP) {
                return result;
            }
            ReplyFlow next = payloadContent(ByteBuffer.wrap(bytes));
            if (next == ReplyFlow.SKIP) {
                return next;
            }
            ReplyFlow last = payloadEnd();
            if (last == ReplyFlow.SKIP) {
                return last;
            }
            return result == ReplyFlow.PAUSE || next == ReplyFlow.PAUSE || last == ReplyFlow.PAUSE
                    ? ReplyFlow.PAUSE : ReplyFlow.CONTINUE;
        }
    }

    /** One string. */
    static final class Bulk extends Payloads {
        private final BulkResultHandler handler;

        Bulk(BulkResultHandler handler, RedisSession session, int maxField) {
            super(session, maxField);
            this.handler = handler;
        }

        @Override
        ReplyFlow payloadNull() {
            handler.handleNull();
            return ReplyFlow.CONTINUE;
        }

        @Override
        ReplyFlow payloadBegin(long length) {
            return flow(handler.handleBegin(length, stream));
        }

        @Override
        ReplyFlow payloadContent(ByteBuffer chunk) {
            return flow(handler.handleContent(chunk));
        }

        @Override
        ReplyFlow payloadEnd() {
            handler.handleEnd();
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow nullValue() {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            return depth == 0 ? payloadNull() : wrongShape();
        }

        @Override
        public ReplyFlow integer(long value) {
            return scalar(scalarText(value));
        }

        @Override
        public ReplyFlow doubleValue(double value) {
            return scalar(Double.toString(value));
        }

        @Override
        public ReplyFlow booleanValue(boolean value) {
            return scalar(value ? "1" : "0");
        }

        @Override
        public ReplyFlow bigNumber(String digits) {
            return scalar(digits);
        }

        @Override
        public ReplyFlow simpleString(String value) {
            return scalar(value);
        }

        private ReplyFlow scalar(String text) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            return depth == 0 ? scalarPayload(text) : wrongShape();
        }

        @Override
        public ReplyFlow error(String message) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            return topLevelError(message);
        }

        @Override
        public ReplyFlow beginAggregate(RespType kind, long length) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            return wrongShape();
        }

        @Override
        public ReplyFlow endAggregate() {
            return ReplyFlow.SKIP;
        }

        @Override
        public ReplyFlow beginString(RespType kind, long length, String format) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            if (kind == RespType.BLOB_ERROR) {
                return reject(RedisClientProtocolHandler.L10N.getString("err.blob_error"));
            }
            return payloadBegin(length);
        }

        @Override
        public ReplyFlow stringChunk(ByteBuffer chunk) {
            return discarding() ? ReplyFlow.SKIP : payloadContent(chunk);
        }

        @Override
        public ReplyFlow endString() {
            return discarding() ? ReplyFlow.SKIP : payloadEnd();
        }

        @Override
        void succeed() {
            handler.handleComplete(null, session);
        }

        @Override
        void fail(String error) {
            handler.handleComplete(error, session);
        }
    }

    /**
     * Strings in an aggregate: the elements of an array, or the names and
     * values of a map or a flat array of pairs.
     */
    abstract static class Elements extends Payloads {

        // elements seen of the outer aggregate
        long index;
        // a map is read as pairs, and a flat array as pairs if the handler says so
        boolean pairs;
        // the element being read is a string, delivered as chunks
        boolean inString;

        Elements(RedisSession session, int maxField) {
            super(session, maxField);
        }

        /** Whether the element about to begin is an identifier (a name). */
        final boolean atName() {
            return pairs && (index & 1) == 0;
        }

        abstract ReplyFlow begun(long count);

        abstract ReplyFlow name(String name);

        abstract void ended();

        @Override
        public ReplyFlow beginAggregate(RespType kind, long length) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            if (depth != 0) {
                return wrongShape();
            }
            depth = 1;
            index = 0;
            // the elements, counting a map's names and values separately
            long count = kind == RespType.MAP && length >= 0 ? length * 2 : length;
            return begun(count);
        }

        @Override
        public ReplyFlow endAggregate() {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            depth = 0;
            ended();
            return ReplyFlow.CONTINUE;
        }

        // ── elements ──

        private ReplyFlow elementDone(ReplyFlow flow) {
            index++;
            return flow;
        }

        @Override
        public ReplyFlow nullValue() {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            if (depth == 0) {
                return topLevelNull();
            }
            if (atName()) {
                return wrongShape();
            }
            return elementDone(payloadNull());
        }

        abstract ReplyFlow topLevelNull();

        private ReplyFlow scalarElement(String text) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            if (depth == 0) {
                return wrongShape();
            }
            if (atName()) {
                return elementDone(name(text));
            }
            return elementDone(scalarPayload(text));
        }

        @Override
        public ReplyFlow integer(long value) {
            return scalarElement(scalarText(value));
        }

        @Override
        public ReplyFlow doubleValue(double value) {
            return scalarElement(Double.toString(value));
        }

        @Override
        public ReplyFlow booleanValue(boolean value) {
            return scalarElement(value ? "1" : "0");
        }

        @Override
        public ReplyFlow bigNumber(String digits) {
            return scalarElement(digits);
        }

        @Override
        public ReplyFlow simpleString(String value) {
            return scalarElement(value);
        }

        @Override
        public ReplyFlow error(String message) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            return topLevelError(message);
        }

        @Override
        public ReplyFlow beginString(RespType kind, long length, String format) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            if (depth == 0) {
                return wrongShape();
            }
            if (kind == RespType.BLOB_ERROR) {
                return reject(RedisClientProtocolHandler.L10N.getString("err.blob_error"));
            }
            inString = true;
            if (atName()) {
                if (!startText(length)) {
                    return tooLong(length);
                }
                return ReplyFlow.CONTINUE;
            }
            return payloadBegin(length);
        }

        @Override
        public ReplyFlow stringChunk(ByteBuffer chunk) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            if (atName()) {
                if (!addText(chunk)) {
                    return tooLong(maxField + (long) chunk.remaining());
                }
                return ReplyFlow.CONTINUE;
            }
            return payloadContent(chunk);
        }

        @Override
        public ReplyFlow endString() {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            inString = false;
            if (atName()) {
                return elementDone(name(endText()));
            }
            return elementDone(payloadEnd());
        }
    }

    /** An array of strings. */
    static final class PayloadArray extends Elements {
        private final ArrayResultHandler handler;

        PayloadArray(ArrayResultHandler handler, RedisSession session, int maxField) {
            super(session, maxField);
            this.handler = handler;
        }

        @Override
        ReplyFlow topLevelNull() {
            handler.handleNull();
            return ReplyFlow.CONTINUE;
        }

        @Override
        ReplyFlow begun(long count) {
            return flow(handler.handleBegin(count, stream));
        }

        @Override
        ReplyFlow name(String name) {
            return wrongShape();
        }

        @Override
        void ended() {
            handler.handleEnd();
        }

        @Override
        ReplyFlow payloadNull() {
            return flow(handler.handleElementNull());
        }

        @Override
        ReplyFlow payloadBegin(long length) {
            return flow(handler.handleElementBegin(length));
        }

        @Override
        ReplyFlow payloadContent(ByteBuffer chunk) {
            return flow(handler.handleElementContent(chunk));
        }

        @Override
        ReplyFlow payloadEnd() {
            return flow(handler.handleElementEnd());
        }

        @Override
        void succeed() {
            handler.handleComplete(null, session);
        }

        @Override
        void fail(String error) {
            handler.handleComplete(error, session);
        }
    }

    /** Pairs of a name and a string. */
    static final class PairMap extends Elements {
        private final MapResultHandler handler;

        PairMap(MapResultHandler handler, RedisSession session, int maxField) {
            super(session, maxField);
            this.handler = handler;
            this.pairs = true;
        }

        @Override
        ReplyFlow topLevelNull() {
            handler.handleNull();
            return ReplyFlow.CONTINUE;
        }

        @Override
        ReplyFlow begun(long count) {
            // a map announces its pairs, a flat array twice as many elements
            return flow(handler.handleBegin(count < 0 ? count : count / 2, stream));
        }

        @Override
        ReplyFlow name(String name) {
            return flow(handler.handleField(name));
        }

        @Override
        void ended() {
            handler.handleEnd();
        }

        @Override
        ReplyFlow payloadNull() {
            return flow(handler.handleValueNull());
        }

        @Override
        ReplyFlow payloadBegin(long length) {
            return flow(handler.handleValueBegin(length));
        }

        @Override
        ReplyFlow payloadContent(ByteBuffer chunk) {
            return flow(handler.handleValueContent(chunk));
        }

        @Override
        ReplyFlow payloadEnd() {
            return flow(handler.handleValueEnd());
        }

        @Override
        void succeed() {
            handler.handleComplete(null, session);
        }

        @Override
        void fail(String error) {
            handler.handleComplete(error, session);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Identifiers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads the elements of an array as identifiers, one at a time, each
     * delivered whole. {@code level} is how deep the array is, so that the
     * same reading serves the keys of a scan.
     */
    abstract static class Identifiers extends ReplyAdapter {

        Identifiers(RedisSession session, int maxField) {
            super(session, maxField);
        }

        /** An identifier is complete. */
        abstract ReplyFlow identifier(String value);

        // collects a scalar's text as an identifier
        final ReplyFlow scalarIdentifier(String text) {
            if (text.length() > maxField) {
                return tooLong(text.length());
            }
            return identifier(text);
        }

        final ReplyFlow beginIdentifier(long length) {
            if (!startText(length)) {
                return tooLong(length);
            }
            return ReplyFlow.CONTINUE;
        }

        final ReplyFlow identifierChunk(ByteBuffer chunk) {
            if (!addText(chunk)) {
                return tooLong((long) maxField + chunk.remaining());
            }
            return ReplyFlow.CONTINUE;
        }

        final ReplyFlow endIdentifier() {
            return identifier(endText());
        }
    }

    /** An array of identifiers. */
    static final class KeyArray extends Identifiers {
        private final KeyArrayResultHandler handler;

        KeyArray(KeyArrayResultHandler handler, RedisSession session, int maxField) {
            super(session, maxField);
            this.handler = handler;
        }

        @Override
        ReplyFlow identifier(String value) {
            return flow(handler.handleKey(value));
        }

        @Override
        public ReplyFlow nullValue() {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            if (depth == 0) {
                handler.handleNull();
                return ReplyFlow.CONTINUE;
            }
            return wrongShape();
        }

        @Override
        public ReplyFlow integer(long value) {
            return scalar(scalarText(value));
        }

        @Override
        public ReplyFlow doubleValue(double value) {
            return scalar(Double.toString(value));
        }

        @Override
        public ReplyFlow booleanValue(boolean value) {
            return scalar(value ? "1" : "0");
        }

        @Override
        public ReplyFlow bigNumber(String digits) {
            return scalar(digits);
        }

        @Override
        public ReplyFlow simpleString(String value) {
            return scalar(value);
        }

        private ReplyFlow scalar(String text) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            return depth == 1 ? scalarIdentifier(text) : wrongShape();
        }

        @Override
        public ReplyFlow error(String message) {
            return discarding() ? ReplyFlow.SKIP : topLevelError(message);
        }

        @Override
        public ReplyFlow beginAggregate(RespType kind, long length) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            if (depth != 0) {
                return wrongShape();
            }
            depth = 1;
            return flow(handler.handleBegin(
                    kind == RespType.MAP && length >= 0 ? length * 2 : length, stream));
        }

        @Override
        public ReplyFlow endAggregate() {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            depth = 0;
            handler.handleEnd();
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow beginString(RespType kind, long length, String format) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            if (depth != 1 || kind == RespType.BLOB_ERROR) {
                return wrongShape();
            }
            return beginIdentifier(length);
        }

        @Override
        public ReplyFlow stringChunk(ByteBuffer chunk) {
            return discarding() ? ReplyFlow.SKIP : identifierChunk(chunk);
        }

        @Override
        public ReplyFlow endString() {
            return discarding() ? ReplyFlow.SKIP : endIdentifier();
        }

        @Override
        void succeed() {
            handler.handleComplete(null, session);
        }

        @Override
        void fail(String error) {
            handler.handleComplete(error, session);
        }
    }

    /** The batch of a SCAN: a cursor, then an array of keys. */
    static final class Scan extends Identifiers {
        private final ScanResultHandler handler;
        // which element of the outer array is being read
        private int element;

        Scan(ScanResultHandler handler, RedisSession session, int maxField) {
            super(session, maxField);
            this.handler = handler;
        }

        @Override
        ReplyFlow identifier(String value) {
            if (depth == 1) {
                // the cursor, the first element
                element++;
                return flow(handler.handleCursor(value, stream));
            }
            return flow(handler.handleKey(value));
        }

        @Override
        public ReplyFlow nullValue() {
            return discarding() ? ReplyFlow.SKIP : wrongShape();
        }

        @Override
        public ReplyFlow integer(long value) {
            return scalar(scalarText(value));
        }

        @Override
        public ReplyFlow doubleValue(double value) {
            return scalar(Double.toString(value));
        }

        @Override
        public ReplyFlow booleanValue(boolean value) {
            return scalar(value ? "1" : "0");
        }

        @Override
        public ReplyFlow bigNumber(String digits) {
            return scalar(digits);
        }

        @Override
        public ReplyFlow simpleString(String value) {
            return scalar(value);
        }

        private ReplyFlow scalar(String text) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            if (depth == 1 && element == 0 || depth == 2) {
                return scalarIdentifier(text);
            }
            return wrongShape();
        }

        @Override
        public ReplyFlow error(String message) {
            return discarding() ? ReplyFlow.SKIP : topLevelError(message);
        }

        @Override
        public ReplyFlow beginAggregate(RespType kind, long length) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            if (depth == 0) {
                depth = 1;
                element = 0;
                return ReplyFlow.CONTINUE;
            }
            if (depth == 1 && element == 1) {
                depth = 2;
                return ReplyFlow.CONTINUE;
            }
            return wrongShape();
        }

        @Override
        public ReplyFlow endAggregate() {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            if (depth == 2) {
                depth = 1;
                element = 2;
                return ReplyFlow.CONTINUE;
            }
            depth = 0;
            handler.handleEnd();
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow beginString(RespType kind, long length, String format) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            if (kind == RespType.BLOB_ERROR || depth == 1 && element != 0 || depth == 0) {
                return wrongShape();
            }
            return beginIdentifier(length);
        }

        @Override
        public ReplyFlow stringChunk(ByteBuffer chunk) {
            return discarding() ? ReplyFlow.SKIP : identifierChunk(chunk);
        }

        @Override
        public ReplyFlow endString() {
            return discarding() ? ReplyFlow.SKIP : endIdentifier();
        }

        @Override
        void succeed() {
            handler.handleComplete(null, session);
        }

        @Override
        void fail(String error) {
            handler.handleComplete(error, session);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scalars: bounded by the line, delivered as values
    // ─────────────────────────────────────────────────────────────────────────

    /** A reply that is one value no larger than a line. */
    abstract static class Scalar extends ReplyAdapter {

        // what arrived
        boolean isNull;
        boolean isInteger;
        long integerValue;
        String stringValue;
        boolean arrived;

        Scalar(RedisSession session, int maxField) {
            super(session, maxField);
        }

        private ReplyFlow take() {
            if (arrived) {
                return wrongShape();
            }
            arrived = true;
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow nullValue() {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            if (depth != 0) {
                return wrongShape();
            }
            isNull = true;
            return take();
        }

        @Override
        public ReplyFlow integer(long value) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            if (depth != 0) {
                return wrongShape();
            }
            isInteger = true;
            integerValue = value;
            stringValue = Long.toString(value);
            return take();
        }

        @Override
        public ReplyFlow doubleValue(double value) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            if (depth != 0) {
                return wrongShape();
            }
            stringValue = Double.toString(value);
            if (value == Math.rint(value) && !Double.isInfinite(value)
                    && Math.abs(value) < 9.0e18) {
                isInteger = true;
                integerValue = (long) value;
            }
            return take();
        }

        @Override
        public ReplyFlow booleanValue(boolean value) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            if (depth != 0) {
                return wrongShape();
            }
            isInteger = true;
            integerValue = value ? 1 : 0;
            stringValue = value ? "1" : "0";
            return take();
        }

        @Override
        public ReplyFlow bigNumber(String digits) {
            return textValue(digits);
        }

        @Override
        public ReplyFlow simpleString(String value) {
            return textValue(value);
        }

        private ReplyFlow textValue(String text) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            if (depth != 0) {
                return wrongShape();
            }
            stringValue = text;
            return take();
        }

        @Override
        public ReplyFlow error(String message) {
            return discarding() ? ReplyFlow.SKIP : topLevelError(message);
        }

        @Override
        public ReplyFlow beginAggregate(RespType kind, long length) {
            return discarding() ? ReplyFlow.SKIP : wrongShape();
        }

        @Override
        public ReplyFlow endAggregate() {
            return ReplyFlow.SKIP;
        }

        @Override
        public ReplyFlow beginString(RespType kind, long length, String format) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            if (depth != 0 || kind == RespType.BLOB_ERROR) {
                return wrongShape();
            }
            if (!startText(length)) {
                return tooLong(length);
            }
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow stringChunk(ByteBuffer chunk) {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            if (!addText(chunk)) {
                return tooLong((long) maxField + chunk.remaining());
            }
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow endString() {
            if (discarding()) {
                return ReplyFlow.SKIP;
            }
            return textValue(endText());
        }

        // a text reply read as a number, or failing
        final boolean numberOrFail() {
            if (isInteger) {
                return true;
            }
            if (stringValue != null) {
                try {
                    integerValue = Long.parseLong(stringValue.trim());
                    return true;
                } catch (NumberFormatException e) {
                    // fall through
                }
            }
            return false;
        }
    }

    /** An integer. */
    static final class IntegerScalar extends Scalar {
        private final IntegerResultHandler handler;

        IntegerScalar(IntegerResultHandler handler, RedisSession session, int maxField) {
            super(session, maxField);
            this.handler = handler;
        }

        @Override
        void succeed() {
            if (!arrived || isNull || !numberOrFail()) {
                fail(RedisClientProtocolHandler.L10N.getString("err.reply_shape"));
                return;
            }
            handler.handleResult(integerValue, session);
        }

        @Override
        void fail(String error) {
            handler.handleError(error, session);
        }
    }

    /** A boolean, as the integer 0 or 1. */
    static final class BooleanScalar extends Scalar {
        private final BooleanResultHandler handler;

        BooleanScalar(BooleanResultHandler handler, RedisSession session, int maxField) {
            super(session, maxField);
            this.handler = handler;
        }

        @Override
        void succeed() {
            if (!arrived || isNull || !numberOrFail()) {
                fail(RedisClientProtocolHandler.L10N.getString("err.reply_shape"));
                return;
            }
            handler.handleResult(integerValue != 0, session);
        }

        @Override
        void fail(String error) {
            handler.handleError(error, session);
        }
    }

    /** A short string such as {@code OK}. */
    static final class StringScalar extends Scalar {
        private final StringResultHandler handler;

        StringScalar(StringResultHandler handler, RedisSession session, int maxField) {
            super(session, maxField);
            this.handler = handler;
        }

        @Override
        void succeed() {
            if (!arrived) {
                fail(RedisClientProtocolHandler.L10N.getString("err.reply_shape"));
                return;
            }
            handler.handleResult(isNull ? null : stringValue, session);
        }

        @Override
        void fail(String error) {
            handler.handleError(error, session);
        }
    }

}
