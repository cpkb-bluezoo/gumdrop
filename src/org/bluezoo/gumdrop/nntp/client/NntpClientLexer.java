/*
 * NntpClientLexer.java
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

package org.bluezoo.gumdrop.nntp.client;

import org.bluezoo.gumdrop.ByteStreamLexer;

/**
 * Streaming lexer for NNTP status lines (RFC 3977 section 3.2):
 * {@code code [SP text] CRLF}. The multi-line blocks that follow some
 * status lines are not lexed here: the protocol handler stops the lexer and
 * passes those bytes through a dot-unstuffer.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class NntpClientLexer extends ByteStreamLexer<NntpClientLexer.Token> {

    enum Token { WORD, SP, TEXT, CRLF }

    private boolean lastWasCR;

    NntpClientLexer(Handler<Token> handler, int maxTokenLength) {
        super(handler, maxTokenLength, Token.CRLF, Token.TEXT);
    }

    /** Stops tokenising so the handler can route the bytes of a block elsewhere. */
    void stopForHandoff() {
        requestStop();
    }

    @Override
    protected boolean consume(byte b) {
        int pos = currentPosition();
        if (b == '\n' && lastWasCR) {
            int crlfStart = pos - 2;
            if (crlfStart > regionStart()) {
                emit(Token.WORD, regionStart(), crlfStart);
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
                emit(Token.WORD, start, pos - 1);
            }
            emit(Token.SP, pos - 1, pos);
            return true;
        }
        return true;
    }

}
