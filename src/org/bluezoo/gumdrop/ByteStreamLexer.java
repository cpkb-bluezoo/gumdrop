/*
 * ByteStreamLexer.java
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

package org.bluezoo.gumdrop;

import java.nio.ByteBuffer;

/**
 * Shared scaffold for streaming byte-to-token lexers (issue #85).
 *
 * <p>Replaces the buffered-line model of {@link LineParser} with a
 * zero-copy, zero-allocation token stream: bytes arrive via {@link
 * #feed(ByteBuffer)}, and complete tokens are delivered to a {@link
 * Handler} as windows over the <em>same</em> buffer the caller passed in.
 * No {@code Token} object is ever created and no token payload is copied
 * by this class; a {@link Handler} that needs to retain a token's bytes
 * beyond the callback must copy them out itself.
 *
 * <h3>Token window lifetime (SAX {@code characters()} semantics)</h3>
 * <p>The {@link ByteBuffer} passed to {@link Handler#token(Enum, ByteBuffer)}
 * and {@link Handler#rawBytes(ByteBuffer)} is <strong>valid only for the
 * duration of that call</strong>. Its position and limit are temporarily
 * narrowed to the token's byte span and are restored immediately
 * afterwards. Do not store the buffer reference and read from it later.
 *
 * <h3>Structured tokens vs. free-form text</h3>
 * <p>A subclass implements {@link #consume(byte)} to recognise
 * <em>structured</em> tokens (verbs, atoms, numbers, punctuation) one byte
 * at a time, calling {@link #emit(Enum, int, int)} whenever it recognises a
 * complete token. Structured tokens are subject to a hard per-byte cap
 * (see below).
 *
 * <p>Free-form trailing text (a reply message, a header value) cannot be
 * lexed context-free, so a subclass's {@link #consume(byte)} return from
 * {@link #emit(Enum, int, int)} whose result is {@code true} tells this
 * class to <strong>latch text mode</strong>: from the next byte until the
 * terminating CRLF, this class itself scans for the line terminator and
 * emits the configured text token type in chunks — one token per
 * contiguous run available in the buffer at any given moment, flushing at
 * every buffer boundary rather than accumulating. A value spanning several
 * {@link #feed(ByteBuffer)} calls therefore arrives as multiple text
 * tokens followed by one CRLF token. Whether to concatenate those chunks,
 * stream them elsewhere, or discard them is entirely the {@link Handler}'s
 * choice; this class never buffers them itself. Text mode is exempt from
 * the per-token cap.
 *
 * <h3>Binary escapes</h3>
 * <p>A subclass calls {@link #enterRaw(long)} or {@link
 * #enterRawUntil(byte[])} (typically from within {@link #consume(byte)},
 * or from a protocol-specific public method that a parser calls on its
 * lexer instance once it has decided to accept a binary phase, e.g. after
 * sending an SMTP "354" reply) to switch into raw mode: subsequent bytes
 * are delivered to {@link Handler#rawBytes(ByteBuffer)} without
 * tokenising, until the byte count ({@link #enterRaw(long)}) or delimiter
 * ({@link #enterRawUntil(byte[])}) is satisfied, at which point structured
 * token scanning resumes automatically — within the same {@link
 * #feed(ByteBuffer)} call if the buffer still has data, which is what lets
 * a mid-line binary escape (e.g. an IMAP {@code {nnn}} literal) resume
 * token scanning for the rest of the same command line.
 *
 * <p>{@link #enterRawUntil(byte[])} needs to recognise a multi-byte
 * delimiter that may itself be split across {@link #feed(ByteBuffer)}
 * calls. Bytes that are only a <em>tentative</em> match for a prefix of
 * the delimiter are held in a small side buffer, sized exactly to the
 * delimiter length (a handful of bytes), until the match is confirmed or
 * refuted — this is the one bounded exception to "no copies" in this
 * class, and it does not reintroduce the O(line) buffering problem this
 * design replaces, since its size is fixed by the delimiter, never by the
 * amount of raw content scanned.
 *
 * <h3>The hard per-byte cap (closes the accumulation window in SEC-032)</h3>
 * <p>A structured token that has not completed within {@code
 * maxTokenLength} bytes of the last confirmed token boundary triggers
 * {@link Handler#tokenTooLong()} immediately, without ever assembling the
 * over-long run. Use {@link #checkTokenCap(int, int)} when configuring a
 * lexer to assert that {@code maxTokenLength} does not exceed the
 * transport's {@code maxNetInSize}: if it did, a legitimately-long but
 * capped-length token could never fit in the connection's receive buffer,
 * and the transport would close the connection with an {@code
 * IOException} before {@link Handler#tokenTooLong()} ever had a chance to
 * fire. Free-form text and raw escapes are exempt from this cap, since
 * they are delivered incrementally and never need to fit in the buffer
 * whole.
 *
 * <h3>Buffer contract</h3>
 * <p>Identical to {@link LineParser}: the input buffer must be in read
 * mode (position at the start of available data, limit at the end).
 * After {@link #feed(ByteBuffer)} returns, the buffer's position marks the
 * start of unconsumed data (a partial structured token still being
 * accumulated). The caller is responsible for compacting the buffer
 * before reading more data — see {@code TCPEndpoint.processInbound()} for
 * the transport-level contract this class relies on: unconsumed bytes are
 * preserved across reads via {@code compact()}, and the receive buffer
 * grows (bounded by {@code maxNetInSize}) if a single token does not yet
 * fit.
 *
 * <h3>Replay safety for subclasses</h3>
 * <p>When a structured token is incomplete at the end of a {@link
 * #feed(ByteBuffer)} call, this class rewinds the buffer to the last
 * confirmed token boundary (exactly as {@link LineParser} rewinds to the
 * start of an incomplete line). On the next call — after the caller has
 * compacted and refilled the buffer, which physically moves those bytes —
 * every byte since that boundary, including ones already seen, is fed
 * through {@link #consume(byte)} again.
 *
 * <p><strong>A subclass must never persist an absolute buffer position
 * across a {@link #feed(ByteBuffer)} call as "where my in-progress token
 * started."</strong> Compaction invalidates it. Use {@link
 * #regionStart()} instead: it is recomputed correctly before every
 * re-entry into token scanning (including after a rewind), so it is
 * always the true start of whatever is currently accumulating. For a
 * token that begins at the very first byte since the last {@link
 * #emit(Enum, int, int)}, its start position <em>is</em> {@link
 * #regionStart()}; for a sub-position within a multi-part production
 * (e.g. the digits inside an IMAP {@code {nnn}} literal header, which
 * begin one byte after the production's own start), track it as an
 * <em>offset from</em> {@link #regionStart()}, not as an independently
 * remembered absolute position.
 *
 * <p>Non-positional state (an enum-like sub-state, a running count) may
 * be persisted freely across calls — the hazard is specifically absolute
 * positions. This is the same discipline {@link LineParser} already
 * follows for CRLF detection, which does not persist its "last byte was
 * CR" flag across calls, instead re-deriving it by replaying the trailing
 * CR from the start on the next call.
 *
 * @param <T> the protocol-specific token type enum
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see LineParser
 */
