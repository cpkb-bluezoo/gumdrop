/*
 * DotUnstuffer.java
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

package org.bluezoo.gumdrop.pop3.client;

import java.nio.ByteBuffer;

/**
 * Handles POP3 dot-unstuffing and termination detection for multi-line
 * responses.
 *
 * <p>Per RFC 1939, multi-line responses are terminated by a line
 * containing only a dot ({@code .\r\n}). Lines beginning with a dot have
 * the extra dot removed (dot-unstuffing).
 *
 * <p>This class processes raw ByteBuffer chunks arriving from the network
 * and delivers decoded content to a {@link Callback}. It maintains state
 * across chunk boundaries to handle cases where CRLF sequences or the
 * termination dot are split across buffers.
 *
 * <p>The state machine tracks:
 * <ul>
 * <li>NORMAL - processing normal content</li>
 * <li>SAW_CR - saw CR, waiting for LF</li>
 * <li>SAW_CRLF - saw CRLF, checking for dot at start of line</li>
 * <li>SAW_CRLF_DOT - saw CRLF followed by dot, checking for
 *     termination (CRLF) or stuffed dot</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class DotUnstuffer {

    /**
     * Callback for receiving decoded content and termination signals.
     */
    interface Callback {

        /**
         * Called with a chunk of decoded (dot-unstuffed) content.
         *
         * <p>The ByteBuffer is a slice of the input buffer or a
         * temporary buffer. It is only valid for the duration of
         * this call.
         *
         * @param content decoded content chunk
         */
        void content(ByteBuffer content);

        /**
         * Called when the terminating dot line is detected.
         */
        void complete();
    }

    private enum State {
        NORMAL,
        SAW_CR,
        SAW_CRLF,
        SAW_CRLF_DOT,
        SAW_CRLF_DOT_CR
    }

    private State state;
    private final Callback callback;
    private boolean atStart;

    /**
     * Creates a dot unstuffer.
     *
     * @param callback receives decoded content and termination
     */
    DotUnstuffer(Callback callback) {
        this.callback = callback;
        this.state = State.SAW_CRLF;
        this.atStart = true;
    }

    /**
     * Processes a chunk of raw data from the network.
     *
     * <p>This method may call {@link Callback#content} zero or more
     * times, and {@link Callback#complete} at most once.
     *
     * @param input the raw data chunk
     * @return true if more data is expected, false if the terminator
     *         was detected
     */
    boolean process(ByteBuffer input) {
        int flushFrom = input.position();

        while (input.hasRemaining()) {
            byte b = input.get();

            switch (state) {
                case NORMAL:
                    if (b == '\r') {
                        state = State.SAW_CR;
                    }
                    break;

                case SAW_CR:
                    if (b == '\n') {
                        // Flush content before the CR-LF pair; hold
                        // the CRLF pending until we know whether the
                        // next line starts with a dot.
                        int flushTo = input.position() - 2;
                        if (flushTo > flushFrom) {
                            flush(input, flushFrom, flushTo);
                        }
                        flushFrom = input.position();
                        state = State.SAW_CRLF;
                    } else {
                        state = (b == '\r') ? State.SAW_CR
                                : State.NORMAL;
                    }
                    break;

                case SAW_CRLF:
                    if (b == '.') {
                        state = State.SAW_CRLF_DOT;
                        flushFrom = input.position();
                    } else if (b == '\r') {
                        // Not a dot: emit the held CRLF as content.
                        if (!atStart) {
                            callback.content(
                                    ByteBuffer.wrap(CRLF_BYTES));
                        }
                        atStart = false;
                        flushFrom = input.position() - 1;
                        state = State.SAW_CR;
                    } else {
                        if (!atStart) {
                            callback.content(
                                    ByteBuffer.wrap(CRLF_BYTES));
                        }
                        atStart = false;
                        state = State.NORMAL;
                    }
                    break;

                case SAW_CRLF_DOT:
                    if (b == '\r') {
                        state = State.SAW_CRLF_DOT_CR;
                    } else if (b == '.') {
                        // Dot-stuffed line: skip the first dot, emit
                        // the held CRLF + second dot onward.
                        if (!atStart) {
                            callback.content(
                                    ByteBuffer.wrap(CRLF_BYTES));
                        }
                        flushFrom = input.position() - 1;
                        state = State.NORMAL;
                        atStart = false;
                    } else {
                        // Dot at start of line but not stuffed or
                        // termination: emit held CRLF + dot.
                        if (!atStart) {
                            callback.content(
                                    ByteBuffer.wrap(CRLF_DOT_BYTES));
                        } else {
                            callback.content(
                                    ByteBuffer.wrap(DOT_BYTES));
                        }
                        flushFrom = input.position() - 1;
                        state = State.NORMAL;
                        atStart = false;
                    }
                    break;

                case SAW_CRLF_DOT_CR:
                    if (b == '\n') {
                        // Termination: CRLF.CRLF
                        // Emit the held CRLF for the last content line.
                        if (!atStart) {
                            callback.content(
                                    ByteBuffer.wrap(CRLF_BYTES));
                        }
                        flushFrom = input.position();
                        callback.complete();
                        reset();
                        return false;
                    } else {
                        // CRLF.CR but no LF: emit held CRLF + dot + CR
                        // and continue.
                        if (!atStart) {
                            callback.content(
                                    ByteBuffer.wrap(CRLF_DOT_CR_BYTES));
                        } else {
                            callback.content(
                                    ByteBuffer.wrap(DOT_CR_BYTES));
                        }
                        flushFrom = input.position() - 1;
                        state = (b == '\r') ? State.SAW_CR
                                : State.NORMAL;
                        atStart = false;
                    }
                    break;
            }
        }

        // Flush remaining content that doesn't involve pending state.
        // Content before a pending CRLF was already flushed eagerly
        // in the SAW_CR → SAW_CRLF transition above.
        int limit = input.position();
        if (limit > flushFrom && state == State.NORMAL) {
            flush(input, flushFrom, limit);
        } else if (limit > flushFrom && state == State.SAW_CR) {
            if (limit - 1 > flushFrom) {
                flush(input, flushFrom, limit - 1);
            }
        }

        return true;
    }

    /**
     * Resets the unstuffer for reuse.
     */
    void reset() {
        state = State.SAW_CRLF;
        atStart = true;
    }

    private void flush(ByteBuffer buf, int from, int to) {
        if (to <= from) {
            return;
        }
        int savedPos = buf.position();
        int savedLimit = buf.limit();
        buf.position(from);
        buf.limit(to);
        callback.content(buf.slice());
        buf.limit(savedLimit);
        buf.position(savedPos);
    }

    private static final byte[] CRLF_BYTES = {'\r', '\n'};
    private static final byte[] DOT_BYTES = {'.'};
    private static final byte[] CRLF_DOT_BYTES = {'\r', '\n', '.'};
    private static final byte[] DOT_CR_BYTES = {'.', '\r'};
    private static final byte[] CRLF_DOT_CR_BYTES =
            {'\r', '\n', '.', '\r'};
}
