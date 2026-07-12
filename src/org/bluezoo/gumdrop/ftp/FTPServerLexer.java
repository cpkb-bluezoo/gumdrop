/*
 * FTPServerLexer.java
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

package org.bluezoo.gumdrop.ftp;

import org.bluezoo.gumdrop.ByteStreamLexer;

/**
 * Streaming lexer for the FTP command grammar (RFC 959 section 4):
 * {@code KEYWORD [SP TEXT] CRLF}.
 *
 * <p>{@code TEXT} is everything after the first space, delivered in
 * zero-copy chunks as free-form text — this preserves embedded spaces
 * verbatim (needed for pathnames), matching the pre-existing
 * split-on-first-space semantics, and is never buffered by the lexer
 * itself. Unlike POP3, FTP's control channel has no continuation-mode
 * concept: {@code AUTH TLS}/{@code AUTH SSL} completes in a single
 * command/reply exchange (RFC 2228/4217), so every {@code KEYWORD} token
 * is always a command verb, never raw continuation data.
 *
 * <p>FTP's control channel is always line-based — there is no binary
 * escape (data transfers use a separate connection, handled entirely
 * outside this lexer), so this lexer only ever operates in
 * structured/text token modes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see FTPProtocolHandler
 */
final class FTPServerLexer extends ByteStreamLexer<FTPServerLexer.Token> {

    enum Token { KEYWORD, SP, TEXT, CRLF }

    private boolean lastWasCR;

    FTPServerLexer(Handler<Token> handler, int maxTokenLength) {
        super(handler, maxTokenLength, Token.CRLF, Token.TEXT);
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
