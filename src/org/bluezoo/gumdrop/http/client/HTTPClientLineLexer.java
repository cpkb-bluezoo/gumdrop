/*
 * HTTPClientLineLexer.java
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

package org.bluezoo.gumdrop.http.client;

import org.bluezoo.gumdrop.ByteStreamLexer;

/**
 * Streaming lexer for HTTP/1.1 response lines (RFC 9112 sections 4, 5,
 * 7): status-line, field-lines, chunk-size lines, and trailer-section
 * lines. Structurally identical to the server-side {@code
 * org.bluezoo.gumdrop.http.HTTPLineLexer}: a single token type, {@link
 * Token#LINE}, spanning a whole line <strong>including its CRLF
 * terminator</strong>, deliberately matching the pre-conversion bespoke
 * {@code findCRLF}/{@code parseBuffer} path's own line-extraction shape
 * (line bytes, followed by two explicit CRLF byte reads) — so {@link
 * HTTPClientProtocolHandler}'s per-line-type decode (US-ASCII for the
 * status-line/chunk-size line, UTF-8 for header/trailer lines) and string
 * parsing needed no changes beyond being called once per line instead of
 * in a loop pulling from a shared accumulation buffer.
 *
 * <p>Unlike the server, this lexer has no fixed per-token cap suitable
 * for construction time: {@code maxResponseHeaderSize} is public,
 * mutable, mutable-after-construction API. {@link
 * HTTPClientProtocolHandler} tracks the cumulative header-section byte
 * count itself (mirroring the pre-conversion buffer-growth check) and
 * this lexer's own {@code maxTokenLength} is set to the current {@code
 * maxResponseHeaderSize} value at {@code connected()} time purely as a
 * defense-in-depth backstop against a single, never-terminated line
 * growing the receive buffer unboundedly before the parser's own
 * cumulative check ever gets a chance to run.
 *
 * <p>Response bodies (Content-Length, RFC 9112 section 6.2; chunk-data,
 * section 7.1) are read via {@link #enterRawBody(long)}. HTTP/2 framing
 * (negotiated via ALPN, h2c upgrade, or prior knowledge) and the
 * read-until-close body (reachable only when no Content-Length is
 * present — dead code in the pre-conversion parser, since {@code
 * parseState} only ever becomes {@code BODY} once {@code contentLength >
 * 0} has already been validated, but preserved defensively) are handled
 * entirely outside this lexer; {@link #stopForHandoff()} hands control
 * back to {@code HTTPClientProtocolHandler.receive()}'s own dispatch for
 * those.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTPClientProtocolHandler
 */
final class HTTPClientLineLexer extends ByteStreamLexer<HTTPClientLineLexer.Token> {

    enum Token { LINE, TEXT }

    private boolean lastWasCR;

    HTTPClientLineLexer(Handler<Token> handler, int maxTokenLength) {
        super(handler, maxTokenLength, Token.LINE, Token.TEXT);
    }

    /**
     * Switches to raw mode for exactly {@code n} bytes — a Content-Length
     * body, or a chunk's data plus its trailing CRLF (RFC 9112 section
     * 7.1; unlike the server, this client does not validate the
     * terminator's content, matching the pre-conversion code's own
     * unconditional 2-byte skip). Wraps the base class's {@code
     * protected final enterRaw(long)} for the parser to call from
     * outside this package.
     *
     * @param n the number of raw bytes to deliver before resuming
     */
    void enterRawBody(long n) {
        enterRaw(n);
    }

    /**
     * Hands control of the connection's raw bytes to {@code
     * HTTPClientProtocolHandler.receive()}'s own dispatch (HTTP/2
     * framing, the read-until-close body, or simply nothing further to
     * do while idle between responses) — see {@link
     * ByteStreamLexer#requestStop()}. Wraps the base class's {@code
     * protected final requestStop()} for the parser to call from outside
     * this package.
     */
    void stopForHandoff() {
        requestStop();
    }

    @Override
    protected boolean consume(byte b) {
        int pos = currentPosition();
        if (b == '\n' && lastWasCR) {
            // Emitted unconditionally, even for a zero-content "bare
            // CRLF" line — the empty line terminating a header/trailer
            // section is a LINE token like any other; processHeaderLine()/
            // processChunkTrailerLine() handle a zero-length window
            // themselves, exactly as the pre-conversion findCRLF-based
            // code checked for "lineEnd == 0".
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
