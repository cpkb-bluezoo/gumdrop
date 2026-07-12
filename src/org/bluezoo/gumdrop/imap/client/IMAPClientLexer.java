/*
 * IMAPClientLexer.java
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

package org.bluezoo.gumdrop.imap.client;

import org.bluezoo.gumdrop.ByteStreamLexer;

/**
 * Streaming lexer for IMAP server response lines (RFC 9051 section 7):
 * {@code KEYWORD [SP TEXT] CRLF}, structurally identical to {@code
 * IMAPServerLexer} — this lexer, too, knows nothing about IMAP literals.
 *
 * <p>Unlike the server side, {@link IMAPClientProtocolHandler} does not
 * need to distinguish {@code KEYWORD} from {@code TEXT} at all: a
 * response line's leading word (a tag, {@code "*"}, or {@code "+"}) and
 * everything after it are simply concatenated back into one string and
 * handed whole to the existing, unchanged, string-based {@code
 * IMAPResponse.parse(String)} — exactly what the pre-conversion {@code
 * LineParser}-buffered line decode produced. A response line's literal
 * marker ({@code {nnn}}, RFC 9051 section 4.3) always appears at the very
 * end of a complete, self-contained line (never embedded mid-line the way
 * a command's literal can be on the server side), so once {@link
 * IMAPClientProtocolHandler} has fully dispatched that line and — as a
 * side effect of doing so — set up a raw-content read (mirroring the
 * pre-existing {@link LiteralTracker}), it calls {@link #enterRaw(long)}
 * after the fact, the same "check state after dispatch" pattern used for
 * SMTP's DATA/BDAT transition. Once those octets are delivered, this
 * lexer resumes token scanning automatically, so trailing content after
 * the literal (e.g. a closing {@code ")"} before the FETCH response's
 * real terminating CRLF) is picked up as an ordinary follow-on line, the
 * same way the pre-conversion code's next {@code LineParser.parse} call
 * would have picked it up.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see IMAPClientProtocolHandler
 */
final class IMAPClientLexer extends ByteStreamLexer<IMAPClientLexer.Token> {

    enum Token { KEYWORD, SP, TEXT, CRLF }

    private boolean lastWasCR;

    IMAPClientLexer(Handler<Token> handler, int maxTokenLength) {
        super(handler, maxTokenLength, Token.CRLF, Token.TEXT);
    }

    /**
     * Switches to raw mode for exactly {@code n} bytes — a FETCH response
     * literal's octets. Wraps the base class's {@code protected final
     * enterRaw(long)} for the parser to call from outside this package.
     *
     * @param n the literal's declared byte count
     */
    void enterLiteral(long n) {
        enterRaw(n);
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
