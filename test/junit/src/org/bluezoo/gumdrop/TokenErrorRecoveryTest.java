/*
 * TokenErrorRecoveryTest.java
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

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link TokenErrorRecovery}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TokenErrorRecoveryTest {

    enum Tok { WORD, SP, CRLF }

    @Test
    public void testNotDiscardingByDefault() {
        TokenErrorRecovery<Tok> recovery = new TokenErrorRecovery<Tok>(Tok.CRLF);
        assertFalse(recovery.isDiscarding());
    }

    @Test
    public void testHandleTokenDoesNothingWhenNotDiscarding() {
        TokenErrorRecovery<Tok> recovery = new TokenErrorRecovery<Tok>(Tok.CRLF);
        assertFalse(recovery.handleToken(Tok.WORD));
        assertFalse(recovery.handleToken(Tok.CRLF));
        assertFalse(recovery.isDiscarding());
    }

    @Test
    public void testBeginDiscardStartsDiscarding() {
        TokenErrorRecovery<Tok> recovery = new TokenErrorRecovery<Tok>(Tok.CRLF);
        recovery.beginDiscard();
        assertTrue(recovery.isDiscarding());
    }

    @Test
    public void testDiscardsTokensUntilCrlf() {
        TokenErrorRecovery<Tok> recovery = new TokenErrorRecovery<Tok>(Tok.CRLF);
        recovery.beginDiscard();

        assertTrue(recovery.handleToken(Tok.WORD));
        assertTrue(recovery.isDiscarding());

        assertTrue(recovery.handleToken(Tok.SP));
        assertTrue(recovery.isDiscarding());

        assertTrue(recovery.handleToken(Tok.WORD));
        assertTrue(recovery.isDiscarding());

        // CRLF ends discard mode; handleToken still reports it as consumed
        // (the caller checks isDiscarding() afterwards to know discard
        // mode just ended, and should send its error reply now).
        assertTrue(recovery.handleToken(Tok.CRLF));
        assertFalse(recovery.isDiscarding());
    }

    @Test
    public void testResumesNormalDispatchAfterCrlf() {
        TokenErrorRecovery<Tok> recovery = new TokenErrorRecovery<Tok>(Tok.CRLF);
        recovery.beginDiscard();
        recovery.handleToken(Tok.WORD);
        recovery.handleToken(Tok.CRLF);

        // Discard mode has ended; the next token is dispatched normally.
        assertFalse(recovery.handleToken(Tok.WORD));
        assertFalse(recovery.isDiscarding());
    }

    @Test
    public void testCanReenterDiscardModeAfterResync() {
        TokenErrorRecovery<Tok> recovery = new TokenErrorRecovery<Tok>(Tok.CRLF);
        recovery.beginDiscard();
        recovery.handleToken(Tok.CRLF);
        assertFalse(recovery.isDiscarding());

        recovery.beginDiscard();
        assertTrue(recovery.isDiscarding());
        recovery.handleToken(Tok.CRLF);
        assertFalse(recovery.isDiscarding());
    }

    @Test
    public void testConstructorRejectsNullCrlfType() {
        try {
            new TokenErrorRecovery<Tok>(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
