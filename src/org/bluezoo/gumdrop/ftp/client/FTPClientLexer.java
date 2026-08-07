/*
 * FTPClientLexer.java
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

package org.bluezoo.gumdrop.ftp.client;

import org.bluezoo.gumdrop.ByteStreamLexer;

/**
 * Streaming lexer for FTP server replies (RFC 959 §4.2):
 * {@code CODE [SEP TEXT] CRLF}, where {@code CODE} is a fixed 3-digit
 * reply code and {@code SEP} is a single byte — {@code '-'} for a
 * multi-line continuation, or (leniently) any other byte for a final
 * line — that is consumed but not itself part of the message text.
 *
 * <p>This grammar is identical to SMTP's (RFC 5321 §4.2), so this lexer
 * mirrors {@code SMTPClientLexer} exactly: the reply code is a fixed
 * width, so this lexer tracks a small amount of per-line non-positional
 * state ({@code sawCode}) rather than scanning for a delimiter. Once
 * {@code SEP} is emitted, the lexer latches text mode for the rest of the
 * line.
 *
 * <p>FTP client replies never carry raw binary content on the control
 * connection — file content flows over the separate data connection — so
 * this lexer never enters a raw escape.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see FTPClientProtocolHandler
 */
final class FTPClientLexer extends ByteStreamLexer<FTPClientLexer.Token> {

    enum Token { CODE, DASH, SP, TEXT, CRLF }

    private boolean lastWasCR;
    private boolean sawCode;

    FTPClientLexer(Handler<Token> handler, int maxTokenLength) {
        super(handler, maxTokenLength, Token.CRLF, Token.TEXT);
    }

    /**
     * Resets the CODE-vs-separator tracking for the next line. See {@code
     * SMTPClientLexer#resetForNextLine()} for why this must be called
     * externally, once per line, rather than from within {@link
     * #consume(byte)}.
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
                // parse attempt surfaces the error.
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
        // is latched from here, so consume() will not be re-entered again
        // for the rest of this line.
        int start = pos - 1;
        if (b == '-') {
            emit(Token.DASH, start, pos);
        } else {
            emit(Token.SP, start, pos);
        }
        return true;
    }
}
