/*
 * TokenErrorRecovery.java
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

package org.bluezoo.gumdrop;

/**
 * Shared *discard-until-CRLF* recovery helper for {@link
 * ByteStreamLexer.Handler} implementations (issue #85).
 *
 * <p>When a token-stream parser recognises a malformed sequence, it needs
 * to stay in sync with the wire without the lexer knowing anything about
 * protocol errors: drop every subsequent token until the next CRLF, then
 * emit its protocol error reply and resume normal parsing. This class
 * holds that tiny piece of state so every protocol parser implements the
 * pattern identically, rather than re-inventing it per protocol.
 *
 * <p>This is deliberately a small composed helper rather than a base
 * class: protocol handlers already extend other classes, so a parser
 * holds an instance of this as a field and delegates to it from its
 * {@link ByteStreamLexer.Handler#token(Enum, java.nio.ByteBuffer)}
 * dispatch method.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * private final TokenErrorRecovery<MyTokenType> recovery =
 *     new TokenErrorRecovery<>(MyTokenType.CRLF);
 *
 * public boolean token(MyTokenType type, ByteBuffer window) {
 *     if (recovery.handleToken(type)) {
 *         if (!recovery.isDiscarding()) {
 *             sendReply(500, "Syntax error");
 *         }
 *         return false;
 *     }
 *     switch (type) {
 *         case KEYWORD:
 *             ...
 *             break;
 *         ...
 *         default:
 *             recovery.beginDiscard();
 *             break;
 *     }
 *     return false;
 * }
 * }</pre>
 *
 * @param <T> the protocol-specific token type enum
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ByteStreamLexer
 */
public final class TokenErrorRecovery<T extends Enum<T>> {

    private final T crlfTokenType;
    private boolean discarding;

    /**
     * Creates a new recovery helper.
     *
     * @param crlfTokenType the token type representing a CRLF line
     *      terminator, at which discard-mode ends
     */
    public TokenErrorRecovery(T crlfTokenType) {
        if (crlfTokenType == null) {
            throw new IllegalArgumentException("crlfTokenType must not be null");
        }
        this.crlfTokenType = crlfTokenType;
    }

    /**
     * Enters discard mode: subsequent tokens are dropped until the next
     * CRLF token.
     */
    public void beginDiscard() {
        discarding = true;
    }

    /**
     * Returns whether discard mode is currently active.
     *
     * @return true if tokens are currently being discarded
     */
    public boolean isDiscarding() {
        return discarding;
    }

    /**
     * Call from a parser's token dispatch for every token received.
     *
     * <p>If discard mode is not active, does nothing and returns
     * {@code false} — the caller should dispatch the token normally. If
     * discard mode is active, consumes the token and returns {@code true}
     * — the caller should do nothing else with it, except that when this
     * call also ends discard mode (the token was CRLF), the caller should
     * send its error reply immediately afterwards (check {@link
     * #isDiscarding()} after this call to tell the two cases apart).
     *
     * @param type the token type just received
     * @return true if the token was consumed by discard recovery
     */
    public boolean handleToken(T type) {
        if (!discarding) {
            return false;
        }
        if (type == crlfTokenType) {
            discarding = false;
        }
        return true;
    }
}
