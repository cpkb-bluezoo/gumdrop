/*
 * ZoneFileLexer.java
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

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.ByteStreamLexer;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Streaming lexer for BIND-style zone files: whitespace-separated atoms,
 * quoted strings, parenthesis grouping, and {@code ;} comments.
 *
 * <p>Emits {@link Token#NEWLINE} only when parenthesis depth is zero, so
 * multi-line {@code (...)} groups are a single token stream without a
 * second pass over assembled lines.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ZoneFileParser
 */
final class ZoneFileLexer extends ByteStreamLexer<ZoneFileLexer.Token> {

    enum Token {
        ATOM,
        QUOTED,
        LPAREN,
        RPAREN,
        NEWLINE,
        TEXT
    }

    private boolean lastWasCR;
    private boolean inQuote;
    private boolean inComment;
    private int parenDepth;
    private ByteArrayOutputStream quoteBuffer;
    private final Handler<Token> tokens;

    ZoneFileLexer(Handler<Token> handler, int maxTokenLength) {
        super(handler, maxTokenLength, Token.NEWLINE, Token.TEXT);
        this.tokens = handler;
    }

    /**
     * Clears a quoted string interrupted by buffer underflow so replay
     * rebuilds it from the opening {@code "}.
     */
    void discardPartialQuote() {
        if (inQuote) {
            inQuote = false;
            quoteBuffer = null;
        }
    }

    int getParenDepth() {
        return parenDepth;
    }

    boolean isInsideQuote() {
        return inQuote;
    }

    boolean isInsideComment() {
        return inComment;
    }

    @Override
    protected boolean consume(byte b) {
        int pos = currentPosition();
        if (inComment) {
            if (b == '\n') {
                inComment = false;
                lastWasCR = false;
            } else if (b == '\r') {
                lastWasCR = true;
            } else {
                lastWasCR = false;
            }
            return true;
        }
        if (inQuote) {
            if (b == '"') {
                byte[] quoted = quoteBuffer.toByteArray();
                quoteBuffer = null;
                inQuote = false;
                emitQuoted(quoted);
                advancePastDelimiter(pos);
                return true;
            }
            quoteBuffer.write(b);
            return true;
        }
        if (b == '\n' && lastWasCR) {
            flushAtom(pos - 1);
            if (parenDepth == 0) {
                maybeEmitNewline(pos);
            } else {
                advancePastDelimiter(pos);
            }
            lastWasCR = false;
            return true;
        }
        if (b == '\n') {
            flushAtom(pos - 1);
            if (parenDepth == 0) {
                maybeEmitNewline(pos);
            } else {
                advancePastDelimiter(pos);
            }
            lastWasCR = false;
            return true;
        }
        if (b == '\r') {
            flushAtom(pos - 1);
            advancePastDelimiter(pos);
            lastWasCR = true;
            return true;
        }
        lastWasCR = false;
        if (b == ' ' || b == '\t') {
            flushAtom(pos - 1);
            advancePastDelimiter(pos);
            return true;
        }
        if (b == ';') {
            flushAtom(pos - 1);
            inComment = true;
            return true;
        }
        if (b == '"') {
            flushAtom(pos - 1);
            inQuote = true;
            quoteBuffer = new ByteArrayOutputStream();
            emit(Token.ATOM, pos - 1, pos - 1);
            return true;
        }
        if (b == '(') {
            flushAtom(pos - 1);
            emit(Token.LPAREN, pos - 1, pos);
            parenDepth++;
            return true;
        }
        if (b == ')') {
            flushAtom(pos - 1);
            emit(Token.RPAREN, pos - 1, pos);
            parenDepth--;
            if (parenDepth < 0) {
                return false;
            }
            return true;
        }
        return true;
    }

    private void maybeEmitNewline(int pos) {
        if (parenDepth == 0) {
            emit(Token.NEWLINE, pos - 1, pos);
        }
    }

    private void flushAtom(int endExclusive) {
        int start = regionStart();
        if (endExclusive > start) {
            emit(Token.ATOM, start, endExclusive);
        }
    }

    /**
     * Moves the token boundary past a delimiter byte without emitting it.
     */
    private void advancePastDelimiter(int pos) {
        emit(Token.ATOM, pos, pos);
    }

    private void emitQuoted(byte[] utf8) {
        tokens.token(Token.QUOTED, ByteBuffer.wrap(utf8));
    }

}
