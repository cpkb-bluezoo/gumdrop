/*
 * HttpMethodTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Tests for {@link HttpMethod}, the open enumeration of request methods.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpMethodTest {

    private static ByteBuffer octets(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    public void knownMethodsAreSharedConstants() {
        assertSame(HttpMethod.GET, HttpMethod.of("GET"));
        assertSame(HttpMethod.GET, HttpMethod.of(octets("GET")));
        assertSame(HttpMethod.PROPFIND, HttpMethod.of(octets("PROPFIND")));
        assertTrue(HttpMethod.GET.isKnown());
    }

    @Test
    public void ofByteBufferDoesNotConsumeTheBuffer() {
        ByteBuffer b = octets("DELETE");
        HttpMethod.of(b);
        assertEquals(6, b.remaining());
    }

    @Test
    public void ofByteBufferHonoursPositionAndLimit() {
        ByteBuffer b = octets("xxPOSTyy");
        b.position(2).limit(6);
        assertSame(HttpMethod.POST, HttpMethod.of(b));
    }

    @Test
    public void extensionMethodsCarryTheirName() {
        HttpMethod m = HttpMethod.of(octets("PURGE"));
        assertFalse(m.isKnown());
        assertEquals("PURGE", m.name());
        assertEquals("PURGE", m.toString());
    }

    @Test
    public void extensionMethodsCompareByName() {
        assertEquals(HttpMethod.of("PURGE"), HttpMethod.of(octets("PURGE")));
        assertEquals(HttpMethod.of("PURGE").hashCode(), HttpMethod.of("PURGE").hashCode());
        assertNotEquals(HttpMethod.of("PURGE"), HttpMethod.of("PURGEX"));
    }

    @Test
    public void methodNamesAreCaseSensitive() {
        // RFC 9110 section 9.1
        HttpMethod lower = HttpMethod.of("get");
        assertFalse(lower.isKnown());
        assertNotEquals(HttpMethod.GET, lower);
    }

    @Test
    public void nonTokenIsRejected() {
        String[] bad = {"", "GE T", "GET\r", "G(T", "GéT"};
        for (int i = 0; i < bad.length; i++) {
            try {
                HttpMethod.of(bad[i]);
                fail("expected IllegalArgumentException for '" + bad[i] + "'");
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }
}
