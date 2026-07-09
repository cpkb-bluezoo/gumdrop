/*
 * SMTPClientLexer.java
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

package org.bluezoo.gumdrop.smtp.client;

import org.bluezoo.gumdrop.ByteStreamLexer;

/**
 * Streaming lexer for SMTP server replies (RFC 5321 §4.2):
 * {@code CODE [SEP TEXT] CRLF}, where {@code CODE} is a fixed 3-digit
 * reply code and {@code SEP} is a single byte — {@code '-'} for a
 * multi-line continuation, or (leniently, matching the pre-conversion
 * behaviour) any other byte for a final line — that is consumed but not
 * itself part of the message text.
 *
 * <p>Unlike the command grammar used by the server-side lexers (a
 * variable-length {@code KEYWORD} up to the first space), the reply code
 * is a fixed width, so this lexer tracks a small amount of per-line
 * non-positional state ({@code sawCode}) rather than scanning for a
 * delimiter. Once {@code SEP} is emitted, the lexer latches text mode
 * exactly as the command lexers do after {@code SP}: {@link
 * #consume(byte)} is not re-entered for the rest of the line, since the
 * base class's text-mode handling takes over.
 *
 * <p>SMTP client responses never carry raw binary content — DATA/BDAT
 * content is written by the client, not received — so this lexer never
 * enters a raw escape and {@link SMTPClientProtocolHandler} never needs
 * to call {@code requestStop()}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SMTPClientProtocolHandler
 */
final class SMTPClientLexer extends ByteStreamLexer<SMTPClientLexer.Token> {

    enum Token { CODE, DASH, SP, TEXT, CRLF }

    private boolean lastWasCR;
    private boolean sawCode;

    SMTPClientLexer(Handler<Token> handler, int maxTokenLength) {
        super(handler, maxTokenLength, Token.CRLF, Token.TEXT);
    }

    /**
     * Resets the CODE-vs-separator tracking for the next line.
     *
     * <p>Once {@code SEP} latches text mode, {@link #consume(byte)} is not
     * re-entered for the rest of that line — the base class's text-mode
     * handling (see {@code ByteStreamLexer.continueText}) emits the
     * terminating CRLF itself. So {@code sawCode} cannot be reset from
     * within {@link #consume(byte)} the way {@code lastWasCR} is, since
     * that reset would never run for any line with a separator/text
     * portion — which, for SMTP replies, is nearly every line. The parser
     * calls this once per line, from its own {@code Token.CRLF} handling,
     * which reliably fires exactly once per line regardless of which path
     * produced the CRLF.
     */
    void resetForNextLine() {
        sawCode = false;
    }

    @Override
    protected boolean consume(byte b) {
        int pos = currentPosition();
        if (b == '\n' && lastWasCR) {
            int crlfStart = pos - 2;
            if (!sawCode && crlfStart > regionStart()) {
                // Malformed/short line: fewer than 3 bytes before CRLF.
                // Emit whatever is there as CODE so the parser's own
                // Integer.parseInt attempt (or explicit length check)
                // surfaces the error, exactly as the pre-conversion code's
                // "line.length() < 3" check did.
                emit(Token.CODE, regionStart(), crlfStart);
            }
            emit(Token.CRLF, crlfStart, pos);
            lastWasCR = false;
            return true;
        }
        if (b == '\r') {
            lastWasCR = true;
            return true;
        }
        lastWasCR = false;
        if (!sawCode) {
            if (pos - regionStart() == 3) {
                emit(Token.CODE, regionStart(), pos);
                sawCode = true;
            }
            return true;
        }
        // The single separator byte immediately following CODE; text mode
        // is latched from here (see class Javadoc), so consume() will not
        // be re-entered again for the rest of this line.
        int start = pos - 1;
        if (b == '-') {
            emit(Token.DASH, start, pos);
        } else {
            emit(Token.SP, start, pos);
        }
        return true;
    }
}
