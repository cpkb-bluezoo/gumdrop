/*
 * ZoneFileParser.java
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Single-pass push-parser for BIND-style zone files.
 *
 * <p>Bytes are lexed once by {@link ZoneFileLexer} ({@link
 * org.bluezoo.gumdrop.ByteStreamLexer}); each complete token advances a
 * feedforward state machine and invokes {@link ZoneFileHandler} methods.
 * There is no line assembly step and no whitespace resplit of full lines.
 * After {@link #receive(ByteBuffer)}, the buffer position marks unconsumed
 * input (a partial token). Compact before reading more data when
 * {@link #isUnderflow()} is true.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ZoneFileHandler
 */
public final class ZoneFileParser implements ByteStreamLexer.Handler<ZoneFileLexer.Token> {

    private static final int MAX_TOKEN_LENGTH = 1024;

    private final ZoneFileHandler handler;
    private final ZoneFileLexer lexer;
    private boolean underflow;
    private boolean closed;
    private IOException pendingError;

    private State state = State.BETWEEN_ENTRIES;
    private String generateRange;
    private String includeFilename;

    private enum State {
        BETWEEN_ENTRIES,
        DIRECTIVE_ORIGIN,
        DIRECTIVE_TTL,
        DIRECTIVE_INCLUDE_FILE,
        DIRECTIVE_INCLUDE_OPTIONAL,
        GENERATE_RANGE,
        GENERATE_OWNER,
        GENERATE_BODY,
        RECORD_BODY,
        SKIP_DIRECTIVE
    }

