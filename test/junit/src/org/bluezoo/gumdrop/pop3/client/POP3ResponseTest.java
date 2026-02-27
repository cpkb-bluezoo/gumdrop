/*
 * POP3ResponseTest.java
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

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link POP3Response}.
 */
public class POP3ResponseTest {

    // ── +OK responses ──

    @Test
    public void testParseOkWithMessage() {
        POP3Response r = POP3Response.parse("+OK POP3 server ready");
        assertNotNull(r);
        assertTrue(r.isOk());
        assertFalse(r.isErr());
        assertFalse(r.isContinuation());
        assertEquals("POP3 server ready", r.getMessage());
    }

    @Test
    public void testParseOkEmpty() {
        POP3Response r = POP3Response.parse("+OK");
        assertNotNull(r);
        assertTrue(r.isOk());
        assertEquals("", r.getMessage());
    }

    @Test
    public void testParseOkWithNumbers() {
        POP3Response r = POP3Response.parse("+OK 2 320");
        assertNotNull(r);
        assertTrue(r.isOk());
        assertEquals("2 320", r.getMessage());
    }

    @Test
    public void testParseOkWithApopTimestamp() {
        POP3Response r = POP3Response.parse(
                "+OK POP3 server ready <1896.697170952@dbc.mtview.ca.us>");
        assertNotNull(r);
        assertTrue(r.isOk());
        assertTrue(r.getMessage().contains("<1896.697170952@dbc.mtview.ca.us>"));
    }

    // ── -ERR responses ──

    @Test
    public void testParseErrWithMessage() {
        POP3Response r = POP3Response.parse("-ERR authentication failed");
        assertNotNull(r);
        assertTrue(r.isErr());
        assertFalse(r.isOk());
        assertFalse(r.isContinuation());
        assertEquals("authentication failed", r.getMessage());
    }

    @Test
    public void testParseErrEmpty() {
        POP3Response r = POP3Response.parse("-ERR");
        assertNotNull(r);
        assertTrue(r.isErr());
        assertEquals("", r.getMessage());
    }

    @Test
    public void testParseErrNoSuchMessage() {
        POP3Response r = POP3Response.parse("-ERR no such message");
        assertNotNull(r);
        assertTrue(r.isErr());
        assertEquals("no such message", r.getMessage());
    }

    // ── Continuation responses ──

    @Test
    public void testParseContinuationWithChallenge() {
        POP3Response r = POP3Response.parse("+ dGVzdA==");
        assertNotNull(r);
        assertTrue(r.isContinuation());
        assertFalse(r.isOk());
        assertFalse(r.isErr());
        assertEquals("dGVzdA==", r.getMessage());
    }

    @Test
    public void testParseContinuationEmpty() {
        POP3Response r = POP3Response.parse("+");
        assertNotNull(r);
        assertTrue(r.isContinuation());
        assertEquals("", r.getMessage());
    }

    @Test
    public void testParseContinuationWithSpace() {
        POP3Response r = POP3Response.parse("+ ");
        assertNotNull(r);
        assertTrue(r.isContinuation());
        assertEquals("", r.getMessage());
    }

    // ── Invalid responses ──

    @Test
    public void testParseInvalidReturnsNull() {
        assertNull(POP3Response.parse("QUIT"));
    }

    @Test
    public void testParseGarbageReturnsNull() {
        assertNull(POP3Response.parse("garbage"));
    }

    @Test
    public void testParseEmptyReturnsNull() {
        assertNull(POP3Response.parse(""));
    }

    @Test
    public void testParseNumericReturnsNull() {
        assertNull(POP3Response.parse("220 SMTP ready"));
    }

    // ── Status enum ──

    @Test
    public void testStatusValues() {
        assertEquals(POP3Response.Status.OK,
                POP3Response.parse("+OK").getStatus());
        assertEquals(POP3Response.Status.ERR,
                POP3Response.parse("-ERR").getStatus());
        assertEquals(POP3Response.Status.CONTINUATION,
                POP3Response.parse("+ data").getStatus());
    }

    // ── toString ──

    @Test
    public void testToStringOk() {
        POP3Response r = POP3Response.parse("+OK hello");
        assertNotNull(r);
        assertEquals("+OK hello", r.toString());
    }

    @Test
    public void testToStringErr() {
        POP3Response r = POP3Response.parse("-ERR nope");
        assertNotNull(r);
        assertEquals("-ERR nope", r.toString());
    }

    @Test
    public void testToStringContinuation() {
        POP3Response r = POP3Response.parse("+ challenge");
        assertNotNull(r);
        assertEquals("+ challenge", r.toString());
    }
}
