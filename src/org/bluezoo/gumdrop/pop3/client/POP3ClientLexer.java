/*
 * POP3ClientLexer.java
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

import org.bluezoo.gumdrop.ByteStreamLexer;

/**
 * Streaming lexer for POP3 server responses (RFC 1939 section 3):
 * {@code WORD [SP TEXT] CRLF}.
 *
 * <p>{@code WORD} is either a status marker ({@code +OK}, {@code -ERR}, or
 * the SASL continuation prefix {@code +}) or, while receiving a CAPA/LIST/
 * UIDL multi-line data block, the first field of a data line (including
 * the lone {@code .} terminator). Either way, the lexer only recognises
 * the lexical shape; {@link POP3ClientProtocolHandler} decides which
 * interpretation applies based on the current connection state, exactly
 * as {@link org.bluezoo.gumdrop.pop3.POP3ServerLexer} does for command
 * verbs on the server side.
 *
 * <p>RETR/TOP message content is <strong>not</strong> handled by this
 * lexer at all: it is dot-terminated and dot-stuffed content with no
 * fixed length known in advance, and {@link DotUnstuffer} already
 * processes it correctly in constant memory across arbitrary chunk
 * boundaries. Rather than reimplement that as a {@code RAW_UNTIL} escape
 * (which would need a second, separate unstuffing pass over whatever this
 * lexer delivered), {@link POP3ClientProtocolHandler#receive(java.nio.ByteBuffer)}
 * keeps driving {@code DotUnstuffer} directly, exactly as it did before
 * this lexer existed. This lexer only needs to know when to get out of
 * the way: see {@link #stopForHandoff()}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see POP3ClientProtocolHandler
 */
final class POP3ClientLexer extends ByteStreamLexer<POP3ClientLexer.Token> {

    enum Token { WORD, SP, TEXT, CRLF }

    private boolean lastWasCR;

    POP3ClientLexer(Handler<Token> handler, int maxTokenLength) {
        super(handler, maxTokenLength, Token.CRLF, Token.TEXT);
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

    /**
     * Requests that this lexer stop immediately after the CRLF token
     * currently being dispatched, leaving the buffer position right after
     * it. Thin package-visible wrapper around {@link ByteStreamLexer#requestStop()}
     * so {@link POP3ClientProtocolHandler} — a separate object from this
     * lexer — can call it from within the CRLF token dispatch (the same
     * "nested call during token dispatch" pattern used elsewhere for
     * {@code enterRaw}/{@code enterRawUntil}) when the response just
     * completed hands control to something that reads the connection's
     * raw bytes directly — currently only RETR/TOP transitioning to
     * {@code DotUnstuffer} — so this lexer does not try to tokenise
     * message content as if it were more response lines.
     *
     * <p><strong>Note:</strong> a line that latches text mode (any line
     * with a space, e.g. {@code "+OK 13 octets"}) has its CRLF emitted by
     * the base class's own text-mode handling, not by {@link
     * #consume(byte)} above — {@link ByteStreamLexer#requestStop()}
     * itself is what makes this work correctly regardless of which path
     * emitted the triggering CRLF; see its Javadoc.
     */
    void stopForHandoff() {
        requestStop();
    }
}
