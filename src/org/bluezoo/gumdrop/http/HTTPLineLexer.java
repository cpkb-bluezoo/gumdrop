/*
 * HTTPLineLexer.java
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

package org.bluezoo.gumdrop.http;

import org.bluezoo.gumdrop.ByteStreamLexer;

/**
 * Streaming lexer for the HTTP/1.1 line-based grammar (RFC 9112 section 2):
 * request-line, field-lines, chunk-size lines, and trailer-section lines
 * are all, lexically, just "a run of bytes terminated by CRLF" — none of
 * them need to be split into sub-tokens at this layer, since {@link
 * HTTPProtocolHandler}'s existing {@code processRequestLine}/{@code
 * processHeaderLine}/{@code processChunkSizeLine}/{@code
 * processTrailerLine} already do their own whole-line decode (with
 * per-line-type charset choice — US-ASCII vs the historically-allowed
 * ISO-8859-1 for field values) and string parsing.
 *
 * <p>So unlike every other lexer in this conversion, this one emits a
 * single token type, {@link Token#LINE}, spanning the <em>entire</em> line
 * <strong>including its CRLF terminator</strong> — deliberately matching
 * {@link org.bluezoo.gumdrop.LineParser.Callback#lineReceived}'s exact
 * buffer contract, so the four {@code processXxxLine} methods needed zero
 * changes to keep working against this lexer's token windows. {@code
 * LINE} doubles as this lexer's {@code crlfTokenType}: emitting it always
 * returns to structured token-scanning mode, and since {@link
 * HTTPProtocolHandler} never returns {@code true} from {@link
 * ByteStreamLexer.Handler#token}, latched text mode is never entered —
 * {@code TEXT} exists only to satisfy the constructor's non-null
 * requirement.
 *
 * <p>Message bodies (Content-Length, RFC 9112 section 6.2; chunk-data,
 * section 7.1) are read via {@link #enterRawBody(long)}, triggered by
 * {@link HTTPProtocolHandler} after a {@code LINE} token's dispatch (or a
 * raw body's own completion) transitions {@code state}. HTTP/2 framing,
 * the h2c/prior-knowledge prefaces, the until-close HTTP/1.0 body, and
 * WebSocket data are all read completely outside this lexer — {@link
 * #stopForHandoff()} hands control back to {@code
 * HTTPProtocolHandler.receive()}'s own state dispatch for those.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTPProtocolHandler
 */
final class HTTPLineLexer extends ByteStreamLexer<HTTPLineLexer.Token> {

    enum Token { LINE, TEXT }

    private boolean lastWasCR;

    HTTPLineLexer(Handler<Token> handler, int maxTokenLength) {
        super(handler, maxTokenLength, Token.LINE, Token.TEXT);
    }

    /**
     * Switches to raw mode for exactly {@code n} bytes — a Content-Length
     * body, or a chunk's data plus its mandatory trailing CRLF (RFC 9112
     * section 7.1). Wraps the base class's {@code protected final
     * enterRaw(long)} for the parser to call from outside this package.
     *
     * @param n the number of raw bytes to deliver before resuming
     */
    void enterRawBody(long n) {
        enterRaw(n);
    }

    /**
     * Hands control of the connection's raw bytes to {@code
     * HTTPProtocolHandler.receive()}'s own state-based dispatch (HTTP/2
     * framing, preface matching, until-close bodies, WebSocket) — see
     * {@link ByteStreamLexer#requestStop()}. Wraps the base class's
     * {@code protected final requestStop()} for the parser to call from
     * outside this package.
     */
    void stopForHandoff() {
        requestStop();
    }

    @Override
    protected boolean consume(byte b) {
        int pos = currentPosition();
        if (b == '\n' && lastWasCR) {
            // Unlike every other lexer here, LINE is emitted unconditionally
            // (even for a zero-content "bare CRLF" line) — LineParser
            // delivered every CRLF-terminated span the same way, including
            // the empty line that terminates a header/trailer section, and
            // processHeaderLine()/processTrailerLine() already handle that
            // shape themselves (checking for zero-length content).
            emit(Token.LINE, regionStart(), pos);
            lastWasCR = false;
            return true;
        }
        if (b == '\r') {
            lastWasCR = true;
            return true;
        }
        lastWasCR = false;
        return true;
    }
}