    public ZoneFileParser(ZoneFileHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler");
        }
        this.handler = handler;
        this.lexer = new ZoneFileLexer(this, MAX_TOKEN_LENGTH);
    }

    public boolean isUnderflow() {
        return underflow;
    }

    public void receive(ByteBuffer data) throws IOException {
        if (closed) {
            throw new IllegalStateException("parser closed");
        }
        pendingError = null;
        underflow = false;
        lexer.feed(data);
        underflow = data.hasRemaining();
        if (underflow) {
            lexer.discardPartialQuote();
        }
        if (pendingError != null) {
            throw pendingError;
        }
    }

    public void close() throws IOException {
        if (closed) {
            return;
        }
        if (underflow) {
            throw new IOException("Zone file ends with incomplete token");
        }
        if (lexer.getParenDepth() != 0 || lexer.isInsideQuote() || lexer.isInsideComment()) {
            throw new IOException("Zone file ends with incomplete entry");
        }
        if (state != State.BETWEEN_ENTRIES) {
            throw new IOException("Zone file ends mid-entry");
        }
        closed = true;
    }

    @Override
    public boolean token(ZoneFileLexer.Token type, ByteBuffer window) {
        try {
            dispatchToken(type, window);
        } catch (IOException e) {
            pendingError = e;
            return false;
        }
        return false;
    }

    @Override
    public void rawBytes(ByteBuffer slice) {
        pendingError = new IOException("Unexpected binary data in zone file");
    }

    @Override
    public void tokenTooLong() {
        pendingError = new IOException("Zone file token exceeds " + MAX_TOKEN_LENGTH + " bytes");
    }

    private void dispatchToken(ZoneFileLexer.Token type, ByteBuffer window) throws IOException {
        if (window.remaining() == 0) {
            return;
        }
        if (type == ZoneFileLexer.Token.NEWLINE) {
            endCurrentLine();
            return;
        }
        if (type == ZoneFileLexer.Token.LPAREN || type == ZoneFileLexer.Token.RPAREN) {
            if (state == State.RECORD_BODY || state == State.GENERATE_BODY) {
                return;
            }
            throw new IOException("Unexpected parenthesis in zone file");
        }
        String text = decodeToken(window);
        switch (state) {
            case BETWEEN_ENTRIES:
                startEntry(text);
                break;
            case DIRECTIVE_ORIGIN:
                handler.origin(ZoneFile.normalizeName(text));
                state = State.BETWEEN_ENTRIES;
                break;
            case DIRECTIVE_TTL:
                handler.defaultTtl(Integer.parseInt(text));
                state = State.BETWEEN_ENTRIES;
                break;
            case DIRECTIVE_INCLUDE_FILE:
                includeFilename = unquoteAtom(text);
                state = State.DIRECTIVE_INCLUDE_OPTIONAL;
                break;
            case DIRECTIVE_INCLUDE_OPTIONAL:
                handler.include(includeFilename, ZoneFile.normalizeName(text));
                includeFilename = null;
                state = State.BETWEEN_ENTRIES;
                break;
            case GENERATE_RANGE:
                generateRange = text;
                state = State.GENERATE_OWNER;
                break;
            case GENERATE_OWNER:
                handler.beginGenerate(generateRange, text);
                generateRange = null;
                state = State.GENERATE_BODY;
                break;
            case GENERATE_BODY:
                handler.appendField(unquoteAtom(text));
                break;
            case RECORD_BODY:
                handler.appendField(unquoteAtom(text));
                break;
            case SKIP_DIRECTIVE:
                break;
            default:
                throw new IOException("Unexpected zone token: " + text);
        }
    }

    private void startEntry(String text) throws IOException {
        if ("$ORIGIN".equalsIgnoreCase(text)) {
            state = State.DIRECTIVE_ORIGIN;
            return;
        }
        if ("$TTL".equalsIgnoreCase(text)) {
            state = State.DIRECTIVE_TTL;
            return;
        }
        if ("$INCLUDE".equalsIgnoreCase(text)) {
            state = State.DIRECTIVE_INCLUDE_FILE;
            return;
        }
        if ("$GENERATE".equalsIgnoreCase(text)) {
            state = State.GENERATE_RANGE;
            return;
        }
        if (text.startsWith("$")) {
            handler.unknownDirective(text);
            state = State.SKIP_DIRECTIVE;
            return;
        }
        handler.beginRecord(text);
        state = State.RECORD_BODY;
    }

    private void endCurrentLine() throws IOException {
        switch (state) {
            case DIRECTIVE_INCLUDE_FILE:
                if (includeFilename == null) {
                    throw new IOException("Malformed $INCLUDE: missing file");
                }
                handler.include(includeFilename, null);
                includeFilename = null;
                state = State.BETWEEN_ENTRIES;
                break;
            case DIRECTIVE_INCLUDE_OPTIONAL:
                if (includeFilename == null) {
                    throw new IOException("Malformed $INCLUDE: missing file");
                }
                handler.include(includeFilename, null);
                includeFilename = null;
                state = State.BETWEEN_ENTRIES;
                break;
            case GENERATE_BODY:
                handler.endGenerate();
                state = State.BETWEEN_ENTRIES;
                break;
            case RECORD_BODY:
                handler.endRecord();
                state = State.BETWEEN_ENTRIES;
                break;
            case GENERATE_RANGE:
                throw new IOException("Malformed $GENERATE: missing range");
            case GENERATE_OWNER:
                throw new IOException("Malformed $GENERATE: missing owner template");
            case DIRECTIVE_ORIGIN:
                throw new IOException("Malformed $ORIGIN: missing origin name");
            case DIRECTIVE_TTL:
                throw new IOException("Malformed $TTL: missing value");
            case SKIP_DIRECTIVE:
            case BETWEEN_ENTRIES:
                state = State.BETWEEN_ENTRIES;
                break;
            default:
                state = State.BETWEEN_ENTRIES;
        }
    }

    private static String decodeToken(ByteBuffer window) {
        byte[] bytes = new byte[window.remaining()];
        window.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static String unquoteAtom(String token) {
        if (token.length() >= 2 && token.startsWith("\"") && token.endsWith("\"")) {
            return token.substring(1, token.length() - 1);
        }
        return token;
    }
}
