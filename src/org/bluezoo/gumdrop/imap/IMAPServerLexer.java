/*
 * IMAPServerLexer.java
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

package org.bluezoo.gumdrop.imap;

import org.bluezoo.gumdrop.ByteStreamLexer;

/**
 * Streaming lexer for the outer IMAP command-line grammar (RFC 9051
 * section 2.2): {@code KEYWORD [SP TEXT] CRLF}, structurally identical to
 * the POP3/FTP/SMTP server lexers — {@code KEYWORD} is the leading word up
 * to the first space (the command's tag, or a bare IDLE {@code DONE} /
 * SASL continuation line with no tag at all), and {@code TEXT} is
 * everything after it, chunked verbatim.
 *
 * <p>This lexer knows nothing about IMAP literals ({@code {nnn}} /
 * {@code {nnn+}}, RFC 9051 section 4.3, RFC 7888) at all — the {@code
 * "{" number ["+"] "}" CRLF} production only ever appears immediately
 * before a CRLF, so {@link IMAPProtocolHandler} detects it by inspecting
 * the just-accumulated text when the {@code CRLF} token arrives, and (for
 * literals it doesn't hand off wholesale to a command's own business logic,
 * e.g. APPEND's message body) calls {@link #enterRaw(long)} from within
 * that same callback — the "nested call during token dispatch" pattern
 * already used throughout this conversion. Once the raw octets are
 * delivered, this class resumes token scanning automatically, which is
 * what lets a chained literal (a second {@code {nnn}} on the continuation
 * line) be detected the exact same way at the next {@code CRLF}.
 *
 * <p>One wrinkle specific to this grammar: after a raw literal escape
 * completes, the base class always resumes in structured token-scanning
 * mode, not latched text mode (that's what makes it possible to detect a
 * chained literal, or a real terminating {@code CRLF}, in the continuation
 * text at all). This lexer does not need to do anything special about
 * that itself — the resumed bytes are ordinary {@code KEYWORD}/{@code SP}
 * tokens like any other, and {@link IMAPProtocolHandler} knows (from its
 * own {@code freshCommand} tracking, not from anything this class exposes)
 * whether a given {@code KEYWORD} is genuinely a fresh command's tag or
 * just leftover text after a literal resume that happened not to be
 * preceded by a space, and routes it accordingly.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see IMAPProtocolHandler
 */
final class IMAPServerLexer extends ByteStreamLexer<IMAPServerLexer.Token> {

    enum Token { KEYWORD, SP, TEXT, CRLF }

    private boolean lastWasCR;

    IMAPServerLexer(Handler<Token> handler, int maxTokenLength) {
        super(handler, maxTokenLength, Token.CRLF, Token.TEXT);
    }

    /**
     * Switches to raw mode for exactly {@code n} bytes — an IMAP literal's
     * octets (RFC 9051 section 4.3, RFC 7888), whether APPEND's own
     * message body or a general-purpose literal argument elsewhere in a
     * command. Wraps the base class's {@code protected final
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
