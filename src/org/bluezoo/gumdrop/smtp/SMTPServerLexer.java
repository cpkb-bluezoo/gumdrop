/*
 * SMTPServerLexer.java
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

package org.bluezoo.gumdrop.smtp;

import org.bluezoo.gumdrop.ByteStreamLexer;

/**
 * Streaming lexer for the SMTP command grammar (RFC 5321 section 2.3.8, 4.1.1):
 * {@code KEYWORD [SP TEXT] CRLF}.
 *
 * <p>Structurally identical to {@link org.bluezoo.gumdrop.pop3.POP3ServerLexer}
 * and {@link org.bluezoo.gumdrop.ftp.FTPServerLexer} — the lexer only
 * recognises the lexical shape; {@code SMTPProtocolHandler} decides how to
 * interpret {@code KEYWORD} (a command verb, or — while an AUTH continuation
 * exchange is in progress — raw continuation data) and which charset to
 * decode {@code TEXT} with (ASCII, or UTF-8 for SMTPUTF8/RFC 6531), based on
 * connection state.
 *
 * <p>DATA (RFC 5321 section 4.5.2, dot-stuffed) and BDAT (RFC 3030,
 * fixed-length binary) message content is <strong>not</strong> handled by
 * this lexer at all: {@code SMTPProtocolHandler.receive()} checks state
 * before ever calling {@link #feed}, exactly as it did before this lexer
 * existed, routing DATA/BDAT content directly to the existing (unchanged)
 * {@code processDataBuffer}/{@code handleBdatContent} state machines. Those
 * already handle dot-unstuffing and fixed-length chunking correctly in
 * constant memory across arbitrary chunk boundaries, including the
 * asynchronous-delivery retained-input replay path — reimplementing them as
 * lexer escapes would only duplicate already-correct, subtle logic (see the
 * equivalent reasoning for POP3 client's {@code DotUnstuffer} handoff).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SMTPProtocolHandler
 */
final class SMTPServerLexer extends ByteStreamLexer<SMTPServerLexer.Token> {

    enum Token { KEYWORD, SP, TEXT, CRLF }

    private boolean lastWasCR;

    SMTPServerLexer(Handler<Token> handler, int maxTokenLength) {
        super(handler, maxTokenLength, Token.CRLF, Token.TEXT);
    }

    /**
     * Tells the lexer to stop tokenising and hand control of the
     * connection's raw bytes to the DATA (RFC 5321 §4.5.2) / BDAT
     * (RFC 3030) content state machines, which {@code
     * SMTPProtocolHandler.receive()} drives directly once {@code state}
     * has transitioned to {@code DATA} or {@code BDAT}. Wraps the base
     * class's {@code protected final requestStop()} for the parser to
     * call from outside this package.
     */
    void enterContentMode() {
        requestStop();
    }

    @Override
    protected boolean consume(byte b) {
        int pos = currentPosition();
        if (b == '\n' && lastWasCR) {
            int crlfStart = pos - 2;
            if (crlfStart > regionStart()) {
                emit(Token.KEYWORD, regionStart(), crlfStart);
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
        if (b == ' ') {
            int start = regionStart();
            if (pos - 1 > start) {
                emit(Token.KEYWORD, start, pos - 1);
            }
            emit(Token.SP, pos - 1, pos);
            return true;
        }
        return true;
    }
}