public abstract class ByteStreamLexer<T extends Enum<T>> {

    private static final byte CR = (byte) '\r';
    private static final byte LF = (byte) '\n';

    private static final int MODE_TOKEN = 0;
    private static final int MODE_RAW_FIXED = 1;
    private static final int MODE_RAW_UNTIL = 2;
    private static final int MODE_TEXT = 3;

    /**
     * Callback interface for receiving lexed tokens. Implemented by the
     * per-protocol parser (the existing protocol handler).
     *
     * @param <T> the protocol-specific token type enum
     */
    public interface Handler<T extends Enum<T>> {

        /**
         * Called when a complete structured token, or a chunk of free-form
         * text, has been recognised.
         *
         * <p>{@code window} is a zero-copy view over the lexer's input
         * buffer, windowed to exactly this token's bytes, valid only for
         * the duration of this call.
         *
         * @param type the token type
         * @param window the token's bytes, valid only during this call
         * @return {@code true} to latch text mode from the next byte until
         *      the terminating CRLF; ignored for text-mode chunk tokens
         *      and for the CRLF token itself
         */
        boolean token(T type, ByteBuffer window);

        /**
         * Called with a run of raw bytes during a {@link
         * #enterRaw(long)} or {@link #enterRawUntil(byte[])} escape.
         *
         * <p>{@code slice} is a zero-copy view, valid only for the
         * duration of this call.
         *
         * @param slice the raw bytes, valid only during this call
         */
        void rawBytes(ByteBuffer slice);

        /**
         * Called when a structured token exceeds the configured {@code
         * maxTokenLength} before completing. Parsing has stopped; the
         * implementation should error the connection.
         */
        void tokenTooLong();
    }

    private final Handler<T> handler;
    private final int maxTokenLength;
    private final T crlfTokenType;
    private final T textTokenType;

    private int mode;
    private int regionStart;

    /** Transiently set for the duration of one {@link #feed(ByteBuffer)} call. */
    private ByteBuffer currentBuf;

    // RAW(n) state, persists across feed() calls
    private long rawRemaining;

    // RAW_UNTIL(delim) state, persists across feed() calls
    private byte[] delimiter;
    private byte[] pending;
    private int delimMatched;

    /**
     * Creates a new lexer.
     *
     * @param handler the handler to receive lexed tokens
     * @param maxTokenLength maximum number of bytes for a structured token
     *      before {@link Handler#tokenTooLong()} fires; not enforced for
     *      free-form text or raw escapes
     * @param crlfTokenType the token type representing a CRLF line
     *      terminator; used by text mode to emit the terminating token
     * @param textTokenType the token type representing a chunk of
     *      free-form text; used by text mode for each chunk emitted
     */
    protected ByteStreamLexer(Handler<T> handler, int maxTokenLength,
                               T crlfTokenType, T textTokenType) {
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        if (maxTokenLength <= 0) {
            throw new IllegalArgumentException("maxTokenLength must be positive");
        }
        if (crlfTokenType == null) {
            throw new IllegalArgumentException("crlfTokenType must not be null");
        }
        if (textTokenType == null) {
            throw new IllegalArgumentException("textTokenType must not be null");
        }
        this.handler = handler;
        this.maxTokenLength = maxTokenLength;
        this.crlfTokenType = crlfTokenType;
        this.textTokenType = textTokenType;
        this.mode = MODE_TOKEN;
    }

    /**
     * Asserts that a lexer's per-token cap can never exceed the
     * transport's receive-buffer growth ceiling.
     *
     * <p>If {@code maxTokenLength} exceeded {@code maxNetInSize}, a
     * legitimately-long-but-still-capped token could never fit in the
     * connection's receive buffer, and the transport would close the
     * connection with an {@code IOException} before {@link
     * Handler#tokenTooLong()} ever had a chance to fire. Call this when
     * constructing a per-protocol lexer, once the transport's configured
     * {@code maxNetInSize} is known.
     *
     * @param maxTokenLength the lexer's configured per-token cap
     * @param maxNetInSize the transport's configured receive-buffer ceiling
     * @throws IllegalArgumentException if maxTokenLength exceeds maxNetInSize
     */
    public static void checkTokenCap(int maxTokenLength, int maxNetInSize) {
        if (maxTokenLength > maxNetInSize) {
            throw new IllegalArgumentException("maxTokenLength (" + maxTokenLength
                    + ") exceeds maxNetInSize (" + maxNetInSize + "); a token of "
                    + "that length could never fit in the connection's receive "
                    + "buffer, so the transport would close the connection before "
                    + "tokenTooLong() could fire");
        }
    }

    /**
     * Feeds bytes to the lexer, dispatching complete tokens (and raw
     * escape data) to the {@link Handler} as they are recognised.
     *
     * <p>Consumes as many complete tokens as the buffer contains. If a
     * structured token is incomplete at the end of the buffer, the
     * buffer's position is left at the start of that token so the caller
     * can compact and await more data (see the buffer contract in the
     * class Javadoc).
     *
     * @param buf the byte data in read mode
     */
    public final void feed(ByteBuffer buf) {
        currentBuf = buf;
        boolean cont = true;
        while (cont && buf.hasRemaining()) {
            switch (mode) {
                case MODE_RAW_FIXED:
                    cont = continueRawFixed(buf);
                    break;
                case MODE_RAW_UNTIL:
                    cont = continueRawUntil(buf);
                    break;
                case MODE_TEXT:
                    cont = continueText(buf);
                    break;
                default:
                    regionStart = buf.position();
                    cont = scanTokens(buf);
                    break;
            }
        }
    }

    /**
     * Recognises one byte of a structured token.
     *
     * <p>Called once per byte while in normal token-scanning mode (never
     * during a raw escape or latched text mode, both of which this class
     * handles generically). Implementations track their own lexical state
     * and call {@link #emit(Enum, int, int)} whenever a complete token has
     * been recognised. Use {@link #currentPosition()} to determine the
     * buffer position of the byte just passed in (it is one past that
     * byte, since this class advances the buffer position before calling
     * this method).
     *
     * @param b the next byte
     * @return {@code true} to continue lexing; {@code false} to abort
     *      immediately (e.g. an unrecoverable per-byte protocol
     *      violation) — parsing stops with the buffer position left just
     *      past {@code b}, and {@link Handler#tokenTooLong()} is
     *      <em>not</em> called (this is a distinct, subclass-driven
     *      abort, not a cap violation)
     */
    protected abstract boolean consume(byte b);

    /**
     * Returns the position, in the buffer currently being fed, one past
     * the byte most recently passed to {@link #consume(byte)}.
     *
     * <p>Only valid while inside a {@link #consume(byte)} call.
     *
     * @return the current buffer position
     */
    protected final int currentPosition() {
        return currentBuf.position();
    }

    /**
     * Returns the start position of the token currently being
     * accumulated: the position immediately after the last confirmed
     * token boundary (the last successful {@link #emit(Enum, int, int)}
     * call), or the start of the current structured-token-scanning phase
     * if none has been emitted yet.
     *
     * <p>This value is recomputed correctly on every re-entry into token
     * scanning, including after a rewind for an incomplete token — see
     * "Replay safety for subclasses" in the class Javadoc. Use this
     * instead of remembering an absolute buffer position across {@link
     * #feed(ByteBuffer)} calls.
     *
     * @return the start position of the in-progress token
     */
    protected final int regionStart() {
        return regionStart;
    }

    /**
     * Emits a complete token, delivering a zero-copy window over
     * {@code [startPos, endPos)} of the buffer currently being fed.
     *
     * <p>May be called from {@link #consume(byte)} for structured tokens,
     * or internally by this class's text-mode handling. Resets the
     * per-byte cap baseline to {@code endPos} and, for the configured
     * CRLF token type, returns to structured token-scanning mode; for any
     * other token type, latches text mode if the handler's return value
     * was {@code true}.
     *
     * @param type the token type
     * @param startPos the token's start position (inclusive)
     * @param endPos the token's end position (exclusive)
     * @return the handler's return value from {@link
     *      Handler#token(Enum, ByteBuffer)}, or {@code false} for the
     *      CRLF token type (whose return value is not used for latching)
     */
    protected final boolean emit(T type, int startPos, int endPos) {
        ByteBuffer buf = currentBuf;
        int savedLimit = buf.limit();
        buf.limit(endPos);
        buf.position(startPos);
        int modeBeforeCallback = mode;
        boolean textRequested;
        if (type == crlfTokenType) {
            handler.token(type, buf);
            textRequested = false;
        } else {
            textRequested = handler.token(type, buf);
        }
        buf.limit(savedLimit);
        buf.position(endPos);
        regionStart = endPos;
        // If the handler synchronously called enterRaw()/enterRawUntil()
        // during the callback above (e.g. accepting a binary phase right
        // after seeing this token), mode already correctly reflects that
        // raw escape — do not override it. Only apply this token's own
        // mode effect (CRLF -> back to token mode; latched text -> text
        // mode) when the callback left the mode unchanged.
        if (mode == modeBeforeCallback) {
            if (type == crlfTokenType) {
                mode = MODE_TOKEN;
            } else if (textRequested) {
                mode = MODE_TEXT;
            }
        }
        return textRequested;
    }

    /**
     * Switches to raw mode for exactly {@code n} bytes, delivered to
     * {@link Handler#rawBytes(ByteBuffer)} without tokenising. Structured
     * token scanning resumes automatically once {@code n} bytes have been
     * delivered, within the same {@link #feed(ByteBuffer)} call if data
     * remains.
     *
     * @param n the number of raw bytes to deliver before resuming
     */
    protected final void enterRaw(long n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must not be negative");
        }
        this.rawRemaining = n;
        this.mode = MODE_RAW_FIXED;
    }

    /**
     * Switches to raw mode until {@code delim} is found in the input,
     * delivering everything before it to {@link
     * Handler#rawBytes(ByteBuffer)} (the delimiter bytes themselves are
     * not delivered). Structured token scanning resumes automatically once
     * the delimiter is found, within the same {@link #feed(ByteBuffer)}
     * call if data remains.
     *
     * @param delim the terminating byte sequence, e.g. {@code "\r\n.\r\n"}
     *      for SMTP DATA / POP3 multiline dot-termination
     */
    protected final void enterRawUntil(byte[] delim) {
        if (delim == null || delim.length == 0) {
            throw new IllegalArgumentException("delim must be non-empty");
        }
        this.delimiter = delim;
        this.pending = new byte[delim.length];
        this.delimMatched = 0;
        this.mode = MODE_RAW_UNTIL;
    }

    private boolean scanTokens(ByteBuffer buf) {
        while (buf.hasRemaining()) {
            int pos = buf.position();
            byte b = buf.get(pos);
            buf.position(pos + 1);
            boolean cont = consume(b);
            if (!cont) {
                return false;
            }
            if (mode != MODE_TOKEN) {
                return true;
            }
            if (buf.position() - regionStart > maxTokenLength) {
                handler.tokenTooLong();
                return false;
            }
        }
        buf.position(regionStart);
        return false;
    }

    private boolean continueText(ByteBuffer buf) {
        int start = buf.position();
        byte last = 0;
        while (buf.hasRemaining()) {
            int pos = buf.position();
            byte c = buf.get(pos);
            buf.position(pos + 1);
            if (c == LF && last == CR) {
                int crStart = pos - 1;
                if (crStart > start) {
                    emit(textTokenType, start, crStart);
                }
                emit(crlfTokenType, crStart, pos + 1);
                return true;
            }
            last = c;
        }
        int flushEnd = buf.position();
        if (last == CR) {
            flushEnd = flushEnd - 1;
        }
        buf.position(flushEnd);
        if (flushEnd > start) {
            emit(textTokenType, start, flushEnd);
        }
        return false;
    }

    private boolean continueRawFixed(ByteBuffer buf) {
        while (rawRemaining > 0 && buf.hasRemaining()) {
            int pos = buf.position();
            long availableLong = Math.min(rawRemaining, (long) buf.remaining());
            int available = (int) availableLong;
            emitRawSlice(buf, pos, pos + available);
            buf.position(pos + available);
            rawRemaining -= available;
        }
        if (rawRemaining > 0) {
            return false;
        }
        mode = MODE_TOKEN;
        return true;
    }

    private boolean continueRawUntil(ByteBuffer buf) {
        int flushStart = buf.position();
        while (buf.hasRemaining()) {
            int pos = buf.position();
            byte b = buf.get(pos);
            if (b == delimiter[delimMatched]) {
                if (delimMatched == 0 && pos > flushStart) {
                    emitRawSlice(buf, flushStart, pos);
                }
                pending[delimMatched] = b;
                delimMatched++;
                buf.position(pos + 1);
                if (delimMatched == delimiter.length) {
                    delimMatched = 0;
                    mode = MODE_TOKEN;
                    return true;
                }
                flushStart = buf.position();
            } else if (delimMatched > 0) {
                ByteBuffer pendingWindow = ByteBuffer.wrap(pending, 0, delimMatched);
                handler.rawBytes(pendingWindow);
                delimMatched = 0;
                flushStart = pos;
                // re-examine b against delimiter[0] on the next iteration
            } else {
                buf.position(pos + 1);
            }
        }
        if (buf.position() > flushStart) {
            emitRawSlice(buf, flushStart, buf.position());
        }
        return false;
    }

    private void emitRawSlice(ByteBuffer buf, int start, int end) {
        if (start >= end) {
            return;
        }
        int savedPosition = buf.position();
        int savedLimit = buf.limit();
        buf.limit(end);
        buf.position(start);
        handler.rawBytes(buf);
        buf.limit(savedLimit);
        buf.position(savedPosition);
    }
}
